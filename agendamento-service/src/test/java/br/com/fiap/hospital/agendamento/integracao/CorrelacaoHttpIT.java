package br.com.fiap.hospital.agendamento.integracao;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.fiap.hospital.security.JwtService;
import br.com.fiap.hospital.security.UsuarioAutenticado;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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
 * Correlacao HTTP do agendamento, com a aplicacao de pe (RNF-08).
 *
 * <p>Mesma configuracao do {@code CadeiaDeSegurancaIT}, sem nada local: o contexto em cache e
 * reaproveitado (D11).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("Correlação HTTP do agendamento")
class CorrelacaoHttpIT {

    private static final String CABECALHO = "X-Correlation-Id";
    private static final String CONSULTAS = "/api/v1/consultas";

    @DynamicPropertySource
    static void propriedades(DynamicPropertyRegistry registro) {
        ContainerPostgres.registrarPropriedades(registro);
    }

    @Autowired private TestRestTemplate rest;
    @Autowired private JwtService jwtService;
    @Autowired private ObjectMapper mapper;

    @Test
    @DisplayName("Scenario: Identificador recebido é honrado e devolvido")
    void identificadorRecebidoEHonrado() {
        String enviado = "http-honrado-" + UUID.randomUUID();

        ResponseEntity<String> resposta = chamar(HttpMethod.GET, CONSULTAS, token("ENFERMEIRO"), enviado, null);

        assertThat(resposta.getStatusCode().value()).isEqualTo(200);
        assertThat(resposta.getHeaders().getFirst(CABECALHO)).isEqualTo(enviado);
    }

    @Test
    @DisplayName("Scenario: Identificador ausente é gerado")
    void identificadorAusenteEGerado() {
        ResponseEntity<String> primeira = chamar(HttpMethod.GET, CONSULTAS, token("ENFERMEIRO"), null, null);
        ResponseEntity<String> segunda = chamar(HttpMethod.GET, CONSULTAS, token("ENFERMEIRO"), null, null);

        assertThat(primeira.getStatusCode().value()).isEqualTo(200);
        assertThat(primeira.getHeaders().getFirst(CABECALHO)).isNotBlank();
        assertThat(segunda.getHeaders().getFirst(CABECALHO)).isNotBlank()
                .isNotEqualTo(primeira.getHeaders().getFirst(CABECALHO));
    }

    @Test
    @DisplayName("Scenario: Recusa de segurança carrega o mesmo identificador — 401")
    void recusa401CarregaOIdentificador() throws Exception {
        String enviado = "http-401-" + UUID.randomUUID();

        ResponseEntity<String> resposta = chamar(HttpMethod.GET, CONSULTAS, null, enviado, null);

        exigirRecusaCorrelacionada(resposta, 401, enviado);
    }

    @Test
    @DisplayName("Scenario: Recusa de segurança carrega o mesmo identificador — 403")
    void recusa403CarregaOIdentificador() throws Exception {
        String enviado = "http-403-" + UUID.randomUUID();
        String corpo = """
                {"pacienteId":"%s","medicoId":"%s","registradoPorId":"%s","dataHora":"%s"}
                """.formatted(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                OffsetDateTime.now().plusDays(30).withNano(0));

        ResponseEntity<String> resposta = chamar(HttpMethod.POST, CONSULTAS, token("PACIENTE"), enviado, corpo);

        exigirRecusaCorrelacionada(resposta, 403, enviado);
    }

    private void exigirRecusaCorrelacionada(ResponseEntity<String> resposta, int status, String enviado)
            throws Exception {
        assertThat(resposta.getStatusCode().value()).isEqualTo(status);
        assertThat(resposta.getHeaders().getFirst(CABECALHO)).isEqualTo(enviado);
        assertThat(mapper.readTree(resposta.getBody()).path("correlationId").asText()).isEqualTo(enviado);
    }

    private ResponseEntity<String> chamar(HttpMethod metodo, String caminho, String token, String correlacao,
                                          String corpo) {
        HttpHeaders cabecalhos = new HttpHeaders();
        cabecalhos.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) cabecalhos.setBearerAuth(token);
        if (correlacao != null) cabecalhos.set(CABECALHO, correlacao);
        return rest.exchange(caminho, metodo, new HttpEntity<>(corpo, cabecalhos), String.class);
    }

    private String token(String perfil) {
        return jwtService.emitir(new UsuarioAutenticado(UUID.randomUUID(), perfil.toLowerCase() + "@hospital.com",
                perfil, perfil.equals("PACIENTE") ? UUID.randomUUID() : null, null));
    }
}
