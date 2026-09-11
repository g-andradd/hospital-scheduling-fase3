package br.com.fiap.hospital.notificacao.integracao;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.fiap.hospital.contracts.MensageriaAutoConfiguration;
import br.com.fiap.hospital.notificacao.sender.LogNotificationSender;
import br.com.fiap.hospital.notificacao.sender.NotificationSenderPort;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.listener.MessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.amqp.RabbitProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.test.util.AopTestUtils;

/**
 * A configuracao que o servico realmente carregou, e nao a que o arquivo sugere.
 *
 * <p>Ler o YAML provaria que alguem escreveu a propriedade; ler os objetos efetivos prova
 * que ela chegou ao componente. Sao coisas diferentes quando existe auto-configuracao no
 * meio — foi o que o M05 fixou para a cadeia de consumo compartilhada.
 */
@DisplayName("Configuracao efetiva do notificacao-service")
class ConfiguracaoNotificacaoIT extends NotificacaoITBase {

    @Autowired Environment ambiente;
    @Autowired RabbitProperties propriedades;
    @Autowired RabbitListenerEndpointRegistry listeners;
    @Autowired ApplicationContext contexto;

    @Test
    @DisplayName("datasource e Flyway apontam para o notificacao_db provisionado")
    void datasourceEFlywayConfigurados() {
        assertThat(jdbc.queryForObject("SELECT current_database()", String.class))
                .isEqualTo("notificacao_db");
        assertThat(ambiente.getProperty("spring.flyway.locations"))
                .isEqualTo("classpath:db/migration");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE success = true", Long.class))
                .isPositive();
    }

    @Test
    @DisplayName("retry e rejeicao seguem o contrato compartilhado")
    void retryERejeicaoSeguemOContrato() {
        var retry = propriedades.getListener().getSimple().getRetry();
        assertThat(propriedades.getListener().getSimple().getDefaultRequeueRejected())
                .as("sem isto uma mensagem envenenada volta para a fila para sempre")
                .isFalse();
        assertThat(retry.isEnabled()).isTrue();
        assertThat(retry.getMaxAttempts()).isEqualTo(3);
        assertThat(retry.getInitialInterval().toMillis()).isEqualTo(1_000);
        assertThat(retry.getMultiplier()).isEqualTo(2.0);
        assertThat(retry.getMaxInterval().toMillis()).isEqualTo(10_000);
    }

    @Test
    @DisplayName("existe exatamente um listener, ligado a fila normativa")
    void existeExatamenteUmListener() {
        assertThat(listeners.getListenerContainers())
                .as("um segundo listener dividiria a fila e quebraria a fronteira transacional")
                .hasSize(1);

        MessageListenerContainer container = listeners.getListenerContainers().iterator().next();
        assertThat(container.isRunning()).isTrue();
        assertThat(((org.springframework.amqp.rabbit.listener.AbstractMessageListenerContainer) container)
                .getQueueNames())
                .containsExactly(MensageriaAutoConfiguration.NOTIFICACAO);
    }

    @Test
    @DisplayName("Scenario: Sem configuracao, o canal padrao e o de log")
    void canalPadraoEODeLog() {
        Map<String, NotificationSenderPort> adaptadores =
                contexto.getBeansOfType(NotificationSenderPort.class);

        assertThat(adaptadores)
                .as("so um adaptador pode estar ativo por vez")
                .hasSize(1);
        Object adaptadorAtivo = AopTestUtils.getTargetObject(adaptadores.values().iterator().next());
        assertThat(adaptadorAtivo).isInstanceOf(LogNotificationSender.class);
        assertThat(sender.canal()).isEqualTo("LOG");
    }

    @Test
    @DisplayName("os testes sobem no profile test, com o agendador do lembrete desligado")
    void profileTestComAgendadorDesligado() {
        assertThat(ambiente.getActiveProfiles())
                .as("vem de src/test/resources, e vale para toda suite do modulo")
                .containsExactly("test");
        assertThat(ambiente.getProperty("notificacao.lembrete.agendador-habilitado")).isEqualTo("false");
        assertThat(ambiente.getProperty("notificacao.lembrete.cron"))
                .as("o padrao do application.yml: inicio de cada hora")
                .isEqualTo("0 0 * * * *");
    }

    @Test
    @DisplayName("a cadeia compartilhada autentica /internal/** e valida o token do agendamento")
    void segurancaConfiguradaParaOEndpointInterno() {
        var caminhos = contexto.getBean(br.com.fiap.hospital.security.CaminhosDeSeguranca.class);

        assertThat(caminhos.autenticados())
                .as("substitui o padrao /api/**: o servico nao tem /api")
                .containsExactly("/internal/**");
        assertThat(caminhos.publicosAdicionais()).isEmpty();
        assertThat(propriedadesJwt.expiracao()).isEqualTo(java.time.Duration.ofHours(8));
        assertThat(propriedadesJwt.emissor()).isEqualTo("hospital-agendamento");
        assertThat(propriedadesJwt.secret())
                .as("o segredo dos testes vem de src/test/resources; src/main nao tem fallback")
                .isEqualTo("segredo-de-teste-do-notificacao-com-mais-de-32-bytes");
    }
}
