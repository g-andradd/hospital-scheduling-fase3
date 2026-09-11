package br.com.fiap.hospital.notificacao.integracao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * O disparo manual pela cadeia real de filtros: sem credencial, com credencial invalida e com
 * o banco indisponivel.
 *
 * <p>Os resultados por perfil — medico, enfermeiro, paciente — nao estao aqui: eles vem da
 * matriz de docs/02, em {@code MatrizDeAutorizacaoNotificacaoIT}. Uma segunda copia da
 * politica neste arquivo poderia divergir do documento sem que nada acusasse.
 */
@DisplayName("Disparo manual do lembrete D-1")
class DisparoManualIT extends NotificacaoITBase {

    private static final String ENDPOINT = "/internal/lembretes/executar";
    private static final String NAO_AUTENTICADO = "https://hospital.fiap.br/erros/nao-autenticado";
    private static final String ACESSO_NEGADO = "https://hospital.fiap.br/erros/acesso-negado";

    @Autowired ApplicationContext contexto;

    @Test
    @DisplayName("Scenario: Disparo sem candidatos responde zero")
    void disparoSemCandidatosRespondeZero() throws Exception {
        var resposta = disparar(post(ENDPOINT).header(HttpHeaders.AUTHORIZATION, bearer(tokenDe("MEDICO"))));

        assertThat(resposta.getStatus()).isEqualTo(200);
        assertThat((Integer) JsonPath.read(resposta.getContentAsString(), "$.lembretesEnviados"))
                .isZero();
    }

    @Test
    @DisplayName("Scenario: Sem token recebe 401")
    void semTokenRecebe401() throws Exception {
        agendar(AGORA.plus(Duration.ofHours(1)), "AGENDADA");

        exigirRecusa(disparar(post(ENDPOINT)), 401, NAO_AUTENTICADO);
    }

    @Test
    @DisplayName("Scenario: Token expirado recebe 401")
    void tokenExpiradoRecebe401() throws Exception {
        agendar(AGORA.plus(Duration.ofHours(1)), "AGENDADA");

        exigirRecusa(disparar(post(ENDPOINT)
                .header(HttpHeaders.AUTHORIZATION, bearer(tokenExpiradoDe("MEDICO")))),
                401, NAO_AUTENTICADO);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"malformado", "assinado com outro segredo"})
    @DisplayName("Scenario: Token invalido recebe 401")
    void tokenInvalidoRecebe401(String caso) throws Exception {
        agendar(AGORA.plus(Duration.ofHours(1)), "AGENDADA");
        String token = caso.equals("malformado") ? "nao-e-um-token" : tokenDeOutroSegredo("MEDICO");

        exigirRecusa(disparar(post(ENDPOINT).header(HttpHeaders.AUTHORIZATION, bearer(token))),
                401, NAO_AUTENTICADO);
    }

    /**
     * A falha e real: a tabela some por DDL durante a chamada, e o PostgreSQL devolve um erro
     * verdadeiro, com o SQL na mensagem — exatamente o que nao pode sair na resposta.
     */
    @Test
    @DisplayName("Scenario: Falha na leitura inicial dos candidatos responde 500 em Problem Detail")
    void falhaNaLeituraInicialRespondeProblemDetail500() throws Exception {
        agendar(AGORA.plus(Duration.ofHours(1)), "AGENDADA");

        MockHttpServletResponse resposta;
        jdbc.execute("ALTER TABLE agenda_local RENAME TO " + AGENDA_INDISPONIVEL);
        try {
            resposta = disparar(post(ENDPOINT)
                    .header(HttpHeaders.AUTHORIZATION, bearer(tokenDe("MEDICO")))
                    .header("X-Correlation-Id", "correlacao-m07"));
        } finally {
            jdbc.execute("ALTER TABLE " + AGENDA_INDISPONIVEL + " RENAME TO agenda_local");
        }

        String corpo = resposta.getContentAsString();
        assertThat(resposta.getStatus()).isEqualTo(500);
        assertThat(resposta.getContentType()).startsWith("application/problem+json");
        assertThat((String) JsonPath.read(corpo, "$.type"))
                .isEqualTo("https://hospital.fiap.br/erros/erro-interno");
        assertThat((String) JsonPath.read(corpo, "$.title")).isEqualTo("Erro interno");
        assertThat((String) JsonPath.read(corpo, "$.detail"))
                .isEqualTo("Nao foi possivel executar os lembretes. Tente novamente mais tarde.");
        assertThat((String) JsonPath.read(corpo, "$.instance")).isEqualTo(ENDPOINT);
        assertThat((String) JsonPath.read(corpo, "$.correlationId")).isEqualTo("correlacao-m07");
        assertThat((String) JsonPath.read(corpo, "$.timestamp")).startsWith("2026-09-11T12:00");
        assertThat(corpo)
                .as("nada interno na resposta: a causa vai para o log")
                .doesNotContain("agenda_local", "select", "SELECT", "Exception", "org.",
                        "\tat ", "PSQL", "postgres");

        Mockito.verify(sender, Mockito.never()).enviar(Mockito.any());
        assertThat(lembretesRegistrados()).isZero();
    }

    @Test
    @DisplayName("a notificacao usa a cadeia compartilhada, sem cadeia propria")
    void existeUmaUnicaCadeia() {
        assertThat(contexto.getBeansOfType(SecurityFilterChain.class)).hasSize(1);
    }

    /** 403 exato com token valido: caminho fora de /internal/** nao e autenticado, e negado. */
    @Test
    @DisplayName("caminho fora de /internal/** cai no denyAll")
    void caminhoForaDeInternalCaiNoDenyAll() throws Exception {
        var comToken = disparar(post("/api/qualquer")
                .header(HttpHeaders.AUTHORIZATION, bearer(tokenDe("MEDICO"))));
        assertThat(comToken.getStatus()).isEqualTo(403);
        assertThat((String) JsonPath.read(comToken.getContentAsString(), "$.type"))
                .isEqualTo(ACESSO_NEGADO);

        assertThat(disparar(post("/api/qualquer")).getStatus()).isEqualTo(401);
    }

    private void exigirRecusa(MockHttpServletResponse resposta, int status, String type)
            throws Exception {
        assertThat(resposta.getStatus()).isEqualTo(status);
        assertThat(resposta.getContentType()).startsWith("application/problem+json");
        assertThat((String) JsonPath.read(resposta.getContentAsString(), "$.type")).isEqualTo(type);
        Mockito.verify(lembretes, Mockito.never()).executar();
        Mockito.verify(sender, Mockito.never()).enviar(Mockito.any());
        assertThat(lembretesRegistrados()).isZero();
    }

    private MockHttpServletResponse disparar(MockHttpServletRequestBuilder requisicao) throws Exception {
        return mvc.perform(requisicao).andReturn().getResponse();
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }
}
