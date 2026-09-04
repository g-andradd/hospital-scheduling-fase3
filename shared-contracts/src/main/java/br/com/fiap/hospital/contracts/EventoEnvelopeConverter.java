package br.com.fiap.hospital.contracts;

import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import org.springframework.amqp.core.*;
import org.springframework.amqp.support.converter.MessageConverter;
import static br.com.fiap.hospital.contracts.MensagemInvalidaException.Motivo.METADADOS;

public class EventoEnvelopeConverter implements MessageConverter {
    private final EventoJson json;
    public EventoEnvelopeConverter(EventoJson json) { this.json=json; }

    @Override public Message toMessage(Object object, MessageProperties properties) {
        if(!(object instanceof EventoEnvelope<?> e) || !(e.payload() instanceof ConsultaPayload p))
            throw new MensagemInvalidaException(METADADOS,"tipo");
        var envelope = new EventoEnvelope<>(e.eventId(),e.eventType(),e.aggregateId(),e.occurredAt(),e.version(),e.correlationId(),p);
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setContentEncoding(StandardCharsets.UTF_8.name());
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        properties.setHeader("x-event-id",envelope.eventId().toString());
        properties.setHeader("x-event-type",envelope.eventType().name());
        properties.setHeader("x-correlation-id",envelope.correlationId());
        return new Message(json.escrever(envelope).getBytes(StandardCharsets.UTF_8),properties);
    }
    @Override public EventoEnvelope<ConsultaPayload> fromMessage(Message message) {
        MessageProperties p=message.getMessageProperties();
        if(!MessageProperties.CONTENT_TYPE_JSON.equals(p.getContentType()) ||
                p.getHeaders().containsKey("__TypeId__") || p.getHeaders().containsKey("__ContentTypeId__") ||
                p.getHeaders().containsKey("__KeyTypeId__"))
            throw new MensagemInvalidaException(METADADOS,"content-type/tipo");
        String corpo;
        try {
            corpo=StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(message.getBody())).toString();
        } catch(CharacterCodingException e) {
            throw new MensagemInvalidaException(MensagemInvalidaException.Motivo.JSON,"utf-8");
        }
        var evento=json.ler(corpo);
        conferir(p,"x-event-id",evento.eventId().toString());
        conferir(p,"x-event-type",evento.eventType().name());
        conferir(p,"x-correlation-id",evento.correlationId());
        if(p.getReceivedRoutingKey()!=null && !evento.eventType().routingKey().equals(p.getReceivedRoutingKey()))
            throw new MensagemInvalidaException(METADADOS,"routing-key");
        return evento;
    }
    private static void conferir(MessageProperties p,String nome,String valor) {
        if(!valor.equals(p.getHeader(nome))) throw new MensagemInvalidaException(METADADOS,nome);
    }
}

