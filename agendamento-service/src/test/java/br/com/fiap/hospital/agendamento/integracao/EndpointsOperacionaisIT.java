package br.com.fiap.hospital.agendamento.integracao;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.fiap.hospital.security.JwtService;
import br.com.fiap.hospital.security.UsuarioAutenticado;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.endpoint.ExposableEndpoint;
import org.springframework.boot.actuate.endpoint.web.PathMappedEndpoints;
import org.springframework.boot.actuate.health.HealthContributorRegistry;
import org.springframework.boot.actuate.health.NamedContributor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Endpoints operacionais do agendamento: exposicao exata e protecao na cadeia compartilhada.
 *
 * <p>Mesma configuracao do {@code CadeiaDeSegurancaIT}, sem nada local (D11).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("Endpoints operacionais do agendamento")
class EndpointsOperacionaisIT {

    private static final String TIPO_NAO_AUTENTICADO = "https://hospital.fiap.br/erros/nao-autenticado";

    @DynamicPropertySource
    static void propriedades(DynamicPropertyRegistry registro) {
        ContainerPostgres.registrarPropriedades(registro);
    }

    @Autowired private TestRestTemplate rest;
    @Autowired private JwtService jwtService;
    @Autowired private ObjectMapper mapper;
    @Autowired private PathMappedEndpoints endpoints;
    @Autowired private HealthContributorRegistry contribuidores;

    @Test
    @DisplayName("Scenario: Saúde acessível sem credencial e sem detalhes")
    void saudeAcessivelSemCredencialESemDetalhes() throws Exception {
        ResponseEntity<String> resposta = chamar("/actuator/health", null);

        assertThat(resposta.getStatusCode().value()).isEqualTo(200);
        JsonNode corpo = mapper.readTree(resposta.getBody());
        assertThat(corpo.fieldNames()).toIterable().containsExactly("status");
        assertThat(corpo.path("status").asText()).isEqualTo("UP");
        // D5: o broker nao participa do health do agendamento (ADR-006); o PostgreSQL participa.
        assertThat(contribuidores.stream().map(NamedContributor::getName)).contains("db").doesNotContain("rabbit");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"/actuator", "/actuator/info", "/actuator/metrics", "/actuator/prometheus"})
    @DisplayName("Scenario: Informação e métricas exigem credencial")
    void informacaoEMetricasExigemCredencial(String caminho) throws Exception {
        for (String token : java.util.Arrays.asList(null, "nao-e-um-token")) {
            ResponseEntity<String> recusa = chamar(caminho, token);
            assertThat(recusa.getStatusCode().value()).as("%s com token %s", caminho, token).isEqualTo(401);
            assertThat(recusa.getHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)).isTrue();
            assertThat(mapper.readTree(recusa.getBody()).path("type").asText()).isEqualTo(TIPO_NAO_AUTENTICADO);
        }
        for (String perfil : List.of("PACIENTE", "MEDICO")) {
            assertThat(chamar(caminho, token(perfil)).getStatusCode().value())
                    .as("%s com token de %s", caminho, perfil).isEqualTo(200);
        }
    }

    @Test
    @DisplayName("Scenario: Métricas no formato Prometheus")
    void metricasNoFormatoPrometheus() {
        ResponseEntity<String> resposta = chamar("/actuator/prometheus", token("MEDICO"));

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
            assertThat(chamar(proibido, token("MEDICO")).getStatusCode().is2xxSuccessful())
                    .as("%s nao pode responder com sucesso nem com token valido", proibido).isFalse();
        }
    }

    private ResponseEntity<String> chamar(String caminho, String token) {
        HttpHeaders cabecalhos = new HttpHeaders();
        if (token != null) cabecalhos.setBearerAuth(token);
        return rest.exchange(caminho, HttpMethod.GET, new HttpEntity<>(cabecalhos), String.class);
    }

    private String token(String perfil) {
        return jwtService.emitir(new UsuarioAutenticado(UUID.randomUUID(), perfil.toLowerCase() + "@hospital.com",
                perfil, perfil.equals("PACIENTE") ? UUID.randomUUID() : null,
                perfil.equals("MEDICO") ? UUID.randomUUID() : null));
    }
}
