package br.com.fiap.hospital.agendamento.integracao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.fiap.hospital.agendamento.domain.PerfilUsuario;
import br.com.fiap.hospital.agendamento.infrastructure.persistence.entity.MedicoEntity;
import br.com.fiap.hospital.agendamento.infrastructure.persistence.entity.PacienteEntity;
import br.com.fiap.hospital.agendamento.infrastructure.persistence.entity.UsuarioEntity;
import br.com.fiap.hospital.agendamento.infrastructure.persistence.repository.ConsultaJpaRepository;
import br.com.fiap.hospital.agendamento.infrastructure.persistence.repository.MedicoJpaRepository;
import br.com.fiap.hospital.agendamento.infrastructure.persistence.repository.PacienteJpaRepository;
import br.com.fiap.hospital.agendamento.infrastructure.persistence.repository.UsuarioJpaRepository;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Unicidade das chaves naturais, garantida pelo banco.
 *
 * <p>A regra vive em constraint, e nao em verificacao da aplicacao, porque so o banco
 * consegue recusar duas insercoes concorrentes do mesmo valor.
 */
@SpringBootTest
@DisplayName("Unicidade das chaves naturais")
class ChavesNaturaisIT {

    @DynamicPropertySource
    static void propriedades(DynamicPropertyRegistry registro) {
        ContainerPostgres.registrarPropriedades(registro);
    }

    @Autowired private ConsultaJpaRepository consultaJpa;
    @Autowired private PacienteJpaRepository pacienteJpa;
    @Autowired private MedicoJpaRepository medicoJpa;
    @Autowired private UsuarioJpaRepository usuarioJpa;

    @BeforeEach
    void limpar() {
        consultaJpa.deleteAll();
        pacienteJpa.deleteAll();
        medicoJpa.deleteAll();
        usuarioJpa.deleteAll();
    }

    private UsuarioEntity novoUsuario(PerfilUsuario perfil, String email) {
        return new UsuarioEntity(UUID.randomUUID(), "Fulano", email, "$2a$10$hash",
                perfil, true, OffsetDateTime.now());
    }

    @Test
    @DisplayName("Scenario: E-mail duplicado e recusado")
    void emailDuplicadoERecusado() {
        usuarioJpa.saveAndFlush(novoUsuario(PerfilUsuario.ENFERMEIRO, "repetido@hospital.com"));

        assertThatThrownBy(() -> usuarioJpa.saveAndFlush(
                        novoUsuario(PerfilUsuario.ENFERMEIRO, "repetido@hospital.com")))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(usuarioJpa.count()).as("o registro original permanece").isEqualTo(1);
    }

    @Test
    @DisplayName("Scenario: CPF duplicado e recusado")
    void cpfDuplicadoERecusado() {
        UsuarioEntity primeiro =
                usuarioJpa.saveAndFlush(novoUsuario(PerfilUsuario.PACIENTE, "p1@hospital.com"));
        UsuarioEntity segundo =
                usuarioJpa.saveAndFlush(novoUsuario(PerfilUsuario.PACIENTE, "p2@hospital.com"));

        pacienteJpa.saveAndFlush(new PacienteEntity(UUID.randomUUID(), primeiro,
                "52998224725", LocalDate.of(1990, 5, 12), "+5561999990000"));

        assertThatThrownBy(() -> pacienteJpa.saveAndFlush(new PacienteEntity(
                        UUID.randomUUID(), segundo, "52998224725",
                        LocalDate.of(1985, 3, 1), "+5561988880000")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("Scenario: CRM duplicado e recusado")
    void crmDuplicadoERecusado() {
        UsuarioEntity primeiro =
                usuarioJpa.saveAndFlush(novoUsuario(PerfilUsuario.MEDICO, "m1@hospital.com"));
        UsuarioEntity segundo =
                usuarioJpa.saveAndFlush(novoUsuario(PerfilUsuario.MEDICO, "m2@hospital.com"));

        medicoJpa.saveAndFlush(new MedicoEntity(
                UUID.randomUUID(), primeiro, "DF-12345", "Cardiologia"));

        assertThatThrownBy(() -> medicoJpa.saveAndFlush(new MedicoEntity(
                        UUID.randomUUID(), segundo, "DF-12345", "Ortopedia")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
