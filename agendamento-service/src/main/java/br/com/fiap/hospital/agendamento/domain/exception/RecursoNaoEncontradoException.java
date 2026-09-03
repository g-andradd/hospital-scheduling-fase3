package br.com.fiap.hospital.agendamento.domain.exception;

/**
 * Base das recusas por recurso inexistente.
 *
 * <p>Existe para que o mapa de erros de docs/01-arquitetura.md secao 8 precise de uma
 * unica entrada — {@code RecursoNaoEncontrado -> 404} — e o tratador do M03 capture a
 * familia inteira. Cada subtipo carrega a mensagem correta do seu recurso, de modo que
 * um paciente inexistente nao responda "Consulta nao encontrada".
 */
public abstract class RecursoNaoEncontradoException extends RuntimeException {

    protected RecursoNaoEncontradoException(String mensagem) {
        super(mensagem);
    }
}
