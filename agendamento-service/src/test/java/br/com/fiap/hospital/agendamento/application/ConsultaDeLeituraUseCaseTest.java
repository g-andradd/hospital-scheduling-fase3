package br.com.fiap.hospital.agendamento.application;

import static br.com.fiap.hospital.agendamento.Cenario.consultaExistente;
import static br.com.fiap.hospital.agendamento.Cenario.daquiA;
import static br.com.fiap.hospital.agendamento.Cenario.haQuanto;
import static br.com.fiap.hospital.agendamento.Cenario.medico;
import static br.com.fiap.hospital.agendamento.Cenario.paciente;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.fiap.hospital.agendamento.domain.Consulta;
import br.com.fiap.hospital.agendamento.domain.Medico;
import br.com.fiap.hospital.agendamento.domain.Paciente;
import br.com.fiap.hospital.agendamento.domain.StatusConsulta;
import br.com.fiap.hospital.agendamento.domain.exception.ConsultaNaoEncontradaException;
import br.com.fiap.hospital.agendamento.fake.ConsultaRepositoryFake;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("Requirement: Consulta e listagem de consultas")
class ConsultaDeLeituraUseCaseTest {

    private ConsultaRepositoryFake consultas;
    private BuscarConsultaPorIdUseCase buscar;
    private ListarConsultasUseCase listar;

    private Paciente maria;
    private Paciente jose;
    private Medico joao;
    private Medico ana;

    private Consulta futuraDeMaria;
    private Consulta passadaDeJose;
    private Consulta canceladaDeMaria;

    @BeforeEach
    void preparar() {
        maria = paciente();
        jose = paciente("Jose Silva", "jose@hospital.com", "111.444.777-35");
        joao = medico();
        ana = medico("Dra. Ana Reis", "ana.reis@hospital.com", "SP-54321");

        futuraDeMaria = consultaExistente(maria, joao, daquiA(24), StatusConsulta.AGENDADA);
        passadaDeJose = consultaExistente(jose, ana, haQuanto(72), StatusConsulta.REALIZADA);
        canceladaDeMaria = consultaExistente(maria, ana, daquiA(96), StatusConsulta.CANCELADA);

        consultas = new ConsultaRepositoryFake()
                .com(futuraDeMaria, passadaDeJose, canceladaDeMaria);
        buscar = new BuscarConsultaPorIdUseCase(consultas);
        listar = new ListarConsultasUseCase(consultas);
    }

    @Nested
    @DisplayName("recuperacao por identificador")
    class Recuperacao {

        @Test
        @DisplayName("Scenario: Recuperacao por identificador")
        void recuperacaoPorIdentificador() {
            ConsultaResumo resumo = buscar.executar(futuraDeMaria.id());

            assertThat(resumo.id()).isEqualTo(futuraDeMaria.id());
            assertThat(resumo.pacienteId()).isEqualTo(maria.id());
        }

        @Test
        @DisplayName("Scenario: Recuperacao de identificador inexistente")
        void recuperacaoDeIdentificadorInexistente() {
            assertThatThrownBy(() -> buscar.executar(UUID.randomUUID()))
                    .isInstanceOf(ConsultaNaoEncontradaException.class)
                    .hasMessageContaining("Consulta nao encontrada");
        }
    }

    @Nested
    @DisplayName("listagem")
    class Listagem {

        @Test
        @DisplayName("Scenario: Listagem sem filtros devolve todas")
        void listagemSemFiltros() {
            assertThat(listar.executar(ListarConsultasQuery.semFiltro())).hasSize(3);
            assertThat(listar.executar(null)).hasSize(3);
        }

        @Test
        @DisplayName("Scenario: Listagem filtrada — por paciente")
        void listagemFiltradaPorPaciente() {
            var resultado = listar.executar(new ListarConsultasQuery(
                    maria.id(), null, null, null, null));

            assertThat(resultado).extracting(ConsultaResumo::id)
                    .containsExactlyInAnyOrder(futuraDeMaria.id(), canceladaDeMaria.id());
        }

        @Test
        @DisplayName("Scenario: Listagem filtrada — por medico")
        void listagemFiltradaPorMedico() {
            var resultado = listar.executar(new ListarConsultasQuery(
                    null, joao.id(), null, null, null));

            assertThat(resultado).extracting(ConsultaResumo::id)
                    .containsExactly(futuraDeMaria.id());
        }

        @Test
        @DisplayName("Scenario: Listagem filtrada — por status")
        void listagemFiltradaPorStatus() {
            var resultado = listar.executar(new ListarConsultasQuery(
                    null, null, Set.of(StatusConsulta.CANCELADA, StatusConsulta.REALIZADA),
                    null, null));

            assertThat(resultado).extracting(ConsultaResumo::id)
                    .containsExactlyInAnyOrder(canceladaDeMaria.id(), passadaDeJose.id());
        }

        @Test
        @DisplayName("Scenario: Listagem filtrada — por intervalo de datas")
        void listagemFiltradaPorIntervalo() {
            var resultado = listar.executar(new ListarConsultasQuery(
                    null, null, null, daquiA(1), daquiA(48)));

            assertThat(resultado).extracting(ConsultaResumo::id)
                    .containsExactly(futuraDeMaria.id());
        }

        @Test
        @DisplayName("Scenario: Listagem filtrada — criterios combinados com E")
        void listagemComCriteriosCombinados() {
            var resultado = listar.executar(new ListarConsultasQuery(
                    maria.id(), joao.id(), Set.of(StatusConsulta.AGENDADA), null, null));

            assertThat(resultado).extracting(ConsultaResumo::id)
                    .containsExactly(futuraDeMaria.id());
        }

        @Test
        @DisplayName("Scenario: Listagem sem resultados devolve lista vazia, sem erro")
        void listagemSemResultados() {
            var resultado = listar.executar(new ListarConsultasQuery(
                    UUID.randomUUID(), null, null, null, null));

            assertThat(resultado).isEmpty();
        }
    }
}
