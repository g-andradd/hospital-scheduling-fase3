package br.com.fiap.hospital.agendamento.domain;

import java.time.OffsetDateTime;
import java.util.Set;

/**
 * Criterios de listagem. Qualquer campo nulo ou vazio significa "nao filtrar por isto";
 * os criterios informados sao combinados com E.
 *
 * <p>E um tipo de dominio, e nao de aplicacao, porque a porta de repositorio o recebe:
 * o adaptador do M02 vai traduzi-lo para clausulas SQL.
 */
public record FiltroDeConsultas(
        java.util.UUID pacienteId,
        java.util.UUID medicoId,
        Set<StatusConsulta> status,
        OffsetDateTime de,
        OffsetDateTime ate) {

    public FiltroDeConsultas {
        status = status == null ? Set.of() : Set.copyOf(status);
    }

    public static FiltroDeConsultas vazio() {
        return new FiltroDeConsultas(null, null, Set.of(), null, null);
    }

    public boolean aceita(Consulta consulta) {
        if (pacienteId != null && !pacienteId.equals(consulta.pacienteId())) {
            return false;
        }
        if (medicoId != null && !medicoId.equals(consulta.medicoId())) {
            return false;
        }
        if (!status.isEmpty() && !status.contains(consulta.status())) {
            return false;
        }
        OffsetDateTime inicio = consulta.periodo().inicio();
        if (de != null && inicio.isBefore(de)) {
            return false;
        }
        return ate == null || !inicio.isAfter(ate);
    }
}
