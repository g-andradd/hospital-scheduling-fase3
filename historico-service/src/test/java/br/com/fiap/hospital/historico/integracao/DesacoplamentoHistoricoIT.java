package br.com.fiap.hospital.historico.integracao;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.fiap.hospital.contracts.ConsultaPayload;
import br.com.fiap.hospital.contracts.TipoEvento;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Desacoplamento do histórico")
class DesacoplamentoHistoricoIT extends HistoricoITBase {
    @Test @DisplayName("Scenario: Evento completo materializa o histórico sem consulta remota")
    void eventoCompletoMaterializaSemConsultaRemota() throws Exception {
        var evento = evento(TipoEvento.CONSULTA_CRIADA, UUID.randomUUID(), Instant.parse("2026-09-10T13:00:00Z"), ConsultaPayload.Status.AGENDADA, "autossuficiente");
        consumidor.consumir(evento);
        assertThat(snapshots.findById(evento.aggregateId()).orElseThrow().getPacienteNome()).isEqualTo("Maria Souza");
        var payload = new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                eventos.findById(evento.eventId()).orElseThrow().getPayload());
        assertThat(payload.path("consultaId").asText()).isEqualTo(evento.aggregateId().toString());
    }

}
