package br.com.fiap.hospital.historico.integracao;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.endpoint.ExposableEndpoint;
import org.springframework.boot.actuate.endpoint.web.PathMappedEndpoints;
import org.springframework.boot.actuate.health.HealthContributorRegistry;
import org.springframework.boot.actuate.health.NamedContributor;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Endpoints operacionais do historico: exposicao exata e protecao na cadeia compartilhada.
 *
 * <p>Reutiliza a {@link GraphqlITBase}, cujo listener ja esta parado, sem configuracao local
 * (D11).
 */
@DisplayName("Endpoints operacionais do histórico")
class EndpointsOperacionaisIT extends GraphqlITBase {

    private static final String TIPO_NAO_AUTENTICADO = "https://hospital.fiap.br/erros/nao-autenticado";

    @Autowired PathMappedEndpoints endpoints;
    @Autowired HealthContributorRegistry contribuidores;

    @Test
    @DisplayName("Scenario: Saúde acessível sem credencial e sem detalhes")
    void saudeAcessivelSemCredencialESemDetalhes() throws Exception {
        ResponseEntity<String> resposta = chamar("/actuator/health", null);

        assertThat(resposta.getStatusCode().value()).isEqualTo(200);
        JsonNode corpo = mapper.readTree(resposta.getBody());
        assertThat(corpo.fieldNames()).toIterable().containsExactly("status");
        assertThat(corpo.path("status").asText()).isEqualTo("UP");
        // D5: nenhum indicador desabilitado no historico.
        assertThat(contribuidores.stream().map(NamedContributor::getName)).contains("db", "rabbit");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"/actuator", "/actuator/info", "/actuator/metrics", "/actuator/prometheus"})
    @DisplayName("Scenario: Informação e métricas exigem credencial")
    void informacaoEMetricasExigemCredencial(String caminho) throws Exception {
        for (String token : Arrays.asList(null, "nao-e-um-token")) {
            ResponseEntity<String> recusa = chamar(caminho, token);
            assertThat(recusa.getStatusCode().value()).as("%s com token %s", caminho, token).isEqualTo(401);
            assertThat(recusa.getHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)).isTrue();
            assertThat(mapper.readTree(recusa.getBody()).path("type").asText()).isEqualTo(TIPO_NAO_AUTENTICADO);
        }
        assertThat(chamar(caminho, tokenPaciente(pacienteA)).getStatusCode().value())
                .as("%s com token de PACIENTE", caminho).isEqualTo(200);
        assertThat(chamar(caminho, tokenMedico()).getStatusCode().value())
                .as("%s com token de MEDICO", caminho).isEqualTo(200);
    }

    @Test
    @DisplayName("Scenario: Métricas no formato Prometheus")
    void metricasNoFormatoPrometheus() {
        ResponseEntity<String> resposta = chamar("/actuator/prometheus", tokenMedico());

        assertThat(resposta.getStatusCode().value()).isEqualTo(200);
        assertThat(resposta.getHeaders().getContentType()).isNotNull();
        assertThat(resposta.getHeaders().getContentType().toString()).startsWith("text/plain");
        assertThat(resposta.getBody()).contains("# TYPE jvm_");
    }

    @Test
    @DisplayName("Scenario: Exposição é exatamente a permitida")
    void exposicaoEExatamenteAPermitida() {
        assertThat(endpoints.stream().map(endpoint -> ((ExposableEndpoint<?>) endpoint).getEndpointId().toString()))
                .containsExactlyInAnyOrder("health", "info", "metrics", "prometheus");
        for (String proibido : List.of("/actuator/env", "/actuator/beans")) {
            assertThat(chamar(proibido, tokenMedico()).getStatusCode().is2xxSuccessful())
                    .as("%s nao pode responder com sucesso nem com token valido", proibido).isFalse();
        }
    }

    private ResponseEntity<String> chamar(String caminho, String token) {
        HttpHeaders cabecalhos = new HttpHeaders();
        if (token != null) cabecalhos.setBearerAuth(token);
        return rest.exchange(caminho, HttpMethod.GET, new HttpEntity<>(cabecalhos), String.class);
    }
}
