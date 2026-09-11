# Changelog

Formato baseado em [Keep a Changelog](https://keepachangelog.com/pt-BR/1.1.0/).
Versionamento semântico. Cada entrada corresponde a uma change do OpenSpec,
arquivada em `openspec/changes/archive/`.

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
