package br.com.fiap.hospital.agendamento.integracao;

import br.com.fiap.hospital.agendamento.contrato.ConsultaRepositoryContractTest;
import br.com.fiap.hospital.agendamento.domain.PerfilUsuario;
import br.com.fiap.hospital.agendamento.domain.port.ConsultaRepositoryPort;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * O mesmo contrato do fake, executado contra o adaptador real e um Postgres de verdade.
 *
 * <p>Se o SQL de sobreposicao divergir do {@code PeriodoConsulta.sobrepoe} — um
 * {@code <=} onde deveria haver {@code <}, por exemplo — a divergencia aparece aqui, e
 * nao meses depois em producao.
 */
@SpringBootTest
@DisplayName("Contrato de ConsultaRepositoryPort — adaptador contra Postgres")
class ConsultaRepositoryAdapterIT extends ConsultaRepositoryContractTest {

    @DynamicPropertySource
    static void propriedades(DynamicPropertyRegistry registro) {
        ContainerPostgres.registrarPropriedades(registro);
    }

    @Autowired private ConsultaRepositoryPort adaptador;
    @Autowired private ConsultaJpaRepository consultaJpa;
    @Autowired private PacienteJpaRepository pacienteJpa;
    @Autowired private MedicoJpaRepository medicoJpa;
    @Autowired private UsuarioJpaRepository usuarioJpa;

    private UUID paciente;
    private UUID medico;
    private UUID outroPaciente;
    private UUID outroMedico;
    private UUID registrante;

    @BeforeEach
    void prepararBase() {
        consultaJpa.deleteAll();
        pacienteJpa.deleteAll();
        medicoJpa.deleteAll();
        usuarioJpa.deleteAll();

        paciente = gravarPaciente("Maria Souza", "paciente@hospital.com", "52998224725").getId();
        outroPaciente = gravarPaciente("Jose Silva", "jose@hospital.com", "11144477735").getId();
        medico = gravarMedico("Dr. Joao Lima", "medico@hospital.com", "DF-12345").getId();
        outroMedico = gravarMedico("Dra. Ana Reis", "ana@hospital.com", "SP-54321").getId();
        registrante = gravarUsuario(
                PerfilUsuario.ENFERMEIRO, "Ana Enfermeira", "enfermeiro@hospital.com").getId();
    }

    private UsuarioEntity gravarUsuario(PerfilUsuario perfil, String nome, String email) {
        return usuarioJpa.save(new UsuarioEntity(
                UUID.randomUUID(), nome, email, "$2a$10$hash", perfil, true, OffsetDateTime.now()));
    }

    private PacienteEntity gravarPaciente(String nome, String email, String cpf) {
        return pacienteJpa.save(new PacienteEntity(
                UUID.randomUUID(),
                gravarUsuario(PerfilUsuario.PACIENTE, nome, email),
                cpf,
                LocalDate.of(1990, 5, 12),
                "+5561999990000"));
    }

    private MedicoEntity gravarMedico(String nome, String email, String crm) {
        return medicoJpa.save(new MedicoEntity(
                UUID.randomUUID(),
                gravarUsuario(PerfilUsuario.MEDICO, nome, email),
                crm,
                "Cardiologia"));
    }

    @Override
    protected ConsultaRepositoryPort repositorio() {
        return adaptador;
    }

    @Override
    protected UUID pacienteId() {
        return paciente;
    }

    @Override
    protected UUID medicoId() {
        return medico;
    }

    @Override
    protected UUID outroPacienteId() {
        return outroPaciente;
    }

    @Override
    protected UUID outroMedicoId() {
        return outroMedico;
    }

    @Override
    protected UUID registradoPorId() {
        return registrante;
    }

    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired org.springframework.transaction.support.TransactionTemplate transacao;

    @org.junit.jupiter.api.Test
    @DisplayName("Leitura com outro deslocamento preserva o instante")
    
    // Scenario: Leitura com outro deslocamento preserva o instante
    void fusoDeSessaoNaoAlteraInstante() {
        var data=OffsetDateTime.parse("2018-01-15T14:00:00+05:00");
        var id=UUID.randomUUID();
        transacao.executeWithoutResult(s-> {
            var c=br.com.fiap.hospital.agendamento.domain.Consulta.reconstituir(id,paciente,medico,registrante,
                new br.com.fiap.hospital.agendamento.domain.PeriodoConsulta(data,30),
                br.com.fiap.hospital.agendamento.domain.StatusConsulta.REALIZADA,null,null,data,data);
            adaptador.salvar(c);
        });
        transacao.executeWithoutResult(s->{
            jdbc.execute("SET LOCAL TIME ZONE 'Asia/Tokyo'");
            assert org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive();
            org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("SELECT data_hora::text FROM consulta WHERE id=?",String.class,id)).endsWith("+09");
            org.assertj.core.api.Assertions.assertThat(adaptador.buscarPorId(id).orElseThrow().periodo().inicio().toInstant()).isEqualTo(data.toInstant());
        });
    }
}
