package br.com.fiap.hospital.agendamento.domain.exception;

/**
 * Cancelamento exige justificativa registrada.
 *
 * <p>E regra de negocio, nao erro de formato: a requisicao esta bem formada e o que a
 * recusa e a politica de que cancelamento hospitalar precisa ser justificado. Por isso
 * e mapeada para 422, e nao para os 400 do {@code IllegalArgumentException}.
 */
public class MotivoDeCancelamentoObrigatorioException extends RuntimeException {

    public MotivoDeCancelamentoObrigatorioException() {
        super("O motivo do cancelamento e obrigatorio");
    }
}
