package br.com.fiap.hospital.agendamento.domain.exception;

import java.util.UUID;

/**
 * Duas alteracoes concorrentes atingiram a mesma consulta e uma delas perdeu.
 *
 * <p>Existe para que a falha de lock otimista chegue as camadas de cima como conceito
 * de dominio. Sem ela, um tipo do Spring subiria pela aplicacao e o M03 precisaria
 * conhecer excecoes de persistencia para montar o ProblemDetail. Mapeada para 409.
 */
public class AlteracaoConcorrenteException extends RuntimeException {

    public AlteracaoConcorrenteException(UUID consultaId) {
        super("A consulta " + consultaId + " foi alterada por outra operacao. Recarregue e tente novamente");
    }
}
