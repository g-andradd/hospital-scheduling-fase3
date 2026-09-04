package br.com.fiap.hospital.agendamento.domain.exception;

/**
 * Autenticacao recusada.
 *
 * <p>Uma unica excecao para e-mail inexistente, senha errada e usuario inativo, de
 * proposito: distinguir os casos para o cliente informaria se o e-mail existe.
 */
public class CredencialInvalidaException extends RuntimeException {

    public CredencialInvalidaException() {
        super("Credencial invalida");
    }
}
