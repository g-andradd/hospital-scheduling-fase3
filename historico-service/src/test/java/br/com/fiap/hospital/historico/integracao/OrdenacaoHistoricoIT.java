package br.com.fiap.hospital.historico.integracao;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.fiap.hospital.contracts.ConsultaPayload;
import br.com.fiap.hospital.contracts.TipoEvento;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Ordenação do snapshot")
class OrdenacaoHistoricoIT extends HistoricoITBase {
    @Test @DisplayName("Scenario: Evento antigo amplia a trilha sem regredir o snapshot")
    void eventoAntigoAmpliaTrilhaSemRegredirSnapshot() {
        UUID consulta = UUID.randomUUID();
        Instant novo = Instant.parse("2026-09-10T14:00:00Z");
        var eventoNovo = evento(TipoEvento.CONSULTA_CONFIRMADA, consulta, novo, ConsultaPayload.Status.CONFIRMADA, "novo");
        consumidor.consumir(eventoNovo);
        var snapshotNovo = snapshots.findById(consulta).orElseThrow();
        UUID pacienteNovo = snapshotNovo.getPacienteId();
        String nomePacienteNovo = snapshotNovo.getPacienteNome();
        UUID medicoNovo = snapshotNovo.getMedicoId();
        String nomeMedicoNovo = snapshotNovo.getMedicoNome();
        String especialidadeNova = snapshotNovo.getEspecialidade();
        var horaNova = snapshotNovo.getDataHora().toInstant();
        String statusNovo = snapshotNovo.getStatus();
        String observacoesNovas = snapshotNovo.getObservacoes();
        var criadoEmNovo = snapshotNovo.getCriadoEm();
        var atualizadoEmNovo = snapshotNovo.getAtualizadoEm();
        var eventoAntigo = evento(TipoEvento.CONSULTA_CRIADA, consulta, novo.minusSeconds(60), ConsultaPayload.Status.AGENDADA, "antigo");
        consumidor.consumir(eventoAntigo);
        assertThat(quantidade("consulta_evento")).isEqualTo(2);
        assertThat(quantidade("evento_processado")).isEqualTo(2);
        assertThat(eventos.findAll()).extracting(fato -> fato.getId()).containsExactlyInAnyOrder(eventoNovo.eventId(), eventoAntigo.eventId());
        var snapshot = snapshots.findById(consulta).orElseThrow();
        assertThat(snapshot.getPacienteId()).isEqualTo(pacienteNovo);
        assertThat(snapshot.getPacienteNome()).isEqualTo(nomePacienteNovo);
        assertThat(snapshot.getMedicoId()).isEqualTo(medicoNovo);
        assertThat(snapshot.getMedicoNome()).isEqualTo(nomeMedicoNovo);
        assertThat(snapshot.getEspecialidade()).isEqualTo(especialidadeNova);
        assertThat(snapshot.getDataHora().toInstant()).isEqualTo(horaNova);
        assertThat(snapshot.getStatus()).isEqualTo(statusNovo);
        assertThat(snapshot.getObservacoes()).isEqualTo(observacoesNovas);
        assertThat(snapshot.getCriadoEm()).isEqualTo(criadoEmNovo);
        assertThat(snapshot.getAtualizadoEm()).isEqualTo(atualizadoEmNovo);
    }

    @Test @DisplayName("Scenario: Eventos fora de ordem processados concorrentemente preservam o mais novo")
    void eventosConcorrentesPreservamOMaisNovo() throws Exception {
        verificarAmbasOrdensDeAquisicaoDoLock(false);
        limparDados();
        verificarAmbasOrdensDeAquisicaoDoLock(true);
    }

    private void verificarAmbasOrdensDeAquisicaoDoLock(boolean novoPrimeiro) throws Exception {
        UUID consulta = UUID.randomUUID();
        Instant recente = Instant.parse("2026-09-10T14:00:00Z");
        var novo = evento(TipoEvento.CONSULTA_CONFIRMADA, consulta, recente, ConsultaPayload.Status.CONFIRMADA, "novo");
        var antigo = evento(TipoEvento.CONSULTA_CRIADA, consulta, recente.minusSeconds(60), ConsultaPayload.Status.AGENDADA, "antigo");
        var primeiro = novoPrimeiro ? novo : antigo;
        var segundo = novoPrimeiro ? antigo : novo;
        sincronizacao.reterNoUpsert(consulta);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> consumidor.consumir(primeiro));
            sincronizacao.aguardarUpsert();
            var b = executor.submit(() -> consumidor.consumir(segundo));
            aguardarSegundaTransacaoBloqueadaNoPostgres();
            sincronizacao.liberarUpsert();
            a.get(15, TimeUnit.SECONDS);
            b.get(15, TimeUnit.SECONDS);
        } finally {
            sincronizacao.desarmar();
        }
        assertThat(quantidade("consulta_evento")).isEqualTo(2);
        assertThat(quantidade("evento_processado")).isEqualTo(2);
        var snapshot = snapshots.findById(consulta).orElseThrow();
        assertThat(snapshot.getStatus()).isEqualTo("CONFIRMADA");
        assertThat(snapshot.getAtualizadoEm()).isEqualTo(recente);
    }

    @Test @DisplayName("Scenario: Fatos no mesmo instante seguem a ordem de chegada")
    void fatosNoMesmoInstanteSeguemOrdemDeChegada() {
        UUID consulta = UUID.randomUUID();
        Instant instante = Instant.parse("2026-09-10T14:00:00Z");
        consumidor.consumir(evento(TipoEvento.CONSULTA_CRIADA, consulta, instante, ConsultaPayload.Status.AGENDADA, "primeiro"));
        consumidor.consumir(evento(TipoEvento.CONSULTA_CONFIRMADA, consulta, instante, ConsultaPayload.Status.CONFIRMADA, "segundo"));
        assertThat(quantidade("consulta_evento")).isEqualTo(2);
        assertThat(quantidade("evento_processado")).isEqualTo(2);
        assertThat(snapshots.findById(consulta).orElseThrow().getStatus()).isEqualTo("CONFIRMADA");
        assertThat(snapshots.findById(consulta).orElseThrow().getObservacoes()).isEqualTo("segundo");
    }
}
