package br.com.fiap.hospital.agendamento.domain;

import static br.com.fiap.hospital.agendamento.domain.StatusConsulta.AGENDADA;
import static br.com.fiap.hospital.agendamento.domain.StatusConsulta.CANCELADA;
import static br.com.fiap.hospital.agendamento.domain.StatusConsulta.CONFIRMADA;
import static br.com.fiap.hospital.agendamento.domain.StatusConsulta.REALIZADA;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

@DisplayName("StatusConsulta — maquina de estados")
class StatusConsultaTest {

    /**
     * As 16 combinacoes de origem e destino. A tabela esta escrita por extenso, e nao
     * derivada do proprio metodo sob teste, porque um teste que reimplementa a regra
     * que verifica nao verifica nada.
     */
    static Stream<Arguments> todasAsCombinacoes() {
        return Stream.of(
                Arguments.of(AGENDADA, AGENDADA, false),
                Arguments.of(AGENDADA, CONFIRMADA, true),
                Arguments.of(AGENDADA, REALIZADA, true),
                Arguments.of(AGENDADA, CANCELADA, true),

                Arguments.of(CONFIRMADA, AGENDADA, false),
                Arguments.of(CONFIRMADA, CONFIRMADA, false),
                Arguments.of(CONFIRMADA, REALIZADA, true),
                Arguments.of(CONFIRMADA, CANCELADA, true),

                Arguments.of(REALIZADA, AGENDADA, false),
                Arguments.of(REALIZADA, CONFIRMADA, false),
                Arguments.of(REALIZADA, REALIZADA, false),
                Arguments.of(REALIZADA, CANCELADA, false),

                Arguments.of(CANCELADA, AGENDADA, false),
                Arguments.of(CANCELADA, CONFIRMADA, false),
                Arguments.of(CANCELADA, REALIZADA, false),
                Arguments.of(CANCELADA, CANCELADA, false));
    }

    @ParameterizedTest(name = "{0} -> {1} permitido? {2}")
    @MethodSource("todasAsCombinacoes")
    @DisplayName("cobre as 16 combinacoes de origem e destino")
    void cobreTodasAsCombinacoes(StatusConsulta origem, StatusConsulta destino, boolean permitido) {
        assertThat(origem.podeTransicionarPara(destino)).isEqualTo(permitido);
    }

    @ParameterizedTest
    @EnumSource(value = StatusConsulta.class, names = {"REALIZADA", "CANCELADA"})
    @DisplayName("REALIZADA e CANCELADA sao terminais e nao admitem saida")
    void statusTerminaisNaoAdmitemSaida(StatusConsulta terminal) {
        assertThat(terminal.terminal()).isTrue();
        assertThat(terminal.ativa()).isFalse();
        for (StatusConsulta destino : StatusConsulta.values()) {
            assertThat(terminal.podeTransicionarPara(destino)).isFalse();
        }
    }

    @ParameterizedTest
    @EnumSource(value = StatusConsulta.class, names = {"AGENDADA", "CONFIRMADA"})
    @DisplayName("AGENDADA e CONFIRMADA ocupam a agenda")
    void statusNaoTerminaisOcupamAgenda(StatusConsulta ativo) {
        assertThat(ativo.ativa()).isTrue();
        assertThat(ativo.terminal()).isFalse();
    }

    @Test
    @DisplayName("transicao para o proprio status e recusada em todos os casos")
    void transicaoParaOMesmoStatusERecusada() {
        for (StatusConsulta status : StatusConsulta.values()) {
            assertThat(status.podeTransicionarPara(status))
                    .as("%s -> %s", status, status)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("destino nulo e recusado")
    void destinoNuloERecusado() {
        assertThatThrownBy(() -> AGENDADA.podeTransicionarPara(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("obrigatorio");
    }
}
