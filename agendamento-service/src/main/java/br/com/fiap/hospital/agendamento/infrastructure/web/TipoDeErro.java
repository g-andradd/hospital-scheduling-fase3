package br.com.fiap.hospital.agendamento.infrastructure.web;

import java.net.URI;
import org.springframework.http.HttpStatus;

/**
 * Categorias de erro da API, com o {@code type}, o {@code title} e o status de cada uma.
 *
 * <p>Fonte unica do mapa de erros de docs/01-arquitetura.md secao 8. O tratador nunca
 * escreve um status nem monta uma URI: pede a constante. Erro novo e constante nova, e
 * o compilador guia o resto.
 *
 * <p>Repare que {@link #CONFLITO_DE_AGENDA} e {@link #ALTERACAO_CONCORRENTE} sao ambos
 * 409 com {@code type} diferente. O status nao distingue o que o cliente deve fazer: no
 * primeiro caso repetir da o mesmo resultado e ele precisa escolher outro horario; no
 * segundo, recarregar e repetir provavelmente funciona. O {@code type} da RFC 7807 e o
 * identificador estavel da categoria, e e onde o cliente programa essa reacao.
 */
public enum TipoDeErro {

    AGENDAMENTO_NO_PASSADO(
            "agendamento-no-passado", "Agendamento no passado", HttpStatus.UNPROCESSABLE_ENTITY),

    AGENDAMENTO_FORA_DO_HORIZONTE(
            "agendamento-fora-do-horizonte", "Agendamento fora do horizonte",
            HttpStatus.UNPROCESSABLE_ENTITY),

    CONFLITO_DE_AGENDA(
            "conflito-de-agenda", "Conflito de agenda", HttpStatus.CONFLICT),

    RECURSO_NAO_ENCONTRADO(
            "recurso-nao-encontrado", "Recurso nao encontrado", HttpStatus.NOT_FOUND),

    TRANSICAO_DE_STATUS_INVALIDA(
            "transicao-de-status-invalida", "Transicao de status invalida", HttpStatus.CONFLICT),

    MOTIVO_DE_CANCELAMENTO_OBRIGATORIO(
            "motivo-de-cancelamento-obrigatorio", "Motivo de cancelamento obrigatorio",
            HttpStatus.UNPROCESSABLE_ENTITY),

    ALTERACAO_CONCORRENTE(
            "alteracao-concorrente", "Alteracao concorrente", HttpStatus.CONFLICT),

    ARGUMENTO_INVALIDO(
            "argumento-invalido", "Argumento invalido", HttpStatus.BAD_REQUEST),

    VALIDACAO_DE_CAMPOS(
            "validacao-de-campos", "Campos invalidos", HttpStatus.BAD_REQUEST),

    REQUISICAO_MALFORMADA(
            "requisicao-malformada", "Requisicao malformada", HttpStatus.BAD_REQUEST),

    PARAMETRO_INVALIDO(
            "parametro-invalido", "Parametro invalido", HttpStatus.BAD_REQUEST),

    PARAMETRO_AUSENTE(
            "parametro-ausente", "Parametro obrigatorio ausente", HttpStatus.BAD_REQUEST),

    METODO_NAO_SUPORTADO(
            "metodo-nao-suportado", "Metodo nao suportado", HttpStatus.METHOD_NOT_ALLOWED),

    MIDIA_NAO_SUPORTADA(
            "midia-nao-suportada", "Tipo de midia nao suportado",
            HttpStatus.UNSUPPORTED_MEDIA_TYPE),

    ROTA_NAO_ENCONTRADA(
            "rota-nao-encontrada", "Rota nao encontrada", HttpStatus.NOT_FOUND),

    /**
     * Rede de seguranca para as demais falhas de requisicao do Spring MVC.
     *
     * <p>Existe para que uma excecao de MVC ainda nao mapeada nominalmente continue
     * saindo como 4xx com corpo Problem Detail, em vez de cair no tratador generico e
     * virar 500. Erro do cliente respondido como falha de servidor e o defeito que esta
     * categoria elimina.
     */
    DATA_FORA_DO_INTERVALO(
            "data-fora-do-intervalo", "Data ou hora fora do intervalo suportado",
            HttpStatus.BAD_REQUEST),

    REQUISICAO_INVALIDA(
            "requisicao-invalida", "Requisicao invalida", HttpStatus.BAD_REQUEST),

    ERRO_INTERNO(
            "erro-interno", "Erro interno", HttpStatus.INTERNAL_SERVER_ERROR);

    private static final String BASE = "https://hospital.fiap.br/erros/";

    private final String sufixo;
    private final String titulo;
    private final HttpStatus status;

    TipoDeErro(String sufixo, String titulo, HttpStatus status) {
        this.sufixo = sufixo;
        this.titulo = titulo;
        this.status = status;
    }

    public URI type() {
        return URI.create(BASE + sufixo);
    }

    public String titulo() {
        return titulo;
    }

    public HttpStatus status() {
        return status;
    }
}
