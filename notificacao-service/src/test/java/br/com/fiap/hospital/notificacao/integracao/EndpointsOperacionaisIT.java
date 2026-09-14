package br.com.fiap.hospital.notificacao.integracao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.endpoint.ExposableEndpoint;
import org.springframework.boot.actuate.endpoint.web.PathMappedEndpoints;
import org.springframework.boot.actuate.health.HealthContributorRegistry;
import org.springframework.boot.actuate.health.NamedContributor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Endpoints operacionais da notificacao: exposicao exata e protecao na cadeia compartilhada.
 *
 * <p>Reutiliza a {@link NotificacaoITBase} exatamente como esta, com o listener canonico ativo
 * e sem configuracao local (D11).
 */
@DisplayName("Endpoints operacionais da notificação")
class EndpointsOperacionaisIT extends NotificacaoITBase {

    private static final String TIPO_NAO_AUTENTICADO = "https://hospital.fiap.br/erros/nao-autenticado";

    @Autowired PathMappedEndpoints endpoints;
    @Autowired HealthContributorRegistry contribuidores;

    @Test
    @DisplayName("Scenario: Saúde acessível sem credencial e sem detalhes")
    void saudeAcessivelSemCredencialESemDetalhes() throws Exception {
        var resposta = chamar(get("/actuator/health"));

        assertThat(resposta.getStatus()).isEqualTo(200);
        Map<String, Object> corpo = JsonPath.read(resposta.getContentAsString(), "$");
        assertThat(corpo).containsOnlyKeys("status").containsEntry("status", "UP");
        // D5: sem SMTP ativo, o mail nao participa; PostgreSQL e RabbitMQ participam.
        assertThat(contribuidores.stream().map(NamedContributor::getName)).contains("db", "rabbit").doesNotContain("mail");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"/actuator", "/actuator/info", "/actuator/metrics", "/actuator/prometheus"})
    @DisplayName("Scenario: Informação e métricas exigem credencial")
    void informacaoEMetricasExigemCredencial(String caminho) throws Exception {
        for (var requisicao : List.of(get(caminho),
                get(caminho).header(HttpHeaders.AUTHORIZATION, "Bearer nao-e-um-token"))) {
            var recusa = chamar(requisicao);
            assertThat(recusa.getStatus()).as(caminho).isEqualTo(401);
            assertThat(MediaType.parseMediaType(recusa.getContentType()).isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .isTrue();
            assertThat((String) JsonPath.read(recusa.getContentAsString(), "$.type")).isEqualTo(TIPO_NAO_AUTENTICADO);
        }
        for (String perfil : List.of("PACIENTE", "MEDICO")) {
            assertThat(chamar(get(caminho).header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenDe(perfil))).getStatus())
                    .as("%s com token de %s", caminho, perfil).isEqualTo(200);
        }
    }

    @Test
    @DisplayName("Scenario: Métricas no formato Prometheus")
    void metricasNoFormatoPrometheus() throws Exception {
        var resposta = chamar(get("/actuator/prometheus").header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenDe("MEDICO")));

        assertThat(resposta.getStatus()).isEqualTo(200);
        assertThat(resposta.getContentType()).startsWith("text/plain");
        assertThat(resposta.getContentAsString()).contains("# TYPE jvm_");
    }

    @Test
    @DisplayName("Scenario: Exposição é exatamente a permitida")
    void exposicaoEExatamenteAPermitida() throws Exception {
        assertThat(endpoints.stream().map(endpoint -> ((ExposableEndpoint<?>) endpoint).getEndpointId().toString()))
                .containsExactlyInAnyOrder("health", "info", "metrics", "prometheus");
        for (String proibido : List.of("/actuator/env", "/actuator/beans")) {
            int status = chamar(get(proibido).header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenDe("MEDICO"))).getStatus();
            assertThat(status >= 200 && status < 300)
                    .as("%s nao pode responder com sucesso nem com token valido, respondeu %s", proibido, status)
                    .isFalse();
        }
    }

    private MockHttpServletResponse chamar(MockHttpServletRequestBuilder requisicao) throws Exception {
        return mvc.perform(requisicao).andReturn().getResponse();
    }
}
