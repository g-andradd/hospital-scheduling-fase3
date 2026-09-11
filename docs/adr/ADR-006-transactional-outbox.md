# ADR-006 — Publicação por Transactional Outbox

## Contexto

Uma consulta confirmada no PostgreSQL deve produzir seu evento mesmo quando o broker está indisponível. Commit no banco e publicação AMQP são dois recursos independentes. O M02 já delimita as operações de escrita com decoradores transacionais de infraestrutura, preservando domínio/aplicação sem framework.

## Decisão

O caso de uso salva a consulta e entrega um fato imutável à EventPublisherPort. OutboxEventPublisher exige transação MANDATORY e insere o envelope completo via JdbcTemplate no mesmo datasource/JpaTransactionManager dos decoradores REQUIRED. Erro em qualquer escrita desfaz ambas; RabbitMQ não participa da requisição HTTP.

O adaptador compõe o snapshot de pessoas e valores anteriores durante essa transação. Gera eventId uma vez, persiste occurredAt do fato em UTC e captura correlationId do MDC HTTP (ou gera um para execução sem request). dataHora e dataHoraAnterior derivam do instante na zona America/Sao_Paulo na data da consulta; não representam o offset original, que timestamptz descarta.

Um scheduler fixedDelay=1000 chama o proxy transacional do relay. O lote seleciona até 50 pendentes, ORDER BY tentativas, criado_em, id, FOR UPDATE SKIP LOCKED. O índice parcial é outbox_evento(publicado_em) WHERE publicado_em IS NULL. Locks ficam retidos até o commit do lote.

A mensagem persistente é marcada como publicada somente após ACK e ausência de return. A confirmação tem timeout de 5s; conexão e RPC de canal, 2s. Erro/NACK/return/timeout incrementa uma vez o contador numeric inteiro não negativo e mantém a linha pendente. Não há teto nem descarte: ordenar por tentativas impede que cinquenta eventos defeituosos monopolizem lotes com eventos novos.

Se o broker aceitar e o commit local falhar, o mesmo eventId/envelope reaparece. At-least-once é consequência aceita e exige idempotência em M06/M08. O relay restaura correlação persistida por evento e limpa/restaura o MDC anterior; nunca consulta os cadastros para reconstruir o fato.

## Alternativas consideradas

- Publicar diretamente no caso de uso: introduz dual write, depende da disponibilidade do broker e permite divergência entre evento e consulta.
- Transação isolada para o outbox: quebra atomicidade com os decoradores existentes.
- Marcar publicado ao enviar ou só ao receber ACK: pode perder eventos por falha de conexão ou ausência de rota.
- Teto de tentativas com descarte: viola retenção de fatos aceitos.
- Reconstruir snapshot no relay: reescreve o passado com dados alterados depois do fato.
- XA entre PostgreSQL e RabbitMQ: aumenta complexidade e não elimina a obrigação de idempotência dos consumidores.

## Consequências

Aceitam-se duplicatas, crescimento de pendências e locks durante I/O. Operação deve observar contagem/idade/tentativas e corrigir a causa; não purgar pendências ou DLQs como recuperação automática. O agendamento permanece disponível durante falha do broker.

As exclusões GiST da V3 protegem consulta e outbox contra double-booking concorrente. 23P01 nas duas constraints nomeadas é conflito de agenda definitivo. 40P01/40001 são falhas transitórias de concorrência, traduzidas para AlteracaoConcorrenteException e seu type 409 próprio, com rollback integral. Não existe retry automático por decisão consciente para o ambiente demonstrativo; reavaliar retentativa limitada da transação inteira antes de múltiplas instâncias ou tráfego concorrente real.

Rollback da aplicação preserva migrations/dados e pode desligar o relay. Voltar ao adaptador antigo de log não é rollback funcional transparente: novas operações deixariam de produzir outbox. Preferir correção e avanço.

## Status

Aceita no M05, incluindo a decisão de concorrência ajustada e autorizada durante o apply. Archive, commit e push do archive precedem o merge, após aprovação do PR.

## Referências

- [Contrato local](../03-contrato-de-eventos.md)
- [Spring AMQP — confirms e returns](https://docs.spring.io/spring-amqp/reference/3.2/amqp/template.html)
