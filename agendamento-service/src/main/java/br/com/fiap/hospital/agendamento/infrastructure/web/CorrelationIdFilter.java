package br.com.fiap.hospital.agendamento.infrastructure.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Da a cada requisicao um identificador de correlacao.
 *
 * <p>Le {@code X-Correlation-Id} do cliente quando presente, para nao quebrar um id que
 * ja vem de outro sistema; caso contrario gera um. O valor fica no atributo da
 * requisicao, e dali o tratador de erros o copia para o ProblemDetail, e volta no
 * cabecalho da resposta.
 *
 * <p>Antecipado do M11 porque o ProblemDetail da secao 8 ja exige o campo. M05 leva este id ao MDC e ao envelope persistido. Ao M11 resta
 * propagar este mesmo id ao consumidor e replicar o
 * filtro nos outros dois servicos.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String CABECALHO = "X-Correlation-Id";
    public static final String ATRIBUTO = "correlationId";

    @Override
    protected void doFilterInternal(
            HttpServletRequest requisicao, HttpServletResponse resposta, FilterChain cadeia)
            throws ServletException, IOException {

        String recebido = requisicao.getHeader(CABECALHO);
        String correlationId = recebido == null || recebido.isBlank()
                ? UUID.randomUUID().toString()
                : recebido;

        requisicao.setAttribute(ATRIBUTO, correlationId);
        resposta.setHeader(CABECALHO, correlationId);
        String anterior = MDC.get(ATRIBUTO);
        MDC.put(ATRIBUTO, correlationId);
        try {
            cadeia.doFilter(requisicao, resposta);
        } finally {
            if (anterior == null) MDC.remove(ATRIBUTO);
            else MDC.put(ATRIBUTO, anterior);
        }
    }

    /** Recupera o id da requisicao corrente, gerando um se o filtro nao passou. */
    public static String de(HttpServletRequest requisicao) {
        Object valor = requisicao == null ? null : requisicao.getAttribute(ATRIBUTO);
        return valor instanceof String id ? id : UUID.randomUUID().toString();
    }
}
