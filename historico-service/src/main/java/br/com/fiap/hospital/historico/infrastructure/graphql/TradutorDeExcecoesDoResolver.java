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
                .message(erro.sanitizar(mensagemDoServico(excecao, erro)))
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
        // Falha ao ligar o argumento ao tipo declarado: o valor recebido nao encaixa no
        // schema que o proprio servico publica. Isso e erro do cliente por definicao, e sem
        // esta linha um ID que nao e UUID saia como erro interno. A classificacao e nominal
        // — so esta excecao, e nao uma varredura de causas que rebaixasse falha de banco a
        // erro de entrada.
        if (excecao instanceof org.springframework.validation.BindException) {
            return ErroDoHistorico.BAD_REQUEST;
        }
        if (excecao instanceof ExcecoesDoHistorico.CorrecaoInvalida
                || excecao instanceof IllegalArgumentException) {
            return ErroDoHistorico.BAD_REQUEST;
        }
        return ErroDoHistorico.INTERNAL_ERROR;
    }


    /**
     * So o texto que o proprio servico escreveu chega ao cliente.
     *
     * <p>As recusas de dominio trazem mensagem util — qual campo, qual limite —, e ela passa.
     * A falha de binding, nao: a mensagem dela e do framework e cita pacote e classe, isto e,
     * descreve a implementacao para quem esta do lado de fora. Nesse caso vale a mensagem
     * padrao da categoria.
     */
    private static String mensagemDoServico(Throwable excecao, ErroDoHistorico erro) {
        if (erro == ErroDoHistorico.INTERNAL_ERROR
                || excecao instanceof org.springframework.validation.BindException) {
            return null;
        }
        return excecao.getMessage();
    }
}
