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
@org.springframework.boot.autoconfigure.ImportAutoConfiguration(exclude = {
        org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration.class,
        org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration.class})
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
    /**
     * Este teste verifica o contrato HTTP e a forma dos erros, nao a autorizacao. Quem
     * cobre a matriz de perfis e a regra de propriedade e o MatrizDeAutorizacaoIT, com
     * token real contra Postgres — aqui os filtros de seguranca sao dispensados e a
     * identidade e posta direto no contexto.
     */
    @org.junit.jupiter.api.BeforeEach
    void autenticarComoMedico() {
        var principal = new br.com.fiap.hospital.security.UsuarioAutenticado(
                UUID.randomUUID(), "medico@hospital.com", "MEDICO", null, UUID.randomUUID());
        var autenticacao = new org.springframework.security.authentication
                .UsernamePasswordAuthenticationToken(principal, null,
                java.util.List.of(new org.springframework.security.core.authority
                        .SimpleGrantedAuthority("ROLE_MEDICO")));
        org.springframework.security.core.context.SecurityContextHolder.getContext()
                .setAuthentication(autenticacao);
    }

    @org.junit.jupiter.api.AfterEach
    void limparContexto() {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

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
            given(confirmar.executar(any(), any())).willReturn(resumo(StatusConsulta.CONFIRMADA, null));

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
            given(buscar.executar(any(), any())).willReturn(resumo(StatusConsulta.AGENDADA, null));

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
            given(listar.executar(any(), any())).willReturn(new Pagina<>(
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
            given(listar.executar(any(), any())).willReturn(Pagina.vazia(0, 20));

            mvc.perform(get("/api/v1/consultas").param("pacienteId", UUID.randomUUID().toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.conteudo", org.hamcrest.Matchers.hasSize(0)))
                    .andExpect(jsonPath("$.total").value(0));
        }

        @Test
        @DisplayName("Scenario: Tamanho de página acima do teto — a resposta informa o aplicado")
        void tetoAplicadoAparece() throws Exception {
            given(listar.executar(any(), any())).willReturn(new Pagina<>(List.of(), 0, 100, 0));

            mvc.perform(get("/api/v1/consultas").param("tamanho", "100000"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.tamanho")
                            .value(br.com.fiap.hospital.agendamento.domain.FiltroDeConsultas
                                    .TAMANHO_MAXIMO));
        }

        @Test
        @DisplayName("Scenario: Navegação entre páginas")
        void navegacaoEntrePaginas() throws Exception {
            given(listar.executar(any(), any())).willReturn(new Pagina<>(
                    List.of(resumo(StatusConsulta.AGENDADA, null)), 1, 2, 5));

            mvc.perform(get("/api/v1/consultas").param("pagina", "1").param("tamanho", "2"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.pagina").value(1))
                    .andExpect(jsonPath("$.totalDePaginas").value(3));
        }

        @Test
        @DisplayName("Scenario: Listagem filtrada — os filtros chegam ao caso de uso")
        void filtrosChegamAoCasoDeUso() throws Exception {
            given(listar.executar(any(), any())).willReturn(Pagina.vazia(0, 20));
            UUID paciente = UUID.randomUUID();

            mvc.perform(get("/api/v1/consultas")
                            .param("pacienteId", paciente.toString())
                            .param("status", "AGENDADA")
                            .param("de", AGORA.toString())
                            .param("ate", AGORA.plusDays(30).toString()))
                    .andExpect(status().isOk());

            org.mockito.ArgumentCaptor<ListarConsultasQuery> capturado =
                    org.mockito.ArgumentCaptor.forClass(ListarConsultasQuery.class);
            org.mockito.Mockito.verify(listar).executar(capturado.capture(), any());

            org.assertj.core.api.Assertions.assertThat(capturado.getValue().pacienteId())
                    .isEqualTo(paciente);
            org.assertj.core.api.Assertions.assertThat(capturado.getValue().status())
                    .containsExactly(StatusConsulta.AGENDADA);
        }
    }

    @Nested
    @DisplayName("erros de requisicao do Spring MVC")
    class ErrosDeRequisicao {

        @Test
        @DisplayName("Scenario: JSON malformado → 400, nao 500")
        void jsonMalformado() throws Exception {
            mvc.perform(post("/api/v1/consultas")
                            .contentType("application/json")
                            .content("{\"pacienteId\": "))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type")
                            .value("https://hospital.fiap.br/erros/requisicao-malformada"))
                    .andExpect(jsonPath("$.correlationId").isNotEmpty())
                    .andExpect(jsonPath("$.timestamp").isNotEmpty());
        }

        @Test
        @DisplayName("Scenario: UUID invalido no path → 400, nao 500")
        void uuidInvalidoNoPath() throws Exception {
            mvc.perform(get("/api/v1/consultas/nao-e-um-uuid"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type")
                            .value("https://hospital.fiap.br/erros/parametro-invalido"))
                    .andExpect(jsonPath("$.correlationId").isNotEmpty());
        }

        @Test
        @DisplayName("Scenario: enum invalido em query param → 400, nao 500")
        void enumInvalidoEmQueryParam() throws Exception {
            mvc.perform(get("/api/v1/consultas").param("status", "NAO_EXISTE"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type")
                            .value("https://hospital.fiap.br/erros/parametro-invalido"));
        }

        @Test
        @DisplayName("Scenario: metodo nao suportado → 405, nao 500")
        void metodoNaoSuportado() throws Exception {
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .delete("/api/v1/consultas/" + ID))
                    .andExpect(status().isMethodNotAllowed())
                    .andExpect(jsonPath("$.type")
                            .value("https://hospital.fiap.br/erros/metodo-nao-suportado"))
                    .andExpect(jsonPath("$.correlationId").isNotEmpty());
        }

        @Test
        @DisplayName("Scenario: tipo de midia nao suportado → 415, nao 500")
        void midiaNaoSuportada() throws Exception {
            mvc.perform(post("/api/v1/consultas")
                            .contentType("text/plain")
                            .content("qualquer coisa"))
                    .andExpect(status().isUnsupportedMediaType())
                    .andExpect(jsonPath("$.type")
                            .value("https://hospital.fiap.br/erros/midia-nao-suportada"));
        }

        @Test
        @DisplayName("Scenario: corpo ausente onde e obrigatorio → 400, nao 500")
        void corpoAusente() throws Exception {
            mvc.perform(patch("/api/v1/consultas/" + ID + "/cancelar")
                            .contentType("application/json"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type")
                            .value("https://hospital.fiap.br/erros/requisicao-malformada"));
        }

        @Test
        @DisplayName("Scenario: data em formato invalido → 400, nao 500")
        void dataEmFormatoInvalido() throws Exception {
            mvc.perform(get("/api/v1/consultas").param("de", "10/09/2026"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type")
                            .value("https://hospital.fiap.br/erros/parametro-invalido"));
        }

        @Test
        @DisplayName("UUID invalido produz detail estavel, sem tipo Java")
        void uuidInvalidoProduzDetailEstavel() throws Exception {
            mvc.perform(get("/api/v1/consultas/nao-e-um-uuid"))
                    .andExpect(jsonPath("$.detail")
                            .value("Valor nao permitido para o parametro 'id'"));
        }

        @Test
        @DisplayName("nenhum erro de requisicao vaza tipo Java, pacote nem nome de classe")
        void nenhumErroVazaInterno() throws Exception {
            record Caso(String descricao, org.springframework.test.web.servlet.RequestBuilder req) {}

            java.util.List<Caso> casos = java.util.List.of(
                    new Caso("UUID invalido no path", get("/api/v1/consultas/nao-e-um-uuid")),
                    new Caso("enum invalido", get("/api/v1/consultas").param("status", "XPTO")),
                    new Caso("data invalida", get("/api/v1/consultas").param("de", "10/09/2026")),
                    new Caso("JSON malformado", post("/api/v1/consultas")
                            .contentType("application/json").content("{\"pacienteId\": ")),
                    new Caso("corpo ausente", patch("/api/v1/consultas/" + ID + "/cancelar")
                            .contentType("application/json")),
                    new Caso("metodo nao suportado",
                            org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                    .delete("/api/v1/consultas/" + ID)),
                    new Caso("midia nao suportada", post("/api/v1/consultas")
                            .contentType("text/plain").content("x")));

            for (Caso caso : casos) {
                String corpo = mvc.perform(caso.req())
                        .andReturn().getResponse().getContentAsString();

                org.assertj.core.api.Assertions.assertThat(corpo)
                        .as("%s nao pode expor detalhe interno de implementacao", caso.descricao())
                        .doesNotContain("java.lang", "java.util", "org.springframework",
                                "com.fasterxml", "Exception", "required type", "nested exception");
            }
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
            willThrow(new ConsultaNaoEncontradaException(ID)).given(buscar).executar(any(), any());

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
                    .given(confirmar).executar(any(), any());

            mvc.perform(patch("/api/v1/consultas/" + ID + "/confirmar"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.type")
                            .value("https://hospital.fiap.br/erros/transicao-de-status-invalida"));
        }

        /**
         * O corpo enviado precisa ser mesmo "sem motivo".
         *
         * <p>A versao anterior deste teste mandava {@code {"motivo":"algum"}} e so
         * provava que o tratador mapeia a excecao — nao o Scenario que o nome promete.
         * Enquanto existia um {@code @NotBlank} no DTO, um corpo realmente sem motivo
         * nem chegava ao caso de uso: era interceptado e virava 400.
         */
        @org.junit.jupiter.params.ParameterizedTest(name = "corpo = {0}")
        @org.junit.jupiter.params.provider.ValueSource(strings = {
                "{}",
                "{\"motivo\": null}",
                "{\"motivo\": \"\"}",
                "{\"motivo\": \"   \"}"
        })
        @DisplayName("Scenario: Cancelamento sem motivo → 422")
        void cancelamentoSemMotivo(String corpo) throws Exception {
            willThrow(new MotivoDeCancelamentoObrigatorioException())
                    .given(cancelar).executar(any());

            mvc.perform(patch("/api/v1/consultas/" + ID + "/cancelar")
                            .contentType("application/json").content(corpo))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.type").value(
                            "https://hospital.fiap.br/erros/motivo-de-cancelamento-obrigatorio"));

            org.mockito.ArgumentCaptor<br.com.fiap.hospital.agendamento.application
                    .CancelarConsultaCommand> capturado = org.mockito.ArgumentCaptor.forClass(
                            br.com.fiap.hospital.agendamento.application
                                    .CancelarConsultaCommand.class);
            org.mockito.Mockito.verify(cancelar).executar(capturado.capture());

            org.assertj.core.api.Assertions.assertThat(capturado.getValue().motivo())
                    .as("o corpo sem motivo precisa CHEGAR ao caso de uso; se a validacao "
                            + "do DTO o interceptasse, este Scenario nunca aconteceria")
                    .satisfies(motivo -> org.assertj.core.api.Assertions
                            .assertThat(motivo == null || motivo.isBlank()).isTrue());
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
                    .given(buscar).executar(any(), any());

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
            willThrow(new ConsultaNaoEncontradaException(ID)).given(buscar).executar(any(), any());

            mvc.perform(get("/api/v1/consultas/" + ID))
                    .andExpect(jsonPath("$.correlationId").isNotEmpty())
                    .andExpect(jsonPath("$.timestamp").isNotEmpty())
                    .andExpect(jsonPath("$.instance").value("/api/v1/consultas/" + ID));
        }

        @Test
        @DisplayName("o correlationId recebido no cabeçalho é o que volta na resposta")
        void correlacaoRecebidaEPreservada() throws Exception {
            willThrow(new ConsultaNaoEncontradaException(ID)).given(buscar).executar(any(), any());

            mvc.perform(get("/api/v1/consultas/" + ID).header("X-Correlation-Id", "id-do-cliente"))
                    .andExpect(jsonPath("$.correlationId").value("id-do-cliente"))
                    .andExpect(header().string("X-Correlation-Id", "id-do-cliente"));
        }
    }
}
