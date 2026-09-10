package br.com.fiap.hospital.notificacao.integracao;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.fiap.hospital.contracts.ConsultaPayload;
import br.com.fiap.hospital.contracts.TipoEvento;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * O servico trabalha com o agendamento fora do ar.
 *
 * <p>Nao existe agendamento algum de pe nesta suite: o contexto so tem PostgreSQL e RabbitMQ.
 * Se o processamento dependesse de uma chamada remota, ela falharia aqui — a ausencia de
 * dependencia e demonstrada pelo teste ser possivel, nao por uma asercao sobre mocks.
 */
@DisplayName("Desacoplamento do servico de agendamento")
class DesacoplamentoNotificacaoIT extends NotificacaoITBase {

    private static final Instant FATO = Instant.parse("2026-09-11T09:00:00Z");

    @Test
    @DisplayName("Scenario: Evento completo notifica sem consulta remota")
    void eventoCompletoNotificaSemConsultaRemota() {
        UUID consultaId = UUID.randomUUID();

        publicarEAguardar(evento(TipoEvento.CONSULTA_CRIADA, consultaId, FATO,
                ConsultaPayload.Status.AGENDADA));

        Map<String, Object> linha = agenda(consultaId);
        assertThat(linha)
                .as("agenda materializada apenas com o snapshot do evento")
                .isNotEmpty();
        assertThat(linha.get("paciente_nome")).isEqualTo("Maria Souza");
        assertThat(linha.get("paciente_email")).isEqualTo("maria@hospital.com");
        assertThat(linha.get("medico_nome")).isEqualTo("Dr. Joao Lima");
        assertThat(quantidade("notificacao_enviada")).isEqualTo(1);
    }
}
