package br.com.fiap.hospital.agendamento.integracao;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** A especificacao OpenAPI e a interface de navegacao sobem com a aplicacao. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("Documentacao da API")
class ApiDocumentadaIT {

    @DynamicPropertySource
    static void propriedades(DynamicPropertyRegistry registro) {
        ContainerPostgres.registrarPropriedades(registro);
    }

    @Autowired private TestRestTemplate rest;

    @LocalServerPort private int porta;

    @Test
    @DisplayName("Scenario: Especificacao disponivel")
    void especificacaoDisponivel() {
        ResponseEntity<String> resposta = rest.getForEntity("/v3/api-docs", String.class);

        assertThat(resposta.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(resposta.getBody())
                .as("todos os endpoints de consulta precisam aparecer na especificacao")
                .contains("/api/v1/consultas")
                .contains("/api/v1/consultas/{id}")
                .contains("/api/v1/consultas/{id}/confirmar")
                .contains("/api/v1/consultas/{id}/cancelar")
                .contains("Agendamento de consultas");
    }

    @Test
    @DisplayName("Scenario: Interface de navegacao disponivel")
    void interfaceDeNavegacaoDisponivel() {
        ResponseEntity<String> resposta = rest.getForEntity("/swagger-ui/index.html", String.class);

        assertThat(resposta.getStatusCode().is2xxSuccessful()).isTrue();
    }

    @Test
    @DisplayName("a semantica de preservacao do PUT esta documentada")
    void semanticaDoPutDocumentada() {
        String spec = rest.getForObject("/v3/api-docs", String.class);

        assertThat(spec)
                .as("quem le a especificacao precisa saber que campo ausente preserva")
                .contains("PRESERVA o valor atual");
    }

    @Test
    @DisplayName("o teto de pagina esta documentado na listagem")
    void tetoDePaginaDocumentado() {
        String spec = rest.getForObject("/v3/api-docs", String.class);

        assertThat(spec).contains("teto de 100");
    }
}
