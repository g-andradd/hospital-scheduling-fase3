package br.com.fiap.hospital.agendamento.domain.exception;

/**
 * Texto livre com caractere que o armazenamento e o outbox nao representam: o NUL.
 *
 * <p>Mesma natureza de {@link MotivoDeCancelamentoObrigatorioException}: a requisicao e bem
 * formada, e o que ela pede e que nao pode ser atendido. Por isso 422, e nao 400.
 *
 * <p>A mensagem nao repete o caractere recebido: devolve-lo na resposta seria usar o erro
 * como canal para ele.
 */
public class TextoComCaractereInvalidoException extends RuntimeException {

    public TextoComCaractereInvalidoException(String campo) {
        super("O campo '" + campo + "' contem caractere nulo, que nao pode ser gravado");
    }
}
