package br.com.fiap.hospital.contracts;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.core.RabbitAdmin;

class TopologiaRabbitMqIT extends RabbitITBase {
    @Test @DisplayName("Todos os destinos recebem os eventos")
    
    // Scenario: Todos os destinos recebem os eventos
    void definicoesEfetivasEFanout() throws Exception {
        for(String fila:List.of(N,H,N+".dlq",H+".dlq")) {
            var q=management("/api/queues/%2F/"+fila);
            assertThat(q.get("durable").asBoolean()).isTrue();
            assertThat(q.get("type").asText()).isEqualTo("quorum");
            if(!fila.endsWith(".dlq")) {
                assertThat(q.at("/arguments/x-dead-letter-exchange").asText()).isEqualTo(D);
                assertThat(q.at("/arguments/x-dead-letter-strategy").asText()).isEqualTo("at-least-once");
                assertThat(q.at("/arguments/x-overflow").asText()).isEqualTo("reject-publish");
            }
        }
        for(String exchange:List.of(X,D)) {
            var x=management("/api/exchanges/%2F/"+exchange);
            assertThat(x.get("type").asText()).isEqualTo("topic");
            assertThat(x.get("durable").asBoolean()).isTrue();
            var bindings=management("/api/exchanges/%2F/"+exchange+"/bindings/source");
            assertThat(bindings).hasSize(2);
            bindings.forEach(b->assertThat(b.get("routing_key").asText()).isEqualTo("consulta.#"));
        }
        var message=EntradasAmqp.mensagem(EntradasAmqp.fixture());
        rabbit.send(X,"consulta.criada",message);
        assertThat(receber(N).getBody()).isEqualTo(message.getBody());
        assertThat(receber(H).getBody()).isEqualTo(message.getBody());
    }
    @Test @DisplayName("Dead letter alcança as duas DLQs")
    
    // Scenario: Dead letter alcança as duas DLQs
    void rejeicaoPreservaCorpoChaveEMarcaXDeath() {
        var message=EntradasAmqp.mensagem("{");
        rabbit.send(X,"consulta.criada",message);
        rejeitar(N);
        for(String fila:List.of(N+".dlq",H+".dlq")) {
            var dead=receber(fila);
            assertThat(dead.getBody()).isEqualTo(message.getBody());
            assertThat(dead.getMessageProperties().getReceivedRoutingKey()).isEqualTo("consulta.criada");
            assertThat(dead.getMessageProperties().getXDeathHeader()).anySatisfy(d-> {
                assertThat(d.get("reason").toString()).isEqualTo("rejected");
                assertThat(d.get("queue").toString()).isEqualTo(N);
            });
        }
        assertThat(rabbit.receive(N)).isNull();
        assertThat(receber(H).getBody()).isEqualTo(message.getBody());
    }
    @Test @DisplayName("Destino de dead letter temporariamente indisponível")
    
    // Scenario: Destino de dead letter temporariamente indisponível
    void dlxIndisponivelRetemEEntregaAoRestaurar() {
        admin.deleteExchange(D);
        try {
            var message=EntradasAmqp.mensagem("{");
            rabbit.send(X,"consulta.criada",message);
            rejeitar(N);
            await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(5)).untilAsserted(()-> {
                assertThat(rabbit.receive(N+".dlq")).isNull();
                assertThat(rabbit.receive(H+".dlq")).isNull();
                assertThat(rabbit.receive(N)).isNull(); // retido pelo dead-letter worker, sem requeue
            });
            ((RabbitAdmin)admin).initialize();
            // RabbitMQ 3.13 usa 180s para retentar os confirms do dead-letter worker.
            // https://github.com/rabbitmq/rabbitmq-server/blob/v3.13.7/deps/rabbit/Makefile
            await().atMost(Duration.ofSeconds(210)).untilAsserted(()->assertThat(admin.getQueueInfo(N+".dlq").getMessageCount()).isPositive());
            assertThat(receber(N+".dlq").getBody()).isEqualTo(message.getBody());
            assertThat(receber(H+".dlq").getBody()).isEqualTo(message.getBody());
        } finally { ((RabbitAdmin)admin).initialize(); }
    }
    @Test @DisplayName("Recursos e mensagens sobrevivem ao reinício do broker")
    
    // Scenario: Recursos e mensagens sobrevivem ao reinício do broker
    void reinicioDoBrokerNoMesmoVolume() throws Exception {
        var message=EntradasAmqp.mensagem(EntradasAmqp.fixture());
        var c=new org.springframework.amqp.rabbit.connection.CorrelationData(UUID.randomUUID().toString());
        rabbit.send(X,"consulta.criada",message,c);
        assertThat(c.getFuture().get(5,java.util.concurrent.TimeUnit.SECONDS).isAck()).isTrue();
        assertThat(BROKER.execInContainer("rabbitmqctl","stop_app").getExitCode()).isZero();
        assertThat(BROKER.execInContainer("rabbitmqctl","start_app").getExitCode()).isZero();
        assertThat(receber(N).getBody()).isEqualTo(message.getBody());
        assertThat(receber(H).getBody()).isEqualTo(message.getBody());
    }
    private com.fasterxml.jackson.databind.JsonNode management(String path) throws Exception {
        var authorization=Base64.getEncoder().encodeToString((BROKER.getAdminUsername()+":"+BROKER.getAdminPassword()).getBytes(StandardCharsets.UTF_8));
        var request=HttpRequest.newBuilder(URI.create("http://"+BROKER.getHost()+":"+BROKER.getHttpPort()+path))
                .header("Authorization","Basic "+authorization).timeout(Duration.ofSeconds(5)).build();
        var response=HttpClient.newHttpClient().send(request,HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return EntradasAmqp.MAPPER.readTree(response.body());
    }
}
