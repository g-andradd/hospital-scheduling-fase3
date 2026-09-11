package br.com.fiap.hospital.notificacao.web;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Erro interno do disparo manual em Problem Detail, como todo erro HTTP do projeto.
 *
 * <p>Estreito em duas direcoes. Alcanca apenas o controller do lembrete, e nao controllers
 * futuros sem decisao propria. E <b>nao</b> trata a recusa de seguranca: um tratador generico
 * capturaria o {@link AccessDeniedException} do {@code @PreAuthorize} e transformaria 403 em
 * 500 — o defeito de docs/01-arquitetura.md secao 8. Relancar a excecao original faz o
 * resolvedor considera-la nao tratada; ela segue ate o {@code ExceptionTranslationFilter} e
 * sai da mesma {@code RespostaDeSeguranca} dos demais servicos.
 *
 * <p>O detalhe e fixo: SQL, nome de tabela e classe de excecao ficam no log, junto com o
 * {@code correlationId} que a resposta devolve.
 */
@RestControllerAdvice(assignableTypes = LembreteController.class)
class TratadorDeErroDoLembrete {

    static final URI TYPE_ERRO_INTERNO = URI.create("https://hospital.fiap.br/erros/erro-interno");
    static final String DETALHE =
            "Nao foi possivel executar os lembretes. Tente novamente mais tarde.";

    private static final Logger log = LoggerFactory.getLogger(TratadorDeErroDoLembrete.class);

    private final Clock clock;

    TratadorDeErroDoLembrete(Clock clock) {
        this.clock = clock;
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> erroInterno(Exception excecao, HttpServletRequest requisicao)
            throws Exception {
        if (excecao instanceof AccessDeniedException || excecao instanceof AuthenticationException) {
            throw excecao;
        }
        String correlationId = correlationIdDe(requisicao);
        log.error("Falha ao executar os lembretes D-1 [correlationId={}]", correlationId, excecao);

        ProblemDetail problema =
                ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, DETALHE);
        problema.setType(TYPE_ERRO_INTERNO);
        problema.setTitle("Erro interno");
        problema.setInstance(URI.create(requisicao.getRequestURI()));
        problema.setProperty("correlationId", correlationId);
        problema.setProperty("timestamp",
                OffsetDateTime.ofInstant(clock.instant(), clock.getZone()).toString());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problema);
    }

    /** A mesma ordem de RespostaDeSeguranca: atributo, cabecalho do cliente, gerado. */
    private static String correlationIdDe(HttpServletRequest requisicao) {
        Object atributo = requisicao.getAttribute("correlationId");
        if (atributo instanceof String id) {
            return id;
        }
        String cabecalho = requisicao.getHeader("X-Correlation-Id");
        return cabecalho == null || cabecalho.isBlank() ? UUID.randomUUID().toString() : cabecalho;
    }
}
