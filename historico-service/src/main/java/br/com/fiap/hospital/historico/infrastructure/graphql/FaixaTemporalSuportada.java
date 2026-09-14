package br.com.fiap.hospital.historico.infrastructure.graphql;

import java.time.OffsetDateTime;

/**
 * Faixa de instantes que o historico consegue representar de ponta a ponta.
 *
 * <p>{@link OffsetDateTime} vai do ano -999999999 ao 999999999. O armazenamento nao vai: um
 * limite de filtro ou uma data corrigida nesses extremos chega ao SQL e o banco recusa com
 * {@code timestamp out of range} — uma violacao de integridade, isto e, erro interno,
 * provocada por um valor que o cliente digitou.
 *
 * <p>A faixa fica definida <b>aqui e em nenhum outro lugar</b>. Repetir os extremos no
 * resolver, no filtro e na correcao criaria varias definicoes da mesma regra, e elas
 * divergiriam.
 *
 * <p>Os limites sao o ano 1 e o fim do ano 9999, com precisao de microssegundo — a faixa que
 * o ISO-8601 de quatro digitos, o driver JDBC, o {@code timestamptz} e o contrato de eventos
 * representam sem perda.
 */
public final class FaixaTemporalSuportada {

    public static final OffsetDateTime MINIMO = OffsetDateTime.parse("0001-01-01T00:00:00Z");

    public static final OffsetDateTime MAXIMO =
            OffsetDateTime.parse("9999-12-31T23:59:59.999999Z");

    private FaixaTemporalSuportada() {}

    /** Nulo e suportado: significa "sem limite", e nao um instante. */
    public static boolean suporta(OffsetDateTime instante) {
        return instante == null || (!instante.isBefore(MINIMO) && !instante.isAfter(MAXIMO));
    }

    public static String mensagemDeRecusa() {
        return "DateTime fora do intervalo suportado, que vai de " + MINIMO + " a " + MAXIMO + ".";
    }
}
