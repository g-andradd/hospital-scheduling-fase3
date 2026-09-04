package br.com.fiap.hospital.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;

/**
 * As recusas de seguranca no mesmo contrato de erro do resto da API.
 *
 * <p>Elas nascem antes de o tratador global existir na requisicao, entao sao o unico
 * ponto da API onde o formato do erro poderia divergir sem ninguem notar.
 */
@DisplayName("RespostaDeSeguranca")
class RespostaDeSegurancaTest {

    private static final Instant AGORA = Instant.parse("2026-09-04T12:00:00Z");

    private final ObjectMapper mapper = new ObjectMapper();
    private final RespostaDeSeguranca respostas =
            new RespostaDeSeguranca(mapper, Clock.fixed(AGORA, ZoneOffset.UTC));

    private static MockHttpServletRequest requisicao() {
        MockHttpServletRequest requisicao =
                new MockHttpServletRequest("GET", "/api/v1/consultas/42");
        requisicao.setRequestURI("/api/v1/consultas/42");
        return requisicao;
    }

    private JsonNode corpoDe(MockHttpServletResponse resposta) throws Exception {
        return mapper.readTree(resposta.getContentAsString());
    }

    @Test
    @DisplayName("Scenario: Recusa por falta de autenticacao")
    void semTokenRecebe401() throws Exception {
        MockHttpServletResponse resposta = new MockHttpServletResponse();

        respostas.commence(requisicao(), resposta, new BadCredentialsException("qualquer"));

        assertThat(resposta.getStatus()).isEqualTo(401);
        assertThat(resposta.getContentType()).startsWith("application/problem+json");

        JsonNode corpo = corpoDe(resposta);
        assertThat(corpo.get("type").asText())
                .isEqualTo(RespostaDeSeguranca.TYPE_NAO_AUTENTICADO);
        assertThat(corpo.get("status").asInt()).isEqualTo(401);
        assertThat(corpo.get("instance").asText()).isEqualTo("/api/v1/consultas/42");
        assertThat(corpo.hasNonNull("correlationId")).isTrue();
        assertThat(corpo.get("timestamp").asText()).startsWith("2026-09-04T12:00");
    }

    @Test
    @DisplayName("Scenario: Recusa por falta de permissao")
    void semPermissaoRecebe403() throws Exception {
        MockHttpServletResponse resposta = new MockHttpServletResponse();

        respostas.handle(requisicao(), resposta, new AccessDeniedException("qualquer"));

        assertThat(resposta.getStatus()).isEqualTo(403);

        JsonNode corpo = corpoDe(resposta);
        assertThat(corpo.get("type").asText()).isEqualTo(RespostaDeSeguranca.TYPE_ACESSO_NEGADO);
        assertThat(corpo.get("status").asInt()).isEqualTo(403);
    }

    @Test
    @DisplayName("401 e 403 tem type distinto — o cliente consegue diferenciar sem ler o texto")
    void tiposSaoDistintos() {
        assertThat(RespostaDeSeguranca.TYPE_NAO_AUTENTICADO)
                .isNotEqualTo(RespostaDeSeguranca.TYPE_ACESSO_NEGADO);
    }

    @Test
    @DisplayName("o correlationId do filtro e reaproveitado quando existe")
    void correlationIdDoFiltroEReaproveitado() throws Exception {
        MockHttpServletRequest requisicao = requisicao();
        requisicao.setAttribute("correlationId", "abc-123");
        MockHttpServletResponse resposta = new MockHttpServletResponse();

        respostas.commence(requisicao, resposta, new BadCredentialsException("x"));

        assertThat(corpoDe(resposta).get("correlationId").asText())
                .as("sem isso, o 401 seria o unico erro da API impossivel de correlacionar "
                        + "com a linha de log da mesma requisicao")
                .isEqualTo("abc-123");
    }

    @Test
    @DisplayName("o X-Correlation-Id do cliente e honrado quando o filtro nao passou")
    void cabecalhoDoClienteEHonrado() throws Exception {
        MockHttpServletRequest requisicao = requisicao();
        requisicao.addHeader("X-Correlation-Id", "vindo-do-cliente");
        MockHttpServletResponse resposta = new MockHttpServletResponse();

        respostas.commence(requisicao, resposta, new BadCredentialsException("x"));

        assertThat(corpoDe(resposta).get("correlationId").asText())
                .as("a recusa pode acontecer antes do filtro de correlacao; o cabecalho "
                        + "do cliente e a unica pista restante")
                .isEqualTo("vindo-do-cliente");
    }

    @Test
    @DisplayName("cabecalho de correlacao em branco nao vira correlationId vazio")
    void cabecalhoEmBrancoNaoViraIdVazio() throws Exception {
        MockHttpServletRequest requisicao = requisicao();
        requisicao.addHeader("X-Correlation-Id", "   ");
        MockHttpServletResponse resposta = new MockHttpServletResponse();

        respostas.commence(requisicao, resposta, new BadCredentialsException("x"));

        assertThat(corpoDe(resposta).get("correlationId").asText()).isNotBlank();
    }

    @Test
    @DisplayName("sem correlationId algum, um e gerado — o campo nunca falta")
    void correlationIdEGeradoQuandoNaoHa() throws Exception {
        MockHttpServletResponse resposta = new MockHttpServletResponse();

        respostas.commence(requisicao(), resposta, new BadCredentialsException("x"));

        assertThat(corpoDe(resposta).get("correlationId").asText()).isNotBlank();
    }

    /**
     * O detalhe nao pode contar por que a credencial falhou.
     *
     * <p>"Token expirado" informa que o token ja foi valido; "usuario nao encontrado"
     * transforma o login em oraculo de e-mails cadastrados. O texto e sempre o mesmo.
     */
    @Test
    @DisplayName("Scenario: A recusa nao revela o motivo")
    void recusaNaoRevelaOMotivo() throws Exception {
        MockHttpServletResponse expirado = new MockHttpServletResponse();
        MockHttpServletResponse ausente = new MockHttpServletResponse();

        respostas.commence(requisicao(), expirado,
                new BadCredentialsException("JWT expired at 2026-01-01"));
        respostas.commence(requisicao(), ausente,
                new BadCredentialsException("no credentials"));

        assertThat(corpoDe(expirado).get("detail").asText())
                .isEqualTo(corpoDe(ausente).get("detail").asText());
    }

    @Test
    @DisplayName("Scenario: Nenhuma recusa expoe detalhe interno")
    void recusaNaoVazaInterno() throws Exception {
        MockHttpServletResponse naoAutenticado = new MockHttpServletResponse();
        MockHttpServletResponse negado = new MockHttpServletResponse();

        respostas.commence(requisicao(), naoAutenticado,
                new BadCredentialsException("io.jsonwebtoken.security.SignatureException: bad"));
        respostas.handle(requisicao(), negado,
                new AccessDeniedException("org.springframework.security.access.Denied"));

        for (MockHttpServletResponse resposta : java.util.List.of(naoAutenticado, negado)) {
            assertThat(resposta.getContentAsString())
                    .doesNotContain("org.springframework", "io.jsonwebtoken", "java.lang.",
                            "Exception", "\tat ", "SignatureException");
        }
    }
}
