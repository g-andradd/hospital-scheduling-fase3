package br.com.fiap.hospital.notificacao.integracao;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.fiap.hospital.contracts.ConsultaPayload;
import br.com.fiap.hospital.contracts.TipoEvento;
import br.com.fiap.hospital.notificacao.consumer.ConsumidorDeNotificacoes;
import br.com.fiap.hospital.notificacao.repository.AgendaLocalRepository;
import br.com.fiap.hospital.notificacao.repository.NotificacaoEnviadaRepository;
import br.com.fiap.hospital.notificacao.sender.LogNotificationSender;
import br.com.fiap.hospital.notificacao.sender.NotificationSenderPort;
import br.com.fiap.hospital.notificacao.sender.SmtpNotificationSender;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailSender;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * O canal de correio selecionado por configuracao, com o adaptador de producao.
 *
 * <p>Este contexto e diferente do da base — outra propriedade de {@code notificacao.sender}
 * muda a chave do cache — e por isso ele sobe com o <b>listener desligado</b>. Dois
 * contextos com listener ativo competiriam pelas mensagens da mesma fila, e cada suite
 * receberia parte delas: o defeito e silencioso e aparece longe da causa.
 *
 * <p>Com o listener parado, a fronteira e invocada diretamente pelo bean proxificado. Isso
 * preserva a transacao e o wiring reais e nao afirma travessia AMQP — que e provada por
 * {@code ConsumoNotificacaoRabbitMqIT}.
 *
 * <p>O transporte de correio e substituido, e so ele. O {@code SmtpNotificationSender} e o
 * de producao: o que se prova aqui e que a configuracao o seleciona, que ele monta a
 * mensagem e que o efeito persistido nao muda com o canal. Entrega contra servidor SMTP real
 * pertence ao M12, e esta suite nao a afirma.
 */
@SpringBootTest(properties = {
        "logging.level.root=ERROR",
        "notificacao.sender=smtp",
        "spring.rabbitmq.listener.simple.auto-startup=false"})
@Import(SelecaoDeSenderIT.RelogioFixo.class)
@DisplayName("Selecao do canal de envio")
class SelecaoDeSenderIT {

    private static final Instant AGORA = Instant.parse("2026-09-11T12:00:00Z");
    private static final Instant FATO = Instant.parse("2026-09-11T09:00:00Z");

    @DynamicPropertySource
    static void propriedades(DynamicPropertyRegistry registro) {
        ContainersDeNotificacao.registrar(registro);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class RelogioFixo {
        @Bean @Primary Clock relogioFixo() { return Clock.fixed(AGORA, ZoneOffset.UTC); }
    }

    /** Unico ponto substituido: o transporte. O adaptador continua o de producao. */
    @MockBean MailSender mailSender;

    @Autowired ApplicationContext contexto;
    @Autowired NotificationSenderPort sender;
    @Autowired ConsumidorDeNotificacoes consumidor;
    @Autowired JdbcTemplate jdbc;
    @Autowired AgendaLocalRepository agendas;
    @Autowired NotificacaoEnviadaRepository auditoria;
    @Autowired
    org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry listeners;

    @org.junit.jupiter.api.BeforeEach
    void limpar() {
        jdbc.execute("TRUNCATE agenda_local, notificacao_enviada, evento_processado");
    }

    @Test
    @DisplayName("o listener deste contexto esta parado, para nao competir pela fila")
    void listenerDesteContextoEstaParado() {
        assertThat(listeners.getListenerContainers())
                .as("o container existe, mas nao pode estar consumindo")
                .allSatisfy(container -> assertThat(container.isRunning()).isFalse());
    }

    @Test
    @DisplayName("Scenario: Canal selecionado por configuracao substitui o padrao")
    void canalSelecionadoSubstituiOPadrao() {
        Map<String, NotificationSenderPort> adaptadores =
                contexto.getBeansOfType(NotificationSenderPort.class);

        assertThat(adaptadores)
                .as("apenas o adaptador escolhido pode estar ativo")
                .hasSize(1);
        assertThat(adaptadores.values().iterator().next())
                .isInstanceOf(SmtpNotificationSender.class);
        assertThat(contexto.getBeansOfType(LogNotificationSender.class))
                .as("o padrao sai de cena quando outro canal e escolhido")
                .isEmpty();
        assertThat(sender.canal()).isEqualTo("SMTP");
    }

    @Test
    @DisplayName("o adaptador de correio monta a mensagem e o efeito persistido nao muda")
    void efeitoPersistidoNaoMudaComOCanal() {
        UUID consultaId = UUID.randomUUID();
        var evento = eventoDeCriacao(consultaId);

        consumidor.consumir(evento);

        ArgumentCaptor<SimpleMailMessage> email = ArgumentCaptor.forClass(SimpleMailMessage.class);
        Mockito.verify(mailSender).send(email.capture());
        assertThat(email.getValue().getTo()).containsExactly("maria@hospital.com");
        assertThat(email.getValue().getSubject()).isEqualTo("Consulta agendada");
        assertThat(email.getValue().getFrom())
                .as("o remetente vem de configuracao, nao do codigo")
                .isNotBlank();

        var registro = auditoria.findAll().getFirst();
        assertThat(registro.getCanal())
                .as("a auditoria registra o canal efetivamente usado")
                .isEqualTo("SMTP");
        assertThat(registro.getConteudo())
                .as("e o texto e o mesmo do canal padrao — o adaptador nao reescreve conteudo")
                .isEqualTo(email.getValue().getText());
        assertThat(agendas.findById(consultaId)).isPresent();
        assertThat(agendas.findById(consultaId).orElseThrow().getStatus()).isEqualTo("AGENDADA");
    }

    private br.com.fiap.hospital.contracts.EventoEnvelope<ConsultaPayload> eventoDeCriacao(UUID consultaId) {
        var paciente = new ConsultaPayload.Paciente(
                UUID.randomUUID(), "Maria Souza", "maria@hospital.com", "+5561999990000");
        var medico = new ConsultaPayload.Medico(
                UUID.randomUUID(), "Dr. Joao Lima", "DF-12345", "Cardiologia");
        var registrante = new ConsultaPayload.Registrante(
                UUID.randomUUID(), "Ana Enfermeira", ConsultaPayload.Perfil.ENFERMEIRO);
        var payload = new ConsultaPayload(consultaId, ConsultaPayload.Status.AGENDADA,
                OffsetDateTime.ofInstant(Instant.parse("2026-09-20T14:30:00Z"), ZoneOffset.UTC),
                30, "observacao", null, paciente, medico, registrante, null);
        return new br.com.fiap.hospital.contracts.EventoEnvelope<>(
                UUID.randomUUID(), TipoEvento.CONSULTA_CRIADA, consultaId, FATO, 1,
                "correlacao-m06", payload);
    }
}
