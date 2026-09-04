package br.com.fiap.hospital.agendamento.application;

import br.com.fiap.hospital.agendamento.domain.FiltroDeConsultas;
import br.com.fiap.hospital.agendamento.domain.StatusConsulta;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;

/**
 * Entrada de {@link ListarConsultasUseCase}. Campo nulo ou vazio nao filtra.
 *
 * <p>O tamanho de pagina pedido pode ser aparado pelo teto do dominio; o resultado
 * informa o tamanho efetivamente aplicado.
 */
public record ListarConsultasQuery(
        UUID pacienteId,
        UUID medicoId,
        Set<StatusConsulta> status,
        OffsetDateTime de,
        OffsetDateTime ate,
        int pagina,
        int tamanho) {

    /** Filtros informados, primeira pagina, tamanho padrao. */
    public static ListarConsultasQuery filtrando(
            UUID pacienteId,
            UUID medicoId,
            Set<StatusConsulta> status,
            OffsetDateTime de,
            OffsetDateTime ate) {
        return new ListarConsultasQuery(
                pacienteId, medicoId, status, de, ate, 0, FiltroDeConsultas.TAMANHO_PADRAO);
    }

    public static ListarConsultasQuery semFiltro() {
        return new ListarConsultasQuery(
                null, null, null, null, null, 0, FiltroDeConsultas.TAMANHO_PADRAO);
    }

    /** Substitui o filtro de paciente pelo identificador informado. */
    ListarConsultasQuery recortadaPara(java.util.UUID proprioPacienteId) {
        return new ListarConsultasQuery(
                proprioPacienteId, medicoId, status, de, ate, pagina, tamanho);
    }

    FiltroDeConsultas paraFiltro() {
        return new FiltroDeConsultas(pacienteId, medicoId, status, de, ate, pagina, tamanho);
    }
}
