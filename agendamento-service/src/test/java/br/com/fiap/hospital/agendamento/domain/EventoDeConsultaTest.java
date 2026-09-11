package br.com.fiap.hospital.agendamento.domain;

import static br.com.fiap.hospital.agendamento.Cenario.AGORA;
import static br.com.fiap.hospital.agendamento.Cenario.consultaAgendada;
import static br.com.fiap.hospital.agendamento.Cenario.daquiA;
import static br.com.fiap.hospital.agendamento.Cenario.medico;
import static br.com.fiap.hospital.agendamento.Cenario.paciente;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

@DisplayName("EventoDeConsulta")
class EventoDeConsultaTest {

    @ParameterizedTest
    @EnumSource(TipoEventoConsulta.class)
    @DisplayName("o evento preserva o tipo da mudanca que o originou")
    void eventoPreservaOTipo(TipoEventoConsulta tipo) {
        Consulta consulta = consultaAgendada(paciente(), medico(), daquiA(24));

        EventoDeConsulta evento = EventoDeConsulta.de(consulta, tipo);

        assertThat(evento.tipo()).isEqualTo(tipo);
        assertThat(evento.consultaId()).isEqualTo(consulta.id());
        assertThat(evento.ocorridoEm()).isEqualTo(consulta.atualizadoEm());
    }

    @Test
    @DisplayName("o instante do evento acompanha a ultima mudanca da consulta")
    void instanteAcompanhaAUltimaMudanca() {
        Consulta consulta = consultaAgendada(paciente(), medico(), daquiA(24));
        consulta.confirmar(daquiA(2));

        assertThat(EventoDeConsulta.de(consulta, TipoEventoConsulta.CONFIRMADA).ocorridoEm())
                .isEqualTo(daquiA(2));
    }

    @Test
    @DisplayName("os tipos espelham as routing keys do contrato de eventos")
    void tiposEspelhamAsRoutingKeys() {
        assertThat(TipoEventoConsulta.values())
                .containsExactly(
                        TipoEventoConsulta.CRIADA,
                        TipoEventoConsulta.ATUALIZADA,
                        TipoEventoConsulta.CONFIRMADA,
                        TipoEventoConsulta.CANCELADA,
                        TipoEventoConsulta.REALIZADA);
    }

    @Test
    @DisplayName("campos obrigatorios ausentes sao recusados")
    void camposObrigatoriosAusentes() {
        assertThatThrownBy(() -> new EventoDeConsulta(null, TipoEventoConsulta.CRIADA, AGORA, null, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new EventoDeConsulta(UUID.randomUUID(), null, AGORA, null, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() ->
                        new EventoDeConsulta(UUID.randomUUID(), TipoEventoConsulta.CRIADA, null, null, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> EventoDeConsulta.de(null, TipoEventoConsulta.CRIADA))
                .isInstanceOf(NullPointerException.class);
    }
}
