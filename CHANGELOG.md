# Changelog

Formato baseado em [Keep a Changelog](https://keepachangelog.com/pt-BR/1.1.0/).
Versionamento semântico. Cada entrada corresponde a uma change do OpenSpec,
arquivada em `openspec/changes/archive/`.

## [1.0.0] — 2026-09-16

Release final. Entrega a **operação e a verificação** do sistema: ambiente
completo em um comando, cobertura e integração real garantidas pelo build,
correlação ponta a ponta, collection Postman executável e a auditoria que
percorre os 30 requisitos declarados. Fecha RNF-01 a RNF-10.

### Adicionado

- **`add-integration-tests-coverage`** (M10) — módulo técnico `quality-gates` e
  os gates que faltavam: agregação JaCoCo com pisos que **reprovam o build**
  (global 85%, `domain` e `application` 90%), guarda de infraestrutura real que
  recusa mock de banco ou broker, e auditoria de execução que reprova seleção,
  exclusão, omissão, tolerância a falha e relatório de outra sessão — um
  `verify` verde com suíte omitida não passa. Fixture canônica compartilhada e
  superfícies hostis derivadas do registro real de endpoints. Fecha RNF-04 e
  RNF-06.
- **`add-archunit-observability`** (M11) — suíte ArchUnit no
  `agendamento-service`, em escopo de teste, que falha o build quando a direção
  das camadas é cruzada ou o domínio importa framework. Correlação por
  `correlationId` nos três serviços, do filtro HTTP ao outbox, ao consumidor da
  notificação e ao projetor do histórico, com logs JSON e prova por testes
  dirigidos. Fecha RNF-05 e RNF-08.
- **`add-docker-compose-demo`** (M12) — imagem Docker multi-stage por serviço e
  Compose único que sobe PostgreSQL, RabbitMQ, Mailpit e os três serviços, com
  `make demo` idempotente que semeia a consulta de demonstração. Verificação
  estrutural do ambiente no `quality-gates`, só com JDK, sobre Dockerfiles,
  Compose e `.dockerignore`. Fecha RNF-07.
- **`add-postman-documentation`** (M13) — collection Postman v2.1 e environment
  local versionados, cobrindo a jornada REST, o GraphQL, autenticação,
  autorização, lembrete e a família de erros, com execução real verificada por
  Newman. README consolidado como porta de entrada, com flowchart e sequence
  diagram, e os sete ADRs uniformizados num índice rastreável aos archives
  OpenSpec.
- **`finalize-audit-report`** (M14) — `scripts/auditoria.sh`, roteiro em Bash
  somente-leitura que percorre os 30 requisitos de
  `docs/02-especificacao-funcional.md`, confere **75 pares artefato + âncora** e
  imprime `REQUISITO | STATUS | EVIDÊNCIA`. Mais `docs/relatorio-tecnico.md`,
  com números que nomeiam o comando de origem, e `docs/roteiro-demo.md`,
  cronometrado entre 5 e 8 minutos. Capability `operacao-do-ambiente`.

### Decisões

- **`OK` tem significado estreito.** Na auditoria de requisitos, `OK` afirma que
  a evidência versionada existe e está ancorada no elemento declarado — nunca
  que o comportamento foi reexecutado. Build, smoke e ambiente são evidência
  separada.
- **Evidência ancorada, e só ancorada.** Não existe verificador de mera
  existência de arquivo. Citação do identificador em prosa ou nome de comando
  não satisfaz requisito algum — é o falso positivo que um `grep RF-09`
  produziria. O resultado por requisito é a conjunção de todas as suas âncoras.
- **Inventário fechado.** A auditoria exige a sequência contínua RF-01…RF-20 e
  RNF-01…RNF-10, única e na ordem. Documento reduzido, lacuna no meio ou número
  fora da sequência reprovam.
- **Auditoria estática e sem efeito.** O roteiro não sobe container, não alcança
  banco nem broker, não executa o build, não depende de rede e não escreve
  arquivo. Um controle de sentinelas no `PATH` prova que nenhum dos binários
  proibidos é invocado.
- **Duas auditorias, nomes parecidos, instrumentos distintos.** A de requisitos
  é estática, em Bash, sobre os RF/RNF. A de execução roda dentro do
  `mvn verify` e verifica que todas as suítes do reactor executaram
  integralmente. Não são intercambiáveis.

### Garantias estruturais

- `AuditoriaDeRequisitosTest` executa o roteiro de verdade e exige inventário de
  30, catálogo de 75 pares, ausência de entrada órfã e âncora que não seja o
  próprio identificador. Oito negativos sintéticos cobrem requisito omitido,
  sequência quebrada, identificador duplicado, entrada órfã, artefato ausente,
  âncora removida, âncora faltante em requisito multiâncora e falso positivo.
- `RoteiroDeApresentacaoTest` exige a janela de 5 a 8 minutos, duração por
  bloco, pré-demonstração fora da soma, as cinco superfícies com endereço real,
  credenciais idênticas às de `docs/02` §5, comandos existentes, alternativa por
  bloco e nenhum comando que remova volumes. A restrição de janela do lembrete
  é lida de `ServicoDeLembretes.JANELA`, no código de produção.
- `RelatorioTecnicoTest` exige as oito seções na ordem, coluna de origem
  preenchida em toda linha de número, links locais válidos e nenhum binário de
  escritório versionado.
- `LigacaoDosGatesTest`, `InfraestruturaRealTest`, `AuditoriaDeExecucaoTest` e
  `VerificadorDeCoberturaTest` sustentam os gates do M10; `ColecaoPostmanTest`,
  `DocumentacaoFinalTest` e `AmbienteDeDemonstracaoTest`, os do M12 e do M13.

### Correções durante o desenvolvimento

- **RNF-01 violado, encontrado pela própria auditoria.** A cláusula "senha nunca
  em log" não tinha prova automatizada, e o teste escrito para fechar a lacuna
  encontrou o requisito sendo violado: `LoginRequest` é um `record`, e o
  `toString()` gerado inclui todos os componentes — com `org.springframework.web`
  em `DEBUG`, o resolvedor de `@RequestBody` registrava a senha em claro de cada
  login. Em `INFO`, o nível dos profiles de entrega, nada vazava, mas o requisito
  diz **nunca**, e a garantia dependia do nível configurado. `toString()` passou
  a omitir o valor, e `SenhaForaDosLogsIT` exige ausência da senha e do hash em
  `DEBUG`. É a única alteração de produção do M14.
- **Roteiro de demonstração fora da janela do lembrete.** O bloco de abertura
  aceitava "uma consulta futura" qualquer, e o lembrete D-1 só alcança
  `(agora, agora + 24h]`: seguir o roteiro ao pé da letra com data mais distante
  fazia o bloco final devolver `lembretesEnviados: 0`, sem e-mail. O roteiro
  passou a exigir a consulta dentro da janela, com a razão declarada na própria
  linha.
- **Cabeçalho do `scripts/auditoria.sh`.** Afirmava que carregar por `source` só
  definia funções; o `set -Eeuo pipefail` no topo também aplica o modo estrito
  ao shell chamador. O cabeçalho passou a descrever os dois modos como são, e o
  modo estrito continua sendo o primeiro comando do arquivo.
- **Hardening de produção descoberto pelas varreduras de entrada hostil do
  M10**, no agendamento e no histórico, autorizado caso a caso.

### Conhecido e adiado

- **Entrega ao-menos-uma-vez** na publicação de eventos e na notificação. A
  idempotência dos consumidores absorve a repetição; não há exactly-once.
- **Variabilidade de contagem de cobertura entre execuções.** A cobertura de
  linha global oscilou entre **97,27% e 97,37%** sem mudança de produção, nos
  caminhos de falha, timeout e interrupção do `OutboxRelay` e nos tratadores de
  erro em volta — executam conforme uma corrida contra o RabbitMQ real se
  resolve. As três métricas com piso permaneceram muito acima deles em todas as
  execuções. `BRANCH` é informativa e não tem piso.
- **Alcance da prova de senha fora do log.** Cobre o que a JVM da suíte escreve
  durante os dois logins, em `DEBUG` ou acima. Não alcança bibliotecas fora do
  Logback, níveis ligados só em produção, nem outros DTOs que venham a carregar
  segredo pelo `toString()` gerado.
- **A auditoria de requisitos é estática.** `OK` não reexecuta comportamento.
- **A sequência fechada engessa `docs/02` de propósito.** Um RF-21 quebra a
  auditoria até script e catálogo serem atualizados.
- **Polling do histórico na collection Postman.** A asserção do passo GraphQL
  não tolera snapshot intermediário entre a projeção do evento anterior e a do
  `CONFIRMADA`, o que pode reprovar uma execução completa de forma não
  determinística. Registrado na revisão do M13 e não corrigido.

## [0.2.0] — 2026-09-11

Segunda release. Entrega a **arquitetura assíncrona**: publicação transacional
de eventos, notificações ao paciente com lembrete D-1, e histórico de consultas
projetado dos eventos e consultável por GraphQL. Fecha RF-11 a RF-20.

### Adicionado

- **`add-event-publishing-outbox`** (M05) — contrato de eventos em
  `shared-contracts`: topologia, cinco routing keys, envelope versão 1 e
  snapshot completo da consulta. Publicação por Transactional Outbox: consulta e
  envelope na mesma transação, e um relay com lote de até 50, `SKIP LOCKED` e
  confirmação do broker. DLX, duas DLQs e três tentativas com
  `default-requeue-rejected: false`. Capability `mensageria-de-eventos`, com
  ADR-001 e ADR-006. Fecha RF-15 e RF-20.
- **`add-historico-projection`** (M08) — o `historico-service` consome os cinco
  eventos e mantém o snapshot atual em `consulta_historico` e a trilha completa
  em `consulta_evento`, com idempotência por `eventId`. Capability
  `historico-de-consultas`. Fecha RF-18 e, para o histórico, RF-19.
- **`add-historico-graphql`** (M09) — API GraphQL com quatro queries e filtro por
  período (`TODAS`, `FUTURAS`, `PASSADAS`), intervalo `[de, ate)` e status,
  aplicado no PostgreSQL. Inclui:
  - a mutation `corrigirRegistroHistorico`, exclusiva de MEDICO, que grava a
    auditoria `CORRECAO_MANUAL` na mesma transação;
  - erros com código estável (`FORBIDDEN`, `NOT_FOUND`, `BAD_REQUEST`);
  - GraphiQL só nos profiles `dev` e `demo`.

  Fecha RF-11 a RF-14.
- **`add-notificacao-consumer`** (M06) — o `notificacao-service` consome os
  eventos, mantém a `agenda_local` e avisa o paciente de criação, alteração e
  cancelamento. O envio passa por `NotificationSenderPort`, com adaptadores de
  log (padrão) e SMTP. Capability `notificacoes-ao-paciente`. Fecha RF-16 e,
  para a notificação, RF-19.
- **`add-lembrete-24h`** (M07) — lembrete D-1 para consultas agendadas ou
  confirmadas em `(agora, agora + 24h]`. Roda por job horário configurável e por
  `POST /internal/lembretes/executar`, restrito a MEDICO e ENFERMEIRO, com erro
  interno em Problem Detail. Fecha RF-17.

### Decisões

- **Idempotência transacional nos consumidores.** Em notificação e histórico,
  uma única entrada AMQP por serviço grava o efeito e a marca de
  `evento_processado` na mesma transação, com a marca por último. Entregas
  concorrentes do mesmo `eventId` confirmam um único efeito persistido.
- **Estado monotônico por `occurredAt`.** Agenda local e snapshot do histórico
  mudam por upsert condicionado, avaliado pelo PostgreSQL. Um fato anterior ao
  vigente não regride o estado; no histórico, ele ainda entra na trilha.
- **Desacoplamento.** Nenhum consumidor chama o agendamento; o snapshot do
  evento é a única fonte.
- **Uma cadeia de segurança, compartilhada pelos três serviços**, com caminhos
  autenticados configuráveis e `denyAll` por omissão. O histórico autentica
  `/graphql`, e a notificação, `/internal/**`.
- **Lembrete D-1.** Um único comando lê os candidatos, com os limites da janela
  calculados do `Clock`. O registro é reservado antes do envio, sobre uma
  unicidade parcial de `LEMBRETE_D1`, e cada consulta recebe no máximo um
  lembrete durante toda a vida.
- **Índices medidos, e não supostos.** Criados em migration nova — no histórico
  pelo M09, na notificação pelo M07 —, com o plano real verificado em teste.

### Garantias estruturais

- `ProtecoesEstruturaisNotificacaoTest` exige:
  - uma única entrada AMQP, transacional, com a marca gravada depois do efeito;
  - nenhum cliente HTTP para o agendamento e nenhum segredo literal;
  - nenhuma leitura direta de relógio, inclusive do relógio do banco;
  - no lembrete, reserva antes do envio.
- `ProtecoesEstruturaisTest`, no histórico, exige uma fronteira única de
  produção, transacional e com a marca por último, e proíbe cliente HTTP para o
  agendamento. `TiposNormativosIntactosTest` fixa os cinco tipos do contrato e
  impede que o tipo local `CORRECAO_MANUAL` entre nele.
- `CoberturaDeAutorizacaoGraphqlTest` e `CoberturaDeAutorizacaoNotificacaoTest`
  exigem decisão de autorização explícita em toda operação GraphQL e em todo
  endpoint HTTP do serviço.
- `MatrizDeAutorizacaoGraphqlIT` e `MatrizDeAutorizacaoNotificacaoIT` leem
  `docs/02-especificacao-funcional.md` §3 e testam cada célula, com asserção
  positiva de 15 e 3 células, respectivamente.
- `MatrizDeCenariosDoLembreteTest` mantém em código a rastreabilidade
  Scenario → método do M07 e falha se a matriz ficar vazia ou divergir da spec.

### Correções durante o desenvolvimento

- **Agenda dupla sob concorrência**, adiada na 0.1.0. A migration V3 acrescentou
  exclusões GiST para médico e paciente ativos. `23P01` virou conflito de
  agenda, e `40P01`/`40001`, alteração concorrente — ambos 409, com `type`
  distinto.
- **Offset de fuso.** A spec de durabilidade prometia preservar o deslocamento
  de fuso, que `timestamptz` descarta; passou a garantir o instante.
- **`minhasConsultas`.** A matriz de `docs/02` a marcava como permitida aos três
  perfis; passou a ser exclusiva de PACIENTE, com `FORBIDDEN` para médico e
  enfermeiro.
- **Espião numa só suíte.** Um `@SpyBean` declarado numa suíte criava um segundo
  contexto, com um segundo listener competindo pela mesma fila; espiões e
  configuração passaram para a base das suítes.
- **Guarda de relógio da notificação.** Só recusava `now(),` e `now())`; passou a
  recusar qualquer leitura do relógio do banco.
- **Contrato do login no README.** O README descrevia a resposta como
  `token`/`expiraEmSegundos`; o contrato real é `accessToken`/`expiresIn`.

### Conhecido e adiado

- **Garantia de entrega.** O relay publica ao menos uma vez: uma queda entre o
  ACK do broker e o commit local reenvia o mesmo `eventId`. Nos consumidores, o
  efeito **persistido** é exatamente uma vez por `eventId`. O **envio externo**
  da notificação é ao menos uma vez, por dois caminhos:
  - o sender conclui e a transação reverte, e a tentativa seguinte reenvia;
  - duas entregas concorrentes do mesmo evento acionam o canal antes de a chave
    decidir.

  Por isso `notificacao_enviada` registra o que transações confirmadas
  produziram, e não todo envio. Fechar essa janela exigiria outbox no serviço de
  notificação, que nenhum requisito pede.
- **Lembrete D-1.**
  - Vale o mesmo ao menos uma vez: se o envio conclui e a confirmação falha, a
    execução seguinte reenvia.
  - Uma remarcação posterior ao lembrete não gera outro; o aviso de alteração
    informa o novo horário.
  - Um cancelamento aplicado entre a leitura e o envio ainda pode receber o
    lembrete.
- **Alteração concorrente sem retry.** Não há retry automático para
  `alteracao-concorrente`; o cliente relê e repete.
- **DLQs sem reenvio automático.** O reprocessamento é operação manual.
- **Sem paginação no GraphQL.** A API do histórico não pagina resultados.
- **`JWT_SECRET` obrigatório.** O `notificacao-service` não sobe sem a variável,
  que não tem valor padrão.
- **Seed de demonstração incompleto.** Não traz as consultas do roteiro nem uma
  consulta nas próximas 24 horas; isso fica para o M12.
- **Fora desta release.** Smoke test ponta a ponta, gate agregado de cobertura e
  ampliação do `EntradasHostisIT` ficam para o M10; `correlationId` nos logs dos
  três serviços (RNF-08), para o M11; Compose das aplicações e Mailpit, para o
  M12.

## [0.1.0] — 2026-09-04

Primeira release. Entrega o **agendamento seguro ponta a ponta**: domínio,
persistência, API REST e autenticação com os três perfis. Fecha RF-01 a RF-10.

### Adicionado

- **`bootstrap-monorepo`** — POM pai multi-módulo com versão centralizada em
  `${revision}`, cinco módulos Maven, PostgreSQL 16 e RabbitMQ 3.13 por
  `docker compose`, separação surefire/failsafe. Capability
  `operacao-do-ambiente`.
- **`add-agendamento-domain`** — núcleo de domínio sem framework: entidades,
  value objects, máquina de estados no enum `StatusConsulta`, seis casos de uso
  e três portas de saída. Regras de conflito de agenda com intervalo semiaberto
  `[inicio, fim)`. Capability `agendamento-de-consultas`.
- **`add-agendamento-persistence`** — Flyway, JPA e adaptadores das portas.
  Detecção de conflito como query de sobreposição em SQL, com índice. Fronteira
  transacional por decorador em `infrastructure`, preservando `application` sem
  framework. Fecha RNF-09 e RNF-10.
- **`add-agendamento-rest-api`** — API REST com DTOs validados, erros em
  RFC 7807 Problem Detail, paginação com teto e OpenAPI. Fecha RNF-03.
- **`add-autenticacao-jwt`** — autenticação JWT e autorização por perfil, com a
  matriz de permissões aplicada célula a célula. Regra de propriedade na
  assinatura dos casos de uso. Capability `autenticacao-e-autorizacao`.
  Fecha RF-01 a RF-04, RNF-01 e RNF-02.

### Garantias estruturais

Três classes existem para impedir que uma omissão futura passe despercebida,
cada uma nascida de uma falha real:

- `CoberturaDoMapaDeErrosTest` — varre `domain.exception` e exige tratador para
  cada exceção. Uma exceção nova sem handler quebra o build no momento em que é
  criada.
- `CoberturaDeAutorizacaoTest` — exige `@PreAuthorize` em todo método de
  controller.
- `MatrizDeAutorizacaoIT` — lê a tabela de permissões de
  `docs/02-especificacao-funcional.md` e gera um caso por célula, com asserção
  de que encontrou exatamente 21 células.
- `EntradasHostisIT` — ataca os endpoints com entradas malformadas e de borda,
  exigindo que nenhuma resposta seja 5xx.

### Correções durante o desenvolvimento

- Validação passou a preceder a mutação do agregado na alteração de consulta.
  Sob JPA, mutar a entidade gerenciada antes de recusar persistiria a alteração
  rejeitada no commit, sem ninguém chamar `salvar`.
- `Consulta.atualizar` deixou de sobrescrever observações com `null`, o que
  apagava registro clínico numa remarcação.
- Exceções de request do Spring MVC passaram a ser tratadas como 4xx em vez de
  cair no tratador genérico como 500.

### Conhecido e adiado

- Conflito de agenda não é atômico sob concorrência: duas requisições
  simultâneas podem passar pela verificação antes de qualquer commit. Correção
  planejada para o M05, com constraint de exclusão no PostgreSQL.
- A ampliação do `EntradasHostisIT` para cobrir todas as dimensões de entrada
  por varredura está planejada para o M10.
