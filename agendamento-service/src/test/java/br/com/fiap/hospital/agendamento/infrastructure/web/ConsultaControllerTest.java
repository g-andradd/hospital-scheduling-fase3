package br.com.fiap.hospital.agendamento.infrastructure.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.fiap.hospital.agendamento.application.ConsultaResumo;
import br.com.fiap.hospital.agendamento.application.ListarConsultasQuery;
import br.com.fiap.hospital.agendamento.domain.Pagina;
import br.com.fiap.hospital.agendamento.domain.StatusConsulta;
import br.com.fiap.hospital.agendamento.domain.exception.AgendamentoNoPassadoException;
import br.com.fiap.hospital.agendamento.domain.exception.AlteracaoConcorrenteException;
import br.com.fiap.hospital.agendamento.domain.exception.ConflitoDeAgendaException;
import br.com.fiap.hospital.agendamento.domain.exception.ConsultaNaoEncontradaException;
import br.com.fiap.hospital.agendamento.domain.exception.MotivoDeCancelamentoObrigatorioException;
import br.com.fiap.hospital.agendamento.domain.exception.PacienteNaoEncontradoException;
import br.com.fiap.hospital.agendamento.domain.exception.TransicaoDeStatusInvalidaException;
import br.com.fiap.hospital.agendamento.infrastructure.transacao.AgendarConsultaUseCaseTransacional;
import br.com.fiap.hospital.agendamento.infrastructure.transacao.AtualizarConsultaUseCaseTransacional;
import br.com.fiap.hospital.agendamento.infrastructure.transacao.BuscarConsultaPorIdUseCaseTransacional;
import br.com.fiap.hospital.agendamento.infrastructure.transacao.CancelarConsultaUseCaseTransacional;
import br.com.fiap.hospital.agendamento.infrastructure.transacao.ConfirmarConsultaUseCaseTransacional;
import br.com.fiap.hospital.agendamento.infrastructure.transacao.ListarConsultasUseCaseTransacional;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Contrato HTTP e forma dos erros.
 *
 * <p>Os decoradores transacionais sao dublados: aqui o que se verifica e a traducao
 * entre HTTP e caso de uso, e a forma da resposta de erro. Que o dominio realmente
 * produza cada excecao ja e provado pelos testes de caso de uso do M01 e pelos de
 * integracao do M02.
 */
@WebMvcTest(ConsultaController.class)
@Import(ConsultaControllerTest.RelogioFixo.class)
@DisplayName("ConsultaController")
class ConsultaControllerTest {

    private static final OffsetDateTime AGORA =
            OffsetDateTime.of(2026, 9, 3, 12, 0, 0, 0, ZoneOffset.ofHours(-3));

    @TestConfiguration
    static class RelogioFixo {
        @Bean
        Clock clock() {
            return Clock.fixed(AGORA.toInstant(), ZoneId.of("America/Sao_Paulo"));
        }
    }

    @Autowired private MockMvc mvc;

    @MockitoBean private AgendarConsultaUseCaseTransacional agendar;
    @MockitoBean private AtualizarConsultaUseCaseTransacional atualizar;
    @MockitoBean private ConfirmarConsultaUseCaseTransacional confirmar;
    @MockitoBean private CancelarConsultaUseCaseTransacional cancelar;
    @MockitoBean private BuscarConsultaPorIdUseCaseTransacional buscar;
    @MockitoBean private ListarConsultasUseCaseTransacional listar;

    private static final UUID ID = UUID.randomUUID();

    /**
     * As classes aninhadas compartilham o mesmo contexto, e o reset automatico nao
     * alcanca todas: sem isto, um {@code willThrow} declarado nos testes de erro vaza
     * para os de sucesso.
     */
    @org.junit.jupiter.api.BeforeEach
    void reiniciarDubles() {
        org.mockito.Mockito.reset(agendar, atualizar, confirmar, cancelar, buscar, listar);
    }

    private static ConsultaResumo resumo(StatusConsulta status, String observacoes) {
        return new ConsultaResumo(
                ID, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                AGORA.plusDays(1), 30, status, observacoes, null, AGORA, AGORA);
    }

    private static String corpoDeRegistro() {
        return """
                {
                  "pacienteId": "%s",
                  "medicoId": "%s",
                  "registradoPorId": "%s",
                  "dataHora": "%s"
                }
                """.formatted(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                AGORA.plusDays(1));
    }

    @Nested
    @DisplayName("caminhos de sucesso")
    class Sucesso {

        @Test
        @DisplayName("Scenario: Registro bem-sucedido")
        void registro() throws Exception {
            given(agendar.executar(any())).willReturn(resumo(StatusConsulta.AGENDADA, null));

            mvc.perform(post("/api/v1/consultas")
                            .contentType("application/json")
                            .content(corpoDeRegistro()))
                    .andExpect(status().isCreated())
                    .andExpect(header().string("Location", "/api/v1/consultas/" + ID))
                    .andExpect(jsonPath("$.status").value("AGENDADA"))
                    .andExpect(jsonPath("$.id").value(ID.toString()));
        }

        @Test
        @DisplayName("Scenario: Alteração bem-sucedida")
        void alteracao() throws Exception {
            given(atualizar.executar(any())).willReturn(resumo(StatusConsulta.AGENDADA, "nova"));

            mvc.perform(put("/api/v1/consultas/" + ID)
                            .contentType("application/json")
                            .content("{\"observacoes\":\"nova\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.observacoes").value("nova"));
        }

        @Test
        @DisplayName("Scenario: Campo ausente na alteração preserva o valor atual")
        void alteracaoSemObservacoesPreserva() throws Exception {
            given(atualizar.executar(any()))
                    .willReturn(resumo(StatusConsulta.AGENDADA, "observacao clinica original"));

            mvc.perform(put("/api/v1/consultas/" + ID)
                            .contentType("application/json")
                            .content("{\"dataHora\":\"" + AGORA.plusDays(2) + "\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.observacoes").value("observacao clinica original"));
        }

        @Test
        @DisplayName("Scenario: Confirmação bem-sucedida")
        void confirmacao() throws Exception {
            given(confirmar.executar(any())).willReturn(resumo(StatusConsulta.CONFIRMADA, null));

            mvc.perform(patch("/api/v1/consultas/" + ID + "/confirmar"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("CONFIRMADA"));
        }

        @Test
        @DisplayName("Scenario: Cancelamento bem-sucedido")
        void cancelamento() throws Exception {
            ConsultaResumo cancelada = new ConsultaResumo(
                    ID, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                    AGORA.plusDays(1), 30, StatusConsulta.CANCELADA, null,
                    "paciente desistiu", AGORA, AGORA);
            given(cancelar.executar(any())).willReturn(cancelada);

            mvc.perform(patch("/api/v1/consultas/" + ID + "/cancelar")
                            .contentType("application/json")
                            .content("{\"motivo\":\"paciente desistiu\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("CANCELADA"))
                    .andExpect(jsonPath("$.motivoCancelamento").value("paciente desistiu"));
        }

        @Test
        @DisplayName("Scenario: Recuperação bem-sucedida")
        void recuperacao() throws Exception {
            given(buscar.executar(ID)).willReturn(resumo(StatusConsulta.AGENDADA, null));

            mvc.perform(get("/api/v1/consultas/" + ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(ID.toString()));
        }
    }

    @Nested
    @DisplayName("listagem paginada")
    class Listagem {

        @Test
        @DisplayName("Scenario: Listagem sem filtros")
        void semFiltros() throws Exception {
            given(listar.executar(any())).willReturn(new Pagina<>(
                    List.of(resumo(StatusConsulta.AGENDADA, null)), 0, 20, 1));

            mvc.perform(get("/api/v1/consultas"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.conteudo", org.hamcrest.Matchers.hasSize(1)))
                    .andExpect(jsonPath("$.total").value(1))
                    .andExpect(jsonPath("$.pagina").value(0));
        }

        @Test
        @DisplayName("Scenario: Listagem sem resultados")
        void semResultados() throws Exception {
            given(listar.executar(any())).willReturn(Pagina.vazia(0, 20));

            mvc.perform(get("/api/v1/consultas").param("pacienteId", UUID.randomUUID().toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.conteudo", org.hamcrest.Matchers.hasSize(0)))
                    .andExpect(jsonPath("$.total").value(0));
        }

        @Test
        @DisplayName("Scenario: Tamanho de página acima do teto — a resposta informa o aplicado")
        void tetoAplicadoAparece() throws Exception {
            given(listar.executar(any())).willReturn(new Pagina<>(List.of(), 0, 100, 0));

            mvc.perform(get("/api/v1/consultas").param("tamanho", "100000"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.tamanho")
                            .value(br.com.fiap.hospital.agendamento.domain.FiltroDeConsultas
                                    .TAMANHO_MAXIMO));
        }

        @Test
        @DisplayName("Scenario: Navegação entre páginas")
        void navegacaoEntrePaginas() throws Exception {
            given(listar.executar(any())).willReturn(new Pagina<>(
                    List.of(resumo(StatusConsulta.AGENDADA, null)), 1, 2, 5));

            mvc.perform(get("/api/v1/consultas").param("pagina", "1").param("tamanho", "2"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.pagina").value(1))
                    .andExpect(jsonPath("$.totalDePaginas").value(3));
        }

        @Test
        @DisplayName("Scenario: Listagem filtrada — os filtros chegam ao caso de uso")
        void filtrosChegamAoCasoDeUso() throws Exception {
            given(listar.executar(any())).willReturn(Pagina.vazia(0, 20));
            UUID paciente = UUID.randomUUID();

            mvc.perform(get("/api/v1/consultas")
                            .param("pacienteId", paciente.toString())
                            .param("status", "AGENDADA")
                            .param("de", AGORA.toString())
                            .param("ate", AGORA.plusDays(30).toString()))
                    .andExpect(status().isOk());

            org.mockito.ArgumentCaptor<ListarConsultasQuery> capturado =
                    org.mockito.ArgumentCaptor.forClass(ListarConsultasQuery.class);
            org.mockito.Mockito.verify(listar).executar(capturado.capture());

            org.assertj.core.api.Assertions.assertThat(capturado.getValue().pacienteId())
                    .isEqualTo(paciente);
            org.assertj.core.api.Assertions.assertThat(capturado.getValue().status())
                    .containsExactly(StatusConsulta.AGENDADA);
        }
    }

    @Nested
    @DisplayName("mapa de erros — uma entrada por teste")
    class Erros {

        @Test
        @DisplayName("Scenario: Agendamento no passado → 422")
        void agendamentoNoPassado() throws Exception {
            willThrow(new AgendamentoNoPassadoException(AGORA.minusDays(1), AGORA))
                    .given(agendar).executar(any());

            mvc.perform(post("/api/v1/consultas")
                            .contentType("application/json").content(corpoDeRegistro()))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.type")
                            .value("https://hospital.fiap.br/erros/agendamento-no-passado"))
                    .andExpect(jsonPath("$.title").value("Agendamento no passado"));
        }

        @Test
        @DisplayName("Scenario: Conflito de agenda → 409")
        void conflitoDeAgenda() throws Exception {
            willThrow(ConflitoDeAgendaException.doMedico("14:00 a 14:30"))
                    .given(agendar).executar(any());

            mvc.perform(post("/api/v1/consultas")
                            .contentType("application/json").content(corpoDeRegistro()))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.type")
                            .value("https://hospital.fiap.br/erros/conflito-de-agenda"));
        }

        @Test
        @DisplayName("Scenario: Recurso não encontrado → 404, com o recurso no detail")
        void recursoNaoEncontrado() throws Exception {
            UUID desconhecido = UUID.randomUUID();
            willThrow(new PacienteNaoEncontradoException(desconhecido))
                    .given(agendar).executar(any());

            mvc.perform(post("/api/v1/consultas")
                            .contentType("application/json").content(corpoDeRegistro()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.type")
                            .value("https://hospital.fiap.br/erros/recurso-nao-encontrado"))
                    .andExpect(jsonPath("$.detail")
                            .value(org.hamcrest.Matchers.containsString("Paciente nao encontrado")));
        }

        @Test
        @DisplayName("a base cobre também a consulta não encontrada")
        void consultaNaoEncontrada() throws Exception {
            willThrow(new ConsultaNaoEncontradaException(ID)).given(buscar).executar(any());

            mvc.perform(get("/api/v1/consultas/" + ID))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.detail")
                            .value(org.hamcrest.Matchers.containsString("Consulta nao encontrada")));
        }

        @Test
        @DisplayName("Scenario: Transição de status inválida → 409")
        void transicaoInvalida() throws Exception {
            willThrow(new TransicaoDeStatusInvalidaException(
                    StatusConsulta.CANCELADA, StatusConsulta.CONFIRMADA))
                    .given(confirmar).executar(any());

            mvc.perform(patch("/api/v1/consultas/" + ID + "/confirmar"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.type")
                            .value("https://hospital.fiap.br/erros/transicao-de-status-invalida"));
        }

        @Test
        @DisplayName("Scenario: Cancelamento sem motivo → 422")
        void cancelamentoSemMotivo() throws Exception {
            willThrow(new MotivoDeCancelamentoObrigatorioException())
                    .given(cancelar).executar(any());

            mvc.perform(patch("/api/v1/consultas/" + ID + "/cancelar")
                            .contentType("application/json").content("{\"motivo\":\"algum\"}"))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.type").value(
                            "https://hospital.fiap.br/erros/motivo-de-cancelamento-obrigatorio"));
        }

        @Test
        @DisplayName("Scenario: Alteração concorrente → 409 com type distinto e orientação")
        void alteracaoConcorrente() throws Exception {
            willThrow(new AlteracaoConcorrenteException(ID)).given(atualizar).executar(any());

            mvc.perform(put("/api/v1/consultas/" + ID)
                            .contentType("application/json").content("{\"observacoes\":\"x\"}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.type")
                            .value("https://hospital.fiap.br/erros/alteracao-concorrente"))
                    .andExpect(jsonPath("$.detail")
                            .value(org.hamcrest.Matchers.containsString("Recarregue")));
        }

        @Test
        @DisplayName("Scenario: Argumento malformado → 400")
        void argumentoMalformado() throws Exception {
            willThrow(new IllegalArgumentException("CPF invalido: 123"))
                    .given(agendar).executar(any());

            mvc.perform(post("/api/v1/consultas")
                            .contentType("application/json").content(corpoDeRegistro()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type")
                            .value("https://hospital.fiap.br/erros/argumento-invalido"));
        }

        @Test
        @DisplayName("Scenario: Validação de corpo com múltiplos campos inválidos → 400")
        void multiplosCamposInvalidos() throws Exception {
            mvc.perform(post("/api/v1/consultas")
                            .contentType("application/json")
                            .content("{\"duracaoMinutos\": 0}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type")
                            .value("https://hospital.fiap.br/erros/validacao-de-campos"))
                    .andExpect(jsonPath("$.campos.pacienteId").exists())
                    .andExpect(jsonPath("$.campos.medicoId").exists())
                    .andExpect(jsonPath("$.campos.dataHora").exists())
                    .andExpect(jsonPath("$.campos.duracaoMinutos").exists());
        }

        @Test
        @DisplayName("Scenario: Falha inesperada não vaza detalhe interno")
        void falhaInesperada() throws Exception {
            willThrow(new IllegalStateException("NullPointer em ConsultaRepositoryAdapter linha 42"))
                    .given(buscar).executar(any());

            mvc.perform(get("/api/v1/consultas/" + ID))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.type")
                            .value("https://hospital.fiap.br/erros/erro-interno"))
                    .andExpect(jsonPath("$.detail")
                            .value(org.hamcrest.Matchers.not(
                                    org.hamcrest.Matchers.containsString("ConsultaRepositoryAdapter"))))
                    .andExpect(jsonPath("$.detail")
                            .value(org.hamcrest.Matchers.not(
                                    org.hamcrest.Matchers.containsString("NullPointer"))));
        }

        @Test
        @DisplayName("Scenario: Correlação presente em toda resposta de erro")
        void correlacaoPresente() throws Exception {
            willThrow(new ConsultaNaoEncontradaException(ID)).given(buscar).executar(any());

            mvc.perform(get("/api/v1/consultas/" + ID))
                    .andExpect(jsonPath("$.correlationId").isNotEmpty())
                    .andExpect(jsonPath("$.timestamp").isNotEmpty())
                    .andExpect(jsonPath("$.instance").value("/api/v1/consultas/" + ID));
        }

        @Test
        @DisplayName("o correlationId recebido no cabeçalho é o que volta na resposta")
        void correlacaoRecebidaEPreservada() throws Exception {
            willThrow(new ConsultaNaoEncontradaException(ID)).given(buscar).executar(any());

            mvc.perform(get("/api/v1/consultas/" + ID).header("X-Correlation-Id", "id-do-cliente"))
                    .andExpect(jsonPath("$.correlationId").value("id-do-cliente"))
                    .andExpect(header().string("X-Correlation-Id", "id-do-cliente"));
        }
    }
}
