package br.com.fiap.hospital.historico.integracao;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.fiap.hospital.security.CaminhosDeSeguranca;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpMethod;
import org.springframework.security.web.SecurityFilterChain;

/**
 * O endpoint entra na cadeia compartilhada, e nada alem dele.
 *
 * <p>A tentacao seria declarar uma cadeia propria no historico. O resultado seriam duas
 * cadeias ordenadas e a pergunta "qual delas atende /graphql?" viraria detalhe de
 * ordenacao — o tipo de configuracao que funciona ate alguem mexer na ordem.
 */
@DisplayName("Seguranca do endpoint GraphQL")
class SegurancaGraphqlIT extends GraphqlITBase {

    @Autowired ApplicationContext contexto;
    @Autowired CaminhosDeSeguranca caminhos;

    @Test
    @DisplayName("existe exatamente uma SecurityFilterChain")
    void existeExatamenteUmaCadeia() {
        assertThat(contexto.getBeansOfType(SecurityFilterChain.class))
                .as("cadeia concorrente faria a ordem decidir quem atende /graphql")
                .hasSize(1);
    }

    @Test
    @DisplayName("/graphql exige token em qualquer profile")
    void graphqlExigeToken() {
        assertThat(caminhos.autenticados()).contains("/graphql");
        assertThat(postar(null, "query { minhasConsultas { id } }", null)
                .getStatusCode().value()).isEqualTo(401);
        assertThat(executar(tokenPaciente(pacienteA), "query { minhasConsultas { id } }")
                .temErro()).isFalse();
    }

    /**
     * Scenario: Caminhos nao relacionados continuam negados.
     *
     * <p>Admitir {@code /graphql} nao pode ter transformado o {@code denyAll} em
     * "autenticado". Se tivesse, um caminho que ninguem liberou passaria a responder para
     * qualquer usuario logado — e so se descobriria quando alguem criasse esse caminho.
     */
    @Test
    @DisplayName("Scenario: Caminhos nao relacionados continuam negados")
    void caminhosNaoRelacionadosContinuamNegados() {
        for (String caminho : new String[] {"/atalho", "/api-interna/dados", "/graphiql"}) {
            var comToken = rest.exchange(caminho, HttpMethod.GET,
                    new org.springframework.http.HttpEntity<>(cabecalhosCom(tokenMedico())),
                    String.class);

            // 403 exato, e nao "401 ou 403 ou 404". Cada um desses codigos conta uma
            // historia diferente: 401 seria falta de credencial, 404 seria caminho
            // inexistente. Aceitar o conjunto deixaria passar uma cadeia que trocou o
            // denyAll por authenticated e agora responde 404 do proprio MVC — que e o
            // defeito que este teste existe para pegar. Com token valido, a unica recusa
            // compativel com denyAll e acesso negado.
            assertThat(comToken.getStatusCode().value())
                    .as("%s nao foi liberado por ninguem: denyAll recusa por autorizacao", caminho)
                    .isEqualTo(403);
        }
    }

    @Test
    @DisplayName("no profile padrao a lista publica adicional esta vazia")
    void profilePadraoNaoLiberaNada() {
        assertThat(caminhos.publicosAdicionais())
                .as("quem abre caminho e o profile, e o padrao nao abre nenhum")
                .isEmpty();
    }

    @Test
    @DisplayName("o historico nao expoe login proprio")
    void historicoNaoExpoeLoginProprio() {
        var resposta = rest.postForEntity("/auth/login",
                Map.of("email", "medico@hospital.com", "senha", "Senha@123"), String.class);

        assertThat(resposta.getStatusCode().value())
                .as("emitir token aqui criaria um segundo emissor para a mesma identidade")
                .isIn(401, 403, 404, 405);
    }

    private org.springframework.http.HttpHeaders cabecalhosCom(String token) {
        var cabecalhos = new org.springframework.http.HttpHeaders();
        cabecalhos.setBearerAuth(token);
        return cabecalhos;
    }
}
