package br.com.fiap.hospital.historico.infrastructure.graphql;

import graphql.ErrorClassification;

/**
 * A politica de codigos e de sanitizacao, num ponto so.
 *
 * <p>Uma requisicao GraphQL pode falhar em dois momentos — na analise do documento, antes
 * de qualquer resolver, e dentro de um resolver. Sao mecanismos diferentes: o
 * {@code DataFetcherExceptionResolver} so ve o segundo. Compartilhar esta classe e o que
 * impede os dois caminhos de responderem codigos diferentes para a mesma natureza de erro.
 */
public enum ErroDoHistorico implements ErrorClassification {
    FORBIDDEN("Acesso negado."),
    NOT_FOUND("Registro nao encontrado."),
    BAD_REQUEST("Requisicao invalida."),
    INTERNAL_ERROR("Nao foi possivel processar a requisicao.");

    private final String mensagemPadrao;

    ErroDoHistorico(String mensagemPadrao) {
        this.mensagemPadrao = mensagemPadrao;
    }

    public String mensagemPadrao() {
        return mensagemPadrao;
    }

    /**
     * Mensagem segura para sair na resposta.
     *
     * <p>So o texto que o proprio servico escreveu passa. Mensagem de excecao inesperada
     * carrega SQL, nome de classe e caminho de arquivo — detalhe que descreve a
     * implementacao para quem esta do lado de fora tentando descobri-la.
     */
    public String sanitizar(String mensagemOriginal) {
        if (this == INTERNAL_ERROR || mensagemOriginal == null || mensagemOriginal.isBlank()) {
            return mensagemPadrao;
        }
        return mensagemOriginal;
    }
}
