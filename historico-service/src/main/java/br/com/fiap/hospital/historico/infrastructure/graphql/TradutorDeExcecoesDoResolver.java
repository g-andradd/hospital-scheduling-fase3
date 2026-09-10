package br.com.fiap.hospital.historico.infrastructure.graphql;

import graphql.GraphQLError;
import graphql.schema.DataFetchingEnvironment;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.graphql.execution.DataFetcherExceptionResolverAdapter;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/**
 * Traduz o que e lancado <b>dentro</b> dos resolvers.
 *
 * <p>Este e apenas um dos dois caminhos de erro. Campo desconhecido, enum inexistente e
 * documento malformado morrem na analise, antes de qualquer data fetcher — este tradutor
 * nunca os ve, e quem os normaliza e o {@link ErrosDeAnaliseInterceptor}. Os dois
 * compartilham {@link ErroDoHistorico}, que e onde a politica de codigos e de sanitizacao
 * mora de fato.
 *
 * <p>Sem este tradutor, {@code AccessDeniedException} sairia como erro interno: uma recusa
 * legitima de autorizacao apareceria para o cliente como defeito do servidor.
 */
@Component
class TradutorDeExcecoesDoResolver extends DataFetcherExceptionResolverAdapter {

    TradutorDeExcecoesDoResolver() {
        setThreadLocalContextAware(true);
    }

    private static final Logger log = LoggerFactory.getLogger(TradutorDeExcecoesDoResolver.class);

    @Override
    protected GraphQLError resolveToSingleError(Throwable excecao, DataFetchingEnvironment ambiente) {
        ErroDoHistorico erro = classificar(excecao);
        if (erro == ErroDoHistorico.INTERNAL_ERROR) {
            // O cliente recebe mensagem generica; quem opera o servico precisa da causa.
            // Sem este log, sanitizar a resposta significaria perder o defeito.
            log.error("Falha inesperada em {}", ambiente.getExecutionStepInfo().getPath(), excecao);
        }
        return graphql.GraphqlErrorBuilder.newError(ambiente)
                .message(erro.sanitizar(erro == ErroDoHistorico.INTERNAL_ERROR
                        ? null : excecao.getMessage()))
                .errorType(erro)
                .extensions(Map.of("code", erro.name()))
                .build();
    }

    private ErroDoHistorico classificar(Throwable excecao) {
        if (excecao instanceof AccessDeniedException) {
            return ErroDoHistorico.FORBIDDEN;
        }
        if (excecao instanceof ExcecoesDoHistorico.RegistroNaoEncontrado) {
            return ErroDoHistorico.NOT_FOUND;
        }
        if (excecao instanceof ExcecoesDoHistorico.CorrecaoInvalida
                || excecao instanceof IllegalArgumentException) {
            return ErroDoHistorico.BAD_REQUEST;
        }
        return ErroDoHistorico.INTERNAL_ERROR;
    }
}
