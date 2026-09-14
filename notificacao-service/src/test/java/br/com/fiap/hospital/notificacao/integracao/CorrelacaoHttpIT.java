package br.com.fiap.hospital.notificacao.integracao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Correlacao HTTP da notificacao (RNF-08).
 *
 * <p>Reutiliza a {@link NotificacaoITBase} exatamente como esta, com o listener canonico
 * ativo e sem configuracao local: o contexto e o mesmo das suites de consumo (D11).
 */
@DisplayName("Correlação HTTP da notificação")
class CorrelacaoHttpIT extends NotificacaoITBase {

    private static final String CABECALHO = "X-Correlation-Id";
    private static final String ENDPOINT = "/internal/lembretes/executar";

    @Test
    @DisplayName("Scenario: Identificador recebido é honrado e devolvido")
    void identificadorRecebidoEHonrado() throws Exception {
        String enviado = "http-honrado-" + UUID.randomUUID();

        var resposta = disparar(post(ENDPOINT).header(HttpHeaders.AUTHORIZATION, bearer(tokenDe("MEDICO")))
                .header(CABECALHO, enviado));

        assertThat(resposta.getStatus()).isEqualTo(200);
        assertThat(resposta.getHeader(CABECALHO)).isEqualTo(enviado);
    }

    @Test
    @DisplayName("Scenario: Identificador ausente é gerado")
    void identificadorAusenteEGerado() throws Exception {
        var primeira = disparar(post(ENDPOINT).header(HttpHeaders.AUTHORIZATION, bearer(tokenDe("MEDICO"))));
        var segunda = disparar(post(ENDPOINT).header(HttpHeaders.AUTHORIZATION, bearer(tokenDe("MEDICO"))));

        assertThat(primeira.getStatus()).isEqualTo(200);
        assertThat(primeira.getHeader(CABECALHO)).isNotBlank();
        assertThat(segunda.getHeader(CABECALHO)).isNotBlank().isNotEqualTo(primeira.getHeader(CABECALHO));
    }

    @Test
    @DisplayName("Scenario: Recusa de segurança carrega o mesmo identificador — 401")
    void recusa401CarregaOIdentificador() throws Exception {
        String enviado = "http-401-" + UUID.randomUUID();

        exigirRecusaCorrelacionada(disparar(post(ENDPOINT).header(CABECALHO, enviado)), 401, enviado);
    }

    @Test
    @DisplayName("Scenario: Recusa de segurança carrega o mesmo identificador — 403")
    void recusa403CarregaOIdentificador() throws Exception {
        String enviado = "http-403-" + UUID.randomUUID();

        var resposta = disparar(post(ENDPOINT).header(HttpHeaders.AUTHORIZATION, bearer(tokenDe("PACIENTE")))
                .header(CABECALHO, enviado));

        exigirRecusaCorrelacionada(resposta, 403, enviado);
    }

    private static void exigirRecusaCorrelacionada(MockHttpServletResponse resposta, int status, String enviado)
            throws Exception {
        assertThat(resposta.getStatus()).isEqualTo(status);
        assertThat(resposta.getHeader(CABECALHO)).isEqualTo(enviado);
        assertThat((String) JsonPath.read(resposta.getContentAsString(), "$.correlationId")).isEqualTo(enviado);
    }

    private MockHttpServletResponse disparar(MockHttpServletRequestBuilder requisicao) throws Exception {
        return mvc.perform(requisicao).andReturn().getResponse();
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }
}
