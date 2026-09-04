# ADR-001 — RabbitMQ para eventos de consulta

## Contexto

Notificação e histórico precisam receber os cinco eventos de consulta independentemente. O contrato de eventos define exchanges topic, routing keys, envelope versionado e retenção de mensagens recusadas. O ambiente de entrega é Docker Compose.

## Decisão

Usar RabbitMQ 3.13 com Spring AMQP. A auto-configuração de shared-contracts declara os exchanges duráveis hospital.consultas e hospital.consultas.dlx, as filas quorum duráveis notificacao.consultas e historico.consultas e suas duas .dlq. Os quatro bindings usam consulta.#; rejeição em qualquer origem alcança ambas as DLQs, identificada por x-death.

Nas origens, x-dead-letter-strategy=at-least-once e x-overflow=reject-publish preservam mensagens durante indisponibilidade dos destinos. A feature flag stream_queue deve estar habilitada. Não existem TTL nem reenvio automático das DLQs.

Publicação usa mensagens persistentes, mandatory, confirms correlacionados e returns. ACK sem return é o critério de sucesso: o ACK sozinho não prova roteamento nem consumo.

A factory compartilhada usa reconhecimento AUTO, default-requeue-rejected=false e uma cadeia stateless: três tentativas totais, intervalos de 1s e 2s (configuração initial=1000ms, multiplier=2, max=10000ms). A conversão/validação estrita ocorre dentro do retry; o recoverer rejeita sem requeue para a DLX. Não há consumidores de negócio antecipados no M05.

## Alternativas consideradas

- Kafka: suas capacidades de log distribuído não atendem a uma necessidade adicional deste escopo e alterariam o contrato normativo.
- Filas clássicas com dead-lettering padrão: podem perder mensagens durante a transferência ao destino indisponível.
- Requeue sem limite: prende mensagens inválidas em um ciclo infinito.
- Validar só no método de negócio: não cobre JSON/conversão que falha antes do método.

## Consequências

Entrega e dead-lettering são at-least-once; consumidores M06/M08 devem ser idempotentes por eventId. O nó único local não oferece alta disponibilidade nem proteção contra perda do disco.

A recuperação do dead-letter worker pode demorar: o RabbitMQ 3.13 usa intervalo de confirmação de 180s. Os ITs exercitam esse intervalo real, além de reinício no mesmo volume, topologia efetiva e ataques às duas filas. Fila existente incompatível deve ser migrada com preservação de mensagens; a aplicação não a apaga para iniciar.

## Status

Aceita no M05. Implementação e verificação registradas em openspec/changes/add-event-publishing-outbox; promoção da capability ocorre no archive após aprovação do PR.

## Referências

- [Contrato local](../03-contrato-de-eventos.md)
- [RabbitMQ 3.13 — quorum queues](https://www.rabbitmq.com/docs/3.13/quorum-queues)
- [Configuração do RabbitMQ 3.13.7](https://github.com/rabbitmq/rabbitmq-server/blob/v3.13.7/deps/rabbit/Makefile)
