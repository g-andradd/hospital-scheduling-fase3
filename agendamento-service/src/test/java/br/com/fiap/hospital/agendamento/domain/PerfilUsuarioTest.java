package br.com.fiap.hospital.agendamento.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("PerfilUsuario")
class PerfilUsuarioTest {

    @Test
    @DisplayName("existem exatamente os tres perfis do enunciado")
    void existemOsTresPerfis() {
        assertThat(PerfilUsuario.values())
                .containsExactly(
                        PerfilUsuario.MEDICO,
                        PerfilUsuario.ENFERMEIRO,
                        PerfilUsuario.PACIENTE);
    }
}
