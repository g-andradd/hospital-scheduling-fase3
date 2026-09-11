# M07 — Lembrete automático D-1

## Why

RF-17 é o único requisito funcional ainda aberto na release `0.2.0`, e é o "por quê" do enunciado: lembretes automáticos para garantir a presença do paciente. O M06 deixou a `agenda_local` materializada por qualquer evento, com cancelamento preservado e auditoria em `notificacao_enviada`, mas nada a lê de forma proativa — hoje o serviço só reage a eventos. Sem esta change, a consulta de amanhã nunca gera aviso.

## What Changes

- Varredura D-1 sobre a `agenda_local`: são candidatas as consultas `AGENDADA` ou `CONFIRMADA` cujo horário esteja em `(agora, agora + 24h]`, com `agora` lido do `Clock` injetado do M06. `CANCELADA` e `REALIZADA` nunca recebem lembrete.
- Seleção dos candidatos por **um único comando** no PostgreSQL, com os limites da janela como parâmetros e sem `now()`; nenhuma leitura por consulta dentro do laço.
- No máximo **um lembrete D-1 persistido por consulta durante toda a sua vida**, identificado por `consulta_id` e tipo `LEMBRETE_D1` em `notificacao_enviada`, garantido por unicidade no PostgreSQL e por reserva antes do envio — sem check-then-act. Consulta remarcada depois de lembrada não recebe segundo lembrete.
- Envio pela `NotificationSenderPort` e pelo adaptador já selecionado no M06, com conteúdo em português; registro com destinatário, canal, instante e conteúdo. Falha do sender reverte a tentativa daquela consulta, não deixa lembrete persistido e não impede as demais.
- Job `@Scheduled` horário, com cron configurável e execução automática habilitada por padrão; **efetivamente ausente** no profile `test`.
- Endpoint `POST /internal/lembretes/executar`, que responde 200 com a quantidade de lembretes enviados (inclusive zero), restrito a `MEDICO` e `ENFERMEIRO`; `PACIENTE` recebe 403 e token ausente, expirado ou inválido recebe 401. Falha na leitura inicial dos candidatos responde **500 em Problem Detail** (`erro-interno`), com `correlationId`, `timestamp` e `instance`, sem vazar detalhe interno e sem lembrete. Job e endpoint delegam ao mesmo caso de uso.
- O `notificacao-service` passa a depender de `shared-security`, reutilizando a única `SecurityFilterChain` com `/internal/**` como caminho autenticado. Comentários e testes do `shared-security` que ainda descrevem dois consumidores são atualizados.
- A matriz normativa de `docs/02-especificacao-funcional.md` §3 ganha a tabela `notificacao-service — REST interno`, com a linha `POST /internal/lembretes/executar | MEDICO ✅ | ENFERMEIRO ✅ | PACIENTE ❌ 403`. O leitor da matriz GraphQL passa a terminar no título novo; a matriz do agendamento permanece intacta; o notificação ganha leitor próprio e um teste de integração por célula alimentado pelo documento.
- Migration `V2` com unicidade parcial de `LEMBRETE_D1` em `notificacao_enviada` e índice de status/horário em `agenda_local`; `V1` permanece intacta.
- Documentação: README e `docs/01-arquitetura.md` §5; alinhamento de `docs/00` e `docs/05` — e do marcador de release no M09 de `docs/04` — ao fechamento da `0.2.0` depois do M07.

## Capabilities

### New Capabilities

Nenhuma.

### Modified Capabilities

- `notificacoes-ao-paciente`: ganha, por `ADDED Requirements`, a janela D-1, o registro do lembrete, a unicidade por consulta, a seleção em comando único, a execução automática configurável e o disparo manual protegido por perfil, com erros em Problem Detail. Os 9 Requirements e 26 Scenarios promovidos pelo M06 não são modificados.

## Impact

**Release alvo:** `0.2.0`, no ciclo `0.2.0-SNAPSHOT`. **Fecha:** RF-17. Preserva RF-16, RF-19 e todas as garantias de `notificacoes-ao-paciente` e `mensageria-de-eventos`.

**Áreas afetadas:**

- `notificacao-service`: caso de uso do lembrete, agendador, endpoint, tratador de erro do endpoint, configuração, `V2` e testes.
- `shared-security`: comentários e um caso de teste, sem mudança de comportamento.
- `historico-service`: somente o marcador final do leitor de teste da matriz GraphQL, que passa ao título novo; a asserção existente de 5 operações, 3 perfis e 15 células é preservada e precisa continuar verde.
- `agendamento-service`: sem mudança; sua matriz de 7 endpoints, 3 perfis e 21 células é conferida.
- Documentação.

A dependência nova do serviço é o próprio `shared-security`, cujas bibliotecas já estão no BOM. `JWT_SECRET` passa a ser obrigatório para subir o `notificacao-service`.

**Garantia declarada com honestidade, como no M06:** o efeito **persistido** é no máximo um lembrete por consulta, sob execuções sequenciais e concorrentes, e duas execuções que chegam juntas produzem uma única entrega ao canal. O **envio externo** continua ao-menos-uma-vez numa janela: se o sender conclui e a confirmação da transação falha, não fica registro, e a execução seguinte reenvia. Fechar essa janela exigiria outbox próprio do serviço, que nenhum requisito pede.

**O que NÃO muda:**

- Listener único, idempotência por `eventId` e ordem efeito→marca.
- Política de notificação reativa por tipo, templates reativos e seleção de adaptador.
- Envelope, topologia, routing keys e `TipoEvento` — `LEMBRETE_D1` é tipo local do registro de envios, não evento de integração.
- `V1`, que não é editada, e as células das matrizes do agendamento e do histórico.
- CHANGELOG, versão e release, que não são preparados aqui: isso acontece depois do M07.
- Escopo das changes seguintes: nada do smoke test e do gate agregado do M10, do Actuator e dos logs JSON do M11, do Mailpit e do Compose final do M12, da collection Postman do M13 nem do relatório do M14.
- Desacoplamento: nenhuma chamada HTTP ao agendamento.

**Processo de entrega:** implementação e archive pertencem ao mesmo PR. O archive é executado e commitado **na própria feature branch, depois da aprovação do PR e antes do merge**. Todas as operações Git permanecem exclusivas do Gabriel.
