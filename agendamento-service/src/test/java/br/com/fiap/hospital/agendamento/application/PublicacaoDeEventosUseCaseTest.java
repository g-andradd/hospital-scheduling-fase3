package br.com.fiap.hospital.agendamento.application;

import static br.com.fiap.hospital.agendamento.Cenario.consultaExistente;
import static br.com.fiap.hospital.agendamento.Cenario.daquiA;
import static br.com.fiap.hospital.agendamento.Cenario.enfermeiro;
import static br.com.fiap.hospital.agendamento.Cenario.haQuanto;
import static br.com.fiap.hospital.agendamento.Cenario.medico;
import static br.com.fiap.hospital.agendamento.Cenario.paciente;
import static br.com.fiap.hospital.agendamento.Cenario.relogioFixo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import br.com.fiap.hospital.agendamento.domain.Consulta;
import br.com.fiap.hospital.agendamento.domain.Medico;
import br.com.fiap.hospital.agendamento.domain.Paciente;
import br.com.fiap.hospital.agendamento.domain.Usuario;
import br.com.fiap.hospital.agendamento.domain.StatusConsulta;
import br.com.fiap.hospital.agendamento.domain.TipoEventoConsulta;
import br.com.fiap.hospital.agendamento.fake.ConsultaRepositoryFake;
import br.com.fiap.hospital.agendamento.fake.EventPublisherFake;
import br.com.fiap.hospital.agendamento.fake.UsuarioRepositoryFake;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Requirement: Publicacao de evento a cada mudanca de estado")
class PublicacaoDeEventosUseCaseTest {

    private ConsultaRepositoryFake consultas;
    private UsuarioRepositoryFake usuarios;
    private EventPublisherFake eventos;

    private Paciente maria;
    private Medico joao;
    private Usuario ana;

    @BeforeEach
    void preparar() {
        maria = paciente();
        joao = medico();
        ana = enfermeiro();
        consultas = new ConsultaRepositoryFake();
        usuarios = new UsuarioRepositoryFake().com(maria).com(joao).com(ana);
        eventos = new EventPublisherFake();
    }

    private AgendarConsultaUseCase agendar() {
        return new AgendarConsultaUseCase(consultas, usuarios, eventos, relogioFixo());
    }

    @Test
    @DisplayName("Scenario: Registro publica evento")
    void registroPublicaEvento() {
        ConsultaResumo resumo = agendar().executar(new AgendarConsultaCommand(
                maria.id(), joao.id(), ana.id(), daquiA(24), null, null));

        assertThat(eventos.quantidade()).isEqualTo(1);
        assertThat(eventos.publicados().getFirst().tipo()).isEqualTo(TipoEventoConsulta.CRIADA);
        assertThat(eventos.publicados().getFirst().consultaId()).isEqualTo(resumo.id());
    }

    @Test
    @DisplayName("Scenario: Cada mudanca de estado publica o evento correspondente")
    void cadaMudancaPublicaOEventoCorrespondente() {
        ConsultaResumo criada = agendar().executar(new AgendarConsultaCommand(
                maria.id(), joao.id(), ana.id(), daquiA(24), null, null));

        new AtualizarConsultaUseCase(consultas, usuarios, eventos, relogioFixo())
                .executar(new AtualizarConsultaCommand(criada.id(), daquiA(48), null, null, null));
        new ConfirmarConsultaUseCase(consultas, eventos, relogioFixo()).executar(criada.id());
        new CancelarConsultaUseCase(consultas, eventos, relogioFixo())
                .executar(new CancelarConsultaCommand(criada.id(), "paciente desistiu"));

        assertThat(eventos.tipos()).containsExactly(
                TipoEventoConsulta.CRIADA,
                TipoEventoConsulta.ATUALIZADA,
                TipoEventoConsulta.CONFIRMADA,
                TipoEventoConsulta.CANCELADA);
        assertThat(eventos.publicados())
                .allSatisfy(e -> assertThat(e.consultaId()).isEqualTo(criada.id()));
    }

    @Test
    @DisplayName("Scenario: Operacao recusada nao publica evento")
    void operacaoRecusadaNaoPublicaEvento() {
        Consulta terminada =
                consultaExistente(maria, joao, daquiA(24), StatusConsulta.CANCELADA);
        consultas.com(terminada);

        // Uma recusa de cada familia de regra de negocio.
        assertThat(catchThrowable(() -> agendar().executar(new AgendarConsultaCommand(
                        maria.id(), joao.id(), ana.id(), haQuanto(1), null, null))))
                .as("agendamento no passado")
                .isNotNull();

        assertThat(catchThrowable(() -> agendar().executar(new AgendarConsultaCommand(
                        UUID.randomUUID(), joao.id(), ana.id(), daquiA(24), null, null))))
                .as("paciente inexistente")
                .isNotNull();

        assertThat(catchThrowable(() ->
                        new ConfirmarConsultaUseCase(consultas, eventos, relogioFixo())
                                .executar(terminada.id())))
                .as("transicao invalida")
                .isNotNull();

        assertThat(catchThrowable(() ->
                        new CancelarConsultaUseCase(consultas, eventos, relogioFixo())
                                .executar(new CancelarConsultaCommand(terminada.id(), null))))
                .as("cancelamento sem motivo")
                .isNotNull();

        assertThat(eventos.nadaPublicado()).isTrue();
    }
}
