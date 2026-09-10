package br.com.fiap.hospital.notificacao.integracao;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.fiap.hospital.contracts.ConsultaPayload;
import br.com.fiap.hospital.contracts.TipoEvento;
import br.com.fiap.hospital.notificacao.sender.NotificationSenderPort;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/**
 * O que o paciente recebe, e o que fica registrado.
 *
 * <p>As duas coisas sao verificadas juntas de proposito: a auditoria so serve para
 * auditar se guardar o mesmo texto que saiu. Guardar outra coisa — um resumo, um
 * identificador de template — seria um registro que nao responde "o que foi dito a esta
 * pessoa".
 */
@DisplayName("Notificacoes reativas")
class NotificacaoReativaIT extends NotificacaoITBase {

    private static final Instant CEDO = Instant.parse("2026-09-11T08:00:00Z");
    private static final Instant TARDE = Instant.parse("2026-09-11T10:00:00Z");
    private static final OffsetDateTime DATA_HORA =
            OffsetDateTime.ofInstant(Instant.parse("2026-09-20T14:30:00Z"), ZoneOffset.UTC);

    @Test
    @DisplayName("Scenario: Criacao notifica o agendamento da consulta")
    void criacaoNotificaOAgendamento() {
        UUID consultaId = UUID.randomUUID();

        publicarEAguardar(evento(TipoEvento.CONSULTA_CRIADA, consultaId, CEDO,
                ConsultaPayload.Status.AGENDADA, "Dr. Joao Lima", DATA_HORA));

        var mensagem = mensagemEntregue();
        assertThat(mensagem.destinatario()).isEqualTo("maria@hospital.com");
        assertThat(mensagem.assunto()).isEqualTo("Consulta agendada");
        assertThat(mensagem.corpo())
                .contains("Maria Souza", "Dr. Joao Lima", "20/09/2026", "agendada");

        Map<String, Object> registro = registroUnico();
        assertThat(registro.get("tipo")).isEqualTo("CONSULTA_CRIADA");
        assertThat(registro.get("destinatario")).isEqualTo("maria@hospital.com");
        assertThat(registro.get("canal")).isEqualTo("LOG");
        assertThat(registro.get("conteudo"))
                .as("a auditoria guarda exatamente o texto entregue")
                .isEqualTo(mensagem.corpo());
        assertThat(((java.sql.Timestamp) registro.get("enviado_em")).toInstant())
                .as("enviado_em vem do Clock injetado")
                .isEqualTo(AGORA);
    }

    @Test
    @DisplayName("Scenario: Atualizacao notifica a alteracao")
    void atualizacaoNotificaAAlteracao() {
        UUID consultaId = UUID.randomUUID();

        publicarEAguardar(evento(TipoEvento.CONSULTA_ATUALIZADA, consultaId, CEDO,
                ConsultaPayload.Status.AGENDADA, "Dra. Marta Reis", DATA_HORA));

        var mensagem = mensagemEntregue();
        assertThat(mensagem.assunto()).isEqualTo("Consulta alterada");
        assertThat(mensagem.corpo()).contains("alterada", "Dra. Marta Reis");
        assertThat(registroUnico().get("conteudo")).isEqualTo(mensagem.corpo());
    }

    /**
     * Scenario: Cancelamento notifica cancelamento, nao confirmacao.
     *
     * <p>Criterio de aceite explicito do M06. Um template reaproveitado por engano avisaria
     * o paciente de que a consulta esta marcada no exato momento em que ela deixou de
     * estar.
     */
    @Test
    @DisplayName("Scenario: Cancelamento notifica cancelamento, nao confirmacao")
    void cancelamentoNotificaCancelamento() {
        UUID consultaId = UUID.randomUUID();

        publicarEAguardar(evento(TipoEvento.CONSULTA_CANCELADA, consultaId, CEDO,
                ConsultaPayload.Status.CANCELADA));

        var mensagem = mensagemEntregue();
        assertThat(mensagem.assunto()).isEqualTo("Consulta cancelada");
        assertThat(mensagem.corpo()).contains("cancelada");
        assertThat(mensagem.corpo())
                .as("nao pode reutilizar o texto de confirmacao")
                .doesNotContain("foi agendada para", "foi alterada");
        assertThat(registroUnico().get("tipo")).isEqualTo("CONSULTA_CANCELADA");
    }

    @Test
    @DisplayName("Scenario: Confirmacao e realizacao nao geram notificacao")
    void confirmacaoERealizacaoNaoNotificam() {
        UUID consultaId = UUID.randomUUID();

        publicarEAguardar(evento(TipoEvento.CONSULTA_CONFIRMADA, consultaId, CEDO,
                ConsultaPayload.Status.CONFIRMADA));
        publicarEAguardar(evento(TipoEvento.CONSULTA_REALIZADA, consultaId, TARDE,
                ConsultaPayload.Status.REALIZADA));

        Mockito.verify(sender, Mockito.never()).enviar(Mockito.any());
        assertThat(quantidade("notificacao_enviada"))
                .as("a documentacao nao pede aviso para estes dois fatos")
                .isZero();
        assertThat(agenda(consultaId).get("status"))
                .as("mas a agenda continua sendo atualizada")
                .isEqualTo("REALIZADA");
        assertThat(quantidade("evento_processado")).isEqualTo(2);
    }

    @Test
    @DisplayName("Scenario: Evento nao aplicado nao gera notificacao obsoleta")
    void eventoNaoAplicadoNaoNotifica() {
        UUID consultaId = UUID.randomUUID();

        publicarEAguardar(evento(TipoEvento.CONSULTA_ATUALIZADA, consultaId, TARDE,
                ConsultaPayload.Status.AGENDADA, "Dra. Marta Reis", DATA_HORA));
        Mockito.clearInvocations(sender);

        publicarEAguardar(evento(TipoEvento.CONSULTA_CRIADA, consultaId, CEDO,
                ConsultaPayload.Status.AGENDADA, "Dr. Joao Lima", DATA_HORA));

        Mockito.verify(sender, Mockito.never()).enviar(Mockito.any());
        assertThat(quantidade("notificacao_enviada"))
                .as("o fato descartado pela monotonicidade nao avisa ninguem")
                .isEqualTo(1);
        assertThat(quantidade("evento_processado"))
                .as("mas ainda assim e marcado como processado")
                .isEqualTo(2);
    }

    // --- apoio --------------------------------------------------------------------------

    private NotificationSenderPort.Mensagem mensagemEntregue() {
        ArgumentCaptor<NotificationSenderPort.Mensagem> captor =
                ArgumentCaptor.forClass(NotificationSenderPort.Mensagem.class);
        Mockito.verify(sender, Mockito.times(1)).enviar(captor.capture());
        return captor.getValue();
    }

    private Map<String, Object> registroUnico() {
        List<Map<String, Object>> registros = jdbc.queryForList("SELECT * FROM notificacao_enviada");
        assertThat(registros)
                .as("uma transacao confirmada deixa exatamente um registro")
                .hasSize(1);
        return registros.getFirst();
    }
}
