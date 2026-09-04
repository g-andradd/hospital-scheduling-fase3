package br.com.fiap.hospital.agendamento.domain.exception;

/**
 * Recusa por regra de propriedade: o solicitante nao e titular do recurso.
 *
 * <p>Distinta da recusa por perfil, que o Spring Security produz antes de chegar ao caso
 * de uso. Esta acontece quando o perfil ate permite a operacao, mas nao sobre este
 * recurso. Mapeada para 403.
 */
public class AcessoNegadoException extends RuntimeException {

    public AcessoNegadoException(String mensagem) {
        super(mensagem);
    }
}
