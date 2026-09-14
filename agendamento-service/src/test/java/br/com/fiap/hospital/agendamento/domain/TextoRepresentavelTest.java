package br.com.fiap.hospital.agendamento.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.fiap.hospital.agendamento.domain.exception.TextoComCaractereInvalidoException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A politica de texto do agendamento recusa o NUL, e so o NUL, e normaliza como sempre.
 *
 * <p>A normalizacao de espacos e a preexistente: nulo ou em branco vira nulo, o resto e
 * aparado. O que estes casos fixam e a borda que o {@code trim()} esconde — ele remove
 * controles, e nao so espacos, entao texto so de controles precisa virar nulo aqui, e nao
 * vazio a caminho do evento.
 */
@DisplayName("Texto representavel")
class TextoRepresentavelTest {

    private static final String NUL = String.valueOf((char) 0);
    private static final char U0001 = (char) 0x01;
    private static final char U001F = (char) 0x1F;

    @Test
    @DisplayName("NUL é recusado nas bordas e no meio, sem ecoar o caractere")
    void nulERecusadoEmQualquerPosicao() {
        for (String valor : new String[] {NUL, NUL + "texto", "te" + NUL + "xto", "texto" + NUL}) {
            assertThat(TextoRepresentavel.representavel(valor)).isFalse();
            assertThatThrownBy(() -> TextoRepresentavel.normalizarOpcional(valor, "observacoes"))
                    .isInstanceOf(TextoComCaractereInvalidoException.class)
                    .satisfies(e -> assertThat(e.getMessage()).contains("observacoes").doesNotContain(NUL));
        }
    }

    @Test
    @DisplayName("controles não-NUL no meio são representáveis e preservados")
    void controlesNaoNulNoMeioSaoPreservados() {
        for (char controle : new char[] {U0001, U001F, '\t', '\n', '\r'}) {
            String valor = "an" + controle + "tes";
            assertThat(TextoRepresentavel.representavel(valor)).isTrue();
            assertThat(TextoRepresentavel.normalizarOpcional(valor, "observacoes")).isEqualTo(valor);
        }
        assertThat(TextoRepresentavel.normalizarOpcional("Ação clínica", "observacoes"))
                .isEqualTo("Ação clínica");
    }

    @Test
    @DisplayName("texto só de controles fica vazio depois de aparado e vira nulo, como branco")
    void textoSoDeControlesViraNulo() {
        String soControles = "" + U0001 + U001F;
        assertThat(soControles.isBlank()).as("nao e branco para isBlank").isFalse();
        assertThat(soControles.trim()).as("mas o trim o esvazia").isEmpty();

        assertThat(TextoRepresentavel.normalizarOpcional(soControles, "observacoes")).isNull();
    }

    @Test
    @DisplayName("a semântica de espaços é preservada: nulo, branco e bordas")
    void semanticaDeEspacosPreservada() {
        assertThat(TextoRepresentavel.normalizarOpcional(null, "observacoes")).isNull();
        assertThat(TextoRepresentavel.normalizarOpcional("", "observacoes")).isNull();
        assertThat(TextoRepresentavel.normalizarOpcional("   \t\n", "observacoes")).isNull();
        String espacoEm = String.valueOf((char) 0x2003);
        assertThat(TextoRepresentavel.normalizarOpcional(espacoEm, "observacoes"))
                .as("branco Unicode continua virando nulo, como antes").isNull();
        assertThat(TextoRepresentavel.normalizarOpcional("  texto  ", "observacoes")).isEqualTo("texto");
        assertThat(TextoRepresentavel.normalizarOpcional(U0001 + "texto" + U001F, "observacoes"))
                .as("controles nas bordas sao aparados como espacos").isEqualTo("texto");
    }
}
