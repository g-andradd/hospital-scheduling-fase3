package br.com.fiap.hospital.agendamento.infrastructure.web;

import br.com.fiap.hospital.agendamento.domain.exception.AgendamentoNoPassadoException;
import br.com.fiap.hospital.agendamento.domain.exception.AlteracaoConcorrenteException;
import br.com.fiap.hospital.agendamento.domain.exception.ConflitoDeAgendaException;
import br.com.fiap.hospital.agendamento.domain.exception.MotivoDeCancelamentoObrigatorioException;
import br.com.fiap.hospital.agendamento.domain.exception.RecursoNaoEncontradoException;
import br.com.fiap.hospital.agendamento.domain.exception.TransicaoDeStatusInvalidaException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ProblemDetail;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Traduz toda falha em ProblemDetail, no formato da secao 8 de docs/01-arquitetura.md.
 *
 * <p>Cada tratador pede o status e o {@code type} ao {@link TipoDeErro}; nenhum deles
 * escreve um codigo HTTP literal. Isso e o que permite ao teste varrer o enum e ao mapa
 * ter uma fonte unica.
 */
@RestControllerAdvice
public class TratadorGlobalDeErros {

    private static final Logger log = LoggerFactory.getLogger(TratadorGlobalDeErros.class);

    private final Clock clock;

    public TratadorGlobalDeErros(Clock clock) {
        this.clock = clock;
    }

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

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail camposInvalidos(
            MethodArgumentNotValidException e, HttpServletRequest requisicao) {
        Map<String, String> campos = new LinkedHashMap<>();
        for (FieldError erro : e.getBindingResult().getFieldErrors()) {
            campos.merge(erro.getField(), mensagemDe(erro), (a, b) -> a + "; " + b);
        }

        ProblemDetail problema = problema(
                TipoDeErro.VALIDACAO_DE_CAMPOS,
                "A requisicao contem " + campos.size() + " campo(s) invalido(s)",
                requisicao);
        problema.setProperty("campos", campos);
        return problema;
    }

    /**
     * Rede de seguranca. Registra a causa no log, com stack trace, e devolve ao cliente
     * uma resposta que nao expoe nada de interno.
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

    private static String mensagemDe(FieldError erro) {
        return erro.getDefaultMessage() == null ? "valor invalido" : erro.getDefaultMessage();
    }

    private ProblemDetail problema(TipoDeErro tipo, String detalhe, HttpServletRequest requisicao) {
        ProblemDetail problema = ProblemDetail.forStatus(tipo.status());
        problema.setType(tipo.type());
        problema.setTitle(tipo.titulo());
        problema.setDetail(detalhe);
        problema.setInstance(java.net.URI.create(requisicao.getRequestURI()));
        problema.setProperty("correlationId", CorrelationIdFilter.de(requisicao));
        problema.setProperty("timestamp", OffsetDateTime.now(clock).toString());
        return problema;
    }
}
