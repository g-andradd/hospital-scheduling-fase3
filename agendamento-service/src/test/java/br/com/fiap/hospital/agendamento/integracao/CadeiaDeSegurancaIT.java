package br.com.fiap.hospital.agendamento.integracao;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * As duas propriedades da cadeia que so aparecem com a aplicacao de pe.
 *
 * <p>Que os caminhos publicos continuem publicos, e que nenhuma sessao seja criada. As
 * duas sao invisiveis em teste de unidade e caras de descobrir em producao: a primeira
 * quebra o acesso da banca a documentacao, a segunda so se manifesta quando o servico
 * escala para mais de uma instancia.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("Cadeia de seguranca")
class CadeiaDeSegurancaIT {

    @DynamicPropertySource
    static void propriedades(DynamicPropertyRegistry registro) {
        ContainerPostgres.registrarPropriedades(registro);
    }

    @Autowired private TestRestTemplate rest;

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
        "/actuator/health",
        "/v3/api-docs",
        "/swagger-ui/index.html"})
    @DisplayName("Scenario: Recursos publicos permanecem acessiveis")
    void recursosPublicosPermanecemAcessiveis(String caminho) {
        ResponseEntity<String> resposta = rest.getForEntity(caminho, String.class);

        assertThat(resposta.getStatusCode().value())
                .as("%s respondeu %s — sem este caminho aberto, a banca nao alcanca a "
                        + "documentacao nem o health check", caminho, resposta.getStatusCode())
                .isNotIn(401, 403);
    }

    @Test
    @DisplayName("o login e alcancavel sem credencial — senao nao haveria como obter uma")
    void loginEAlcancavelSemCredencial() {
        ResponseEntity<String> resposta = rest.postForEntity(
                "/auth/login", entidadeJson("{\"email\":\"x@y.com\",\"senha\":\"errada\"}"),
                String.class);

        assertThat(resposta.getStatusCode().value())
                .as("401 aqui e a recusa da credencial, e nao da cadeia — o que importa e "
                        + "que a requisicao chegou ao endpoint")
                .isEqualTo(401);
        assertThat(resposta.getBody())
                .as("a resposta veio do endpoint de login, no contrato de erro da API")
                .contains("nao-autenticado");
    }

    /**
     * Nenhum caminho fora do que foi liberado responde.
     *
     * <p>E o {@code denyAll} da cadeia: rota que ninguem declarou fica inacessivel. Sem
     * ele, um endpoint acrescentado fora de {@code /api} nasceria aberto.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"/", "/admin", "/actuator/env", "/actuator/beans", "/qualquer-coisa"})
    @DisplayName("caminho nao declarado e negado por padrao")
    void caminhoNaoDeclaradoENegado(String caminho) {
        assertThat(rest.getForEntity(caminho, String.class).getStatusCode().value())
                .as("%s deveria ser inacessivel", caminho)
                .isIn(401, 403);
    }

    @Test
    @DisplayName("Scenario: Nenhuma sessao e criada")
    void nenhumaSessaoECriada() {
        ResponseEntity<String> semToken = rest.getForEntity("/api/v1/consultas", String.class);
        ResponseEntity<String> publico = rest.getForEntity("/actuator/health", String.class);

        for (ResponseEntity<String> resposta : java.util.List.of(semToken, publico)) {
            assertThat(resposta.getHeaders().get("Set-Cookie"))
                    .as("cookie de sessao obrigaria sticky session ou store compartilhado "
                            + "assim que houver mais de uma instancia")
                    .isNullOrEmpty();
        }
    }

    private static org.springframework.http.HttpEntity<String> entidadeJson(String corpo) {
        org.springframework.http.HttpHeaders cabecalhos =
                new org.springframework.http.HttpHeaders();
        cabecalhos.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        return new org.springframework.http.HttpEntity<>(corpo, cabecalhos);
    }
}
