package br.com.fiap.hospital.agendamento.infrastructure.messaging;

import br.com.fiap.hospital.contracts.EventoEnvelopeConverter;
import br.com.fiap.hospital.contracts.EventoJson;
import br.com.fiap.hospital.contracts.MensagemInvalidaException;
import br.com.fiap.hospital.contracts.MensageriaAutoConfiguration;
import java.math.BigInteger;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OutboxRelay {
    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private final OutboxRepository outbox;
    private final RabbitTemplate rabbit;
    private final EventoJson json;
    private final EventoEnvelopeConverter converter;
    private final Clock clock;

    public OutboxRelay(OutboxRepository outbox, RabbitTemplate rabbit, EventoJson json,
            EventoEnvelopeConverter converter, Clock clock) {
        this.outbox = outbox;
        this.rabbit = rabbit;
        this.json = json;
        this.converter = converter;
        this.clock = clock;
    }

    @Transactional
    public int executar() {
        var lote = outbox.bloquearLote();
        for (var pendente : lote) {
            String anterior = MDC.get("correlationId");
            try {
                // Escritas fora do catch de AMQP: erro de banco aborta a transação.
                if (publicar(pendente)) {
                    outbox.publicado(pendente.id(), OffsetDateTime.now(clock));
                } else {
                    outbox.falhou(pendente.id());
                    log.warn("Publicacao pendente eventId={} tipo={} tentativas={}",
                            pendente.id(), pendente.routingKey(),
                            pendente.tentativas().add(BigInteger.ONE));
                }
            } finally {
                if (anterior == null) MDC.remove("correlationId");
                else MDC.put("correlationId", anterior);
            }
        }
        return lote.size();
    }

    private boolean publicar(OutboxRepository.Pendente pendente) {
        try {
            var evento = json.ler(pendente.json());
            MDC.put("correlationId", evento.correlationId());
            if (!evento.eventId().equals(pendente.id())
                    || !evento.eventType().routingKey().equals(pendente.routingKey())) {
                throw new MensagemInvalidaException(
                        MensagemInvalidaException.Motivo.METADADOS, "outbox");
            }
            var confirmacao = new CorrelationData(pendente.id() + ":" + UUID.randomUUID());
            var mensagem = converter.toMessage(evento, new MessageProperties());
            rabbit.send(MensageriaAutoConfiguration.EXCHANGE,
                    pendente.routingKey(), mensagem, confirmacao);
            var ack = confirmacao.getFuture().get(5, TimeUnit.SECONDS);
            return ack.isAck() && confirmacao.getReturned() == null;
        } catch (AmqpException | TimeoutException | ExecutionException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            // Desfaz o lote local; entregas já aceitas pelo broker podem reaparecer.
            throw new IllegalStateException("Relay interrompido", e);
        }
    }
}
