package br.com.fiap.hospital.agendamento.domain;

import br.com.fiap.hospital.agendamento.domain.exception.TextoComCaractereInvalidoException;

/**
 * Politica dos textos livres que o servico grava, consulta ou publica.
 *
 * <p>Vale para as observacoes e o motivo de cancelamento, que viajam no evento de dominio, e
 * para a credencial do login, que vira parametro de consulta ao banco.
 *
 * <p><b>O que e recusado, e por que.</b> A caracterizacao contra o PostgreSQL real mostrou
 * um unico caractere incompativel com os tres destinos: o NUL (U+0000). A coluna
 * {@code text} o recusa com {@code invalid byte sequence for encoding "UTF8": 0x00}; o
 * {@code jsonb} do outbox o recusa com {@code unsupported Unicode escape sequence}; e como
 * parametro de consulta ele derruba o comando antes de haver resultado. Os demais caracteres
 * de controle — U+0001 e U+001F, por exemplo — atravessam coluna, outbox e evento sem perda,
 * e por isso nao sao recusados so por serem controles.
 *
 * <p><b>Normalizacao.</b> A semantica de espacos e a de sempre: texto opcional nulo ou em
 * branco vira nulo, e o restante e aparado com {@code trim()}. O que muda e uma guarda: como
 * {@code trim()} remove tudo abaixo de U+0021, e nao so espacos, um texto formado apenas por
 * controles nao e "branco" para {@code isBlank()} mas fica vazio depois de aparado. Esse
 * vazio passa a ser tratado como branco aqui, na normalizacao — e nao descoberto adiante,
 * quando o contrato de eventos recusasse um texto obrigatorio vazio.
 *
 * <p>A regra e reproduzida aqui em vez de importada do contrato de proposito: o dominio de
 * agendamento nao depende de {@code shared-contracts}.
 */
public final class TextoRepresentavel {

    private static final int NUL = 0;

    private TextoRepresentavel() {}

    /** Nulo e representavel: ausencia de texto nao e texto invalido. */
    public static boolean representavel(String texto) {
        return texto == null || texto.indexOf(NUL) < 0;
    }

    /**
     * Texto opcional: nulo, em branco ou vazio depois de aparado vira nulo; o restante e
     * aparado.
     *
     * @throws TextoComCaractereInvalidoException se houver NUL, em qualquer posicao
     */
    public static String normalizarOpcional(String texto, String campo) {
        exigirRepresentavel(texto, campo);
        if (texto == null || texto.isBlank()) {
            return null;
        }
        String aparado = texto.trim();
        return aparado.isEmpty() ? null : aparado;
    }

    /**
     * @throws TextoComCaractereInvalidoException se houver NUL, em qualquer posicao
     */
    public static void exigirRepresentavel(String texto, String campo) {
        if (!representavel(texto)) {
            throw new TextoComCaractereInvalidoException(campo);
        }
    }
}
