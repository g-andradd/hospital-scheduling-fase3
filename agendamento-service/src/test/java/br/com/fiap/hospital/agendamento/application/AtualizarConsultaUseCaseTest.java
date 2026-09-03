package br.com.fiap.hospital.agendamento.application;

import static br.com.fiap.hospital.agendamento.Cenario.consultaExistente;
import static br.com.fiap.hospital.agendamento.Cenario.daquiA;
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
import br.com.fiap.hospital.agendamento.domain.exception.ConsultaNaoEncontradaException;
import br.com.fiap.hospital.agendamento.domain.exception.TransicaoDeStatusInvalidaException;
import br.com.fiap.hospital.agendamento.fake.ConsultaRepositoryFake;
import br.com.fiap.hospital.agendamento.fake.EventPublisherFake;
import br.com.fiap.hospital.agendamento.fake.UsuarioRepositoryFake;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

@DisplayName("AtualizarConsultaUseCase — Requirement: Alteracao de consulta")
class AtualizarConsultaUseCaseTest {

    private ConsultaRepositoryFake consultas;
    private UsuarioRepositoryFake usuarios;
    private EventPublisherFake eventos;
    private AtualizarConsultaUseCase useCase;

    private Paciente maria;
    private Medico joao;
    private Consulta consulta;

    @BeforeEach
    void preparar() {
        maria = paciente();
        joao = medico();
        consulta = consultaExistente(maria, joao, daquiA(24), StatusConsulta.AGENDADA);
        consultas = new ConsultaRepositoryFake().com(consulta);
        usuarios = new UsuarioRepositoryFake().com(maria).com(joao);
        eventos = new EventPublisherFake();
        useCase = new AtualizarConsultaUseCase(consultas, usuarios, eventos, relogioFixo());
    }

    @Test
    @DisplayName("Scenario: Remarcacao bem-sucedida — novo periodo, status inalterado")
    void remarcacaoBemSucedida() {
        ConsultaResumo resumo = useCase.executar(new AtualizarConsultaCommand(
                consulta.id(), daquiA(48), null, null, "remarcado a pedido"));

        assertThat(resumo.dataHora()).isEqualTo(daquiA(48));
        assertThat(resumo.status()).isEqualTo(StatusConsulta.AGENDADA);
        assertThat(resumo.observacoes()).isEqualTo("remarcado a pedido");
        assertThat(eventos.tipos()).containsExactly(TipoEventoConsulta.ATUALIZADA);
    }

    @Test
    @DisplayName("Scenario: Remarcacao para o passado e recusada — periodo original preservado")
    void remarcacaoParaOPassadoERecusada() {
        assertThatThrownBy(() -> useCase.executar(new AtualizarConsultaCommand(
                        consulta.id(), haQuanto(2), null, null, null)))
                .isInstanceOf(AgendamentoNoPassadoException.class);

        assertThat(consultas.buscarPorId(consulta.id()).orElseThrow().periodo().inicio())
                .isEqualTo(daquiA(24));
        assertThat(eventos.nadaPublicado()).isTrue();
    }

    @Test
    @DisplayName("Scenario: Remarcacao com conflito e recusada — agenda do medico")
    void remarcacaoComConflitoERecusada() {
        Paciente outro = paciente("Jose Silva", "jose@hospital.com", "111.444.777-35");
        consultas.com(consultaExistente(outro, joao, daquiA(48), StatusConsulta.AGENDADA));

        assertThatThrownBy(() -> useCase.executar(new AtualizarConsultaCommand(
                        consulta.id(), daquiA(48), null, null, null)))
                .isInstanceOf(ConflitoDeAgendaException.class);

        assertThat(eventos.nadaPublicado()).isTrue();
    }

    @Test
    @DisplayName("Scenario: A propria consulta nao conflita consigo mesma")
    void aPropriaConsultaNaoConflitaConsigoMesma() {
        ConsultaResumo resumo = useCase.executar(new AtualizarConsultaCommand(
                consulta.id(), null, null, null, "so trocando a observacao"));

        assertThat(resumo.observacoes()).isEqualTo("so trocando a observacao");
        assertThat(resumo.dataHora()).isEqualTo(daquiA(24));
    }

    @Test
    @DisplayName("manter explicitamente o mesmo horario tambem nao conflita")
    void manterOMesmoHorarioNaoConflita() {
        ConsultaResumo resumo = useCase.executar(new AtualizarConsultaCommand(
                consulta.id(), daquiA(24), null, null, null));

        assertThat(resumo.dataHora()).isEqualTo(daquiA(24));
    }

    @ParameterizedTest
    @EnumSource(value = StatusConsulta.class, names = {"REALIZADA", "CANCELADA"})
    @DisplayName("Scenario: Alteracao de consulta em status terminal e recusada")
    void alteracaoDeConsultaTerminalERecusada(StatusConsulta terminal) {
        Consulta terminada = consultaExistente(maria, joao, daquiA(24), terminal);
        consultas.com(terminada);

        assertThatThrownBy(() -> useCase.executar(new AtualizarConsultaCommand(
                        terminada.id(), daquiA(48), null, null, null)))
                .isInstanceOf(TransicaoDeStatusInvalidaException.class);

        assertThat(consultas.buscarPorId(terminada.id()).orElseThrow().periodo().inicio())
                .isEqualTo(daquiA(24));
        assertThat(eventos.nadaPublicado()).isTrue();
    }

    @Test
    @DisplayName("Scenario: Alteracao de consulta inexistente e recusada")
    void alteracaoDeConsultaInexistenteERecusada() {
        assertThatThrownBy(() -> useCase.executar(new AtualizarConsultaCommand(
                        UUID.randomUUID(), daquiA(48), null, null, null)))
                .isInstanceOf(ConsultaNaoEncontradaException.class);
    }

    @Test
    @DisplayName("troca de medico e aplicada e checada contra a agenda do novo medico")
    void trocaDeMedicoEAplicada() {
        Medico ana = medico("Dra. Ana Reis", "ana.reis@hospital.com", "SP-54321");
        usuarios.com(ana);

        ConsultaResumo resumo = useCase.executar(new AtualizarConsultaCommand(
                consulta.id(), null, null, ana.id(), null));

        assertThat(resumo.medicoId()).isEqualTo(ana.id());
    }
}
