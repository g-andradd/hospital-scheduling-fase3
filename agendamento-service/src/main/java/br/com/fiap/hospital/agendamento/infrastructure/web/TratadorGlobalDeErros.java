package br.com.fiap.hospital.agendamento.infrastructure.web;

import br.com.fiap.hospital.agendamento.domain.exception.AgendamentoNoPassadoException;
import br.com.fiap.hospital.agendamento.domain.exception.AlteracaoConcorrenteException;
import br.com.fiap.hospital.agendamento.domain.exception.ConflitoDeAgendaException;
import br.com.fiap.hospital.agendamento.domain.exception.MotivoDeCancelamentoObrigatorioException;
import br.com.fiap.hospital.agendamento.domain.exception.RecursoNaoEncontradoException;
import br.com.fiap.hospital.agendamento.domain.exception.TransicaoDeStatusInvalidaException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Traduz toda falha em ProblemDetail, no formato da secao 8 de docs/01-arquitetura.md.
 *
 * <p>Estende {@link ResponseEntityExceptionHandler} de proposito. A primeira versao deste
 * advice tratava so as excecoes de dominio e caia no {@code @ExceptionHandler(Exception)}
 * para tudo mais: JSON malformado, UUID invalido no path, enum errado em query param,
 * metodo nao suportado — todo erro de cliente virava 500, isto e, falha de servidor.
 *
 * <p>Adicionar dois ou tres tratadores nominais resolveria os casos ja vistos e deixaria
 * os proximos quebrados do mesmo jeito. Herdando de {@code ResponseEntityExceptionHandler}
 * a familia inteira passa a ser tratada, inclusive o que ainda nao apareceu, e o
 * {@link #handleExceptionInternal} garante que todas saiam no mesmo formato.
 *
 * <p>Cada tratador pede o status e o {@code type} ao {@link TipoDeErro}; nenhum escreve
 * um codigo HTTP literal.
 */
@RestControllerAdvice
public class TratadorGlobalDeErros extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(TratadorGlobalDeErros.class);

    private final Clock clock;

    public TratadorGlobalDeErros(Clock clock) {
        this.clock = clock;
    }

    // ---------------------------------------------------------------- dominio

    @ExceptionHandler(AgendamentoNoPassadoException.class)
    public ProblemDetail agendamentoNoPassado(
            AgendamentoNoPassadoException e, HttpServletRequest requisicao) {
        return problema(TipoDeErro.AGENDAMENTO_NO_PASSADO, e.getMessage(), requisicao);
    }

    @ExceptionHandler(ConflitoDeAgendaException.class)
    public ProblemDetail conflitoDeAgenda(
            ConflitoDeAgendaException e, HttpServletRequest requisicao) {
        return problema(TipoDeErro.CONFLITO_DE_AGENDA, e.getMessage(), requisicao);
    }

    /** Cobre consulta, paciente e medico de uma vez, por herdarem da mesma base. */
    @ExceptionHandler(RecursoNaoEncontradoException.class)
    public ProblemDetail recursoNaoEncontrado(
            RecursoNaoEncontradoException e, HttpServletRequest requisicao) {
        return problema(TipoDeErro.RECURSO_NAO_ENCONTRADO, e.getMessage(), requisicao);
    }

    @ExceptionHandler(TransicaoDeStatusInvalidaException.class)
    public ProblemDetail transicaoInvalida(
            TransicaoDeStatusInvalidaException e, HttpServletRequest requisicao) {
        return problema(TipoDeErro.TRANSICAO_DE_STATUS_INVALIDA, e.getMessage(), requisicao);
    }

    @ExceptionHandler(MotivoDeCancelamentoObrigatorioException.class)
    public ProblemDetail motivoObrigatorio(
            MotivoDeCancelamentoObrigatorioException e, HttpServletRequest requisicao) {
        return problema(TipoDeErro.MOTIVO_DE_CANCELAMENTO_OBRIGATORIO, e.getMessage(), requisicao);
    }

    /**
     * Mesmo 409 do conflito de agenda, {@code type} diferente: aqui recarregar e repetir
     * costuma resolver, la nao.
     */
    @ExceptionHandler(AlteracaoConcorrenteException.class)
    public ProblemDetail alteracaoConcorrente(
            AlteracaoConcorrenteException e, HttpServletRequest requisicao) {
        return problema(TipoDeErro.ALTERACAO_CONCORRENTE, e.getMessage(), requisicao);
    }

    /**
     * Formato recusado pelos value objects do dominio. Sem esta entrada cairia no
     * tratador generico e um erro de entrada seria respondido como falha de servidor.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail argumentoInvalido(
            IllegalArgumentException e, HttpServletRequest requisicao) {
        return problema(TipoDeErro.ARGUMENTO_INVALIDO, e.getMessage(), requisicao);
    }

    // ------------------------------------------------------------- Spring MVC

    /** Acrescenta a relacao de campos invalidos ao corpo que a superclasse produz. */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException e,
            HttpHeaders cabecalhos,
            HttpStatusCode status,
            WebRequest requisicao) {

        Map<String, String> campos = new LinkedHashMap<>();
        for (FieldError erro : e.getBindingResult().getFieldErrors()) {
            campos.merge(erro.getField(), mensagemDe(erro), (a, b) -> a + "; " + b);
        }

        ProblemDetail corpo = ProblemDetail.forStatus(TipoDeErro.VALIDACAO_DE_CAMPOS.status());
        corpo.setDetail("A requisicao contem " + campos.size() + " campo(s) invalido(s)");
        corpo.setProperty("campos", campos);

        return handleExceptionInternal(e, corpo, cabecalhos, status, requisicao);
    }

    /**
     * Ponto unico por onde passa toda resposta da familia do Spring MVC.
     *
     * <p>E o que faz uma excecao de MVC que este advice nunca viu sair como 4xx com
     * corpo Problem Detail completo, em vez de 500.
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception e,
            Object corpo,
            HttpHeaders cabecalhos,
            HttpStatusCode status,
            WebRequest requisicao) {

        TipoDeErro tipo = categoriaDe(e, status);
        ProblemDetail problema = corpo instanceof ProblemDetail existente
                ? existente
                : ProblemDetail.forStatus(status);

        problema.setType(tipo.type());
        problema.setTitle(tipo.titulo());
        if (problema.getDetail() == null || problema.getDetail().isBlank()) {
            problema.setDetail(detalheSeguro(e, status));
        }
        enriquecer(problema, servletDe(requisicao));

        return super.handleExceptionInternal(e, problema, cabecalhos, status, requisicao);
    }

    /**
     * Categoria por tipo de excecao, com queda para uma categoria generica derivada do
     * status.
     *
     * <p>A queda e o que cobre "o que ainda nao apareceu": uma excecao de MVC nova sai
     * como {@code requisicao-invalida} em 4xx, e nao como erro interno.
     */
    private static TipoDeErro categoriaDe(Exception e, HttpStatusCode status) {
        if (e instanceof MethodArgumentNotValidException
                || e instanceof HandlerMethodValidationException) {
            return TipoDeErro.VALIDACAO_DE_CAMPOS;
        }
        if (e instanceof HttpMessageNotReadableException) {
            return TipoDeErro.REQUISICAO_MALFORMADA;
        }
        if (e instanceof MethodArgumentTypeMismatchException
                || e instanceof org.springframework.beans.TypeMismatchException) {
            return TipoDeErro.PARAMETRO_INVALIDO;
        }
        if (e instanceof MissingServletRequestParameterException) {
            return TipoDeErro.PARAMETRO_AUSENTE;
        }
        if (e instanceof HttpRequestMethodNotSupportedException) {
            return TipoDeErro.METODO_NAO_SUPORTADO;
        }
        if (e instanceof HttpMediaTypeNotSupportedException) {
            return TipoDeErro.MIDIA_NAO_SUPORTADA;
        }
        if (e instanceof NoHandlerFoundException
                || e instanceof org.springframework.web.servlet.resource.NoResourceFoundException) {
            return TipoDeErro.ROTA_NAO_ENCONTRADA;
        }
        return status.is5xxServerError() ? TipoDeErro.ERRO_INTERNO : TipoDeErro.REQUISICAO_INVALIDA;
    }

    /**
     * Mensagem do Spring so vaza para o cliente em 4xx, onde ela e util e nao expoe
     * interno. Em 5xx a mensagem e generica.
     */
    private static String detalheSeguro(Exception e, HttpStatusCode status) {
        if (status.is5xxServerError()) {
            return "Erro interno ao processar a requisicao. Informe o correlationId ao suporte";
        }
        return e.getMessage() == null ? "Requisicao invalida" : e.getMessage();
    }

    // -------------------------------------------------------------- fallback

    /**
     * Rede de seguranca do que nao e nem dominio nem MVC. Registra a causa no log, com
     * stack trace, e devolve ao cliente uma resposta que nao expoe nada de interno.
     */
    @ExceptionHandler(Exception.class)
    public ProblemDetail falhaInesperada(Exception e, HttpServletRequest requisicao) {
        String correlationId = CorrelationIdFilter.de(requisicao);
        log.error("Falha nao tratada [correlationId={}]", correlationId, e);

        return problema(
                TipoDeErro.ERRO_INTERNO,
                "Erro interno ao processar a requisicao. Informe o correlationId ao suporte",
                requisicao);
    }

    // ---------------------------------------------------------------- comuns

    private static String mensagemDe(FieldError erro) {
        return erro.getDefaultMessage() == null ? "valor invalido" : erro.getDefaultMessage();
    }

    private static HttpServletRequest servletDe(WebRequest requisicao) {
        return requisicao instanceof ServletWebRequest servlet ? servlet.getRequest() : null;
    }

    private ProblemDetail problema(TipoDeErro tipo, String detalhe, HttpServletRequest requisicao) {
        ProblemDetail problema = ProblemDetail.forStatus(tipo.status());
        problema.setType(tipo.type());
        problema.setTitle(tipo.titulo());
        problema.setDetail(detalhe);
        enriquecer(problema, requisicao);
        return problema;
    }

    private void enriquecer(ProblemDetail problema, HttpServletRequest requisicao) {
        if (requisicao != null) {
            problema.setInstance(URI.create(requisicao.getRequestURI()));
        }
        problema.setProperty("correlationId", CorrelationIdFilter.de(requisicao));
        problema.setProperty("timestamp", OffsetDateTime.now(clock).toString());
    }
}
