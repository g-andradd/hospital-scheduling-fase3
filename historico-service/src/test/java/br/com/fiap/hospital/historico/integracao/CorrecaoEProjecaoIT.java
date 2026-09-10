package br.com.fiap.hospital.historico.integracao;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.fiap.hospital.contracts.ConsultaPayload;
import br.com.fiap.hospital.contracts.EventoEnvelope;
import br.com.fiap.hospital.contracts.TipoEvento;
import br.com.fiap.hospital.historico.infrastructure.correcao.CorrecaoDeRegistroHistorico;
import br.com.fiap.hospital.historico.infrastructure.messaging.ConsumidorTransacionalDoHistorico;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * A correcao manual e a projecao por eventos disputam o mesmo snapshot.
 *
 * <p>Nao ha regra nova para essa disputa: a correcao avanca {@code atualizado_em} para o
 * seu instante, e a regra de monotonicidade ja promovida no M08 decide o resto. Estes
 * testes existem para provar que ela decide certo nas duas direcoes — o que e diferente de
 * supor que decide.
 */
@DisplayName("Correcao manual e projecao de eventos")
class CorrecaoEProjecaoIT extends GraphqlITBase {

    private static final String CORRIGIR = """
            mutation($in: CorrigirRegistroHistoricoInput!) {
              corrigirRegistroHistorico(input: $in) { id }
            }
            """;

    @Autowired ConsumidorTransacionalDoHistorico consumidor;

    private void corrigirNome(UUID consultaId, String nome) {
        Map<String, Object> input = new HashMap<>();
        input.put("consultaId", consultaId.toString());
        input.put("justificativa", "correcao manual de nome");
        input.put("pacienteNome", nome);
        assertThat(executar(tokenMedico(), CORRIGIR, Map.of("in", input)).temErro()).isFalse();
    }

    private EventoEnvelope<ConsultaPayload> evento(UUID consultaId, Instant ocorridoEm, String nome) {
        var paciente = new ConsultaPayload.Paciente(pacienteA, nome, "maria@hospital.com", "+5561999990000");
        var medico = new ConsultaPayload.Medico(medicoA, "Dr. Joao Lima", "DF-12345", "Cardiologia");
        var registrante = new ConsultaPayload.Registrante(
                UUID.randomUUID(), "Ana Enfermeira", ConsultaPayload.Perfil.ENFERMEIRO);
        var payload = new ConsultaPayload(consultaId, ConsultaPayload.Status.CONFIRMADA,
                OffsetDateTime.ofInstant(ocorridoEm, ZoneOffset.UTC), 30, "do evento", null,
                paciente, medico, registrante,
                // O contrato exige as alteracoes num evento de atualizacao; sem elas o
                // envelope e recusado antes de chegar a projecao.
                Map.of("observacoesAnterior", "anterior"));
        return new EventoEnvelope<>(UUID.randomUUID(), TipoEvento.CONSULTA_ATUALIZADA, consultaId,
                ocorridoEm, 1, "correlacao-m09", payload);
    }

    @Test
    @DisplayName("Scenario: Evento antigo nao desfaz a correcao")
    void eventoAntigoNaoDesfazACorrecao() {
        UUID id = inserirConsulta(pacienteA, medicoA, instante("2026-09-18T14:30:00Z"), "AGENDADA");
        corrigirNome(id, "Nome Corrigido");

        consumidor.consumir(evento(id, AGORA.minusSeconds(3600), "Nome Do Evento Antigo"));

        assertThat(snapshots.findById(id).orElseThrow().getPacienteNome())
                .as("um evento anterior a correcao nao pode reverter o que o medico corrigiu")
                .isEqualTo("Nome Corrigido");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM consulta_evento WHERE consulta_id = ?", Long.class, id))
                .as("mas o fato antigo continua entrando na trilha")
                .isEqualTo(2L);
    }

    @Test
    @DisplayName("Scenario: Evento posterior volta a atualizar o snapshot")
    void eventoPosteriorVoltaAAtualizarOSnapshot() {
        UUID id = inserirConsulta(pacienteA, medicoA, instante("2026-09-18T14:30:00Z"), "AGENDADA");
        corrigirNome(id, "Nome Corrigido");

        consumidor.consumir(evento(id, AGORA.plusSeconds(3600), "Nome Do Evento Novo"));

        assertThat(snapshots.findById(id).orElseThrow().getPacienteNome())
                .as("a correcao nao congela o registro: fato posterior legitimo prevalece")
                .isEqualTo("Nome Do Evento Novo");
    }

    /**
     * Scenario: Correcao nao registra marca de evento processado.
     *
     * <p>A marca pertence ao protocolo AMQP. Criar uma aqui faria um {@code eventId} que
     * nunca existiu parecer processado — e uma reentrega legitima desse id seria descartada
     * em silencio.
     */
    @Test
    @DisplayName("Scenario: Correcao nao registra marca de evento processado")
    void correcaoNaoRegistraMarcaDeEventoProcessado() {
        UUID id = inserirConsulta(pacienteA, medicoA, instante("2026-09-18T14:30:00Z"), "AGENDADA");

        corrigirNome(id, "Nome Corrigido");

        assertThat(processados.count()).isZero();
    }

    @Test
    @DisplayName("Scenario: Correcao nao altera o contrato de mensageria")
    void correcaoNaoAlteraOContratoDeMensageria() {
        UUID id = inserirConsulta(pacienteA, medicoA, instante("2026-09-18T14:30:00Z"), "AGENDADA");

        corrigirNome(id, "Nome Corrigido");

        String tipo = jdbc.queryForObject(
                "SELECT tipo_evento FROM consulta_evento WHERE consulta_id = ?", String.class, id);
        assertThat(tipo).isEqualTo(CorrecaoDeRegistroHistorico.TIPO_CORRECAO_MANUAL);
        assertThat(java.util.Arrays.stream(TipoEvento.values()).map(Enum::name))
                .as("o tipo da trilha e local do historico e nao pode vazar para o contrato")
                .doesNotContain(CorrecaoDeRegistroHistorico.TIPO_CORRECAO_MANUAL);
    }
}
