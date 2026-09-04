package br.com.fiap.hospital.agendamento.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("Email")
class EmailTest {

    @Test
    @DisplayName("e-mail valido e aceito e normalizado para minusculas")
    void emailValidoENormalizado() {
        assertThat(new Email("  Paciente@Hospital.COM ").valor()).isEqualTo("paciente@hospital.com");
    }

    @ParameterizedTest(name = "\"{0}\"")
    @ValueSource(strings = {
            "paciente.hospital.com",   // sem arroba
            "paciente@",               // sem dominio
            "@hospital.com",           // sem parte local
            "paciente@hospital",       // dominio sem ponto
            "paciente@hospital.c",     // tld de uma letra
            "pac iente@hospital.com"   // espaco no meio
    })
    @DisplayName("Scenario: E-mail invalido e recusado")
    void emailInvalidoERecusado(String invalido) {
        assertThatThrownBy(() -> new Email(invalido))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("E-mail invalido");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    @DisplayName("Scenario: E-mail invalido e recusado — vazio")
    void emailAusenteERecusado(String ausente) {
        assertThatThrownBy(() -> new Email(ausente))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("obrigatorio");
    }
}
