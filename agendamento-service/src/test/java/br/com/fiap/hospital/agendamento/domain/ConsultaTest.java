package br.com.fiap.hospital.agendamento.domain;

import static br.com.fiap.hospital.agendamento.Cenario.AGORA;
import static br.com.fiap.hospital.agendamento.Cenario.daquiA;
import static br.com.fiap.hospital.agendamento.Cenario.haQuanto;
import static br.com.fiap.hospital.agendamento.Cenario.periodo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.fiap.hospital.agendamento.domain.exception.AgendamentoNoPassadoException;
import br.com.fiap.hospital.agendamento.domain.exception.MotivoDeCancelamentoObrigatorioException;
import br.com.fiap.hospital.agendamento.domain.exception.TransicaoDeStatusInvalidaException;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("Consulta")
class ConsultaTest {

    private static Consulta agendadaDaquiA(long horas) {
        return Consulta.agendar(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                periodo(daquiA(horas)),
                "Retorno de rotina",
                AGORA);
    }

    private static Consulta em(StatusConsulta status) {
        return Consulta.reconstituir(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                periodo(daquiA(24)),
                status,
                null,
                null,
                AGORA,
                AGORA);
    }

    @Nested
    @DisplayName("registro")
    class Registro {

        @Test
        @DisplayName("nasce com status AGENDADA e a duracao padrao de 30 minutos")
        void nasceAgendada() {
            Consulta consulta = agendadaDaquiA(24);

            assertThat(consulta.status()).isEqualTo(StatusConsulta.AGENDADA);
            assertThat(consulta.periodo().duracaoMinutos()).isEqualTo(30);
            assertThat(Consulta.DURACAO_PADRAO_MINUTOS).isEqualTo(30);
            assertThat(consulta.motivoCancelamento()).isNull();
        }

        @Test
        @DisplayName("Scenario: Registro em instante passado e recusado")
        void registroNoPassadoERecusado() {
            assertThatThrownBy(() -> Consulta.agendar(
                            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                            UUID.randomUUID(), periodo(haQuanto(1)), null, AGORA))
                    .isInstanceOf(AgendamentoNoPassadoException.class)
                    .hasMessageContaining("passado");
        }

        @Test
        @DisplayName("Scenario: Registro no instante corrente e recusado")
        void registroNoInstanteCorrenteERecusado() {
            assertThatThrownBy(() -> Consulta.agendar(
                            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                            UUID.randomUUID(), periodo(AGORA), null, AGORA))
                    .isInstanceOf(AgendamentoNoPassadoException.class);
        }

        @Test
        @DisplayName("observacoes em branco viram nulo")
        void observacoesEmBrancoViramNulo() {
            Consulta consulta = Consulta.agendar(
                    UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                    UUID.randomUUID(), periodo(daquiA(24)), "   ", AGORA);

            assertThat(consulta.observacoes()).isNull();
        }
    }

    @Nested
    @DisplayName("maquina de estados")
    class MaquinaDeEstados {

        @Test
        @DisplayName("Scenario: Transicoes validas a partir de AGENDADA")
        void transicoesValidasDeAgendada() {
            assertThatCode(() -> em(StatusConsulta.AGENDADA).confirmar(AGORA)).doesNotThrowAnyException();
            assertThatCode(() -> em(StatusConsulta.AGENDADA).registrarRealizacao(AGORA))
                    .doesNotThrowAnyException();
            assertThatCode(() -> em(StatusConsulta.AGENDADA).cancelar("paciente desistiu", AGORA))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Scenario: Transicoes validas a partir de CONFIRMADA")
        void transicoesValidasDeConfirmada() {
            assertThatCode(() -> em(StatusConsulta.CONFIRMADA).registrarRealizacao(AGORA))
                    .doesNotThrowAnyException();
            assertThatCode(() -> em(StatusConsulta.CONFIRMADA).cancelar("medico ausente", AGORA))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Scenario: Retorno a AGENDADA e recusado")
        void retornoAAgendadaERecusado() {
            Consulta confirmada = em(StatusConsulta.CONFIRMADA);

            assertThat(confirmada.status().podeTransicionarPara(StatusConsulta.AGENDADA)).isFalse();
        }

        @ParameterizedTest
        @EnumSource(value = StatusConsulta.class, names = {"REALIZADA", "CANCELADA"})
        @DisplayName("Scenario: Saida de status terminal e recusada")
        void saidaDeStatusTerminalERecusada(StatusConsulta terminal) {
            assertThatThrownBy(() -> em(terminal).confirmar(AGORA))
                    .isInstanceOf(TransicaoDeStatusInvalidaException.class);
            assertThatThrownBy(() -> em(terminal).registrarRealizacao(AGORA))
                    .isInstanceOf(TransicaoDeStatusInvalidaException.class);
            assertThatThrownBy(() -> em(terminal).cancelar("qualquer motivo", AGORA))
                    .isInstanceOf(TransicaoDeStatusInvalidaException.class);
        }

        @Test
        @DisplayName("Scenario: Transicao para o mesmo status e recusada")
        void transicaoParaOMesmoStatusERecusada() {
            assertThatThrownBy(() -> em(StatusConsulta.CONFIRMADA).confirmar(AGORA))
                    .isInstanceOf(TransicaoDeStatusInvalidaException.class);
        }

        @Test
        @DisplayName("a transicao atualiza o instante de atualizacao")
        void transicaoAtualizaOInstante() {
            Consulta consulta = em(StatusConsulta.AGENDADA);
            consulta.confirmar(daquiA(1));

            assertThat(consulta.atualizadoEm()).isEqualTo(daquiA(1));
            assertThat(consulta.criadoEm()).isEqualTo(AGORA);
        }
    }

    @Nested
    @DisplayName("confirmacao")
    class Confirmacao {

        @Test
        @DisplayName("Scenario: Confirmacao bem-sucedida")
        void confirmacaoBemSucedida() {
            Consulta consulta = em(StatusConsulta.AGENDADA);
            consulta.confirmar(AGORA);

            assertThat(consulta.status()).isEqualTo(StatusConsulta.CONFIRMADA);
        }

        @Test
        @DisplayName("Scenario: Confirmacao de consulta cancelada e recusada")
        void confirmacaoDeCanceladaERecusada() {
            assertThatThrownBy(() -> em(StatusConsulta.CANCELADA).confirmar(AGORA))
                    .isInstanceOf(TransicaoDeStatusInvalidaException.class);
        }

        @Test
        @DisplayName("Scenario: Confirmacao de consulta ja realizada e recusada")
        void confirmacaoDeRealizadaERecusada() {
            assertThatThrownBy(() -> em(StatusConsulta.REALIZADA).confirmar(AGORA))
                    .isInstanceOf(TransicaoDeStatusInvalidaException.class);
        }
    }

    @Nested
    @DisplayName("cancelamento")
    class Cancelamento {

        @Test
        @DisplayName("Scenario: Cancelamento bem-sucedido — motivo fica registrado")
        void cancelamentoBemSucedido() {
            Consulta consulta = em(StatusConsulta.AGENDADA);
            consulta.cancelar("  paciente remarcou  ", AGORA);

            assertThat(consulta.status()).isEqualTo(StatusConsulta.CANCELADA);
            assertThat(consulta.motivoCancelamento()).isEqualTo("paciente remarcou");
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   ", "\t"})
        @DisplayName("Scenario: Cancelamento sem motivo e recusado — status preservado")
        void cancelamentoSemMotivoERecusado(String motivoAusente) {
            Consulta consulta = em(StatusConsulta.AGENDADA);

            assertThatThrownBy(() -> consulta.cancelar(motivoAusente, AGORA))
                    .isInstanceOf(MotivoDeCancelamentoObrigatorioException.class)
                    .hasMessageContaining("motivo do cancelamento");

            assertThat(consulta.status()).isEqualTo(StatusConsulta.AGENDADA);
            assertThat(consulta.motivoCancelamento()).isNull();
        }

        @Test
        @DisplayName("Scenario: Cancelamento de consulta ja realizada e recusado")
        void cancelamentoDeRealizadaERecusado() {
            assertThatThrownBy(() -> em(StatusConsulta.REALIZADA).cancelar("motivo", AGORA))
                    .isInstanceOf(TransicaoDeStatusInvalidaException.class);
        }

        @Test
        @DisplayName("Scenario: Cancelamento de consulta ja cancelada e recusado")
        void cancelamentoDeCanceladaERecusado() {
            assertThatThrownBy(() -> em(StatusConsulta.CANCELADA).cancelar("motivo", AGORA))
                    .isInstanceOf(TransicaoDeStatusInvalidaException.class);
        }

        @Test
        @DisplayName("status terminal prevalece sobre motivo ausente")
        void statusTerminalPrevaleceSobreMotivoAusente() {
            assertThatThrownBy(() -> em(StatusConsulta.REALIZADA).cancelar(null, AGORA))
                    .isInstanceOf(TransicaoDeStatusInvalidaException.class);
        }
    }

    @Nested
    @DisplayName("alteracao")
    class Alteracao {

        @Test
        @DisplayName("remarcacao move o periodo e preserva o status")
        void remarcacaoMovePeriodo() {
            Consulta consulta = em(StatusConsulta.CONFIRMADA);
            consulta.atualizar(periodo(daquiA(48)), null, "nova observacao", AGORA);

            assertThat(consulta.periodo().inicio()).isEqualTo(daquiA(48));
            assertThat(consulta.status()).isEqualTo(StatusConsulta.CONFIRMADA);
            assertThat(consulta.observacoes()).isEqualTo("nova observacao");
        }

        @Test
        @DisplayName("Scenario: Remarcacao para o passado e recusada — periodo preservado")
        void remarcacaoParaOPassadoERecusada() {
            Consulta consulta = em(StatusConsulta.AGENDADA);

            assertThatThrownBy(() -> consulta.atualizar(periodo(haQuanto(2)), null, null, AGORA))
                    .isInstanceOf(AgendamentoNoPassadoException.class);

            assertThat(consulta.periodo().inicio()).isEqualTo(daquiA(24));
        }

        @ParameterizedTest
        @EnumSource(value = StatusConsulta.class, names = {"REALIZADA", "CANCELADA"})
        @DisplayName("Scenario: Alteracao de consulta em status terminal e recusada")
        void alteracaoDeConsultaTerminalERecusada(StatusConsulta terminal) {
            Consulta consulta = em(terminal);

            assertThatThrownBy(() -> consulta.atualizar(periodo(daquiA(48)), null, null, AGORA))
                    .isInstanceOf(TransicaoDeStatusInvalidaException.class)
                    .hasMessageContaining("alterar");

            assertThat(consulta.periodo().inicio()).isEqualTo(daquiA(24));
        }

        @Test
        @DisplayName("manter o mesmo periodo nao dispara a regra de passado")
        void manterOMesmoPeriodoNaoDisparaRegraDePassado() {
            Consulta consulta = Consulta.reconstituir(
                    UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                    periodo(haQuanto(2)), StatusConsulta.AGENDADA, null, null, AGORA, AGORA);

            assertThatCode(() -> consulta.atualizar(null, null, "so observacao", AGORA))
                    .doesNotThrowAnyException();
            assertThat(consulta.observacoes()).isEqualTo("so observacao");
        }
    }

    @Test
    @DisplayName("identidade e pelo id, nao pelo conteudo")
    void identidadePeloId() {
        Consulta consulta = em(StatusConsulta.AGENDADA);

        assertThat(consulta).isEqualTo(consulta).hasSameHashCodeAs(consulta);
        assertThat(consulta).isNotEqualTo(em(StatusConsulta.AGENDADA));
        assertThat(consulta.toString()).contains(consulta.id().toString());
    }
}
