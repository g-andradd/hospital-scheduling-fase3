package br.com.fiap.hospital.agendamento.integracao;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.fiap.hospital.agendamento.domain.PerfilUsuario;
import br.com.fiap.hospital.agendamento.domain.StatusConsulta;
import br.com.fiap.hospital.agendamento.infrastructure.persistence.entity.ConsultaEntity;
import br.com.fiap.hospital.agendamento.infrastructure.persistence.entity.MedicoEntity;
import br.com.fiap.hospital.agendamento.infrastructure.persistence.entity.PacienteEntity;
import br.com.fiap.hospital.agendamento.infrastructure.persistence.entity.UsuarioEntity;
import br.com.fiap.hospital.agendamento.infrastructure.persistence.repository.ConsultaJpaRepository;
import br.com.fiap.hospital.agendamento.infrastructure.persistence.repository.MedicoJpaRepository;
import br.com.fiap.hospital.agendamento.infrastructure.persistence.repository.PacienteJpaRepository;
import br.com.fiap.hospital.agendamento.infrastructure.persistence.repository.UsuarioJpaRepository;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

/**
 * Confere que a deteccao de conflito usa indice em vez de varrer a tabela.
 *
 * <p>Um indice declarado na migration nao prova nada sozinho: o planejador pode
 * ignora-lo. Este teste le o plano de execucao real.
 */
@SpringBootTest
@Transactional
@DisplayName("Plano de execucao da query de conflito")
class IndiceDeConflitoIT {

    @DynamicPropertySource
    static void propriedades(DynamicPropertyRegistry registro) {
        ContainerPostgres.registrarPropriedades(registro);
    }

    @Autowired private EntityManager em;
    @Autowired private ConsultaJpaRepository consultaJpa;
    @Autowired private PacienteJpaRepository pacienteJpa;
    @Autowired private MedicoJpaRepository medicoJpa;
    @Autowired private UsuarioJpaRepository usuarioJpa;

    @Test
    @DisplayName("Scenario: Busca de conflito e delimitada na origem — o indice de medico e usado")
    void queryDeConflitoUsaIndice() {
        UUID medicoId = prepararAgendaVolumosa();

        // Sem isto o planejador prefere varredura sequencial em tabela pequena, o que
        // esconderia a ausencia do indice.
        em.createNativeQuery("SET enable_seqscan = OFF").executeUpdate();
        em.createNativeQuery("ANALYZE consulta").executeUpdate();

        @SuppressWarnings("unchecked")
        List<String> plano = em.createNativeQuery("""
                EXPLAIN
                SELECT * FROM consulta c
                 WHERE c.medico_id = :medicoId
                   AND c.status IN ('AGENDADA', 'CONFIRMADA')
                   AND c.data_hora < :fim
                   AND c.data_hora + (c.duracao_minutos * INTERVAL '1 minute') > :inicio
                """)
                .setParameter("medicoId", medicoId)
                .setParameter("inicio", OffsetDateTime.now().plusDays(5))
                .setParameter("fim", OffsetDateTime.now().plusDays(5).plusMinutes(30))
                .getResultList();

        String planoCompleto = String.join("\n", plano);

        assertThat(planoCompleto)
                .as("o plano precisa alcancar as linhas pelo indice de (medico_id, data_hora):%n%s",
                        planoCompleto)
                .contains("ix_consulta_medico_data_hora");
    }

    @Test
    @DisplayName("os dois indices de agenda existem apos a migration")
    void indicesDeclaradosExistem() {
        @SuppressWarnings("unchecked")
        List<String> indices = em.createNativeQuery(
                        "SELECT indexname FROM pg_indexes WHERE tablename = 'consulta'")
                .getResultList();

        assertThat(indices)
                .contains("ix_consulta_medico_data_hora", "ix_consulta_paciente_data_hora");
    }

    private UUID prepararAgendaVolumosa() {
        UsuarioEntity usuarioMedico = usuarioJpa.save(new UsuarioEntity(
                UUID.randomUUID(), "Dr. Joao Lima", "medico.indice@hospital.com",
                "$2a$10$hash", PerfilUsuario.MEDICO, true, OffsetDateTime.now()));
        MedicoEntity medico = medicoJpa.save(new MedicoEntity(
                UUID.randomUUID(), usuarioMedico, "DF-99999", "Cardiologia"));

        UsuarioEntity usuarioPaciente = usuarioJpa.save(new UsuarioEntity(
                UUID.randomUUID(), "Maria Souza", "paciente.indice@hospital.com",
                "$2a$10$hash", PerfilUsuario.PACIENTE, true, OffsetDateTime.now()));
        PacienteEntity paciente = pacienteJpa.save(new PacienteEntity(
                UUID.randomUUID(), usuarioPaciente, "39053344705",
                LocalDate.of(1990, 5, 12), "+5561999990000"));

        OffsetDateTime base = OffsetDateTime.now().plusDays(1);
        for (int i = 0; i < 200; i++) {
            ConsultaEntity consulta = new ConsultaEntity(
                    UUID.randomUUID(), paciente.getId(), medico.getId(), usuarioMedico.getId());
            consulta.copiarDe(medico.getId(), base.plusHours(i), 30, StatusConsulta.AGENDADA,
                    null, null, OffsetDateTime.now(), OffsetDateTime.now());
            consultaJpa.save(consulta);
        }
        consultaJpa.flush();
        return medico.getId();
    }
}
