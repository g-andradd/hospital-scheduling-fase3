# M06 — Consumidor de notificações ao paciente

## Why

O agendamento publica os cinco eventos de consulta desde o M05, e a fila `notificacao.consultas` os recebe e acumula — mas ninguém os consome. O enunciado exige nominalmente que o serviço de notificações processe a mensagem e avise o paciente, e é o único requisito de mensageria da Fase 3 que ainda não tem consumidor. Sem esta change, a fila cresce sem destino e RF-16 fica aberto.

O M07 depende diretamente daqui: o lembrete D-1 só funciona sem chamar o agendamento porque a `agenda_local` é alimentada por estes eventos.

## What Changes

- Criar por Flyway as tabelas `agenda_local`, `notificacao_enviada` e `evento_processado` no `notificacao_db`, conforme `docs/02-especificacao-funcional.md`, mantendo `ddl-auto: validate`.
- Consumir `notificacao.consultas` pelo contrato compartilhado do M05, sem duplicar DTOs, sem alterar envelope, topologia ou tipos normativos, e sem qualquer cliente HTTP para o agendamento.
- Manter a agenda local a partir de **qualquer** um dos cinco tipos: todo evento válido materializa ou atualiza a linha por upsert, inclusive quando a criação daquela consulta ainda não foi processada. Cancelamento **atualiza o status** e nunca remove a linha.
- Impedir regressão da agenda por `occurredAt`: fato posterior aplica, fato estritamente anterior é marcado como processado sem alterar a agenda e sem enviar notificação obsoleta, e empate de instante segue a ordem de chegada, porque o contrato não declara sequência por agregado.
- Enviar as notificações reativas previstas em `docs/01-arquitetura.md` §5 — confirmação de agendamento, alteração e cancelamento — atreladas à **aplicação** do fato; **não** emitir notificação para confirmação e realização, que a documentação não pede, nem para fato descartado por ser anterior ao vigente.
- Tornar o efeito idempotente por `eventId`: agenda, auditoria e marca de processamento na mesma transação, com a marca gravada **depois** do efeito.
- Restaurar o `correlationId` já validado do envelope no MDC durante cada tentativa, com limpeza obrigatória no `finally`.
- Derivar do `Clock` injetado todos os instantes registrados — `atualizado_em`, `enviado_em` e `processado_em` —, sem leitura direta de relógio, com relógio fixo nos testes.
- Introduzir `NotificationSenderPort` com dois adaptadores selecionados por `notificacao.sender`, padrão `log`, sem provedor externo real e sem segredo no repositório.
- Registrar em `notificacao_enviada`, com template em português, a notificação de cada transação confirmada, para auditoria e para sustentar a regra de não reenvio do M07. O registro reflete o resultado persistido, não o transporte: na janela residual descrita abaixo pode haver mensagem entregue sem registro confirmado.
- Aplicar a política de rejeição já promovida: três tentativas, backoff normativo, `default-requeue-rejected: false`, e mensagem inválida sem efeito, sem auditoria e sem marca.

## Capabilities

### New Capabilities

- `notificacoes-ao-paciente`: consumo idempotente dos eventos de consulta, manutenção da agenda local, envio das notificações reativas ao paciente e auditoria do que foi enviado, resistente a reentrega, falha de envio, mensagem inválida e falha persistente.

### Modified Capabilities

Nenhuma. O consumidor cumpre o contrato já promovido de `mensageria-de-eventos` sem alterar seus Requirements.

## Impact

**Release alvo:** `0.2.0`, no ciclo `0.2.0-SNAPSHOT`. **Fecha:** RF-16 e, para o serviço de notificação, RF-19. Preserva RF-15, RF-20 e as garantias de `mensageria-de-eventos`.

Áreas afetadas: `notificacao-service` (migrations, listener, agenda local, porta de envio e adaptadores, templates, auditoria), seu `notificacao_db`, dependências de persistência e de teste, e a documentação operacional do serviço. O contrato compartilhado fornece todos os dados necessários e não precisa ser ampliado.

**Garantia declarada com honestidade:** o efeito **persistido** é exatamente-uma-vez por `eventId` — agenda, auditoria e marca commitam juntas, inclusive sob entregas concorrentes, em que a chave primária da marca serializa a confirmação. O **envio externo** é ao-menos-uma-vez, por dois caminhos: o sender conclui e o commit falha, e a tentativa seguinte reenvia; ou duas entregas concorrentes do mesmo `eventId` chamam ambas o sender antes de uma delas perder a disputa pela chave. Eliminar isso exigiria um outbox próprio do serviço de notificação, que não é pedido por RF-16, por RF-19 nem pelos critérios do M06 — e por isso não é improvisado aqui. As duas janelas ficam registradas em `design.md` e nos riscos, e a consequência sobre a auditoria é dita na spec em vez de suposta: o registro é do resultado persistido, não do transporte.

**Fora do escopo:** job `@Scheduled` do lembrete D-1 e endpoint manual do M07; `scripts/smoke-test.sh`, fixture de contrato cruzado e gate agregado de cobertura do M10; Actuator, logs JSON e propagação ponta a ponta de `correlationId` do M11; Dockerfiles, Compose final e Mailpit do M12; collection Postman do M13; qualquer alteração de topologia, envelope, routing key ou tipo normativo; qualquer chamada HTTP ao agendamento. O CHANGELOG não é tocado aqui — ele fecha com a release `0.2.0`.

**Processo de entrega:** implementação e archive pertencem ao mesmo PR. O archive será executado e commitado **na própria feature branch, depois da aprovação do PR e antes do merge**, para que código e spec promovida entrem em `develop` no mesmo merge `--no-ff`. Todas as operações Git permanecem exclusivas do Gabriel.
