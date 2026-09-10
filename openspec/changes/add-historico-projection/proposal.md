# M08 — Projeção do histórico de consultas

## Why

Os eventos de consulta já são publicados com entrega at-least-once, mas o `historico-service` ainda não os transforma no read model exigido para o histórico clínico. O M08 materializa uma visão atual e uma trilha íntegra, preparando a persistência que o GraphQL do M09 consultará sem acoplar o histórico ao serviço de agendamento.

## What Changes

- Criar por Flyway as tabelas `consulta_historico`, `consulta_evento` e `evento_processado` no `historico_db`, conforme `docs/02-especificacao-funcional.md`.
- Consumir os cinco eventos normativos de consulta e, na mesma transação, atualizar condicionalmente o snapshot, inserir o fato na trilha e só então registrar o `eventId` como processado.
- Tornar o efeito idempotente inclusive sob duas entregas concorrentes do mesmo `eventId`, com proteção estrutural que obriga toda entrada AMQP do serviço a atravessar o processador transacional comum.
- Aceitar eventos fora de ordem na trilha e impedir regressão do snapshot por um upsert atômico que só aplica fatos com `occurredAt >= atualizado_em`.
- Rejeitar entradas malformadas e versões de envelope diferentes de 1 após as três tentativas configuradas, com `default-requeue-rejected: false`; nenhuma tentativa falha deixa trilha, snapshot ou marca de processamento.
- Persistir do snapshot do evento todos os campos do read model em colunas consultáveis por período, status, paciente e médico, sem chamada HTTP ao agendamento e sem antecipar os índices ainda não definidos pelo M09.

## Capabilities

### New Capabilities

- `historico-de-consultas`: projeção idempotente dos eventos de consulta em snapshot atual e trilha completa, resistente a reentrega, concorrência, falha transacional, mensagem inválida e chegada fora de ordem.

### Modified Capabilities

Nenhuma. O consumidor cumpre o contrato já promovido de `mensageria-de-eventos` sem alterar seus Requirements.

## Impact

**Release alvo:** `0.2.0`, no ciclo `0.2.0-SNAPSHOT`. **Fecha:** RF-18 e, para o serviço de histórico, RF-19. Preserva RF-20 e as garantias da capability `mensageria-de-eventos`.

Áreas afetadas: `historico-service`, seu `historico_db`, dependências de persistência/mensageria, migrations, testes com PostgreSQL e RabbitMQ reais e documentação operacional do serviço. O contrato compartilhado do M05 fornece todos os dados necessários e não precisa ser ampliado.

**Fora do escopo:** API GraphQL, autorização, filtros e mutation de correção do M09; índices voltados às consultas ainda não especificadas; consumidores de notificação; lembrete D-1; propagação completa de correlação nos logs e suíte ArchUnit do M11; qualquer endpoint ou cliente HTTP para o agendamento.

**Processo de entrega:** implementação e archive pertencem ao mesmo PR. Após a aprovação, a change será promovida e arquivada nesta feature, e o commit do archive será empurrado antes do merge. Todas as operações Git permanecem exclusivas do Gabriel.
