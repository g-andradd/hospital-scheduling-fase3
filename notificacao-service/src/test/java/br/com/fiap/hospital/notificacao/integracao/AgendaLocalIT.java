package br.com.fiap.hospital.notificacao.integracao;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.fiap.hospital.contracts.ConsultaPayload;
import br.com.fiap.hospital.contracts.TipoEvento;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * A agenda alimentada pelos eventos, atravessando o listener real.
 *
 * <p>Cada caso publica no exchange normativo e espera a marca de processado aparecer. Um
 * teste que chamasse o processador direto seria mais rapido e nao diria nada sobre o
 * listener, a transacao da fronteira ou a desserializacao do envelope.
 */
@DisplayName("Agenda local")
class AgendaLocalIT extends NotificacaoITBase {

    private static final Instant FATO = Instant.parse("2026-09-11T09:00:00Z");
    private static final OffsetDateTime DATA_HORA =
            OffsetDateTime.ofInstant(Instant.parse("2026-09-20T14:30:00Z"), ZoneOffset.UTC);

    static Stream<Arguments> tiposEStatus() {
        return Stream.of(
                Arguments.of(TipoEvento.CONSULTA_CRIADA, ConsultaPayload.Status.AGENDADA),
                Arguments.of(TipoEvento.CONSULTA_ATUALIZADA, ConsultaPayload.Status.AGENDADA),
                Arguments.of(TipoEvento.CONSULTA_CONFIRMADA, ConsultaPayload.Status.CONFIRMADA),
                Arguments.of(TipoEvento.CONSULTA_CANCELADA, ConsultaPayload.Status.CANCELADA),
                Arguments.of(TipoEvento.CONSULTA_REALIZADA, ConsultaPayload.Status.REALIZADA));
    }

    /**
     * Scenario: Evento posterior sem criacao previa materializa a agenda.
     *
     * <p>Cada tipo e testado como <b>primeiro</b> evento da consulta. Depender da criacao
     * ter chegado antes criaria uma falha silenciosa real: entrega fora de ordem, ou
     * replay a partir da DLQ, deixaria a consulta sem linha e o M07 nunca a veria.
     */
    @ParameterizedTest(name = "{0} materializa a agenda sozinho")
    @MethodSource("tiposEStatus")
    @DisplayName("Scenario: Evento posterior sem criacao previa materializa a agenda")
    void qualquerTipoMaterializaAAgenda(TipoEvento tipo, ConsultaPayload.Status status) {
        UUID consultaId = UUID.randomUUID();

        publicarEAguardar(evento(tipo, consultaId, FATO, status));

        Map<String, Object> linha = agenda(consultaId);
        assertThat(linha)
                .as("%s precisa materializar a linha sem depender de evento anterior", tipo)
                .isNotEmpty();
        assertThat(linha.get("status")).isEqualTo(status.name());
        assertThat(linha.get("paciente_nome")).isEqualTo("Maria Souza");
        assertThat(linha.get("paciente_email")).isEqualTo("maria@hospital.com");
        assertThat(linha.get("medico_nome")).isEqualTo("Dr. Joao Lima");
        assertThat(((java.sql.Timestamp) linha.get("ocorrido_em")).toInstant()).isEqualTo(FATO);
        assertThat(((java.sql.Timestamp) linha.get("atualizado_em")).toInstant())
                .as("atualizado_em vem do Clock injetado, nao do relogio do banco")
                .isEqualTo(AGORA);
    }

    @Test
    @DisplayName("Scenario: Criacao materializa a agenda local")
    void criacaoMaterializaAAgendaLocal() {
        UUID consultaId = UUID.randomUUID();

        publicarEAguardar(evento(TipoEvento.CONSULTA_CRIADA, consultaId, FATO,
                ConsultaPayload.Status.AGENDADA, "Dr. Joao Lima", DATA_HORA));

        Map<String, Object> linha = agenda(consultaId);
        assertThat(linha.get("consulta_id")).isEqualTo(consultaId);
        assertThat(linha.get("status")).isEqualTo("AGENDADA");
        assertThat(((java.sql.Timestamp) linha.get("data_hora")).toInstant())
                .isEqualTo(DATA_HORA.toInstant());
        assertThat(quantidade("agenda_local")).isEqualTo(1);
    }

    @Test
    @DisplayName("Scenario: Atualizacao altera a agenda sem duplica-la")
    void atualizacaoAlteraSemDuplicar() {
        UUID consultaId = UUID.randomUUID();
        OffsetDateTime novoHorario = DATA_HORA.plusDays(2);

        publicarEAguardar(evento(TipoEvento.CONSULTA_CRIADA, consultaId, FATO,
                ConsultaPayload.Status.AGENDADA, "Dr. Joao Lima", DATA_HORA));
        publicarEAguardar(evento(TipoEvento.CONSULTA_ATUALIZADA, consultaId, FATO.plusSeconds(60),
                ConsultaPayload.Status.AGENDADA, "Dra. Marta Reis", novoHorario));

        assertThat(quantidade("agenda_local"))
                .as("a mesma consulta nao pode virar duas linhas")
                .isEqualTo(1);
        Map<String, Object> linha = agenda(consultaId);
        assertThat(linha.get("medico_nome")).isEqualTo("Dra. Marta Reis");
        assertThat(((java.sql.Timestamp) linha.get("data_hora")).toInstant())
                .isEqualTo(novoHorario.toInstant());
    }

    /**
     * Scenario: Cancelamento preserva a linha e marca o status.
     *
     * <p>Apagar a linha tornaria "consulta cancelada" indistinguivel de "consulta que nao
     * existe" — e o lembrete do M07 depende justamente dessa diferenca.
     */
    @Test
    @DisplayName("Scenario: Cancelamento preserva a linha e marca o status")
    void cancelamentoPreservaALinha() {
        UUID consultaId = UUID.randomUUID();

        publicarEAguardar(evento(TipoEvento.CONSULTA_CRIADA, consultaId, FATO,
                ConsultaPayload.Status.AGENDADA));
        publicarEAguardar(evento(TipoEvento.CONSULTA_CANCELADA, consultaId, FATO.plusSeconds(60),
                ConsultaPayload.Status.CANCELADA));

        assertThat(agenda(consultaId))
                .as("a linha precisa continuar existindo depois do cancelamento")
                .isNotEmpty();
        assertThat(agenda(consultaId).get("status")).isEqualTo("CANCELADA");
        assertThat(quantidade("agenda_local")).isEqualTo(1);
    }

    @Test
    @DisplayName("Scenario: Confirmacao e realizacao mantem a agenda coerente")
    void confirmacaoERealizacaoAtualizamSomenteOStatus() {
        UUID consultaId = UUID.randomUUID();

        publicarEAguardar(evento(TipoEvento.CONSULTA_CRIADA, consultaId, FATO,
                ConsultaPayload.Status.AGENDADA, "Dr. Joao Lima", DATA_HORA));
        publicarEAguardar(evento(TipoEvento.CONSULTA_CONFIRMADA, consultaId, FATO.plusSeconds(60),
                ConsultaPayload.Status.CONFIRMADA, "Dr. Joao Lima", DATA_HORA));

        Map<String, Object> aposConfirmar = agenda(consultaId);
        assertThat(aposConfirmar.get("status")).isEqualTo("CONFIRMADA");
        assertThat(aposConfirmar.get("medico_nome")).isEqualTo("Dr. Joao Lima");
        assertThat(((java.sql.Timestamp) aposConfirmar.get("data_hora")).toInstant())
                .as("as demais dimensoes continuam as do snapshot recebido")
                .isEqualTo(DATA_HORA.toInstant());

        publicarEAguardar(evento(TipoEvento.CONSULTA_REALIZADA, consultaId, FATO.plusSeconds(120),
                ConsultaPayload.Status.REALIZADA, "Dr. Joao Lima", DATA_HORA));

        Map<String, Object> aposRealizar = agenda(consultaId);
        assertThat(aposRealizar.get("status")).isEqualTo("REALIZADA");
        assertThat(((java.sql.Timestamp) aposRealizar.get("data_hora")).toInstant())
                .isEqualTo(DATA_HORA.toInstant());
        assertThat(quantidade("agenda_local")).isEqualTo(1);
    }
}
