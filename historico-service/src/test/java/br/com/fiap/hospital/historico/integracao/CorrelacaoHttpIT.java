package br.com.fiap.hospital.historico.integracao;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Correlacao HTTP do historico (RNF-08).
 *
 * <p>Reutiliza a {@link GraphqlITBase}, cujo listener ja esta parado, sem configuracao local:
 * nenhum segundo consumidor da fila do historico nasce aqui (D11).
 */
@DisplayName("Correlação HTTP do histórico")
class CorrelacaoHttpIT extends GraphqlITBase {

    private static final String CABECALHO = "X-Correlation-Id";
    private static final String DOCUMENTO = "query { minhasConsultas { id } }";

    @Test
    @DisplayName("Scenario: Identificador recebido é honrado e devolvido")
    void identificadorRecebidoEHonrado() {
        String enviado = "http-honrado-" + UUID.randomUUID();

        ResponseEntity<String> resposta = chamar(HttpMethod.POST, "/graphql", tokenPaciente(pacienteA), enviado);

        assertThat(resposta.getStatusCode().value()).isEqualTo(200);
        assertThat(resposta.getHeaders().getFirst(CABECALHO)).isEqualTo(enviado);
    }

    @Test
    @DisplayName("Scenario: Identificador ausente é gerado")
    void identificadorAusenteEGerado() {
        ResponseEntity<String> primeira = chamar(HttpMethod.POST, "/graphql", tokenPaciente(pacienteA), null);
        ResponseEntity<String> segunda = chamar(HttpMethod.POST, "/graphql", tokenPaciente(pacienteA), null);

        assertThat(primeira.getStatusCode().value()).isEqualTo(200);
        assertThat(primeira.getHeaders().getFirst(CABECALHO)).isNotBlank();
        assertThat(segunda.getHeaders().getFirst(CABECALHO)).isNotBlank()
                .isNotEqualTo(primeira.getHeaders().getFirst(CABECALHO));
    }

    @Test
    @DisplayName("Scenario: Recusa de segurança carrega o mesmo identificador — 401")
    void recusa401CarregaOIdentificador() throws Exception {
        String enviado = "http-401-" + UUID.randomUUID();

        exigirRecusaCorrelacionada(chamar(HttpMethod.POST, "/graphql", null, enviado), 401, enviado);
    }

    @Test
    @DisplayName("Scenario: Recusa de segurança carrega o mesmo identificador — 403")
    void recusa403CarregaOIdentificador() throws Exception {
        String enviado = "http-403-" + UUID.randomUUID();

        ResponseEntity<String> resposta = chamar(HttpMethod.GET, "/qualquer-coisa", tokenMedico(), enviado);

        exigirRecusaCorrelacionada(resposta, 403, enviado);
    }

    private void exigirRecusaCorrelacionada(ResponseEntity<String> resposta, int status, String enviado)
            throws Exception {
        assertThat(resposta.getStatusCode().value()).isEqualTo(status);
        assertThat(resposta.getHeaders().getFirst(CABECALHO)).isEqualTo(enviado);
        assertThat(mapper.readTree(resposta.getBody()).path("correlationId").asText()).isEqualTo(enviado);
    }

    private ResponseEntity<String> chamar(HttpMethod metodo, String caminho, String token, String correlacao) {
        HttpHeaders cabecalhos = new HttpHeaders();
        cabecalhos.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) cabecalhos.setBearerAuth(token);
        if (correlacao != null) cabecalhos.set(CABECALHO, correlacao);
        String corpo = HttpMethod.POST.equals(metodo) ? json(Map.of("query", DOCUMENTO)) : null;
        return rest.exchange(caminho, metodo, new HttpEntity<>(corpo, cabecalhos), String.class);
    }

    private String json(Map<String, Object> valor) {
        try {
            return mapper.writeValueAsString(valor);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
