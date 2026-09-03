# Design — Núcleo de domínio e casos de uso do agendamento

## Context

Ver `proposal.md` — Why. Os requisitos estão em `specs/agendamento-de-consultas/spec.md`.

O `agendamento-service` existe desde o M00 com `domain/` e `application/` vazios e uma única dependência de framework: `spring-boot-starter-web`, herdada do POM do módulo. O POM pai já configura surefire, failsafe e JaCoCo, e o build do M00 emite o aviso de que o Mockito se auto-anexa como agente — irrelevante enquanto não havia testes de verdade, relevante a partir daqui.

A restrição que molda todo o resto: `domain` e `application` não podem importar `org.springframework`, `jakarta.persistence`, `jakarta.validation` nem `com.fasterxml`. Isso é regra de arquitetura do projeto (`docs/01-arquitetura.md` §4), será verificada por ArchUnit no M11, e neste change é sustentada por construção.

## Goals / Non-Goals

**Goals**
- As regras de negócio decidíveis sem banco, sem broker e sem contexto Spring
- Portas de saída cujo contrato o M02, o M03 e o M05 consigam satisfazer sem alterar `domain` nem `application`
- Testes determinísticos no tempo, sem `sleep`, sem depender do relógio da máquina
- Cobertura de `domain` + `application` ≥ 90%, medida de verdade

**Non-Goals**
- Nenhuma otimização de consulta. A eficiência da detecção de conflito é problema do M02, que troca o fake por SQL.
- Nenhuma decisão sobre serialização, transação ou concorrência. Não há framework aqui para tê-las.
- Nenhuma modelagem do envelope de eventos de `docs/03-contrato-de-eventos.md`. Aquele envelope é do `shared-contracts` e nasce no M05.

## Decisions

### D1 — `Clock` injetado, nunca `LocalDateTime.now()`

Toda leitura do tempo passa por um `java.time.Clock` recebido no construtor do caso de uso. Nenhuma chamada a `now()` sem relógio explícito, em nenhum ponto de `domain` ou `application`.

Sem isso, os cenários de borda desta capability são intestáveis: "registro no instante corrente é recusado" e "períodos adjacentes não são conflito" dependem de controlar o instante de referência ao milissegundo. Com `Clock.fixed(...)` cada um vira uma asserção determinística; sem ele, viram testes que falham em fevereiro ou perto da meia-noite.

*Alternativa descartada:* um `ProvedorDeTempo` próprio do projeto. `Clock` já é exatamente essa abstração, está na biblioteca padrão, e `Clock.fixed` e `Clock.offset` cobrem tudo que os testes precisam. Uma interface própria seria um sinônimo com custo de manutenção.

### D2 — A máquina de estados vive no enum `StatusConsulta`

`StatusConsulta.podeTransicionarPara(StatusConsulta destino)` responde se uma transição é legal. Cada constante declara seu conjunto de destinos permitidos:

| De | Para |
|---|---|
| `AGENDADA` | `CONFIRMADA`, `CANCELADA`, `REALIZADA` |
| `CONFIRMADA` | `REALIZADA`, `CANCELADA` |
| `REALIZADA` | — terminal |
| `CANCELADA` | — terminal |

Transição para o próprio status é recusada: não é mudança de estado, e aceitá-la publicaria um evento sem fato correspondente.

A `Consulta` consulta o enum antes de qualquer mudança de status e lança `TransicaoDeStatusInvalidaException` quando a transição não é permitida. Concentrar a tabela no enum evita que a regra se espalhe por quatro casos de uso, que é como ela se torna inconsistente.

*Alternativa descartada:* uma classe `MaquinaDeEstados` separada. Justificável se as transições dependessem de contexto externo — não é o caso, elas dependem só do par (origem, destino).

### D3 — Fakes em memória das portas, não Mockito

As três portas ganham implementações em memória em `src/test`, com armazenamento em `Map` e listas. Mockito não é usado para portas.

Um mock de `ConsultaRepositoryPort` responde o que o teste mandou responder — ele não tem agenda, então não pode contradizer o caso de uso. O fake tem: ao gravar duas consultas sobrepostas, a busca por conflito devolve a segunda porque ela realmente está lá. Testes de regra contra mocks testam o roteiro que o autor imaginou; contra fakes, testam a regra.

Mockito continua disponível para colaboradores que não sejam portas, se algum aparecer.

*Alternativa descartada:* stubs anônimos por teste. Multiplicam código e cada teste passa a ter sua própria noção do que a porta faz.

### D4 — Detecção de conflito: o domínio decide, a porta só busca

`PeriodoConsulta.sobrepoe(PeriodoConsulta outro)` implementa a regra de sobreposição, com a borda explícita: intervalos `[inicio, fim)` — fim exclusivo, de modo que uma consulta que começa exatamente quando outra termina **não** conflita.

A porta expõe uma busca com recorte já aplicado:

```
List<Consulta> buscarAtivasDoMedicoNoPeriodo(UUID medicoId, PeriodoConsulta periodo)
List<Consulta> buscarAtivasDoPacienteNoPeriodo(UUID pacienteId, PeriodoConsulta periodo)
```

O contrato inclui "ativas" — consultas `CANCELADA` e `REALIZADA` ficam fora, porque não ocupam agenda.

Essa assinatura é a decisão importante do change, e é uma decisão tomada *para o M02*: ela obriga o adaptador a filtrar por período e status **no banco**, o que em SQL é a query de sobreposição de intervalo com índice que o roadmap exige. A assinatura alternativa — `buscarTodasDoMedico(UUID)` com o filtro no caso de uso — seria mais simples de implementar com o fake e levaria o M02 direto à reprovação técnica descrita no roadmap: carregar a agenda inteira em memória para comparar.

O fake em memória filtra em Java; o adaptador do M02 filtra em SQL. Os dois satisfazem o mesmo contrato, e é por isso que o M02 não precisa tocar em `application`.

### D5 — Modelo rico: as regras moram nas entidades

`Consulta` não é um saco de getters e setters. Ela expõe `remarcarPara(...)`, `confirmar()`, `cancelar(motivo)` e `registrarRealizacao()`, e cada um valida a própria pré-condição antes de mudar estado. O caso de uso orquestra — carrega, checa conflito consultando a porta, chama o método de negócio, persiste, publica — mas não decide se a transição é legal.

O critério prático: se uma regra pode ser verificada olhando só para a consulta, ela vive na `Consulta`. Se precisa olhar para outras consultas (conflito de agenda) ou para outros agregados, vive no caso de uso.

*Alternativa descartada:* domínio anêmico com toda a lógica nos casos de uso. Torna a `Consulta` incapaz de se proteger e faz cada novo caso de uso repetir as validações — que é como as regras divergem entre "agendar" e "remarcar".

### D6 — `EventPublisherPort` chamada, com um evento de domínio próprio

Os casos de uso chamam `EventPublisherPort.publicar(EventoDeConsulta)` após cada mudança de estado bem-sucedida. O `EventoDeConsulta` é um tipo de `domain`, mínimo: identificador da consulta, tipo da mudança e o instante em que ocorreu.

Ele **não** é o `EventoEnvelope` de `docs/03-contrato-de-eventos.md`. Aquele envelope tem `eventId`, `correlationId`, `version` e o snapshot completo serializado em JSON — conceitos de transporte e de integração, que exigiriam Jackson no domínio. A tradução do evento de domínio para o envelope AMQP é responsabilidade do adaptador, no M05.

A publicação depois do sucesso, e só depois, é o que sustenta o cenário "operação recusada não publica evento": a exceção sobe antes da chamada.

*Alternativa descartada:* acumular eventos na entidade e drená-los depois. É o padrão de eventos de domínio do DDD e é mais elegante, mas exige que alguém garanta a drenagem — o que, sem framework, é uma disciplina não verificável. Com seis casos de uso e um método público cada, a chamada explícita é mais legível e não esconde nada.

### D7 — Datas em `OffsetDateTime`

Instantes de consulta são `OffsetDateTime`. O modelo de dados usa `timestamptz` (`docs/02-especificacao-funcional.md` §4) e o contrato de eventos transmite `2026-09-10T14:00:00-03:00` com offset explícito. `LocalDateTime` perderia essa informação e obrigaria a reintroduzi-la nas bordas; `Instant` a normalizaria para UTC e perderia o offset original, que é dado de negócio numa agenda hospitalar.

`Clock` produz `Instant`; o caso de uso converte com o fuso do relógio, o que mantém o teste determinístico.

### D8 — Duas famílias de recusa: formato malformado e regra de negócio

A distinção que organiza as exceções não é "quantas §4 nomeia", é **o que a recusa diz sobre a requisição**.

**Entrada malformada** — um valor que não chega a representar a coisa que diz representar. CPF com dígito verificador errado, e-mail sem arroba, CRM fora do formato. Esses lançam `IllegalArgumentException`, com mensagem em português, direto do construtor do value object. O `ProblemDetail` do M03 os responde como **400**.

**Regra de negócio** — uma requisição bem formada que o domínio recusa por causa do estado do sistema ou de uma exigência do negócio. Aqui entram `AgendamentoNoPassadoException`, `ConflitoDeAgendaException` e `TransicaoDeStatusInvalidaException`, mais `MotivoDeCancelamentoObrigatorioException`, respondida como **422**.

**Recurso inexistente** — subfamília da anterior, com destino próprio. `RecursoNaoEncontradoException` é a base abstrata de `ConsultaNaoEncontradaException`, `PacienteNaoEncontradoException` e `MedicoNaoEncontradoException`, respondida como **404**.

A base existe porque a spec exige recusar registro para paciente inexistente, e §4 nomeava apenas a exceção de consulta. Reusá-la faria um paciente ausente responder "Consulta não encontrada". Mapear a base no §8 cobre os três subtipos com uma entrada só, o `detail` do `ProblemDetail` vem da mensagem da exceção concreta, e um recurso novo em change futura herda em vez de exigir alteração no mapa.

Cancelar sem motivo pertence a esta segunda família, não à primeira. A requisição é sintaticamente impecável: um identificador de consulta válido e um campo de texto ausente. O que a recusa é a regra de que cancelamento hospitalar exige justificativa registrada — mesma natureza de "não se marca consulta no passado". Tratá-la como erro de formato responderia 400 a uma violação de política e apagaria essa diferença na pasta de cenários de erro da collection do M13, que é justamente onde ela precisa aparecer.

O mapa de exceções de `docs/01-arquitetura.md` §8 cobre as duas famílias explicitamente, incluindo `IllegalArgumentException` → 400 — sem essa linha ela cairia no handler genérico e um erro de entrada seria respondido como falha de servidor.

*Alternativa descartada:* uma exceção dedicada por formato inválido (`CpfInvalidoException`, `EmailInvalidoException`, `CrmInvalidoException`). Três tipos para o mesmo destino HTTP e a mesma reação do chamador, sem que nada no sistema decida diferente entre eles. `IllegalArgumentException` é a semântica exata de "argumento que não satisfaz o contrato do construtor" e já é a convenção da linguagem.

### D9 — Duração padrão de 30 minutos como constante do domínio

`Consulta.DURACAO_PADRAO_MINUTOS = 30`. O caso de uso aceita uma duração opcional e cai no padrão quando ela não vem. "Configurável" no sentido de `docs/01-arquitetura.md` §4 significa parametrizável por chamada; exteriorizar isso para `application.yml` exigiria `@Value`, que é framework, e portanto é assunto do M03.

### D10 — Mockito como java agent no surefire, compondo `@{argLine}`

O JDK vai proibir o auto-attach de agentes, e o Mockito já avisa isso a cada execução. A correção é declará-lo como `-javaagent` no surefire.

A armadilha é o `argLine`. O JaCoCo define a propriedade `argLine` no `prepare-agent`, e o surefire a consome automaticamente. Escrever `<argLine>-javaagent:...</argLine>` **substitui** esse valor: o agente do JaCoCo some, a instrumentação não acontece, e o relatório de cobertura passa a reportar zero — sem erro, sem aviso, com o build verde. É a falha silenciosa clássica dessa configuração.

A forma correta compõe, usando a sintaxe de late replacement do Maven:

```xml
<argLine>@{argLine} -javaagent:${org.mockito:mockito-core:jar}</argLine>
```

`@{argLine}` é resolvido no momento da execução do surefire, já com o valor que o JaCoCo escreveu. O caminho do jar do Mockito vem de `dependency:properties`, que precisa rodar antes — vinculado à fase `initialize`.

*Alternativa descartada:* silenciar o aviso com `-XX:+EnableDynamicAgentLoading`. Esconde o problema em vez de resolvê-lo, e para de funcionar quando o JDK remover o auto-attach de vez.

**Verificação obrigatória desta decisão:** depois de configurar, o relatório do JaCoCo tem de continuar reportando classes analisadas e cobertura diferente de zero. Se o número cair para zero, a composição do `argLine` quebrou.

### D11 — `application` sem retorno de entidade de domínio nua

Os casos de uso recebem e devolvem `record`s próprios de `application`, não a `Consulta`. Isso mantém a fronteira: o M03 serializa DTOs de aplicação, não o agregado, e uma mudança interna na `Consulta` não vira mudança de contrato HTTP.

## Risks / Trade-offs

| Risco | Mitigação |
|---|---|
| `argLine` sobrescrito em vez de composto zera a cobertura silenciosamente, com build verde | Task de verificação explícita: conferir que o relatório do JaCoCo reporta classes analisadas e cobertura > 0 depois da mudança do surefire, e não apenas que o build passou |
| Porta de conflito com assinatura permissiva empurra o filtro para memória e leva o M02 à reprovação técnica | Assinatura decidida em D4 já recorta por período e por status ativo; o fake filtra em Java, mas o contrato obriga o adaptador a filtrar no banco |
| Borda de horários adjacentes implementada como intervalo fechado, tornando adjacente igual a conflito | Cenário dedicado na spec, e a convenção `[inicio, fim)` declarada em D4 |
| `IllegalArgumentException` não mapeada no `@RestControllerAdvice` cair no handler genérico e virar 500 — erro de entrada respondido como falha de servidor | O mapa de `docs/01-arquitetura.md` §8 já a inclui explicitamente como 400, ao lado de `MotivoDeCancelamentoObrigatorio` como 422; a cobertura dessas duas entradas é critério de aceite do M03 |
| Fakes em memória divergirem do comportamento do adaptador real e mascararem um bug até o M02 | Os testes de integração do M02 executam o mesmo conjunto de asserções contra o adaptador real; divergência aparece lá |
| Cobertura ≥ 90% incentivar teste de getter para fechar número | Os cenários vêm da spec, não da lista de métodos não cobertos; a meta é consequência de cobrir as 38 asserções de comportamento |

## Migration Plan

Não se aplica. O change adiciona código novo em pacotes vazios e altera a configuração do surefire no POM pai. Não há dado a migrar, nenhum consumidor a quebrar, e nada em produção. Reverter é reverter o merge.

## Open Questions

Nenhuma. As duas ambiguidades materiais — o escopo do `AutenticarUsuarioUseCase` e o tratamento da `EventPublisherPort` — foram decididas com o Gabriel antes da criação desta change e estão registradas em `proposal.md`.
