package br.com.fiap.hospital.contracts;

import static org.assertj.core.api.Assertions.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;

class ContratoDeEventosRabbitMqIT extends RabbitITBase {
    @ParameterizedTest(name="Cinco tipos usam envelope e routing key correspondentes: {0}")
    @EnumSource(TipoEvento.class)
    
    // Scenario: Cinco tipos usam envelope e routing key correspondentes
    // Scenario: Momento do fato não muda com a publicação tardia
    void envelopeTardioImutavel(TipoEvento tipo) throws Exception {
        var tree=EntradasAmqp.arvore(); tree.put("eventType",tipo.name());
        var p=(com.fasterxml.jackson.databind.node.ObjectNode)tree.get("payload");
        switch(tipo) {
            case CONSULTA_ATUALIZADA -> p.putObject("alteracoes");
            case CONSULTA_CONFIRMADA -> p.put("status","CONFIRMADA");
            case CONSULTA_CANCELADA -> { p.put("status","CANCELADA"); p.put("motivoCancelamento","Solicitação"); }
            case CONSULTA_REALIZADA -> p.put("status","REALIZADA");
            default -> { }
        }
        var evento=json.ler(tree.toString());
        var c=new CorrelationData(evento.eventId().toString());
        rabbit.send(X,tipo.routingKey(),converter.toMessage(evento,new MessageProperties()),c);
        assertThat(c.getFuture().get(5,TimeUnit.SECONDS).isAck()).isTrue();
        assertThat(c.getReturned()).isNull();
        for(String fila:java.util.List.of(N,H)) {
            var recebido=receber(fila);
            assertThat(recebido.getMessageProperties().getReceivedRoutingKey()).isEqualTo(tipo.routingKey());
            var envelope=(EventoEnvelope<?>)converter.fromMessage(recebido);
            assertThat(envelope).isEqualTo(evento);
            assertThat(envelope.occurredAt()).as("Momento do fato não muda com a publicação tardia")
                    .isEqualTo(java.time.Instant.parse(tree.get("occurredAt").asText()));
        }
    }
}
