# M05 — Contrato de eventos e Transactional Outbox

## Why

O agendamento ainda registra eventos apenas em log: uma consulta confirmada no banco não chega aos serviços satélites. O M05 estabelece a publicação recuperável no RabbitMQ e fecha duas lacunas já identificadas: concorrência permitindo agenda dupla e a promessa incorreta de preservar o offset em `timestamptz`.

## What Changes

- Implementar literalmente a topologia, as cinco routing keys, o envelope versão 1 e o snapshot de `docs/03-contrato-de-eventos.md`, com contratos e configuração compartilhados em `shared-contracts`.
- Persistir consulta e envelope completo no outbox na mesma transação dos decoradores do M02. Capturar os valores anteriores para `CONSULTA_ATUALIZADA` antes de mutar a consulta.
- Publicar pelo relay com lote de até 50, intervalo de 1 segundo, bloqueio com `SKIP LOCKED`, confirmação do broker e recuperação de falhas; aceitar reentrega com o mesmo `eventId`.
- Persistir a correlação HTTP no envelope e restaurá-la nos headers da publicação tardia. Representar o horário da consulta com o offset de `America/Sao_Paulo` no instante do agendamento, sem alegar preservar o offset enviado pelo cliente.
- Declarar DLX, duas DLQs e retry limitado a três tentativas, incluindo erros de conversão, campos obrigatórios ausentes e versão desconhecida. `default-requeue-rejected: false` integra o requisito e a evidência de execução.
- Corrigir a disputa entre transações com duas constraints de exclusão GiST, uma para médico e outra para paciente, em migration V3; traduzir `23P01` para conflito de agenda e `40P01`/`40001` para alteração concorrente, ambas como `409 ProblemDetail`, sem retry automático neste marco.
- Corrigir, por delta, a garantia de durabilidade para preservação do instante. Atualizar documentação e escrever ADR-001 e ADR-006 durante a implementação.

## Capabilities

### New Capabilities

- `mensageria-de-eventos`: contrato, publicação transacional recuperável, topologia, correlação, entrega at-least-once e tratamento limitado de falhas de consumo.

### Modified Capabilities

- `agendamento-de-consultas`: modificar integralmente os Requirements **Detecção de conflito resolvida pelo armazenamento** (exclusão também sob concorrência) e **Durabilidade das consultas** (preservação do instante, sem garantia do offset original), preservando os cenários existentes.

## Impact

**Release alvo:** `0.2.0`, no ciclo `0.2.0-SNAPSHOT`. **Fecha:** RF-15 e RF-20. **Corrige:** RF-09 e a redação da garantia de persistência. Mantém RNF-03, RNF-09 e RNF-10; entrega o trecho produtor → AMQP de RNF-08, cujo fechamento permanece no M11. RF-19 será fechado pelos consumidores em M06/M08.

Áreas: `shared-contracts`; eventos de domínio e sua produção em `application`; adaptadores de persistência, mensageria e transação do agendamento; migrations V2 (outbox) e V3 (exclusão); dependências AMQP/JSON/testes; configuração de conexão/retry compartilhada e aplicada aos três serviços. PostgreSQL 16 e RabbitMQ 3.13 reais sustentam os ITs. A migration V3 exige `btree_gist` e ausência de sobreposições ativas preexistentes.

**Fora do escopo:** listeners com efeitos de notificação/projeção, tabelas de idempotência dos consumidores, lembrete D-1, GraphQL, novos endpoints (inclusive realizar consulta), novos perfis e alterações da matriz de autorização. O contrato de `CONSULTA_REALIZADA` será testado por evento de domínio e broker, sem criar uma operação HTTP. Os consumidores usados para provar retry são fixtures de integração sem efeito de negócio. Não antecipa agregação JaCoCo do M10 nem propagação nos logs dos três serviços do M11.

**Processo de entrega:** esta etapa termina na proposta. Implementação depende da aprovação do Gabriel; após aprovação do PR, o archive deverá ser commitado e empurrado na própria feature antes do merge. Todas as operações Git ficam com o Gabriel.
