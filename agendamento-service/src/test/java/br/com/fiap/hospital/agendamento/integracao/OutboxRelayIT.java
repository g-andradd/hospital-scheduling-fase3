package br.com.fiap.hospital.agendamento.integracao;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.slf4j.MDC;

class OutboxRelayIT extends M05RabbitBase {
    @Test @DisplayName("Pendente confirmado passa a publicado")
    
    // Scenario: Pendente confirmado passa a publicado
    void ackSemReturnPublicaPersistente() {
        criar();var e=eventos().getFirst();
        assertThat(relay.executar()).isEqualTo(1);
        publicado(true);tentativas(0);
        var m=receber(N);assertThat(json.ler(new String(m.getBody(),java.nio.charset.StandardCharsets.UTF_8))).isEqualTo(e);
        assertThat(m.getMessageProperties().getReceivedDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
        assertThat(receber(H).getBody()).isEqualTo(m.getBody());
        assertThat(relay.executar()).isZero();
    }
    @ParameterizedTest(name="Falha mantém pendente e incrementa tentativas: {0}")
    @ValueSource(strings={"exchange","rota","conexao"})
    
    // Scenario: Falha mantém pendente e incrementa tentativas
    void falhasReaisRetem(String falha) {
        criar();
        if(falha.equals("exchange"))admin.deleteExchange(X);
        if(falha.equals("rota")) {
            admin.removeBinding(new Binding(N,Binding.DestinationType.QUEUE,X,"consulta.#",null));
            admin.removeBinding(new Binding(H,Binding.DestinationType.QUEUE,X,"consulta.#",null));
        }
        if(falha.equals("conexao"))ctl("stop_app");
        try {
            assertThat(relay.executar()).isEqualTo(1);
            publicado(false);tentativas(1);
            if(falha.equals("rota")) {
                await().atMost(Duration.ofSeconds(5)).untilAsserted(()->assertThat(confirms.acks.values()).contains(true));
                assertThat(rabbit.receive(N)).isNull();assertThat(rabbit.receive(H)).isNull();
            }
        } finally {
            if(falha.equals("conexao"))ctl("start_app");
            ((RabbitAdmin)admin).initialize();
        }
        assertThat(relay.executar()).isEqualTo(1);publicado(true);tentativas(1);
        receber(N);receber(H);
    }
    @Test void nackDoBrokerNaoMarcaPublicado() throws Exception {
        criar();
        String fila="teste.nack.m05";
        admin.declareQueue(QueueBuilder.durable(fila).quorum().withArgument("x-max-length",1L)
                .withArgument("x-overflow","reject-publish").build());
        admin.declareBinding(new Binding(fila,Binding.DestinationType.QUEUE,X,"consulta.#",null));
        try {
            var e=eventos().getFirst();
            boolean nack=false;
            // Quorum admite ultrapassar o limite enquanto propaga o backpressure.
            for(int i=0;i<20&&!nack;i++) {
                var c=new CorrelationData("controle-"+i);
                rabbit.send(X,e.eventType().routingKey(),converter.toMessage(e,new MessageProperties()),c);
                nack=!c.getFuture().get(5,TimeUnit.SECONDS).isAck();
            }
            assertThat(nack).as("broker aplicou reject-publish real").isTrue();
            confirms.acks.clear();
            relay.executar();publicado(false);tentativas(1);
            await().atMost(Duration.ofSeconds(5)).untilAsserted(()->assertThat(confirms.acks.values()).contains(false));
        } finally {
            admin.deleteQueue(fila);
            ((org.springframework.amqp.rabbit.connection.CachingConnectionFactory)rabbit.getConnectionFactory()).resetConnection();
        }
    }
    @Test void timeoutDeConfirmacaoEhFinitoEIncrementaUmaVez() {
        criar();
        // Alarme real de memória faz o broker bloquear publicação, sem simular o RabbitTemplate.
        ctl("set_vm_memory_high_watermark","absolute","1");
        try {
            long antes=System.nanoTime();relay.executar();
            assertThat(Duration.ofNanos(System.nanoTime()-antes)).isBetween(Duration.ofSeconds(4),Duration.ofSeconds(15));
            publicado(false);tentativas(1);
        } finally {ctl("set_vm_memory_high_watermark","0.4");}
    }
    @Test @DisplayName("Falha repetida não descarta nem bloqueia eventos novos")
    
    // Scenario: Falha repetida não descarta nem bloqueia eventos novos
    // Scenario: Contador não transborda em inteiro de 32 bits
    void cinquentaDefeituososNaoMonopolizamLoteEContadorNaoTransborda() {
        criar();duplicar(49);
        jdbc.update("UPDATE outbox_evento SET payload='{}'::jsonb,tentativas=2147483647");
        assertThat(relay.executar()).isEqualTo(50);
        assertThat(jdbc.queryForObject("SELECT min(tentativas) FROM outbox_evento",java.math.BigDecimal.class)).isEqualByComparingTo("2147483648");
        agendar.executar(comando(paciente,medico,INICIO.plusDays(1)));
        assertThat(relay.executar()).isEqualTo(50);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_evento WHERE publicado_em IS NOT NULL",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_evento WHERE publicado_em IS NULL",Integer.class)).isEqualTo(50);
        receber(N);receber(H);
    }
    @Test 
    // Scenario: Eventos sucessivos não compartilham correlação por acidente
    void correlacaoPersistidaRestauraMdcEHeaderSemReconstituirSnapshot() {
        try {
            MDC.put("correlationId","request-a");criar();
            MDC.put("correlationId","request-b");agendar.executar(comando(paciente,medico,INICIO.plusDays(1)));
            MDC.clear();agendar.executar(comando(paciente,medico,INICIO.plusDays(2)));
            var antes=eventos();
            jdbc.update("UPDATE usuario SET nome='Posterior'");
            MDC.put("correlationId","contexto-relay");
            relay.executar();
            assertThat(MDC.get("correlationId")).isEqualTo("contexto-relay");
            assertThat(confirms.correlações).hasSize(3).allSatisfy(c->assertThat(c.mdc()).isEqualTo(c.header()));
            var recebidos=new ArrayList<Object>();
            for(int i=0;i<3;i++){recebidos.add(converter.fromMessage(receber(N)));receber(H);}
            assertThat(recebidos).containsExactlyInAnyOrderElementsOf(antes);
        } finally {MDC.clear();}
    }
}
