package br.com.fiap.hospital.contracts;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerEndpoint;
import org.springframework.amqp.rabbit.listener.adapter.MessageListenerAdapter;
import org.springframework.retry.interceptor.RetryOperationsInterceptor;
import org.springframework.test.util.ReflectionTestUtils;

class MutacaoDoRecovererIT extends RabbitITBase {
    @Test void recovererQueEngoleErroQuebraAProvaDeDlq() {
        var efeito=new Falha();
        var endpoint=new SimpleRabbitListenerEndpoint();
        endpoint.setId("mutacao-recoverer");endpoint.setQueueNames(N);
        var adapter=new MessageListenerAdapter(efeito,converter);adapter.setDefaultListenerMethod("consumir");
        endpoint.setMessageListener(adapter);
        var container=factory.createListenerContainer(endpoint);
        // A mutação usa uma cadeia nova: não altera o interceptor compartilhado pela factory.
        var properties=new org.springframework.boot.autoconfigure.amqp.RabbitProperties();
        var r=properties.getListener().getSimple().getRetry();
        r.setEnabled(true);r.setMaxAttempts(3);r.setInitialInterval(Duration.ofSeconds(1));r.setMultiplier(2);r.setMaxInterval(Duration.ofSeconds(10));
        properties.getListener().getSimple().setDefaultRequeueRejected(false);
        var interceptor=(RetryOperationsInterceptor)MensageriaAutoConfiguration.retry(properties);
        interceptor.setRecoverer((args,cause)->null);
        container.setAdviceChain(interceptor);container.start();
        try {
            rabbit.send(X,"consulta.criada",EntradasAmqp.mensagem(EntradasAmqp.fixture()));
            await().atMost(Duration.ofSeconds(10)).untilAsserted(()->assertThat(efeito.tentativas.get()).isEqualTo(3));
            await().atMost(Duration.ofSeconds(5)).untilAsserted(()->assertThat(admin.getQueueInfo(N).getMessageCount()).isZero());
            assertThatThrownBy(()->assertThat(rabbit.receive(N+".dlq",1000)).as("mensagem deve chegar à DLQ").isNotNull())
                .isInstanceOf(AssertionError.class).hasMessageContaining("DLQ");
        } finally {container.stop();}
    }
    public static class Falha {
        final AtomicInteger tentativas=new AtomicInteger();
        public void consumir(EventoEnvelope<?> evento){tentativas.incrementAndGet();throw new IllegalStateException("falha controlada");}
    }
}
