package br.com.fiap.hospital.agendamento.domain;

import static br.com.fiap.hospital.agendamento.Cenario.medico;
import static br.com.fiap.hospital.agendamento.Cenario.paciente;
import static br.com.fiap.hospital.agendamento.Cenario.usuario;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("Usuario, Paciente e Medico")
class AtoresDoDominioTest {

    @Nested
    @DisplayName("Usuario")
    class UsuarioTest {

        @Test
        @DisplayName("construcao com valores validos funciona e normaliza o nome")
        void construcaoValida() {
            Usuario usuario = new Usuario(
                    UUID.randomUUID(), "  Ana Enfermeira  ", new Email("ana@hospital.com"),
                    "$2a$10$hash", PerfilUsuario.ENFERMEIRO, true);

            assertThat(usuario.nome()).isEqualTo("Ana Enfermeira");
            assertThat(usuario.temPerfil(PerfilUsuario.ENFERMEIRO)).isTrue();
            assertThat(usuario.temPerfil(PerfilUsuario.MEDICO)).isFalse();
        }

        @Test
        @DisplayName("RNF-01: o toString nao expoe o hash da senha")
        void toStringNaoExpoeSenha() {
            Usuario usuario = usuario(PerfilUsuario.MEDICO, "Dr. Joao", "joao@hospital.com");

            assertThat(usuario.toString())
                    .doesNotContain("$2a$10$hash")
                    .doesNotContainIgnoringCase("senha")
                    .contains("Dr. Joao");
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   "})
        @DisplayName("nome ausente e recusado")
        void nomeAusenteERecusado(String nome) {
            assertThatThrownBy(() -> new Usuario(
                            UUID.randomUUID(), nome, new Email("a@b.com"),
                            "hash", PerfilUsuario.PACIENTE, true))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("nome");
        }

        @Test
        @DisplayName("id, e-mail e perfil ausentes sao recusados")
        void camposObrigatoriosAusentesSaoRecusados() {
            assertThatThrownBy(() -> new Usuario(
                            null, "Ana", new Email("a@b.com"), "h", PerfilUsuario.PACIENTE, true))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new Usuario(
                            UUID.randomUUID(), "Ana", null, "h", PerfilUsuario.PACIENTE, true))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new Usuario(
                            UUID.randomUUID(), "Ana", new Email("a@b.com"), "h", null, true))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("Paciente")
    class PacienteTest {

        @Test
        @DisplayName("construcao com valores validos funciona e delega nome e e-mail")
        void construcaoValida() {
            Paciente paciente = paciente();

            assertThat(paciente.nome()).isEqualTo("Maria Souza");
            assertThat(paciente.email().valor()).isEqualTo("paciente@hospital.com");
            assertThat(paciente.cpf().valor()).isEqualTo("52998224725");
        }

        @Test
        @DisplayName("usuario com perfil diferente de PACIENTE e recusado")
        void usuarioComPerfilErradoERecusado() {
            assertThatThrownBy(() -> new Paciente(
                            UUID.randomUUID(),
                            usuario(PerfilUsuario.MEDICO, "Dr. Joao", "joao@hospital.com"),
                            new Cpf("529.982.247-25"),
                            LocalDate.of(1990, 1, 1),
                            "+5561999990000"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("perfil PACIENTE");
        }

        @Test
        @DisplayName("CPF e data de nascimento ausentes sao recusados")
        void camposObrigatoriosAusentesSaoRecusados() {
            Usuario u = usuario(PerfilUsuario.PACIENTE, "Maria", "maria@hospital.com");

            assertThatThrownBy(() -> new Paciente(
                            UUID.randomUUID(), u, null, LocalDate.of(1990, 1, 1), "+55"))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new Paciente(
                            UUID.randomUUID(), u, new Cpf("529.982.247-25"), null, "+55"))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("Medico")
    class MedicoTest {

        @Test
        @DisplayName("construcao com valores validos funciona e normaliza a especialidade")
        void construcaoValida() {
            Medico medico = medico();

            assertThat(medico.nome()).isEqualTo("Dr. Joao Lima");
            assertThat(medico.crm().valor()).isEqualTo("DF-12345");
            assertThat(medico.especialidade()).isEqualTo("Cardiologia");
        }

        @Test
        @DisplayName("usuario com perfil diferente de MEDICO e recusado")
        void usuarioComPerfilErradoERecusado() {
            assertThatThrownBy(() -> new Medico(
                            UUID.randomUUID(),
                            usuario(PerfilUsuario.ENFERMEIRO, "Ana", "ana@hospital.com"),
                            new Crm("DF-12345"),
                            "Cardiologia"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("perfil MEDICO");
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   "})
        @DisplayName("especialidade ausente e recusada")
        void especialidadeAusenteERecusada(String especialidade) {
            assertThatThrownBy(() -> new Medico(
                            UUID.randomUUID(),
                            usuario(PerfilUsuario.MEDICO, "Dr. Joao", "joao@hospital.com"),
                            new Crm("DF-12345"),
                            especialidade))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("especialidade");
        }
    }
}
