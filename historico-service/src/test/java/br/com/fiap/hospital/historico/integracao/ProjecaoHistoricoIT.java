package br.com.fiap.hospital.historico.integracao;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.fiap.hospital.contracts.ConsultaPayload;
import br.com.fiap.hospital.contracts.TipoEvento;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Projeção do histórico")
class ProjecaoHistoricoIT extends HistoricoITBase {
    @Test @DisplayName("Scenario: Sequência completa atualiza snapshot e trilha")
    void sequenciaCompletaAtualizaSnapshotETrilha() {
        UUID consulta = UUID.randomUUID();
        Instant inicio = Instant.parse("2026-09-10T13:00:00Z");
        var criada = evento(TipoEvento.CONSULTA_CRIADA, consulta, inicio, ConsultaPayload.Status.AGENDADA, "criada");
        var atualizada = evento(TipoEvento.CONSULTA_ATUALIZADA, consulta, inicio.plusSeconds(60), ConsultaPayload.Status.AGENDADA, "atualizada");
        var cancelada = evento(TipoEvento.CONSULTA_CANCELADA, consulta, inicio.plusSeconds(120), ConsultaPayload.Status.CANCELADA, "cancelada");
        consumidor.consumir(criada);
        consumidor.consumir(atualizada);
        consumidor.consumir(cancelada);
        assertThat(quantidade("consulta_evento")).isEqualTo(3);
        assertThat(snapshots.findById(consulta).orElseThrow().getStatus()).isEqualTo("CANCELADA");
        assertThat(quantidade("evento_processado")).isEqualTo(3);
        var trilha = eventos.findAll().stream().sorted(java.util.Comparator.comparing(e -> e.getOcorridoEm())).toList();
        assertThat(trilha).extracting(e -> e.getId()).containsExactly(criada.eventId(), atualizada.eventId(), cancelada.eventId());
        assertThat(trilha).extracting(e -> e.getTipoEvento()).containsExactly("CONSULTA_CRIADA", "CONSULTA_ATUALIZADA", "CONSULTA_CANCELADA");
        assertThat(trilha).extracting(e -> e.getOcorridoEm()).containsExactly(inicio, inicio.plusSeconds(60), inicio.plusSeconds(120));
    }

    @Test @DisplayName("Scenario: Todos os tipos normativos são projetáveis")
    void todosOsTiposNormativosSaoProjetaveis() {
        Instant agora = Instant.parse("2026-09-10T13:00:00Z");
        for (TipoEvento tipo : TipoEvento.values()) {
            var status = switch (tipo) {
                case CONSULTA_CRIADA, CONSULTA_ATUALIZADA -> ConsultaPayload.Status.AGENDADA;
                case CONSULTA_CONFIRMADA -> ConsultaPayload.Status.CONFIRMADA;
                case CONSULTA_CANCELADA -> ConsultaPayload.Status.CANCELADA;
                case CONSULTA_REALIZADA -> ConsultaPayload.Status.REALIZADA;
            };
            var evento = evento(tipo, UUID.randomUUID(), agora, status, tipo.name());
            consumidor.consumir(evento);
            assertThat(eventos.findById(evento.eventId())).hasValueSatisfying(fato -> assertThat(fato.getTipoEvento()).isEqualTo(tipo.name()));
            assertThat(snapshots.findById(evento.aggregateId())).hasValueSatisfying(snapshot -> assertThat(snapshot.getStatus()).isEqualTo(status.name()));
        }
        assertThat(quantidade("consulta_historico")).isEqualTo(TipoEvento.values().length);
        assertThat(quantidade("consulta_evento")).isEqualTo(TipoEvento.values().length);
    }

    @Test @DisplayName("Scenario: Snapshot mantém as dimensões do evento aplicado")
    void snapshotMantemDimensoesDoEventoAplicado() {
        UUID consulta = UUID.randomUUID();
        var evento = evento(TipoEvento.CONSULTA_CRIADA, consulta, Instant.parse("2026-09-10T13:00:00Z"), ConsultaPayload.Status.AGENDADA, "retorno");
        consumidor.consumir(evento);
        var medicoAlterado = new ConsultaPayload.Medico(UUID.randomUUID(), "Dra. Lia Ramos", "DF-99999", "Neurologia");
        var payloadAlterado = new ConsultaPayload(consulta, ConsultaPayload.Status.CONFIRMADA,
                evento.payload().dataHora().plusHours(1), 45, "retorno alterado", null, evento.payload().paciente(),
                medicoAlterado, evento.payload().registradoPor(), java.util.Map.of("observacoesAnterior", "retorno"));
        var alterado = new br.com.fiap.hospital.contracts.EventoEnvelope<>(UUID.randomUUID(), TipoEvento.CONSULTA_ATUALIZADA,
                consulta, evento.occurredAt().plusSeconds(60), 1, evento.correlationId(), payloadAlterado);
        consumidor.consumir(alterado);
        var snapshot = snapshots.findById(consulta).orElseThrow();
        assertThat(snapshot.getPacienteId()).isEqualTo(evento.payload().paciente().id());
        assertThat(snapshot.getPacienteNome()).isEqualTo("Maria Souza");
        assertThat(snapshot.getMedicoId()).isEqualTo(medicoAlterado.id());
        assertThat(snapshot.getMedicoNome()).isEqualTo("Dra. Lia Ramos");
        assertThat(snapshot.getEspecialidade()).isEqualTo("Neurologia");
        assertThat(snapshot.getDataHora().toInstant()).isEqualTo(payloadAlterado.dataHora().toInstant());
        assertThat(snapshot.getStatus()).isEqualTo("CONFIRMADA");
        assertThat(snapshot.getObservacoes()).isEqualTo("retorno alterado");
    }
}
