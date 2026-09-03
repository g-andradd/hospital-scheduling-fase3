package br.com.fiap.hospital.agendamento.application;

import static br.com.fiap.hospital.agendamento.Cenario.AGORA;
import static br.com.fiap.hospital.agendamento.Cenario.consultaExistente;
import static br.com.fiap.hospital.agendamento.Cenario.daquiA;
import static br.com.fiap.hospital.agendamento.Cenario.enfermeiroId;
import static br.com.fiap.hospital.agendamento.Cenario.haQuanto;
import static br.com.fiap.hospital.agendamento.Cenario.medico;
import static br.com.fiap.hospital.agendamento.Cenario.paciente;
import static br.com.fiap.hospital.agendamento.Cenario.relogioFixo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.fiap.hospital.agendamento.domain.Consulta;
import br.com.fiap.hospital.agendamento.domain.Medico;
import br.com.fiap.hospital.agendamento.domain.Paciente;
import br.com.fiap.hospital.agendamento.domain.StatusConsulta;
import br.com.fiap.hospital.agendamento.domain.TipoEventoConsulta;
import br.com.fiap.hospital.agendamento.domain.exception.AgendamentoNoPassadoException;
import br.com.fiap.hospital.agendamento.domain.exception.ConflitoDeAgendaException;
import br.com.fiap.hospital.agendamento.domain.exception.MedicoNaoEncontradoException;
import br.com.fiap.hospital.agendamento.domain.exception.PacienteNaoEncontradoException;
import br.com.fiap.hospital.agendamento.fake.ConsultaRepositoryFake;
import br.com.fiap.hospital.agendamento.fake.EventPublisherFake;
import br.com.fiap.hospital.agendamento.fake.UsuarioRepositoryFake;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("AgendarConsultaUseCase — Requirement: Registro de consulta")
class AgendarConsultaUseCaseTest {

    private ConsultaRepositoryFake consultas;
    private UsuarioRepositoryFake usuarios;
    private EventPublisherFake eventos;
    private AgendarConsultaUseCase useCase;

    private Paciente maria;
    private Medico joao;

    @BeforeEach
    void preparar() {
        maria = paciente();
        joao = medico();
        consultas = new ConsultaRepositoryFake();
        usuarios = new UsuarioRepositoryFake().com(maria).com(joao);
        eventos = new EventPublisherFake();
        useCase = new AgendarConsultaUseCase(consultas, usuarios, eventos, relogioFixo());
    }

    private AgendarConsultaCommand comandoPara(OffsetDateTime inicio) {
        return new AgendarConsultaCommand(
                maria.id(), joao.id(), enfermeiroId(), inicio, null, "Retorno de rotina");
    }

    @Test
    @DisplayName("Scenario: Consulta registrada com sucesso")
    void consultaRegistradaComSucesso() {
        ConsultaResumo resumo = useCase.executar(comandoPara(daquiA(24)));

        assertThat(resumo.status()).isEqualTo(StatusConsulta.AGENDADA);
        assertThat(resumo.dataHora()).isEqualTo(daquiA(24));
        assertThat(resumo.duracaoMinutos()).isEqualTo(Consulta.DURACAO_PADRAO_MINUTOS);
        assertThat(resumo.pacienteId()).isEqualTo(maria.id());
        assertThat(resumo.medicoId()).isEqualTo(joao.id());
        assertThat(consultas.quantidade()).isEqualTo(1);
    }

    @Test
    @DisplayName("Scenario: Registro publica evento — exatamente um, do tipo CRIADA")
    void registroPublicaEvento() {
        ConsultaResumo resumo = useCase.executar(comandoPara(daquiA(24)));

        assertThat(eventos.quantidade()).isEqualTo(1);
        assertThat(eventos.publicados().getFirst().tipo()).isEqualTo(TipoEventoConsulta.CRIADA);
        assertThat(eventos.publicados().getFirst().consultaId()).isEqualTo(resumo.id());
    }

    @Test
    @DisplayName("Scenario: Registro em instante passado e recusado — nada persistido, nada publicado")
    void registroNoPassadoERecusado() {
        assertThatThrownBy(() -> useCase.executar(comandoPara(haQuanto(1))))
                .isInstanceOf(AgendamentoNoPassadoException.class);

        assertThat(consultas.quantidade()).isZero();
        assertThat(eventos.nadaPublicado()).isTrue();
    }

    @Test
    @DisplayName("Scenario: Registro no instante corrente e recusado")
    void registroNoInstanteCorrenteERecusado() {
        assertThatThrownBy(() -> useCase.executar(comandoPara(AGORA)))
                .isInstanceOf(AgendamentoNoPassadoException.class);
    }

    @Test
    @DisplayName("Scenario: Conflito com a agenda do medico e recusado")
    void conflitoComAgendaDoMedicoERecusado() {
        Paciente outroPaciente = paciente("Jose Silva", "jose@hospital.com", "111.444.777-35");
        consultas.com(consultaExistente(outroPaciente, joao, daquiA(24), StatusConsulta.AGENDADA));

        assertThatThrownBy(() -> useCase.executar(comandoPara(daquiA(24))))
                .isInstanceOf(ConflitoDeAgendaException.class)
                .hasMessageContaining("medico");

        assertThat(consultas.quantidade()).isEqualTo(1);
        assertThat(eventos.nadaPublicado()).isTrue();
    }

    @Test
    @DisplayName("Scenario: Conflito com a agenda do paciente e recusado")
    void conflitoComAgendaDoPacienteERecusado() {
        Medico outroMedico = medico("Dra. Ana Reis", "ana.reis@hospital.com", "SP-54321");
        usuarios.com(outroMedico);
        consultas.com(consultaExistente(maria, outroMedico, daquiA(24), StatusConsulta.AGENDADA));

        assertThatThrownBy(() -> useCase.executar(comandoPara(daquiA(24))))
                .isInstanceOf(ConflitoDeAgendaException.class)
                .hasMessageContaining("paciente");
    }

    @Test
    @DisplayName("Scenario: Periodos adjacentes nao sao conflito")
    void periodosAdjacentesNaoSaoConflito() {
        Paciente outroPaciente = paciente("Jose Silva", "jose@hospital.com", "111.444.777-35");
        consultas.com(consultaExistente(outroPaciente, joao, daquiA(24), StatusConsulta.AGENDADA));

        // A existente ocupa [24h, 24h30). Esta comeca exatamente as 24h30.
        ConsultaResumo resumo = useCase.executar(comandoPara(daquiA(24).plusMinutes(30)));

        assertThat(resumo.status()).isEqualTo(StatusConsulta.AGENDADA);
        assertThat(consultas.quantidade()).isEqualTo(2);
    }

    @Test
    @DisplayName("Scenario: Consulta encerrada nao bloqueia a agenda — cancelada")
    void consultaCanceladaNaoBloqueiaAgenda() {
        consultas.com(consultaExistente(maria, joao, daquiA(24), StatusConsulta.CANCELADA));

        assertThat(useCase.executar(comandoPara(daquiA(24))).status())
                .isEqualTo(StatusConsulta.AGENDADA);
    }

    @Test
    @DisplayName("Scenario: Consulta encerrada nao bloqueia a agenda — realizada")
    void consultaRealizadaNaoBloqueiaAgenda() {
        consultas.com(consultaExistente(maria, joao, daquiA(24), StatusConsulta.REALIZADA));

        assertThat(useCase.executar(comandoPara(daquiA(24))).status())
                .isEqualTo(StatusConsulta.AGENDADA);
    }

    @Test
    @DisplayName("Scenario: Registro para paciente inexistente e recusado")
    void pacienteInexistenteERecusado() {
        UUID desconhecido = UUID.randomUUID();
        AgendarConsultaCommand comando = new AgendarConsultaCommand(
                desconhecido, joao.id(), enfermeiroId(), daquiA(24), null, null);

        assertThatThrownBy(() -> useCase.executar(comando))
                .isInstanceOf(PacienteNaoEncontradoException.class)
                .hasMessageContaining("Paciente nao encontrado");

        assertThat(consultas.quantidade()).isZero();
        assertThat(eventos.nadaPublicado()).isTrue();
    }

    @Test
    @DisplayName("medico inexistente e recusado com a mensagem do proprio recurso")
    void medicoInexistenteERecusado() {
        AgendarConsultaCommand comando = new AgendarConsultaCommand(
                maria.id(), UUID.randomUUID(), enfermeiroId(), daquiA(24), null, null);

        assertThatThrownBy(() -> useCase.executar(comando))
                .isInstanceOf(MedicoNaoEncontradoException.class)
                .hasMessageContaining("Medico nao encontrado");
    }

    @Test
    @DisplayName("duracao informada substitui o padrao de 30 minutos")
    void duracaoInformadaSubstituiOPadrao() {
        AgendarConsultaCommand comando = new AgendarConsultaCommand(
                maria.id(), joao.id(), enfermeiroId(), daquiA(24), 60, null);

        assertThat(useCase.executar(comando).duracaoMinutos()).isEqualTo(60);
    }
}
