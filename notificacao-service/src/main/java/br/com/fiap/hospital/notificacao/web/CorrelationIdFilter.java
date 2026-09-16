package br.com.fiap.hospital.notificacao.web;

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
 * Da a cada requisicao um identificador de correlacao, com a mesma semantica do filtro do
 * agendamento (RNF-08).
 *
 * <p>Le {@code X-Correlation-Id} do cliente quando presente e nao vazio; caso contrario gera
 * um. O valor fica no atributo da requisicao, de onde a resposta de seguranca o copia para o
 * ProblemDetail das recusas, volta no cabecalho da resposta e fica no MDC durante a cadeia.
 *
 * <p>Copia deliberada, e nao auto-configuracao compartilhada: unificar os filtros mexeria no
 * agendamento sem ganho funcional. Roda com a maior precedencia, antes da cadeia de seguranca,
 * para que 401 e 403 tambem levem o id.
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
}
