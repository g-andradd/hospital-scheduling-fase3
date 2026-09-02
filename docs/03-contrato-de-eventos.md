# Contrato de Eventos — RabbitMQ

Este documento é **normativo**. Produtor e consumidores implementam exatamente o que está aqui. As classes vivem no módulo `shared-contracts` e são compartilhadas pelos três serviços.

## 1. Topologia

| Recurso | Nome | Tipo |
|---|---|---|
| Exchange principal | `hospital.consultas` | topic, durable |
| Exchange de dead-letter | `hospital.consultas.dlx` | topic, durable |
| Fila do notificações | `notificacao.consultas` | durable, `x-dead-letter-exchange: hospital.consultas.dlx` |
| Fila do histórico | `historico.consultas` | durable, `x-dead-letter-exchange: hospital.consultas.dlx` |
| DLQ do notificações | `notificacao.consultas.dlq` | durable |
| DLQ do histórico | `historico.consultas.dlq` | durable |

Bindings:

```
hospital.consultas       --[ consulta.# ]--> notificacao.consultas
hospital.consultas       --[ consulta.# ]--> historico.consultas
hospital.consultas.dlx   --[ consulta.# ]--> notificacao.consultas.dlq
hospital.consultas.dlx   --[ consulta.# ]--> historico.consultas.dlq
```

Ambos os consumidores recebem **todos** os eventos — é um fanout por tópico. Cada um decide o que fazer.

Declaração feita por `@Bean` no `shared-contracts` (ou por `definitions.json` montado no container do RabbitMQ). Preferir os beans: o ambiente se auto-configura, sem passo manual.

## 2. Routing keys

| Routing key | Quando |
|---|---|
| `consulta.criada` | Nova consulta registrada |
| `consulta.atualizada` | Data/hora, médico ou observações alterados |
| `consulta.confirmada` | Status → CONFIRMADA |
| `consulta.cancelada` | Status → CANCELADA |
| `consulta.realizada` | Status → REALIZADA |

## 3. Envelope

Todo evento usa o mesmo envelope. Isso permite um único deserializador e uma única checagem de idempotência.

```json
{
  "eventId": "9c1c5a6e-2f3b-4a1d-8c77-1b2e3f4a5b6c",
  "eventType": "CONSULTA_CRIADA",
  "aggregateId": "3f8b2c1a-...",
  "occurredAt": "2026-09-02T13:45:10.123Z",
  "version": 1,
  "correlationId": "0f2a9d...",
  "payload": { }
}
```

| Campo | Tipo | Regra |
|---|---|---|
| `eventId` | UUID | Único por evento. **Chave de idempotência** nos consumidores. |
| `eventType` | enum | `CONSULTA_CRIADA`, `CONSULTA_ATUALIZADA`, `CONSULTA_CONFIRMADA`, `CONSULTA_CANCELADA`, `CONSULTA_REALIZADA` |
| `aggregateId` | UUID | Id da consulta |
| `occurredAt` | ISO-8601 UTC | Momento do fato de negócio, não da publicação |
| `version` | int | Versão do schema do envelope. Começa em 1. |
| `correlationId` | string | Propagado do request HTTP que originou o fato |
| `payload` | objeto | Ver abaixo |

Headers AMQP: `x-event-id`, `x-event-type`, `x-correlation-id`, `content-type: application/json`.

## 4. Payload

O payload é o **mesmo para todos os tipos** — um snapshot completo da consulta após a mudança, mais o delta quando houver. Snapshot completo evita que o consumidor precise buscar dados no produtor (que quebraria o desacoplamento) e torna os eventos auto-suficientes.

```json
{
  "consultaId": "3f8b2c1a-...",
  "status": "AGENDADA",
  "dataHora": "2026-09-10T14:00:00-03:00",
  "duracaoMinutos": 30,
  "observacoes": "Retorno de rotina",
  "motivoCancelamento": null,
  "paciente": {
    "id": "a1b2...",
    "nome": "Maria Souza",
    "email": "paciente@hospital.com",
    "telefone": "+5561999990000"
  },
  "medico": {
    "id": "c3d4...",
    "nome": "Dr. João Lima",
    "crm": "DF-12345",
    "especialidade": "Cardiologia"
  },
  "registradoPor": {
    "id": "e5f6...",
    "nome": "Ana Enfermeira",
    "perfil": "ENFERMEIRO"
  },
  "alteracoes": {
    "dataHoraAnterior": "2026-09-09T10:00:00-03:00"
  }
}
```

`alteracoes` só aparece em `CONSULTA_ATUALIZADA` e lista apenas os campos que mudaram, com o valor anterior.

## 5. Publicação — Transactional Outbox

O agendamento **não publica direto no RabbitMQ dentro do caso de uso**. O fluxo é:

1. O caso de uso altera a `consulta` e insere a linha em `outbox_evento` — **mesma transação JDBC**. Ou os dois acontecem, ou nenhum.
2. Um `@Scheduled(fixedDelay = 1000)` lê os registros com `publicado_em IS NULL` (com `FOR UPDATE SKIP LOCKED`), publica no exchange e marca como publicado.
3. Falha na publicação incrementa `tentativas`; o registro continua pendente e é retentado.

Consequência aceita: entrega **at-least-once**. Por isso a idempotência do lado do consumidor é obrigatória, não opcional.

> **Corte possível:** se o prazo apertar, M05 pode ser entregue com `RabbitTemplate` chamado direto no caso de uso, atrás da porta `EventPublisherPort`. A porta já isola a decisão — trocar depois é um adaptador novo, não uma refatoração. O relatório deve declarar a escolha e o trade-off.

## 6. Consumo — idempotência e retry

Todo listener segue o mesmo esqueleto:

```java
@RabbitListener(queues = "notificacao.consultas")
public void consumir(EventoEnvelope<ConsultaPayload> evento) {
    if (eventoProcessadoRepository.existsById(evento.eventId())) {
        log.debug("Evento {} já processado, ignorando", evento.eventId());
        return;                              // idempotência
    }
    MDC.put("correlationId", evento.correlationId());
    try {
        processar(evento);                   // efeito de negócio
        eventoProcessadoRepository.save(new EventoProcessado(evento.eventId()));
    } finally {
        MDC.clear();
    }
}
```

Marcar como processado **depois** do efeito, na mesma transação do efeito.

Retry configurado no `application.yml`:

```yaml
spring:
  rabbitmq:
    listener:
      simple:
        acknowledge-mode: auto
        default-requeue-rejected: false     # falha vai pra DLX, não fica em loop
        retry:
          enabled: true
          max-attempts: 3
          initial-interval: 1000ms
          multiplier: 2.0
          max-interval: 10000ms
```

`default-requeue-rejected: false` é crítico. Sem isso, uma mensagem envenenada volta pra fila infinitamente e derruba o consumidor — é o erro clássico com Spring AMQP.

## 7. Testes obrigatórios do contrato

| Teste | Milestone |
|---|---|
| Criar consulta publica exatamente um evento com `eventType=CONSULTA_CRIADA` e o snapshot correto | M05 |
| Falha ao publicar deixa o registro no outbox e ele é reenviado na próxima varredura | M05 |
| Consumidor recebe o mesmo `eventId` duas vezes e produz um único efeito | M06, M08 |
| Payload malformado ou erro repetido leva a mensagem para a DLQ após 3 tentativas | M05 |
| `correlationId` do request HTTP aparece no log do notificacao-service | M11 |
