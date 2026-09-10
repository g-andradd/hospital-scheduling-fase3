package br.com.fiap.hospital.historico.integracao;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.fiap.hospital.contracts.ConsultaPayload;
import br.com.fiap.hospital.contracts.EventoEnvelope;
import br.com.fiap.hospital.contracts.TipoEvento;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Persistência do histórico")
class PersistenciaHistoricoIT extends HistoricoITBase {
    @Test @DisplayName("Scenario: Payload JSONB preserva campos nulos, instantes e criação local")
    void payloadJsonbPreservaCamposNulosEInstante() throws Exception {
        var original = evento(TipoEvento.CONSULTA_CRIADA, UUID.randomUUID(), Instant.parse("2026-09-10T13:00:00Z"), ConsultaPayload.Status.AGENDADA, null);
        var p = original.payload();
        var semTelefone = new ConsultaPayload(p.consultaId(), p.status(), p.dataHora(), p.duracaoMinutos(),
                null, null, new ConsultaPayload.Paciente(p.paciente().id(), p.paciente().nome(), p.paciente().email(), null),
                p.medico(), p.registradoPor(), null);
        var evento = new EventoEnvelope<>(original.eventId(), original.eventType(), original.aggregateId(),
                original.occurredAt(), original.version(), original.correlationId(), semTelefone);
        consumidor.consumir(evento);
        var gravado = eventos.findById(evento.eventId()).orElseThrow();
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var payload = mapper.readTree(gravado.getPayload());
        assertThat(payload).isEqualTo(mapper.readTree(json.escrever(evento)).path("payload"));
        assertThat(payload.path("observacoes").isNull()).isTrue();
        assertThat(payload.path("paciente").path("telefone").isNull()).isTrue();
        assertThat(payload.path("dataHora").asText()).endsWith("-03:00");
        assertThat(gravado.getOcorridoEm()).isEqualTo(evento.occurredAt());
        var snapshot = snapshots.findById(evento.aggregateId()).orElseThrow();
        assertThat(snapshot.getCriadoEm()).isEqualTo(INSTANTE_DE_CRIACAO);
        assertThat(snapshot.getAtualizadoEm()).isEqualTo(evento.occurredAt());
        var atualizado = evento(TipoEvento.CONSULTA_ATUALIZADA, evento.aggregateId(), evento.occurredAt().plusSeconds(60),
                ConsultaPayload.Status.CONFIRMADA, "atualizada");
        consumidor.consumir(atualizado);
        snapshot = snapshots.findById(evento.aggregateId()).orElseThrow();
        assertThat(snapshot.getCriadoEm()).isEqualTo(INSTANTE_DE_CRIACAO);
        assertThat(snapshot.getAtualizadoEm()).isEqualTo(atualizado.occurredAt());
    }
}
