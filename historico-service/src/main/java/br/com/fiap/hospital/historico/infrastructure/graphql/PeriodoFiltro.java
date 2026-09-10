package br.com.fiap.hospital.historico.infrastructure.graphql;

/**
 * Recorte temporal relativo ao instante atual.
 *
 * <p>O corte e semiaberto e sem sobreposicao: um registro exatamente no instante atual
 * pertence a {@link #FUTURAS}. Sem essa definicao explicita, ele cairia nos dois lados ou
 * em nenhum, dependendo de qual comparacao fosse escrita primeiro.
 */
public enum PeriodoFiltro {
    TODAS,
    FUTURAS,
    PASSADAS
}
