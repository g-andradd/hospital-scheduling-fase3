package br.com.fiap.hospital.agendamento.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("Cpf")
class CpfTest {

    private static final String VALIDO_SEM_PONTUACAO = "52998224725";
    private static final String VALIDO_COM_PONTUACAO = "529.982.247-25";

    @Test
    @DisplayName("Scenario: CPF valido e aceito e normalizado — informado com pontuacao")
    void cpfValidoComPontuacaoENormalizado() {
        Cpf cpf = new Cpf(VALIDO_COM_PONTUACAO);

        assertThat(cpf.valor()).isEqualTo(VALIDO_SEM_PONTUACAO);
        assertThat(cpf.valor()).containsOnlyDigits();
    }

    @Test
    @DisplayName("CPF valido sem pontuacao e aceito e os dois formatos sao iguais")
    void cpfValidoSemPontuacaoEAceito() {
        assertThat(new Cpf(VALIDO_SEM_PONTUACAO)).isEqualTo(new Cpf(VALIDO_COM_PONTUACAO));
    }

    @Test
    @DisplayName("expoe a forma mascarada para exibicao")
    void expoeFormaMascarada() {
        assertThat(new Cpf(VALIDO_SEM_PONTUACAO).formatado()).isEqualTo(VALIDO_COM_PONTUACAO);
    }

    @ParameterizedTest(name = "\"{0}\"")
    @ValueSource(strings = {
            "52998224724",      // ultimo digito verificador errado
            "52998224715",      // primeiro digito verificador errado
            "12345678901",      // digitos verificadores nao conferem
            "11111111111",      // todos os digitos iguais
            "00000000000"
    })
    @DisplayName("Scenario: CPF invalido e recusado — digitos verificadores nao conferem")
    void cpfComDigitoVerificadorErradoERecusado(String invalido) {
        assertThatThrownBy(() -> new Cpf(invalido))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CPF invalido");
    }

    @ParameterizedTest(name = "\"{0}\"")
    @ValueSource(strings = {"529982247", "5299822472555", "1"})
    @DisplayName("Scenario: CPF invalido e recusado — quantidade de digitos diferente de onze")
    void cpfComQuantidadeErradaDeDigitosERecusado(String invalido) {
        assertThatThrownBy(() -> new Cpf(invalido))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("11 digitos");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    @DisplayName("CPF ausente e recusado")
    void cpfAusenteERecusado(String ausente) {
        assertThatThrownBy(() -> new Cpf(ausente))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("obrigatorio");
    }
}
