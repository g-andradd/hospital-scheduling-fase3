# Verificação do apply — 2026-09-04

Estado do working tree: implementação concluída, 37/38 tasks. A task 9.6 depende do commit/push e do clone limpo executados pelo Gabriel. Nenhum comando Git foi executado.

## Gates finais

- `openspec validate add-event-publishing-outbox --strict`: válido; `openspec status --change add-event-publishing-outbox`: 4/4 artefatos completos.
- `mvn -q clean verify` na raiz: exit 0; 948 testes, zero falhas, erros ou ignorados. Por módulo: agendamento 744, shared-contracts 133, shared-security 69, notificação 1 e histórico 1.
- Cobertura de linhas: shared-contracts 97,18%; shared-security 100%; agendamento-service 97,70%; domain + application do agendamento 99,01%. Notificação e histórico não têm linhas instrumentáveis nesta change; somente configuração compartilhada e testes de contexto foram tocados.
- Inspeção estrutural: zero imports proibidos em domain/application, decoradores transacionais presentes e zero listeners de negócio antecipados. A suíte ArchUnit permanece no M11, conforme `docs/04-roadmap.md`.
- As três provas de mutação passaram: exclusões removidas fazem o teste concorrente detectar duas gravações; catálogo hostil incompleto é recusado pela cobertura; recoverer que engole erro faz a prova de DLQ detectar a perda.

## Evidência dos critérios de aceite

- Contrato/topologia: cinco tipos e routing keys, envelope v1, mensagens persistentes, dois destinos, quatro filas quorum, DLX/DLQs, `x-death`, reinício e indisponibilidade temporária da DLX validados em RabbitMQ 3.13 real.
- Atomicidade: consulta e outbox confirmam ou revertem juntos nos quatro casos de escrita, inclusive falhas antes/depois do outbox e chamada fora de transação.
- Relay: lote 50 com `FOR UPDATE SKIP LOCKED`, ordenação por tentativas/data/id, concorrência entre duas instâncias, ACK sem return, NACK/return/timeout/conexão e reentrega idêntica após rollback local validados.
- At-least-once/correlação/offset: `eventId` e snapshot persistidos permanecem idênticos, correlationId reaparece no header/MDC após o request e o offset deriva de America/Sao_Paulo no instante do fato.
- Entradas AMQP hostis: catálogo recursivo completo exercido nas duas filas com três tentativas, 1s/2s, bytes originais na DLQ, mensagem válida seguinte e nenhum loop infinito.
- Concorrência: GiST impede dupla reserva por médico/paciente e remarcação; a corrida HTTP resulta em exatamente uma consulta, um outbox, uma resposta 201 e uma 409, aceita `23P01`, `40P01` ou `40001` na categoria correta e não produz 5xx.
- Durabilidade: leitura sob fuso de sessão diferente preserva o instante; a spec não promete preservar o offset original.
- Migrations: banco vazio, upgrade V1, seed V900 real, reaplicação, trigger derivado e recusa explícita de dados sobrepostos foram validados em PostgreSQL 16 real sem alterar V1/V900.
- Documentação: ADR-001, ADR-006, README, arquitetura, modelo físico, Javadoc e matriz dos 49 Scenarios foram conferidos.
