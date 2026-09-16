package br.com.fiap.hospital.historico.infrastructure.graphql;

/**
 * Politica dos textos que a correcao manual grava.
 *
 * <p>Os cinco textos da correcao vao para o snapshot, em colunas de texto, e para o payload
 * {@code jsonb} da trilha. A caracterizacao contra o PostgreSQL real separou o que e
 * incompativel do que so parece estranho: o NUL (U+0000) e recusado pelos dois destinos —
 * {@code invalid byte sequence for encoding "UTF8": 0x00} na coluna e
 * {@code unsupported Unicode escape sequence} no {@code jsonb} —, enquanto os demais
 * caracteres de controle, como U+0001 e U+001F, atravessam correcao, snapshot e auditoria
 * sem perda. Recusar todo caractere de controle proibiria dado persistivel sem motivo; por
 * isso a politica recusa exatamente o NUL.
 *
 * <p>O limite de tamanho <b>nao e inventado aqui</b>: ele vem da migracao que criou as
 * colunas. {@code paciente_nome}, {@code medico_nome} e {@code especialidade} sao
 * {@code VARCHAR(255)} na V1, e o PostgreSQL conta <b>caracteres</b>, nao unidades UTF-16:
 * um emoji ocupa duas unidades em Java e um caractere na coluna. A contagem, portanto, e
 * por code point. {@code justificativa} e {@code observacoes} sao texto livre e nao ganham
 * teto.
 */
public final class TextoDaCorrecao {

    /** V1 do historico: {@code VARCHAR(255)} nas tres colunas de nome, em caracteres. */
    public static final int LIMITE_DOS_NOMES = 255;

    private static final int NUL = 0;

    private TextoDaCorrecao() {}

    /**
     * @throws ExcecoesDoHistorico.CorrecaoInvalida traduzida para {@code BAD_REQUEST}
     */
    public static String representavel(String texto, String campo) {
        if (texto != null && texto.indexOf(NUL) >= 0) {
            // A mensagem nao repete o valor: devolver o byte recusado seria usar o erro
            // como canal para ele.
            throw new ExcecoesDoHistorico.CorrecaoInvalida(
                    "O campo '" + campo + "' contem caractere nulo, que nao pode ser gravado.");
        }
        return texto;
    }

    /** Texto representavel e dentro do tamanho, em caracteres, da coluna que o recebe. */
    public static String nome(String texto, String campo) {
        String validado = representavel(texto, campo);
        if (validado != null
                && validado.codePointCount(0, validado.length()) > LIMITE_DOS_NOMES) {
            throw new ExcecoesDoHistorico.CorrecaoInvalida(
                    "O campo '" + campo + "' excede " + LIMITE_DOS_NOMES + " caracteres.");
        }
        return validado;
    }
}
