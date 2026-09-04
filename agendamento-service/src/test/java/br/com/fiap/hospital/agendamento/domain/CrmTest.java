package br.com.fiap.hospital.agendamento.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("Crm")
class CrmTest {

    @Test
    @DisplayName("CRM valido e aceito, normalizado e decomposto")
    void crmValidoEAceito() {
        Crm crm = new Crm(" df-12345 ");

        assertThat(crm.valor()).isEqualTo("DF-12345");
        assertThat(crm.unidadeFederativa()).isEqualTo("DF");
        assertThat(crm.numero()).isEqualTo("12345");
    }

    @ParameterizedTest(name = "\"{0}\"")
    @ValueSource(strings = {
            "12345",        // sem unidade federativa
            "DF12345",      // sem separador
            "DF-",          // sem numero
            "D-12345",      // uma letra so
            "DFF-12345",    // tres letras
            "DF-1234567",   // numero longo demais
            "DF-12A45"      // numero com letra
    })
    @DisplayName("Scenario: CRM invalido e recusado — formato")
    void crmComFormatoInvalidoERecusado(String invalido) {
        assertThatThrownBy(() -> new Crm(invalido))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CRM invalido");
    }

    @ParameterizedTest(name = "\"{0}\"")
    @ValueSource(strings = {"XX-12345", "ZZ-999"})
    @DisplayName("Scenario: CRM invalido e recusado — unidade federativa inexistente")
    void crmComUnidadeFederativaInexistenteERecusado(String invalido) {
        assertThatThrownBy(() -> new Crm(invalido))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unidade federativa inexistente");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    @DisplayName("CRM ausente e recusado")
    void crmAusenteERecusado(String ausente) {
        assertThatThrownBy(() -> new Crm(ausente))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("obrigatorio");
    }
}
