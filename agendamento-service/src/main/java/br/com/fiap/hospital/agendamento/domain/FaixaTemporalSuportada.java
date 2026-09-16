package br.com.fiap.hospital.agendamento.domain;

import java.time.OffsetDateTime;

/**
 * Faixa de instantes que o servico consegue representar de ponta a ponta.
 *
 * <p>{@link OffsetDateTime} vai do ano -999999999 ao 999999999. O armazenamento nao vai:
 * um limite de intervalo nesses extremos chega ao SQL e o banco recusa com
 * {@code timestamp out of range} — uma violacao de integridade, isto e, 500, provocada
 * por um parametro de query.
 *
 * <p>A faixa fica definida <b>aqui e em nenhum outro lugar</b>. Repetir os extremos em
 * controller, adaptador ou teste criaria varias definicoes da mesma regra, e elas
 * divergiriam: foi assim que o teto do horizonte de agendamento precisou existir.
 *
 * <p>Os limites sao o ano 1 e o fim do ano 9999, com precisao de microssegundo — a faixa
 * que o ISO-8601 de quatro digitos, o driver JDBC, o {@code timestamptz} e o contrato de
 * eventos representam sem perda. Nenhuma data de agenda hospitalar chega perto disso; o
 * horizonte de {@link Consulta#HORIZONTE_MAXIMO_MESES} e quem decide o que e agendavel.
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

    /**
     * @throws IllegalArgumentException se o instante existir e estiver fora da faixa
     */
    public static void exigirSuportado(OffsetDateTime instante, String campo) {
        if (!suporta(instante)) {
            throw new IllegalArgumentException(
                    "O valor de '" + campo + "' esta fora do intervalo de datas suportado, "
                            + "que vai de " + MINIMO + " a " + MAXIMO);
        }
    }
}
