package br.com.fiap.hospital.contracts;

import org.aopalliance.aop.Advice;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;

import org.springframework.amqp.rabbit.listener.ConditionalRejectingErrorHandler;
import org.springframework.amqp.rabbit.retry.RejectAndDontRequeueRecoverer;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration;
import org.springframework.boot.autoconfigure.amqp.RabbitProperties;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.retry.backoff.ExponentialBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;
import java.util.Map;

@AutoConfiguration(before = RabbitAutoConfiguration.class)
public class MensageriaAutoConfiguration {
    public static final String EXCHANGE = "hospital.consultas";
    public static final String DLX = "hospital.consultas.dlx";
    public static final String NOTIFICACAO = "notificacao.consultas";
    public static final String HISTORICO = "historico.consultas";

    @Bean public EventoJson eventoJson() { return new EventoJson(); }
    @Bean public EventoEnvelopeConverter eventoEnvelopeConverter(EventoJson json) {
        return new EventoEnvelopeConverter(json);
    }
    @Bean public Declarables topologiaConsultas() {
        var principal = new TopicExchange(EXCHANGE,true,false);
        var dlx = new TopicExchange(DLX,true,false);
        var notificacao = origem(NOTIFICACAO);
        var historico = origem(HISTORICO);
        var dlqNotificacao = QueueBuilder.durable(NOTIFICACAO+".dlq").quorum().build();
        var dlqHistorico = QueueBuilder.durable(HISTORICO+".dlq").quorum().build();
        return new Declarables(principal,dlx,notificacao,historico,dlqNotificacao,dlqHistorico,
                BindingBuilder.bind(notificacao).to(principal).with("consulta.#"),
                BindingBuilder.bind(historico).to(principal).with("consulta.#"),
                BindingBuilder.bind(dlqNotificacao).to(dlx).with("consulta.#"),
                BindingBuilder.bind(dlqHistorico).to(dlx).with("consulta.#"));
    }
    private static Queue origem(String nome) {
        return QueueBuilder.durable(nome).quorum().deadLetterExchange(DLX)
                .withArgument("x-dead-letter-strategy","at-least-once")
                .withArgument("x-overflow","reject-publish").build();
    }

    @Bean("rabbitListenerContainerFactory")
    @ConditionalOnMissingBean(name="rabbitListenerContainerFactory")
    public SimpleRabbitListenerContainerFactory factory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer, ConnectionFactory connectionFactory,
            EventoEnvelopeConverter converter, RabbitProperties properties) {
        var factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory,connectionFactory);
        factory.setMessageConverter(converter);
        factory.setAcknowledgeMode(AcknowledgeMode.AUTO);
        factory.setDefaultRequeueRejected(false);
        factory.setAdviceChain(retry(properties)); // Substitui a cadeia do Boot, sem empilhar retries.
        var errors = new ConditionalRejectingErrorHandler();
        errors.setDiscardFatalsWithXDeath(false);
        factory.setErrorHandler(errors);
        return factory;
    }

    static SimpleRetryPolicy politicaDeRetry(int tentativas) {
        return new SimpleRetryPolicy(tentativas, Map.of(Exception.class,true),true);
    }
    public static Advice retry(RabbitProperties properties) {
        var p = properties.getListener().getSimple().getRetry();
        if (!p.isEnabled() || p.getMaxAttempts()!=3 || p.getInitialInterval().toMillis()!=1000 ||
                p.getMultiplier()!=2.0 || p.getMaxInterval().toMillis()!=10000 ||
                !Boolean.FALSE.equals(properties.getListener().getSimple().getDefaultRequeueRejected()))
            throw new IllegalStateException("Configuracao de retry difere do contrato de eventos");
        var template = new RetryTemplate();
        template.setRetryPolicy(politicaDeRetry(p.getMaxAttempts()));
        var backoff = new ExponentialBackOffPolicy();
        backoff.setInitialInterval(p.getInitialInterval().toMillis());
        backoff.setMultiplier(p.getMultiplier());
        backoff.setMaxInterval(p.getMaxInterval().toMillis());
        template.setBackOffPolicy(backoff);
        return RetryInterceptorBuilder.stateless().retryOperations(template)
                .recoverer(new RejectAndDontRequeueRecoverer()).build();
    }
}
