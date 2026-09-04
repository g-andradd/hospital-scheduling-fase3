package br.com.fiap.hospital.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.json.ProblemDetailJacksonMixin;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

/**
 * Escreve as recusas de seguranca no mesmo formato das demais respostas de erro.
 *
 * <p>O Spring Security recusa antes de a requisicao alcancar o tratador global de erros,
 * entao 401 e 403 precisam ser montados aqui. Sem isso sairiam no formato padrao do
 * container, quebrando o contrato de erro do restante da API.
 *
 * <p>O detalhe e fixo por categoria e nao menciona o que faltou. Responder "token
 * expirado" em vez de "credencial ausente ou invalida" entregaria a informacao de que o
 * token foi valido em algum momento.
 */
public class RespostaDeSeguranca implements AuthenticationEntryPoint, AccessDeniedHandler {

    public static final String TYPE_NAO_AUTENTICADO =
            "https://hospital.fiap.br/erros/nao-autenticado";
    public static final String TYPE_ACESSO_NEGADO =
            "https://hospital.fiap.br/erros/acesso-negado";

    private final ObjectMapper mapper;
    private final Clock clock;

    /**
     * Registra o mixin do {@link ProblemDetail} numa copia do mapper recebido.
     *
     * <p>Sem ele, {@code correlationId} e {@code timestamp} — que sao propriedades
     * estendidas — simplesmente nao saem no JSON. O Spring Boot registra esse mixin no
     * mapper que auto-configura, entao dentro desta aplicacao funcionaria de qualquer
     * jeito; mas este modulo existe para ser reusado, e um servico que monte o proprio
     * {@code ObjectMapper} perderia os dois campos <b>em silencio</b>, sem erro algum.
     * Uma copia local custa nada e tira a garantia das maos de quem faz a fiacao.
     */
    public RespostaDeSeguranca(ObjectMapper mapper, Clock clock) {
        this.mapper = mapper.copy().addMixIn(ProblemDetail.class, ProblemDetailJacksonMixin.class);
        this.clock = clock;
    }

    @Override
    public void commence(
            HttpServletRequest requisicao,
            HttpServletResponse resposta,
            AuthenticationException excecao)
            throws IOException {

        escrever(requisicao, resposta, HttpStatus.UNAUTHORIZED, TYPE_NAO_AUTENTICADO,
                "Nao autenticado", "Credencial ausente ou invalida");
    }

    @Override
    public void handle(
            HttpServletRequest requisicao,
            HttpServletResponse resposta,
            AccessDeniedException excecao)
            throws IOException {

        escrever(requisicao, resposta, HttpStatus.FORBIDDEN, TYPE_ACESSO_NEGADO,
                "Acesso negado", "Seu perfil nao permite esta operacao");
    }

    private void escrever(
            HttpServletRequest requisicao,
            HttpServletResponse resposta,
            HttpStatus status,
            String type,
            String titulo,
            String detalhe)
            throws IOException {

        ProblemDetail problema = ProblemDetail.forStatus(status);
        problema.setType(URI.create(type));
        problema.setTitle(titulo);
        problema.setDetail(detalhe);
        problema.setInstance(URI.create(requisicao.getRequestURI()));
        problema.setProperty("correlationId", correlationIdDe(requisicao));
        problema.setProperty("timestamp", OffsetDateTime.now(clock).toString());

        resposta.setStatus(status.value());
        resposta.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        resposta.setCharacterEncoding("UTF-8");
        mapper.writeValue(resposta.getOutputStream(), problema);
    }

    private static String correlationIdDe(HttpServletRequest requisicao) {
        Object atributo = requisicao.getAttribute("correlationId");
        if (atributo instanceof String id) {
            return id;
        }
        String cabecalho = requisicao.getHeader("X-Correlation-Id");
        return cabecalho == null || cabecalho.isBlank() ? UUID.randomUUID().toString() : cabecalho;
    }
}
