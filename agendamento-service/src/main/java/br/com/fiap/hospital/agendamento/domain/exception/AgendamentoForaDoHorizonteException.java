package br.com.fiap.hospital.agendamento.domain.exception;

import java.time.OffsetDateTime;

/**
 * Consulta marcada alem do horizonte de agendamento.
 *
 * <p>Mesma natureza de {@link AgendamentoNoPassadoException}: requisicao bem formada
 * recusada por regra de negocio, e por isso 422 e nao 400.
 */
public class AgendamentoForaDoHorizonteException extends RuntimeException {

    public AgendamentoForaDoHorizonteException(
            OffsetDateTime solicitado, OffsetDateTime limite, int horizonteEmMeses) {
        super("Nao e possivel agendar consulta com mais de " + horizonteEmMeses
                + " meses de antecedencia. Solicitado: " + solicitado + ", limite: " + limite);
    }
}
