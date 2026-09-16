package br.com.fiap.hospital.historico.integracao.superficie;

/**
 * Familia de valores hostis que se aplica a uma entrada do schema.
 *
 * <p>As tres ultimas nao vem do tipo de um argumento: elas existem porque uma requisicao
 * GraphQL tem tres camadas atacaveis — o corpo HTTP, o documento e os objetos de entrada —
 * e cada uma falha num ponto diferente do caminho.
 */
public enum DimensaoGraphql {
    UUID,
    ENUM,
    DATA,
    TEXTO,
    INTEIRO,
    /** Objeto de entrada inteiro, como documento. */
    OBJETO,
    /** O documento GraphQL. */
    DOCUMENTO,
    /** O corpo HTTP que transporta o documento. */
    CORPO_HTTP
}
