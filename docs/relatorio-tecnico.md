# Relatório técnico — Sistema de Agendamento e Histórico de Consultas Hospitalares

FIAP Pós Tech — Arquitetura e Desenvolvimento Java · Tech Challenge Fase 3
Autor: Gabriel Andrade Almeida

Este relatório descreve a solução entregue: o que ela faz, como está construída, o que foi
decidido e por quê, o que é garantido e o que não é. Os números vêm de execuções registradas;
nada aqui é estimativa.

---

## 1. Contexto e problema

O enunciado pede um backend hospitalar para agendamento de consultas, histórico de pacientes e
lembretes automáticos, acessível a três perfis — médico, enfermeiro e paciente — com acesso
controlado. Os eixos avaliados são nomeados: segurança com Spring Security, consulta flexível
do histórico por GraphQL, separação em mais de um serviço e comunicação assíncrona por
RabbitMQ ou Kafka.

O problema real por trás disso é o absenteísmo: paciente que falta à consulta desperdiça
agenda médica. Daí o lembrete D-1 ser tratado aqui como funcionalidade de negócio, e não como
enfeite técnico.

A entrega cobre os 20 requisitos funcionais e os 10 não funcionais de
[especificação funcional](02-especificacao-funcional.md), construídos em quinze changes
versionadas em `openspec/changes/archive/`. O escopo foi deliberadamente fechado: sem
frontend, sem provedor real de SMS ou e-mail, sem deploy em nuvem, sem prontuário eletrônico.

## 2. Arquitetura

Três serviços Spring Boot, um banco PostgreSQL por serviço, integrados por eventos no
RabbitMQ. O detalhamento está em [arquitetura](01-arquitetura.md); o resumo operacional:

| Serviço | Porta | Papel |
|---|---:|---|
| `agendamento-service` | 8081 | Escrita. REST, autenticação, regras de negócio, Transactional Outbox |
| `notificacao-service` | 8082 | Consome eventos, notifica o paciente, executa o lembrete D-1 |
| `historico-service` | 8083 | Projeta eventos num read model e expõe GraphQL |

Dois módulos compartilhados sustentam o contrato entre eles: `shared-contracts`, com o
envelope de eventos e a topologia AMQP, e `shared-security`, com o filtro JWT e a cadeia base
do Spring Security. Um sétimo projeto, `quality-gates`, é técnico: agrega cobertura e aplica
os gates do build, sem Spring e sem regra de negócio.

Clean Architecture estrita existe **apenas** no `agendamento-service`, onde as regras vivem.
Notificação e histórico são adaptadores de evento e usam camadas simples. A assimetria é
deliberada e está registrada no [ADR-007](adr/ADR-007-clean-architecture-so-no-core.md); a
fronteira não depende de disciplina, é verificada por ArchUnit no build.

## 3. Decisões e trade-offs

As sete decisões estruturais estão em [ADRs](adr/README.md), cada uma ligada ao change em que
se materializou. As de maior consequência:

**RabbitMQ em vez de Kafka** ([ADR-001](adr/ADR-001-rabbitmq.md)). O caso de uso é pub/sub com
fanout para dois consumidores. AMQP entrega DLQ, retry e roteamento por tópico com muito menos
infraestrutura. O log distribuído do Kafka resolveria um problema que este escopo não tem.

**Três serviços, com histórico separado** ([ADR-002](adr/ADR-002-tres-servicos.md)). O
histórico era opcional no enunciado. Embuti-lo custaria quase nada, mas deixaria o RabbitMQ
servindo a um único consumidor — enfraquecendo justamente o eixo assíncrono. O custo aceito
foi um consumidor, um read model e tratamento de evento fora de ordem.

**Transactional Outbox** ([ADR-006](adr/ADR-006-transactional-outbox.md)). Consulta e evento
são gravados na mesma transação; um relay publica depois. Evita o dual write clássico —
"salvou no banco mas não publicou" — ao custo de entrega ao menos uma vez, que obriga
idempotência nos consumidores.

**JWT stateless** ([ADR-005](adr/ADR-005-jwt-stateless.md)). Sessão em memória exigiria sticky
session ou store compartilhado para que o histórico soubesse quem pergunta. O token atravessa
os três serviços sem estado compartilhado.

**A matriz de autorização como fonte executável**
([ADR-004](adr/ADR-004-matriz-de-autorizacao.md)). A tabela de
[especificação funcional §3](02-especificacao-funcional.md) não é documentação paralela ao
código: as suítes de integração **leem o documento** e geram um caso por célula. Acrescentar
uma linha à tabela cria casos que falham até serem implementados.

## 4. Segurança

Autenticação por `POST /auth/login`, que devolve `accessToken`, `expiresIn` e `perfil`. O token
HS256 carrega `sub`, `email`, `perfil` e `pacienteId`/`medicoId` quando aplicável. Os três
serviços validam com o mesmo filtro, vindo de `shared-security`. Senhas com
`BCryptPasswordEncoder`.

A autorização é a matriz de §3, aplicada célula a célula e testada célula a célula: 21 células
no agendamento, 15 no GraphQL do histórico e 3 no endpoint interno de lembretes. A regra de
propriedade — paciente só alcança os próprios recursos — vive no caso de uso, não no
controller, e `minhasConsultas` resolve o `pacienteId` do token, nunca de argumento.

Recusas são distinguidas por `type` estável em Problem Detail: `nao-autenticado` para 401 e
`acesso-negado` para 403. O detalhe é fixo por categoria e não revela o que faltou — "token
expirado" contaria que o token já foi válido, e "usuário não encontrado" no login transformaria
o endpoint em oráculo de e-mails cadastrados.

**Uma violação encontrada durante esta entrega.** A auditoria de requisitos do M14 expôs que a
cláusula "senha nunca em log" do RNF-01 não tinha prova automatizada alguma. O teste escrito
para fechar a lacuna encontrou o requisito sendo violado: com `org.springframework.web` em
`DEBUG`, o resolvedor de `@RequestBody` do Spring MVC registrava o objeto desserializado, e a
senha em claro ia junto, porque o `toString()` gerado de um `record` inclui todos os
componentes. Nos profiles de entrega o nível é `INFO` e nada vazava na prática — mas o
requisito diz *nunca*, e a garantia dependia do nível configurado. A causa foi corrigida em
`LoginRequest`, cujo `toString()` passou a usar marcador fixo no lugar do valor, e o teste
exige ausência total em `DEBUG`.

## 5. Mensageria e garantias de entrega

O contrato é normativo e está em [contrato de eventos](03-contrato-de-eventos.md): exchange
topic `hospital.consultas`, DLX própria, uma fila e uma DLQ por consumidor, cinco routing keys
e um envelope único com `eventId`, `eventType`, `aggregateId`, `occurredAt`, `version`,
`correlationId` e um payload com o snapshot completo da consulta.

O snapshot completo é o que mantém os consumidores desacoplados: nenhum deles faz HTTP ao
agendamento para completar informação. Tudo que precisam viaja no evento.

As garantias reais, sem arredondar para cima:

- **Publicação: ao menos uma vez.** O relay marca como publicado só depois do ACK do broker
  sem `return`. Uma queda entre o ACK e o commit local reenvia o mesmo `eventId`.
- **Efeito persistido nos consumidores: exatamente uma vez por `eventId`.** Efeito e marca de
  processado entram na mesma transação, com a marca por último.
- **Envio externo da notificação: ao menos uma vez.** Se o sender conclui e a transação
  reverte, a tentativa seguinte reenvia.
- **Ordem:** o snapshot não regride. Um evento anterior ao vigente entra na trilha do
  histórico, mas não sobrescreve o estado atual.
- **Falha:** `default-requeue-rejected: false` e três tentativas; depois a mensagem vai para a
  DLQ, preservada, sem reenvio automático.

## 6. Estratégia de testes

Os números abaixo vêm de uma execução única e identificada — o **gate de medição do M14** —, e
**não são reescritos** por execuções posteriores de verificação. Uma execução seguinte mede uma
árvore diferente, porque inclui os próprios testes acrescentados por esta entrega; essa
diferença é esperada, é registrada à parte e não altera esta tabela.

| Número | Valor | Origem |
|---|---|---|
| Casos executados, total | 1667 | `mvn clean verify` na raiz — gate de medição do M14 |
| Casos em `shared-contracts` | 138 | idem — 122 unitários e 16 de integração |
| Casos em `shared-security` | 74 | idem — sem suíte de integração de infraestrutura |
| Casos em `agendamento-service` | 601 | idem — 379 unitários e 222 de integração |
| Casos em `notificacao-service` | 141 | idem — 32 unitários e 109 de integração |
| Casos em `historico-service` | 205 | idem — 69 unitários e 136 de integração |
| Casos em `quality-gates` | 508 | idem — módulo técnico de verificação |
| Falhas, erros e testes pulados | 0 | idem |
| Cobertura de linha global | 97,37% (1857 cobertas, 50 perdidas) | relatório agregado JaCoCo do gate de medição — piso 85% |
| Cobertura de linha em `domain` | 98,72% (309 cobertas, 4 perdidas) | idem — piso 90% |
| Cobertura de linha em `application` | 100,00% (158 cobertas, 0 perdidas) | idem — piso 90% |
| Requisitos auditados | 30 de 30 aprovados | `bash scripts/auditoria.sh` — código 0 |
| Âncoras de evidência conferidas | 75 | idem |
| Smoke ponta a ponta | código 0, sem recurso órfão | `bash scripts/smoke-test.sh` |
| Duração planejada da demonstração | 6 min 30 s | [roteiro de demonstração](roteiro-demo.md) — validado estruturalmente |

O que sustenta esses números é o desenho das suítes, não a quantidade delas:

**Infraestrutura real, nunca dublê.** Todo teste de integração sobe PostgreSQL 16 e RabbitMQ
3.13 em containers efêmeros. Um gate estrutural reprova o build se algum módulo substituir
banco ou broker por mock, banco em memória ou broker embarcado.

**Execução integral verificada.** Uma auditoria de execução confere, depois das suítes, que
cada suíte executável produziu evidência na sessão corrente, e reprova seleção, exclusão,
omissão, tolerância a falhas e relatório de outra sessão. Um `verify` verde com suíte omitida
não passa.

**Documentos normativos como fonte de teste.** As matrizes de autorização e o inventário de
requisitos são lidos dos documentos, com contagem exata afirmada — a proteção contra o parser
que para de achar a tabela e passa verificando nada.

**Auditoria de requisitos.** `bash scripts/auditoria.sh` percorre os 30 RF/RNF e imprime
`REQUISITO | STATUS | EVIDÊNCIA`. Ela é **estática**: `OK` significa que a evidência versionada
existe e está ancorada no elemento declarado, e não que o comportamento foi reexecutado. Quem
comprova comportamento são o build, o smoke e o ambiente de demonstração, registrados
separadamente. A distinção é deliberada — e não confundir com a *auditoria de execução* acima,
que é outro instrumento, dentro do ciclo Maven.

## 7. Limites conhecidos e o que ficou fora

**Garantias.** A publicação e o envio externo da notificação são **ao menos uma vez**, não
exatamente uma vez. Fechar essa janela exigiria outbox também no serviço de notificação, que
nenhum requisito pede. O efeito *persistido* nos consumidores é exatamente uma vez por
`eventId`.

**Comportamentos aceitos.** Uma remarcação posterior ao lembrete D-1 não gera outro lembrete —
o aviso de alteração informa o novo horário. Um cancelamento aplicado entre a leitura e o envio
ainda pode receber o lembrete. Não há retry automático para `alteracao-concorrente`: o cliente
relê e repete. As DLQs não têm reprocessamento automático. O GraphQL do histórico não pagina.

**Variabilidade da cobertura entre execuções.** Os números da seção 6 são de **uma execução
identificada**, o gate de medição do M14. Uma execução nova pode divergir em poucas linhas sem
que nada de produção mude. A causa observada são os caminhos de falha, timeout e interrupção do
`OutboxRelay` — as linhas do `catch (AmqpException | TimeoutException | ExecutionException)` e as
do tratamento de `InterruptedException` — e os tratadores de erro em volta deles: só executam
quando uma corrida contra o RabbitMQ real se resolve daquele jeito, e essa corrida não é
determinística. No intervalo observado, a cobertura de linha global ficou **entre 97,27% e
97,37%**. As três métricas com piso permaneceram muito acima deles em todas as execuções
observadas. Não há aqui promessa de que os números se repitam exatamente.

**Alcance da auditoria de requisitos.** Ela é **estática** e verifica artefato mais âncora
literal. Não reexecuta teste, não mede cobertura e não sobe ambiente. Um requisito aprovado
significa evidência presente e ancorada — a prova de comportamento é o build e o smoke.

**Alcance da prova de "senha fora dos logs".** Cobre o que a JVM da suíte escreve durante os
dois logins, em `DEBUG` ou acima. Não alcança bibliotecas que escrevam fora do Logback, nem
níveis mais verbosos ligados só em produção. Outros DTOs que venham a carregar segredo pelo
`toString()` gerado não estão cobertos.

**Ambiguidade do enunciado, decidida conscientemente.** O enunciado diz, na seção 1, que
enfermeiros "podem registrar consultas e acessar o histórico" e, na seção 2, que "médicos e
enfermeiros poderão registrar novas consultas e modificar consultas existentes". A resolução
adotada — seção 2 prevalece no serviço de agendamento, seção 1 governa o histórico — está
registrada na [ADR-004](adr/ADR-004-matriz-de-autorizacao.md). Também foi decisão de projeto
que "suas consultas", para o paciente, é permissão e não filtro: o identificador que ele enviar
é irrelevante, o sistema impõe o próprio.

**Fora de escopo por decisão.** Frontend, provedor real de SMS ou WhatsApp, prontuário
eletrônico, prescrição, faturamento, deploy em nuvem, multi-tenancy e tuning de performance.

**Tempos da demonstração.** As durações do roteiro são planejadas e validadas
estruturalmente — soma dentro da janela, superfícies respondendo em menos de 300 ms. O ensaio
falado final é do apresentador.

## 8. Aprendizados

**Documento normativo lido por teste vale mais que documento revisado por humano.** As matrizes
de autorização geram casos a partir da tabela. O risco conhecido é o parser parar de achar a
tabela e a suíte passar verificando nada — por isso toda leitura afirma contagem exata. Sem
essa segunda asserção, a primeira seria decorativa.

**Suíte filtrada esconde defeito que o gate completo pega.** O teste de senha em log passou
verde na execução dirigida e quebrou no build completo: o `deleteAll()` do seu `@BeforeEach`
violava uma FK quando outra suíte já havia deixado dados. Isolado, o banco estava vazio. É o
argumento concreto contra fechar uma entrega só com execução filtrada.

**Auditar requisitos encontra defeito, não só lacuna.** O M14 foi planejado para inventariar
evidências. Ao exigir que cada cláusula tivesse âncora própria, expôs uma cláusula sem prova —
e a prova, ao ser escrita, encontrou a violação. Uma âncora parcial teria dado o requisito por
coberto.

**Garantia que depende de configuração não é garantia.** A senha não vazava porque o nível de
log era `INFO`. Bastava alguém aumentar a verbosidade para o RNF-01 deixar de valer. Corrigir a
origem custou três linhas; descobrir custou uma auditoria.

**Decidir a ambiguidade em voz alta vale mais que escolher em silêncio.** O enunciado tinha
duas passagens em tensão. Registrar a resolução num ADR e derivar a matriz dela transformou uma
dúvida em contrato verificável.

---

## Documentos relacionados

- [Especificação funcional](02-especificacao-funcional.md) — RF/RNF, matriz de autorização, modelo de dados
- [Arquitetura](01-arquitetura.md) — camadas, segurança, tratamento de erros, observabilidade
- [Contrato de eventos](03-contrato-de-eventos.md) — normativo: topologia, envelope, idempotência
- [Decisões arquiteturais](adr/README.md) — os sete ADRs
- [Roteiro de demonstração](roteiro-demo.md) — apresentação cronometrada
- [Script de auditoria de requisitos](../scripts/auditoria.sh)
