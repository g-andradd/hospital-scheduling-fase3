package br.com.fiap.hospital.agendamento.application;

import br.com.fiap.hospital.agendamento.domain.Consulta;
import br.com.fiap.hospital.agendamento.domain.StatusConsulta;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Saida dos casos de uso.
 *
 * <p>Nenhum caso de uso devolve a {@link Consulta} nua: o M03 serializa este record, de
 * modo que uma mudanca interna no agregado nao vire mudanca de contrato HTTP.
 */
public record ConsultaResumo(
        UUID id,
        UUID pacienteId,
        UUID medicoId,
        UUID registradoPorId,
        OffsetDateTime dataHora,
        int duracaoMinutos,
        StatusConsulta status,
        String observacoes,
        String motivoCancelamento,
        OffsetDateTime criadoEm,
        OffsetDateTime atualizadoEm) {

    public static ConsultaResumo de(Consulta consulta) {
        return new ConsultaResumo(
                consulta.id(),
                consulta.pacienteId(),
                consulta.medicoId(),
                consulta.registradoPorId(),
                consulta.periodo().inicio(),
                consulta.periodo().duracaoMinutos(),
                consulta.status(),
                consulta.observacoes(),
                consulta.motivoCancelamento(),
                consulta.criadoEm(),
                consulta.atualizadoEm());
    }
}
