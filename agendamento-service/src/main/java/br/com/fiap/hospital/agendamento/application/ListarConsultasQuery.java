package br.com.fiap.hospital.agendamento.application;

import br.com.fiap.hospital.agendamento.domain.FiltroDeConsultas;
import br.com.fiap.hospital.agendamento.domain.StatusConsulta;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;

/** Entrada de {@link ListarConsultasUseCase}. Campo nulo ou vazio nao filtra. */
public record ListarConsultasQuery(
        UUID pacienteId,
        UUID medicoId,
        Set<StatusConsulta> status,
        OffsetDateTime de,
        OffsetDateTime ate) {

    public static ListarConsultasQuery semFiltro() {
        return new ListarConsultasQuery(null, null, null, null, null);
    }

    FiltroDeConsultas paraFiltro() {
        return new FiltroDeConsultas(pacienteId, medicoId, status, de, ate);
    }
}
