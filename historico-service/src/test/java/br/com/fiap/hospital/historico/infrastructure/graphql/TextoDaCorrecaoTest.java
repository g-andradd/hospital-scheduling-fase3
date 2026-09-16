package br.com.fiap.hospital.historico.infrastructure.graphql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A politica de texto da correcao recusa o que o armazenamento rejeita, e so isso.
 *
 * <p>O limite conta caracteres como o PostgreSQL conta, por code point. Contar unidades
 * UTF-16 recusaria 128 emojis numa coluna de 255 caracteres que os aceita.
 */
@DisplayName("Texto da correcao")
class TextoDaCorrecaoTest {

    private static final String EMOJI = new String(Character.toChars(0x1F600));
    private static final String NUL = String.valueOf((char) 0);

    @Test
    @DisplayName("255 code points são aceitos, em ASCII e em emoji")
    void duzentosECinquentaECincoCodePointsSaoAceitos() {
        assertThat(TextoDaCorrecao.nome("x".repeat(255), "pacienteNome")).hasSize(255);
        String emojis = EMOJI.repeat(255);
        assertThat(emojis).as("cada emoji ocupa duas unidades UTF-16").hasSize(510);
        assertThat(TextoDaCorrecao.nome(emojis, "pacienteNome")).isEqualTo(emojis);
    }

    @Test
    @DisplayName("256 code points são recusados, em ASCII e em emoji")
    void duzentosECinquentaESeisCodePointsSaoRecusados() {
        assertThatThrownBy(() -> TextoDaCorrecao.nome("x".repeat(256), "medicoNome"))
                .isInstanceOf(ExcecoesDoHistorico.CorrecaoInvalida.class)
                .hasMessageContaining("medicoNome");
        assertThatThrownBy(() -> TextoDaCorrecao.nome(EMOJI.repeat(256), "especialidade"))
                .isInstanceOf(ExcecoesDoHistorico.CorrecaoInvalida.class)
                .hasMessageContaining("especialidade");
    }

    @Test
    @DisplayName("o NUL é recusado em qualquer posição, sem ecoar o caractere")
    void nulERecusadoEmQualquerPosicao() {
        for (String valor : new String[] {NUL, NUL + "a", "a" + NUL + "b", "a" + NUL}) {
            assertThatThrownBy(() -> TextoDaCorrecao.representavel(valor, "observacoes"))
                    .isInstanceOf(ExcecoesDoHistorico.CorrecaoInvalida.class)
                    .satisfies(e -> assertThat(e.getMessage()).doesNotContain(NUL));
        }
    }

    @Test
    @DisplayName("controles persistíveis, acento, tabulação e quebra de linha são aceitos")
    void textoPersistivelEAceito() {
        for (char controle : new char[] {(char) 0x01, (char) 0x1F, '\t', '\n', '\r'}) {
            String valor = "an" + controle + "tes";
            assertThat(TextoDaCorrecao.representavel(valor, "justificativa")).isEqualTo(valor);
        }
        assertThat(TextoDaCorrecao.nome("Mário Antônio", "pacienteNome")).isEqualTo("Mário Antônio");
    }

    @Test
    @DisplayName("nulo preserva a semântica de campo ausente")
    void nuloPassaIntacto() {
        assertThat(TextoDaCorrecao.representavel(null, "observacoes")).isNull();
        assertThat(TextoDaCorrecao.nome(null, "pacienteNome")).isNull();
    }
}
