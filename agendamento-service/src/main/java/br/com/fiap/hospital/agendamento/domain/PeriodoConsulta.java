package br.com.fiap.hospital.agendamento.domain;

import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * Intervalo de tempo ocupado por uma consulta.
 *
 * <p>A convencao e <b>{@code [inicio, fim)}</b>: o inicio pertence ao intervalo, o fim
 * nao. E o que faz uma consulta que comeca exatamente quando outra termina <b>nao</b>
 * ser conflito — a borda que o M02 vai ter de reproduzir na query SQL de sobreposicao.
 *
 * <p>Usa {@link OffsetDateTime} para representar o instante sem ambiguidade.
 * O banco preserva o instante, mas nao o offset original. O adaptador de eventos
 * representa esse instante na zona America/Sao_Paulo antes de gravar o envelope.
 */
public record PeriodoConsulta(OffsetDateTime inicio, int duracaoMinutos) {

    public PeriodoConsulta {
        Objects.requireNonNull(inicio, "O inicio do periodo e obrigatorio");
        if (duracaoMinutos <= 0) {
            throw new IllegalArgumentException(
                    "A duracao da consulta deve ser positiva: " + duracaoMinutos);
        }
    }

    /** Fim exclusivo do intervalo. */
    public OffsetDateTime fim() {
        return inicio.plusMinutes(duracaoMinutos);
    }

    /**
     * Responde se este periodo se sobrepoe a {@code outro}.
     *
     * <p>Periodos adjacentes — o fim de um coincidindo com o inicio do outro — nao se
     * sobrepoem.
     */
    public boolean sobrepoe(PeriodoConsulta outro) {
        Objects.requireNonNull(outro, "O periodo comparado e obrigatorio");
        return inicio.isBefore(outro.fim()) && outro.inicio().isBefore(fim());
    }

    /** Responde se o periodo comeca depois do instante de referencia informado. */
    public boolean comecaDepoisDe(OffsetDateTime referencia) {
        Objects.requireNonNull(referencia, "O instante de referencia e obrigatorio");
        return inicio.isAfter(referencia);
    }
}
