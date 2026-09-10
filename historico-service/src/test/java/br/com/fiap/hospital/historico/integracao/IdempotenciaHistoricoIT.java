package br.com.fiap.hospital.historico.integracao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.fiap.hospital.contracts.ConsultaPayload;
import br.com.fiap.hospital.contracts.TipoEvento;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Idempotência transacional")
class IdempotenciaHistoricoIT extends HistoricoITBase {
    @Test @DisplayName("Scenario: Reentrega sequencial não duplica efeito")
    void reentregaSequencialNaoDuplicaEfeito() {
        var evento = evento(TipoEvento.CONSULTA_CRIADA, UUID.randomUUID(), Instant.parse("2026-09-10T13:00:00Z"), ConsultaPayload.Status.AGENDADA, "uma vez");
        consumidor.consumir(evento);
        var snapshotAntes = snapshots.findById(evento.aggregateId()).orElseThrow();
        var fatoAntes = eventos.findById(evento.eventId()).orElseThrow();
        var marcaAntes = processados.findById(evento.eventId()).orElseThrow();
        consumidor.consumir(evento);
        assertThat(quantidade("consulta_historico")).isOne();
        assertThat(quantidade("consulta_evento")).isOne();
        assertThat(quantidade("evento_processado")).isOne();
        assertThat(snapshots.findById(evento.aggregateId()).orElseThrow().getAtualizadoEm()).isEqualTo(snapshotAntes.getAtualizadoEm());
        assertThat(eventos.findById(evento.eventId()).orElseThrow().getPayload()).isEqualTo(fatoAntes.getPayload());
        assertThat(processados.findById(evento.eventId()).orElseThrow().getProcessadoEm()).isEqualTo(marcaAntes.getProcessadoEm());
    }

    @Test @DisplayName("Scenario: Reentregas concorrentes não confirmam dois efeitos")
    void reentregasConcorrentesNaoConfirmamDoisEfeitos() throws Exception {
        var evento = evento(TipoEvento.CONSULTA_CRIADA, UUID.randomUUID(), Instant.parse("2026-09-10T13:00:00Z"), ConsultaPayload.Status.AGENDADA, "concorrente");
        sincronizacao.disputarAposVerificacao(evento.eventId());
        try (var executor = Executors.newFixedThreadPool(2)) {
            var primeira = executor.submit(() -> tentarConsumir(evento));
            var segunda = executor.submit(() -> tentarConsumir(evento));
            assertThat(primeira.get(15, TimeUnit.SECONDS) + segunda.get(15, TimeUnit.SECONDS)).isEqualTo(1);
        } finally {
            sincronizacao.desarmar();
        }
        assertThat(quantidade("consulta_historico")).isOne();
        assertThat(quantidade("consulta_evento")).isOne();
        assertThat(quantidade("evento_processado")).isOne();
    }

    @Test @DisplayName("Scenario: Falha entre o efeito e a marca desfaz a projeção")
    void falhaEntreEfeitoEMarcaDesfazProjecao() {
        var evento = evento(TipoEvento.CONSULTA_CRIADA, UUID.randomUUID(), Instant.parse("2026-09-10T13:00:00Z"), ConsultaPayload.Status.AGENDADA, "reverter");
        jdbc.execute("""
                CREATE FUNCTION rejeitar_marca_m08() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'falha após efeito'; END $$
                """);
        jdbc.execute("""
                CREATE TRIGGER rejeitar_marca_m08 BEFORE INSERT ON evento_processado
                FOR EACH ROW EXECUTE FUNCTION rejeitar_marca_m08()
                """);
        try {
            assertThatThrownBy(() -> consumidor.consumir(evento)).isInstanceOf(RuntimeException.class);
            assertThat(quantidade("consulta_historico")).isZero();
            assertThat(quantidade("consulta_evento")).isZero();
            assertThat(quantidade("evento_processado")).isZero();
        } finally {
            jdbc.execute("DROP TRIGGER rejeitar_marca_m08 ON evento_processado");
            jdbc.execute("DROP FUNCTION rejeitar_marca_m08()");
        }
        consumidor.consumir(evento);
        assertThat(quantidade("consulta_historico")).isOne();
        assertThat(quantidade("consulta_evento")).isOne();
        assertThat(quantidade("evento_processado")).isOne();
    }

    private int tentarConsumir(br.com.fiap.hospital.contracts.EventoEnvelope<ConsultaPayload> evento) {
        try { consumidor.consumir(evento); return 1; }
        catch (RuntimeException excecao) { return 0; }
    }
}
