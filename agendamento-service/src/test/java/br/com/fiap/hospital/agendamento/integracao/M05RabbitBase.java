package br.com.fiap.hospital.agendamento.integracao;

import static org.assertj.core.api.Assertions.*;
import br.com.fiap.hospital.agendamento.infrastructure.messaging.*;
import br.com.fiap.hospital.contracts.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.*;
import org.springframework.test.context.*;
import org.testcontainers.containers.RabbitMQContainer;

@ImportAutoConfiguration(RabbitAutoConfiguration.class)
@Import({OutboxRelay.class,EventoEnvelopeConverter.class,M05RabbitBase.Config.class})
abstract class M05RabbitBase extends M05JpaBase {
    static final RabbitMQContainer BROKER=new RabbitMQContainer("rabbitmq:3.13-management");
    static {BROKER.start();}
    static final String X=MensageriaAutoConfiguration.EXCHANGE,N=MensageriaAutoConfiguration.NOTIFICACAO,H=MensageriaAutoConfiguration.HISTORICO;
    @DynamicPropertySource static void rabbit(DynamicPropertyRegistry r) {
        r.add("spring.rabbitmq.host",BROKER::getHost);r.add("spring.rabbitmq.port",BROKER::getAmqpPort);
        r.add("spring.rabbitmq.username",BROKER::getAdminUsername);r.add("spring.rabbitmq.password",BROKER::getAdminPassword);
    }
    @Autowired OutboxRelay relay;
    @Autowired RabbitTemplate rabbit;
    @Autowired AmqpAdmin admin;
    @Autowired EventoEnvelopeConverter converter;
    @Autowired Probe confirms;
    @BeforeEach void limparBroker() {
        ((org.springframework.amqp.rabbit.connection.CachingConnectionFactory)rabbit.getConnectionFactory()).resetConnection();
        ((RabbitAdmin)admin).initialize();
        for(String fila:List.of(N,H,N+".dlq",H+".dlq"))admin.purgeQueue(fila);
        confirms.acks.clear();confirms.correlações.clear();
    }
    @TestConfiguration static class Config {
        @Bean Declarables declaracoes(){return new MensageriaAutoConfiguration().topologiaConsultas();}
        @Bean Probe confirmsM05(RabbitTemplate rabbit) {
            var probe=new Probe();
            rabbit.setConfirmCallback(probe);
            rabbit.setBeforePublishPostProcessors(m->{
                probe.correlações.add(new Correlacao(m.getMessageProperties().getHeader("x-correlation-id"),org.slf4j.MDC.get("correlationId")));
                return m;
            });
            return probe;
        }
    }
    record Correlacao(String header,String mdc){}
    static class Probe implements RabbitTemplate.ConfirmCallback {
        final Map<String,Boolean> acks=new ConcurrentHashMap<>();
        final List<Correlacao> correlações=new CopyOnWriteArrayList<>();
        public void confirm(CorrelationData c,boolean ack,String cause) {if(c!=null)acks.put(c.getId(),ack);}
    }
    void ctl(String... args) {
        String[] comando=new String[args.length+1];comando[0]="rabbitmqctl";System.arraycopy(args,0,comando,1,args.length);
        try {var resultado=BROKER.execInContainer(comando);assertThat(resultado.getExitCode()).as(resultado.getStderr()).isZero();}
        catch(Exception e){throw new IllegalStateException(e);}
    }
    Message receber(String fila) {var m=rabbit.receive(fila,10000);assertThat(m).isNotNull();return m;}
    void publicado(boolean valor) {
        assertThat(jdbc.queryForObject("SELECT bool_and(publicado_em IS NOT NULL) FROM outbox_evento",Boolean.class)).isEqualTo(valor);
    }
    void tentativas(long valor) {
        assertThat(jdbc.queryForObject("SELECT tentativas FROM outbox_evento",java.math.BigDecimal.class)).isEqualByComparingTo(java.math.BigDecimal.valueOf(valor));
    }
    void duplicar(int quantidade) {
        // Cada envelope mantém a identidade da respectiva linha.
        jdbc.update("""
            INSERT INTO outbox_evento(id,agregado_id,tipo_evento,payload,routing_key,criado_em)
            SELECT copia.id,o.agregado_id,o.tipo_evento,jsonb_set(o.payload,'{eventId}',to_jsonb(copia.id::text)),o.routing_key,o.criado_em
            FROM (SELECT * FROM outbox_evento LIMIT 1) o CROSS JOIN
                (SELECT gen_random_uuid() id FROM generate_series(1,?)) copia
            """,quantidade);
    }
}
