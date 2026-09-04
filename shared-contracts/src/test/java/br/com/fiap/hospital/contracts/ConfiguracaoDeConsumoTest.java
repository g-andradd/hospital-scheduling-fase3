package br.com.fiap.hospital.contracts;

import static org.assertj.core.api.Assertions.*;
import java.time.Duration;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.amqp.*;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.FileSystemResource;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.test.util.ReflectionTestUtils;

class ConfiguracaoDeConsumoTest {
    @ParameterizedTest @ValueSource(strings={"agendamento-service","notificacao-service","historico-service"})
    
    // Scenario: Configuração efetiva exige rejeição sem requeue
    void configuracaoRealDosTresServicos(String modulo) throws Exception {
        var fontes=new YamlPropertySourceLoader().load(modulo,
                new FileSystemResource("../"+modulo+"/src/main/resources/application.yml"));
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(
                MensageriaAutoConfiguration.class,RabbitAutoConfiguration.class))
                .withInitializer(c->fontes.forEach(p->c.getEnvironment().getPropertySources().addLast(p)))
                .run(c-> {
                    assertThat(c).hasNotFailed().hasSingleBean(EventoEnvelopeConverter.class);
                    var p=c.getBean(RabbitProperties.class);
                    assertThat(p.getConnectionTimeout()).isEqualTo(Duration.ofSeconds(2));
                    assertThat(p.getChannelRpcTimeout()).isEqualTo(Duration.ofSeconds(2));
                    assertThat(p.getPublisherConfirmType().name()).isEqualTo("CORRELATED");
                    assertThat(p.isPublisherReturns()).isTrue();
                    assertThat(p.getTemplate().getMandatory()).isTrue();
                    var container=c.getBean(SimpleRabbitListenerContainerFactory.class).createListenerContainer();
                    assertThat(container.getAcknowledgeMode()).isEqualTo(AcknowledgeMode.AUTO);
                    assertThat(ReflectionTestUtils.getField(container,"defaultRequeueRejected")).isEqualTo(false);
                    assertThat((Object[])ReflectionTestUtils.getField(container,"adviceChain")).hasSize(1);
                    assertThat(container.isRunning()).isFalse();
                    assertThat(c.getBeansOfType(org.springframework.amqp.rabbit.listener.MessageListenerContainer.class)).isEmpty();
                });
    }
    @ParameterizedTest @ValueSource(strings={"enabled","attempts","initial","multiplier","max","requeue"})
    void recusaDesvioDoContrato(String campo) {
        var p=new RabbitProperties();
        var r=p.getListener().getSimple().getRetry();
        r.setEnabled(true);r.setMaxAttempts(3);r.setInitialInterval(Duration.ofSeconds(1));
        r.setMultiplier(2);r.setMaxInterval(Duration.ofSeconds(10));
        p.getListener().getSimple().setDefaultRequeueRejected(false);
        switch(campo) {
            case "enabled"->r.setEnabled(false); case "attempts"->r.setMaxAttempts(4);
            case "initial"->r.setInitialInterval(Duration.ofSeconds(2)); case "multiplier"->r.setMultiplier(3);
            case "max"->r.setMaxInterval(Duration.ofSeconds(20)); case "requeue"->p.getListener().getSimple().setDefaultRequeueRejected(true);
        }
        assertThatThrownBy(()->MensageriaAutoConfiguration.retry(p)).isInstanceOf(IllegalStateException.class);
    }
}
