package br.com.fiap.hospital.historico.infrastructure.graphql;

import java.util.UUID;

/**
 * Valida o identificador recebido como {@code ID} antes de ele alcancar o repositorio.
 *
 * <p>O {@code ID} do GraphQL e um escalar de texto: ele aceita {@code "nao-e-um-uuid"},
 * {@code ""} e ate um numero, porque nao descreve formato algum. Quem descreve e o servico.
 *
 * <p>A conversao para {@link UUID} acontece antes do resolver e, quando falha, <b>nao
 * lanca</b>: o argumento chega nulo. Sem esta checagem, esse nulo seguia ate o repositorio
 * e voltava como {@code InvalidDataAccessApiUsageException} — erro interno para uma entrada
 * do cliente. Como o schema declara os identificadores como obrigatorios, nulo aqui so pode
 * ter vindo de uma conversao que falhou.
 */
public final class IdentificadorDoHistorico {

    private IdentificadorDoHistorico() {}

    /**
     * @throws IllegalArgumentException traduzida para {@code BAD_REQUEST} pelo tradutor de
     *     excecoes do resolver
     */
    public static UUID exigir(UUID identificador, String campo) {
        if (identificador == null) {
            throw new IllegalArgumentException(
                    "O valor de '" + campo + "' nao e um identificador valido.");
        }
        return identificador;
    }
}
