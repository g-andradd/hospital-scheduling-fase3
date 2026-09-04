package br.com.fiap.hospital.agendamento.integracao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.fiap.hospital.agendamento.application.AtualizarConsultaCommand;
import br.com.fiap.hospital.agendamento.domain.PerfilUsuario;
import br.com.fiap.hospital.agendamento.domain.StatusConsulta;
import br.com.fiap.hospital.agendamento.domain.exception.AlteracaoConcorrenteException;
import br.com.fiap.hospital.agendamento.domain.exception.ConflitoDeAgendaException;
import br.com.fiap.hospital.agendamento.domain.port.ConsultaRepositoryPort;
import br.com.fiap.hospital.agendamento.infrastructure.persistence.entity.ConsultaEntity;
import br.com.fiap.hospital.agendamento.infrastructure.persistence.entity.MedicoEntity;
import br.com.fiap.hospital.agendamento.infrastructure.persistence.entity.PacienteEntity;
import br.com.fiap.hospital.agendamento.infrastructure.persistence.entity.UsuarioEntity;
import br.com.fiap.hospital.agendamento.infrastructure.persistence.repository.ConsultaJpaRepository;
import br.com.fiap.hospital.agendamento.infrastructure.persistence.repository.MedicoJpaRepository;
import br.com.fiap.hospital.agendamento.infrastructure.persistence.repository.PacienteJpaRepository;
import br.com.fiap.hospital.agendamento.infrastructure.persistence.repository.UsuarioJpaRepository;
import br.com.fiap.hospital.agendamento.infrastructure.transacao.AtualizarConsultaUseCaseTransacional;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Comportamento transacional da alteracao contra um Postgres real.
 *
 * <p>E aqui que a armadilha do flush e exercida de verdade: os testes de caso de uso
 * usam fake, que nao tem persistence context, entao nao poderiam pegar uma entidade
 * gerenciada sendo escrita no commit.
 */
@SpringBootTest
@DisplayName("Alteracao de consulta sob transacao real")
class TransacaoDeAlteracaoIT {

    @DynamicPropertySource
    static void propriedades(DynamicPropertyRegistry registro) {
        ContainerPostgres.registrarPropriedades(registro);
    }

    @Autowired private AtualizarConsultaUseCaseTransacional atualizar;
    @Autowired private ConsultaRepositoryPort consultas;
    @Autowired private ConsultaJpaRepository consultaJpa;
    @Autowired private PacienteJpaRepository pacienteJpa;
    @Autowired private MedicoJpaRepository medicoJpa;
    @Autowired private UsuarioJpaRepository usuarioJpa;
    @Autowired private TransactionTemplate transacao;

    private UUID pacienteId;
    private UUID medicoId;
    private UUID outroPacienteId;
    private UUID consultaId;
    private OffsetDateTime inicioOriginal;

    @BeforeEach
    void preparar() {
        consultaJpa.deleteAll();
        pacienteJpa.deleteAll();
        medicoJpa.deleteAll();
        usuarioJpa.deleteAll();

        pacienteId = gravarPaciente("Maria Souza", "paciente@hospital.com", "52998224725").getId();
        outroPacienteId = gravarPaciente("Jose Silva", "jose@hospital.com", "11144477735").getId();
        medicoId = gravarMedico("Dr. Joao Lima", "medico@hospital.com", "DF-12345").getId();
        UUID registrante = gravarUsuario(
                PerfilUsuario.ENFERMEIRO, "Ana Enfermeira", "enfermeiro@hospital.com").getId();

        inicioOriginal = OffsetDateTime.now().plusDays(2).withNano(0);
        ConsultaEntity entidade =
                new ConsultaEntity(UUID.randomUUID(), pacienteId, medicoId, registrante);
        entidade.copiarDe(medicoId, inicioOriginal, 30, StatusConsulta.AGENDADA,
                "observacao clinica", null, OffsetDateTime.now(), OffsetDateTime.now());
        consultaId = consultaJpa.saveAndFlush(entidade).getId();

        // Ocupa a agenda do medico no horario de destino, para forcar a recusa.
        ConsultaEntity conflitante =
                new ConsultaEntity(UUID.randomUUID(), outroPacienteId, medicoId, registrante);
        conflitante.copiarDe(medicoId, inicioOriginal.plusDays(1), 30, StatusConsulta.AGENDADA,
                null, null, OffsetDateTime.now(), OffsetDateTime.now());
        consultaJpa.saveAndFlush(conflitante);
    }

    @Test
    @DisplayName("Scenario: Operacao recusada nao deixa registro — sob transacao com entidade gerenciada")
    void alteracaoRecusadaNaoEPersistida() {
        assertThatThrownBy(() -> atualizar.executar(new AtualizarConsultaCommand(
                        consultaId, inicioOriginal.plusDays(1), null, null, "nao deveria persistir")))
                .isInstanceOf(ConflitoDeAgendaException.class);

        ConsultaEntity relida = consultaJpa.findById(consultaId).orElseThrow();
        assertThat(relida.getDataHora().toInstant())
                .as("o horario recusado nao pode ter sido escrito no flush do commit")
                .isEqualTo(inicioOriginal.toInstant());
        assertThat(relida.getObservacoes()).isEqualTo("observacao clinica");
        assertThat(relida.getVersao()).as("nenhuma escrita, nenhuma versao nova").isZero();
    }

    @Test
    @DisplayName("Scenario: Segunda alteracao concorrente e recusada")
    void alteracaoConcorrenteERecusada() throws Exception {
        // Transacao A le a consulta e segura o contexto de persistencia aberto.
        // No meio dela, outra transacao altera a mesma linha e sobe a versao.
        //
        // A excecao propaga para fora do template de proposito: captura-la aqui dentro
        // marcaria a transacao como rollback-only e o commit estouraria com
        // UnexpectedRollbackException, escondendo a causa real.
        assertThatThrownBy(() -> transacao.executeWithoutResult(status -> {
            var carregada = consultas.buscarPorId(consultaId).orElseThrow();

            alterarEmOutraTransacao();

            carregada.atualizar(null, null, "alteracao da transacao A", OffsetDateTime.now());
            consultas.salvar(carregada);
        }))
                .as("a alteracao que partiu de um estado ja superado precisa ser recusada")
                .isInstanceOf(AlteracaoConcorrenteException.class);

        ConsultaEntity relida = consultaJpa.findById(consultaId).orElseThrow();
        assertThat(relida.getObservacoes())
                .as("o estado final e o da alteracao confirmada")
                .isEqualTo("alteracao concorrente vencedora");
    }

    @Test
    @DisplayName("Scenario: Alteracoes sequenciais nao sao afetadas")
    void alteracoesSequenciaisSaoAceitas() {
        atualizar.executar(new AtualizarConsultaCommand(consultaId, null, null, null, "primeira"));
        atualizar.executar(new AtualizarConsultaCommand(consultaId, null, null, null, "segunda"));

        ConsultaEntity relida = consultaJpa.findById(consultaId).orElseThrow();
        assertThat(relida.getObservacoes()).isEqualTo("segunda");
        assertThat(relida.getVersao()).isEqualTo(2);
    }

    /** Roda numa transacao propria, em outra thread, para nao herdar o contexto atual. */
    private void alterarEmOutraTransacao() {
        try (var executor = Executors.newSingleThreadExecutor()) {
            Future<?> tarefa = executor.submit(() -> transacao.executeWithoutResult(s -> {
                ConsultaEntity entidade = consultaJpa.findById(consultaId).orElseThrow();
                entidade.copiarDe(entidade.getMedicoId(), entidade.getDataHora(),
                        entidade.getDuracaoMinutos(), entidade.getStatus(),
                        "alteracao concorrente vencedora", null,
                        entidade.getCriadoEm(), OffsetDateTime.now());
                consultaJpa.saveAndFlush(entidade);
            }));
            tarefa.get();
        } catch (Exception e) {
            throw new IllegalStateException("falha ao alterar em outra transacao", e);
        }
    }

    private UsuarioEntity gravarUsuario(PerfilUsuario perfil, String nome, String email) {
        return usuarioJpa.save(new UsuarioEntity(
                UUID.randomUUID(), nome, email, "$2a$10$hash", perfil, true, OffsetDateTime.now()));
    }

    private PacienteEntity gravarPaciente(String nome, String email, String cpf) {
        return pacienteJpa.save(new PacienteEntity(
                UUID.randomUUID(), gravarUsuario(PerfilUsuario.PACIENTE, nome, email),
                cpf, LocalDate.of(1990, 5, 12), "+5561999990000"));
    }

    private MedicoEntity gravarMedico(String nome, String email, String crm) {
        return medicoJpa.save(new MedicoEntity(
                UUID.randomUUID(), gravarUsuario(PerfilUsuario.MEDICO, nome, email),
                crm, "Cardiologia"));
    }
}
