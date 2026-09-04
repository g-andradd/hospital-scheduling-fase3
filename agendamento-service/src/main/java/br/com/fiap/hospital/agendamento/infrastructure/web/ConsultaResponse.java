package br.com.fiap.hospital.agendamento.infrastructure.web;

import br.com.fiap.hospital.agendamento.application.ConsultaResumo;
import br.com.fiap.hospital.agendamento.domain.StatusConsulta;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Representacao publicada de uma consulta.
 *
 * <p>Distinta do {@code ConsultaResumo} de aplicacao de proposito. Hoje os campos
 * coincidem, mas os dois significam coisas diferentes: o resumo e saida de caso de uso e
 * pode mudar porque o caso de uso mudou; este record e contrato, e muda-lo quebra
 * cliente. Fundi-los faria alteracao interna virar quebra de contrato sem ninguem
 * decidir isso.
 */
public record ConsultaResponse(
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

    public static ConsultaResponse de(ConsultaResumo resumo) {
        return new ConsultaResponse(
                resumo.id(), resumo.pacienteId(), resumo.medicoId(), resumo.registradoPorId(),
                resumo.dataHora(), resumo.duracaoMinutos(), resumo.status(), resumo.observacoes(),
                resumo.motivoCancelamento(), resumo.criadoEm(), resumo.atualizadoEm());
    }
}
