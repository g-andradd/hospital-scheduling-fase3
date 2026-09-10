package br.com.fiap.hospital.historico.infrastructure.graphql;

import graphql.ErrorType;
import graphql.GraphQLError;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.graphql.server.WebGraphQlInterceptor;
import org.springframework.graphql.server.WebGraphQlRequest;
import org.springframework.graphql.server.WebGraphQlResponse;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Normaliza o que falha <b>antes</b> da execucao.
 *
 * <p>Campo desconhecido no input, valor de enum inexistente, argumento de tipo errado e
 * documento sintaticamente invalido sao recusados pelo motor na analise e validacao. Nesse
 * momento nenhum data fetcher rodou, entao o {@code DataFetcherExceptionResolver} nao tem
 * o que resolver — e sem este interceptor a classificacao desses erros ficaria no padrao
 * da biblioteca, com a spec dependendo de um detalhe que nao controlamos.
 *
 * <p>Que nada tenha executado nao e detalhe: e o que torna testavel a diferenca entre
 * "recusado antes" e "executado e revertido depois".
 */
@Component
class ErrosDeAnaliseInterceptor implements WebGraphQlInterceptor {

    private static final Logger log = LoggerFactory.getLogger(ErrosDeAnaliseInterceptor.class);

    @Override
    public Mono<WebGraphQlResponse> intercept(WebGraphQlRequest requisicao, Chain proxima) {
        return proxima.next(requisicao).map(resposta -> {
            if (resposta.getErrors().isEmpty()) {
                return resposta;
            }
            List<GraphQLError> normalizados =
                    resposta.getExecutionResult().getErrors().stream()
                            .map(this::normalizar)
                            .toList();
            return resposta.transform(builder -> builder.errors(normalizados));
        });
    }

    private GraphQLError normalizar(GraphQLError erro) {
        if (jaClassificado(erro)) {
            return erro;
        }
        ErroDoHistorico codigo = anteriorAExecucao(erro)
                ? ErroDoHistorico.BAD_REQUEST
                : ErroDoHistorico.INTERNAL_ERROR;
        if (codigo == ErroDoHistorico.INTERNAL_ERROR) {
            // Mesma razao do tradutor de resolver: o cliente recebe mensagem generica, e
            // sanitizar sem registrar equivaleria a descartar o defeito.
            log.error("Erro nao classificado ({}): {}", erro.getErrorType(), erro.getMessage());
        }
        return graphql.GraphqlErrorBuilder.newError()
                .message(codigo == ErroDoHistorico.BAD_REQUEST
                        ? codigo.sanitizar(erro.getMessage())
                        : codigo.mensagemPadrao())
                .errorType(codigo)
                .locations(erro.getLocations())
                .extensions(Map.of("code", codigo.name()))
                .build();
    }

    private boolean jaClassificado(GraphQLError erro) {
        Map<String, Object> extensoes = erro.getExtensions();
        return extensoes != null && extensoes.containsKey("code");
    }

    /** Erros de analise e validacao do documento, anteriores a qualquer data fetcher. */
    private boolean anteriorAExecucao(GraphQLError erro) {
        Object tipo = erro.getErrorType();
        return tipo == ErrorType.InvalidSyntax
                || tipo == ErrorType.ValidationError
                || tipo == ErrorType.OperationNotSupported
                || tipo == org.springframework.graphql.execution.ErrorType.BAD_REQUEST;
    }
}
