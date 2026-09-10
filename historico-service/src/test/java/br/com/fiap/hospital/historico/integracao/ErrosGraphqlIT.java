package br.com.fiap.hospital.historico.integracao;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.Map;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import br.com.fiap.hospital.historico.infrastructure.graphql.ErroDoHistorico;
import br.com.fiap.hospital.historico.infrastructure.leitura.ConsultasDoHistorico;
import java.util.UUID;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Os dois caminhos de erro, provados como caminhos distintos.
 *
 * <p>Falha na analise do documento e falha dentro do resolver sao tratadas por mecanismos
 * diferentes — interceptor e {@code DataFetcherExceptionResolver}. O que estes testes
 * exigem e que, sendo diferentes, respondam a mesma politica: mesmos codigos, mesma
 * sanitizacao. E, no caminho anterior a execucao, que nada tenha sido executado.
 */
@DisplayName("Erros e fronteira do endpoint GraphQL")
class ErrosGraphqlIT extends GraphqlITBase {

    /** Espia o servico real para injetar a falha; tudo o mais continua verdadeiro. */
    @SpyBean ConsultasDoHistorico consultas;

    private static final OffsetDateTime DATA = instante("2026-09-18T14:30:00Z");
    private static final String MINHAS = "query { minhasConsultas { id } }";

    private static final String CORRIGIR = """
            mutation($in: CorrigirRegistroHistoricoInput!) {
              corrigirRegistroHistorico(input: $in) { id }
            }
            """;

    // --- fronteira HTTP ------------------------------------------------------------------

    @Test
    @DisplayName("Scenario: Requisicao sem token e recusada na fronteira")
    void requisicaoSemTokenERecusadaNaFronteira() {
        UUID id = inserirConsulta(pacienteA, medicoA, DATA, "AGENDADA");

        var resposta = postar(null, "query($i: ID!) { consulta(id: $i) { id } }", null);

        assertThat(resposta.getStatusCode().value()).isEqualTo(401);
        assertThat(resposta.getBody())
                .as("nenhum resolver executou: o id sequer aparece na resposta")
                .doesNotContain(id.toString());
    }

    @Test
    @DisplayName("Scenario: Token invalido e recusado na fronteira")
    void tokenInvalidoERecusadoNaFronteira() {
        String adulterado = tokenMedico() + "x";

        var resposta = postar(adulterado, MINHAS, null);

        assertThat(resposta.getStatusCode().value()).isEqualTo(401);
        assertThat(resposta.getBody().toLowerCase())
                .as("dizer qual parte do token falhou entrega informacao a quem esta tentando")
                .doesNotContain("expirado", "assinatura", "signature", "malformed");
    }

    // --- caminho anterior a execucao -----------------------------------------------------

    /**
     * Campo desconhecido morre na validacao do documento.
     *
     * <p>O {@code DataFetcherExceptionResolver} nunca ve este caso: nenhum data fetcher
     * roda. Sem o interceptor da fronteira, o codigo aqui seria o padrao da biblioteca.
     */
    @Test
    @DisplayName("Scenario: campo desconhecido no input e recusado como BAD_REQUEST")
    void campoDesconhecidoNoInputERecusado() {
        UUID id = inserirConsulta(pacienteA, medicoA, DATA, "AGENDADA", "original");

        var resposta = executar(tokenMedico(), CORRIGIR, Map.of("in", Map.of(
                "consultaId", id.toString(),
                "justificativa", "tentativa de forjar autor",
                "autorId", UUID.randomUUID().toString(),
                "pacienteNome", "Nome Novo")));

        assertThat(resposta.primeiroCodigo()).isEqualTo("BAD_REQUEST");
        assertThat(snapshots.findById(id).orElseThrow().getPacienteNome())
                .as("nada pode ser persistido por um documento que nem chegou a executar")
                .isNotEqualTo("Nome Novo");
        assertThat(trilha.count()).isZero();
    }

    @Test
    @DisplayName("Scenario: valor de enum inexistente e recusado como BAD_REQUEST")
    void enumInexistenteERecusado() {
        var resposta = executar(tokenMedico(),
                "query { consultasDoPaciente(pacienteId: \"" + pacienteA
                        + "\", filtro: { periodo: ONTEM }) { id } }");

        assertThat(resposta.primeiroCodigo()).isEqualTo("BAD_REQUEST");
    }

    @Test
    @DisplayName("argumento de tipo incompativel e recusado como BAD_REQUEST")
    void argumentoDeTipoIncompativelERecusado() {
        var resposta = executar(tokenMedico(),
                "query($p: ID!) { consultasDoPaciente(pacienteId: $p, filtro: { de: \"ontem\" })"
                        + " { id } }",
                Map.of("p", pacienteA.toString()));

        assertThat(resposta.primeiroCodigo()).isEqualTo("BAD_REQUEST");
    }

    // --- caminho de execucao -------------------------------------------------------------

    @Test
    @DisplayName("Scenario: Acesso negado nao vira erro interno")
    void acessoNegadoNaoViraErroInterno() {
        var resposta = executar(tokenPaciente(pacienteA),
                "query($m: ID!) { consultasDoMedico(medicoId: $m) { id } }",
                Map.of("m", medicoA.toString()));

        assertThat(resposta.primeiroCodigo()).isEqualTo("FORBIDDEN");
        assertThat(resposta.corpo().path("errors").path(0).path("extensions").path("classification")
                .asText()).isNotEqualTo("INTERNAL_ERROR");
    }

    @Test
    @DisplayName("Scenario: Entrada invalida e recusada como requisicao invalida")
    void entradaInvalidaDeNegocioERecusada() {
        UUID id = inserirConsulta(pacienteA, medicoA, DATA, "AGENDADA");

        var resposta = executar(tokenMedico(), CORRIGIR, Map.of("in", Map.of(
                "consultaId", id.toString(),
                "justificativa", "corrigir status",
                "status", "AGENDADA")));

        assertThat(resposta.primeiroCodigo())
                .as("correcao que nao altera nada e recusa de negocio, dentro do resolver")
                .isEqualTo("BAD_REQUEST");
    }

    /**
     * Falha realmente inesperada: excecao de programacao, com detalhe sensivel dentro.
     *
     * <p>Um identificador malformado nao serve como prova aqui — isso e entrada invalida, e
     * ja tem cenario proprio. O que precisa ser demonstrado e o caminho do defeito: uma
     * excecao que ninguem previu, carregando SQL e nome de classe na mensagem, tem de sair
     * como {@code INTERNAL_ERROR} com o texto padrao e nada mais.
     */
    @Test
    @DisplayName("Scenario: Falha inesperada nao vaza detalhe interno")
    void falhaInesperadaNaoVazaDetalheInterno() {
        String detalheSensivel = "PSQLException: ERROR: relation \"consulta_historico\" "
                + "select paciente_id from consulta_historico where id = 42 [org.postgresql.jdbc]";
        doThrow(new IllegalStateException(detalheSensivel))
                .when(consultas).doPaciente(any(), any());

        var resposta = executar(tokenMedico(),
                "query($p: ID!) { consultasDoPaciente(pacienteId: $p) { id } }",
                Map.of("p", pacienteA.toString()));

        assertThat(resposta.temErro()).isTrue();
        assertThat(resposta.primeiroCodigo())
                .as("excecao nao prevista e erro interno, e nao entrada invalida")
                .isEqualTo("INTERNAL_ERROR");
        assertThat(resposta.primeiraMensagem())
                .as("o cliente recebe exatamente a mensagem padrao")
                .isEqualTo(ErroDoHistorico.INTERNAL_ERROR.mensagemPadrao());
        assertThat(resposta.corpo().toString())
                .as("e nenhum fragmento da causa pode atravessar a fronteira")
                .doesNotContain("PSQLException", "consulta_historico", "org.postgresql",
                        "select ", "stackTrace", "java.lang", "IllegalStateException");
    }
}
