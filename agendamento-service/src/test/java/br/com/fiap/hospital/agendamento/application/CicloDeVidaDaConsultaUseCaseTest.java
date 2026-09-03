package br.com.fiap.hospital.agendamento.application;

import static br.com.fiap.hospital.agendamento.Cenario.consultaExistente;
import static br.com.fiap.hospital.agendamento.Cenario.daquiA;
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
import br.com.fiap.hospital.agendamento.domain.exception.ConsultaNaoEncontradaException;
import br.com.fiap.hospital.agendamento.domain.exception.MotivoDeCancelamentoObrigatorioException;
import br.com.fiap.hospital.agendamento.domain.exception.TransicaoDeStatusInvalidaException;
import br.com.fiap.hospital.agendamento.fake.ConsultaRepositoryFake;
import br.com.fiap.hospital.agendamento.fake.EventPublisherFake;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("Confirmacao e cancelamento de consulta")
class CicloDeVidaDaConsultaUseCaseTest {

    private ConsultaRepositoryFake consultas;
    private EventPublisherFake eventos;
    private ConfirmarConsultaUseCase confirmar;
    private CancelarConsultaUseCase cancelar;

    private Paciente maria;
    private Medico joao;

    @BeforeEach
    void preparar() {
        maria = paciente();
        joao = medico();
        consultas = new ConsultaRepositoryFake();
        eventos = new EventPublisherFake();
        confirmar = new ConfirmarConsultaUseCase(consultas, eventos, relogioFixo());
        cancelar = new CancelarConsultaUseCase(consultas, eventos, relogioFixo());
    }

    private Consulta existenteEm(StatusConsulta status) {
        Consulta consulta = consultaExistente(maria, joao, daquiA(24), status);
        consultas.com(consulta);
        return consulta;
    }

    @Nested
    @DisplayName("Requirement: Confirmacao de consulta")
    class Confirmacao {

        @Test
        @DisplayName("Scenario: Confirmacao bem-sucedida")
        void confirmacaoBemSucedida() {
            Consulta consulta = existenteEm(StatusConsulta.AGENDADA);

            ConsultaResumo resumo = confirmar.executar(consulta.id());

            assertThat(resumo.status()).isEqualTo(StatusConsulta.CONFIRMADA);
            assertThat(eventos.tipos()).containsExactly(TipoEventoConsulta.CONFIRMADA);
        }

        @Test
        @DisplayName("Scenario: Confirmacao de consulta cancelada e recusada")
        void confirmacaoDeCanceladaERecusada() {
            Consulta consulta = existenteEm(StatusConsulta.CANCELADA);

            assertThatThrownBy(() -> confirmar.executar(consulta.id()))
                    .isInstanceOf(TransicaoDeStatusInvalidaException.class);
            assertThat(eventos.nadaPublicado()).isTrue();
        }

        @Test
        @DisplayName("Scenario: Confirmacao de consulta ja realizada e recusada")
        void confirmacaoDeRealizadaERecusada() {
            Consulta consulta = existenteEm(StatusConsulta.REALIZADA);

            assertThatThrownBy(() -> confirmar.executar(consulta.id()))
                    .isInstanceOf(TransicaoDeStatusInvalidaException.class);
            assertThat(eventos.nadaPublicado()).isTrue();
        }

        @Test
        @DisplayName("confirmacao de consulta inexistente e recusada")
        void confirmacaoDeInexistenteERecusada() {
            assertThatThrownBy(() -> confirmar.executar(UUID.randomUUID()))
                    .isInstanceOf(ConsultaNaoEncontradaException.class);
        }
    }

    @Nested
    @DisplayName("Requirement: Cancelamento de consulta")
    class Cancelamento {

        @ParameterizedTest
        @EnumSource(value = StatusConsulta.class, names = {"AGENDADA", "CONFIRMADA"})
        @DisplayName("Scenario: Cancelamento bem-sucedido — motivo registrado")
        void cancelamentoBemSucedido(StatusConsulta origem) {
            Consulta consulta = existenteEm(origem);

            ConsultaResumo resumo = cancelar.executar(
                    new CancelarConsultaCommand(consulta.id(), "paciente desistiu"));

            assertThat(resumo.status()).isEqualTo(StatusConsulta.CANCELADA);
            assertThat(resumo.motivoCancelamento()).isEqualTo("paciente desistiu");
            assertThat(eventos.tipos()).containsExactly(TipoEventoConsulta.CANCELADA);
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   "})
        @DisplayName("Scenario: Cancelamento sem motivo e recusado — status anterior preservado")
        void cancelamentoSemMotivoERecusado(String motivoAusente) {
            Consulta consulta = existenteEm(StatusConsulta.AGENDADA);

            assertThatThrownBy(() -> cancelar.executar(
                            new CancelarConsultaCommand(consulta.id(), motivoAusente)))
                    .isInstanceOf(MotivoDeCancelamentoObrigatorioException.class);

            assertThat(consultas.buscarPorId(consulta.id()).orElseThrow().status())
                    .isEqualTo(StatusConsulta.AGENDADA);
            assertThat(eventos.nadaPublicado()).isTrue();
        }

        @Test
        @DisplayName("Scenario: Cancelamento de consulta ja realizada e recusado")
        void cancelamentoDeRealizadaERecusado() {
            Consulta consulta = existenteEm(StatusConsulta.REALIZADA);

            assertThatThrownBy(() -> cancelar.executar(
                            new CancelarConsultaCommand(consulta.id(), "motivo")))
                    .isInstanceOf(TransicaoDeStatusInvalidaException.class);
            assertThat(eventos.nadaPublicado()).isTrue();
        }

        @Test
        @DisplayName("Scenario: Cancelamento de consulta ja cancelada e recusado")
        void cancelamentoDeCanceladaERecusado() {
            Consulta consulta = existenteEm(StatusConsulta.CANCELADA);

            assertThatThrownBy(() -> cancelar.executar(
                            new CancelarConsultaCommand(consulta.id(), "motivo")))
                    .isInstanceOf(TransicaoDeStatusInvalidaException.class);
        }

        @Test
        @DisplayName("cancelamento de consulta inexistente e recusado")
        void cancelamentoDeInexistenteERecusado() {
            assertThatThrownBy(() -> cancelar.executar(
                            new CancelarConsultaCommand(UUID.randomUUID(), "motivo")))
                    .isInstanceOf(ConsultaNaoEncontradaException.class);
        }
    }
}
