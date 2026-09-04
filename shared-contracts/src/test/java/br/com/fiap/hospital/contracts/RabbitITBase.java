package br.com.fiap.hospital.contracts;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.core.*;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.RabbitMQContainer;

@SpringBootTest(classes=RabbitITBase.App.class, properties={
    "spring.rabbitmq.publisher-confirm-type=correlated","spring.rabbitmq.publisher-returns=true",
    "spring.rabbitmq.template.mandatory=true","spring.rabbitmq.connection-timeout=2s",
    "spring.rabbitmq.channel-rpc-timeout=2s","spring.rabbitmq.cache.channel.checkout-timeout=2s",
    "spring.rabbitmq.listener.simple.default-requeue-rejected=false",
    "spring.rabbitmq.listener.simple.retry.enabled=true","spring.rabbitmq.listener.simple.retry.max-attempts=3",
    "spring.rabbitmq.listener.simple.retry.initial-interval=1000ms",
    "spring.rabbitmq.listener.simple.retry.multiplier=2","spring.rabbitmq.listener.simple.retry.max-interval=10000ms",
    "logging.level.root=ERROR"})
abstract class RabbitITBase {
    @SpringBootConfiguration @EnableAutoConfiguration static class App {}
    static final RabbitMQContainer BROKER=new RabbitMQContainer("rabbitmq:3.13-management");
    static { BROKER.start(); }
    @DynamicPropertySource static void propriedades(DynamicPropertyRegistry p) {
        p.add("spring.rabbitmq.host",BROKER::getHost);
        p.add("spring.rabbitmq.port",BROKER::getAmqpPort);
        p.add("spring.rabbitmq.username",BROKER::getAdminUsername);
        p.add("spring.rabbitmq.password",BROKER::getAdminPassword);
    }
    @Autowired RabbitTemplate rabbit;
    @Autowired AmqpAdmin admin;
    @Autowired SimpleRabbitListenerContainerFactory factory;
    @Autowired EventoEnvelopeConverter converter;
    @Autowired EventoJson json;
    static final String N=MensageriaAutoConfiguration.NOTIFICACAO;
    static final String H=MensageriaAutoConfiguration.HISTORICO;
    static final String X=MensageriaAutoConfiguration.EXCHANGE;
    static final String D=MensageriaAutoConfiguration.DLX;
    @BeforeEach void limparFilas() {
        ((RabbitAdmin)admin).initialize();
        for(String fila:List.of(N,H,N+".dlq",H+".dlq")) admin.purgeQueue(fila);
    }
    Message receber(String fila) {
        Message m=rabbit.receive(fila,10000);
        assertThat(m).as("mensagem em "+fila).isNotNull();
        return m;
    }
    void rejeitar(String fila) {
        await().atMost(Duration.ofSeconds(10)).untilAsserted(()->assertThat(admin.getQueueInfo(fila).getMessageCount()).isPositive());
        rabbit.execute(channel-> {
            var entrega=channel.basicGet(fila,false);
            assertThat(entrega).isNotNull();
            channel.basicReject(entrega.getEnvelope().getDeliveryTag(),false);
            return null;
        });
    }
}
