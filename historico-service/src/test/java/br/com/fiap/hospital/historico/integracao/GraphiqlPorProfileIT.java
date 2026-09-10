package br.com.fiap.hospital.historico.integracao;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.fiap.hospital.security.CaminhosDeSeguranca;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * A interface interativa em quatro estados, porque duas condicoes independentes a governam.
 *
 * <p>Habilitar a interface e liberar o caminho sao coisas diferentes. Habilitada mas nao
 * liberada, a pagina fica atras do {@code denyAll} e nao carrega; liberada mas nao
 * habilitada, existe um caminho aberto que nao serve para nada. So o par completo entrega
 * a ferramenta em dev e demo — e so a ausencia do par mantem o profile padrao fechado.
 *
 * <p>A ausencia precisa ser testada. "Desabilitado" sem verificacao e intencao, nao
 * garantia — e e justamente o estado em que o servico roda em producao.
 */
@DisplayName("GraphiQL por profile")
class GraphiqlPorProfileIT {

    private static final String CAMINHO = "/graphiql";

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
            properties = {"logging.level.root=ERROR",
                    // Mesma razao de GraphqlITBase: nao competir pela fila compartilhada.
                    "spring.rabbitmq.listener.simple.auto-startup=false"})
    @DisplayName("profile padrao")
    class ProfilePadrao {

        @DynamicPropertySource
        static void propriedades(DynamicPropertyRegistry registro) {
            ContainersDoHistorico.registrar(registro);
        }

        @Autowired TestRestTemplate rest;
        @Autowired CaminhosDeSeguranca caminhos;
        @Autowired(required = false)
        org.springframework.boot.autoconfigure.graphql.GraphQlProperties graphql;

        @Test
        @DisplayName("Scenario: Interface interativa existe apenas em dev e demo (ausencia)")
        void interfaceDesabilitadaENaoLiberada() {
            assertThat(graphql.getGraphiql().isEnabled())
                    .as("a interface nao pode estar montada fora de dev e demo")
                    .isFalse();
            assertThat(caminhos.publicosAdicionais())
                    .as("e o caminho nao pode estar aberto")
                    .isEmpty();
            assertThat(rest.getForEntity(CAMINHO, String.class).getStatusCode().value())
                    .as("sem as duas condicoes, o caminho cai no denyAll")
                    .isIn(401, 403, 404);
        }
    }

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
            properties = {"logging.level.root=ERROR",
                    // Mesma razao de GraphqlITBase: nao competir pela fila compartilhada.
                    "spring.rabbitmq.listener.simple.auto-startup=false"})
    @ActiveProfiles("dev")
    @DisplayName("profile dev")
    class ProfileDev {

        @DynamicPropertySource
        static void propriedades(DynamicPropertyRegistry registro) {
            ContainersDoHistorico.registrar(registro);
        }

        @Autowired TestRestTemplate rest;
        @Autowired CaminhosDeSeguranca caminhos;
        @Autowired org.springframework.boot.autoconfigure.graphql.GraphQlProperties graphql;

        @Test
        @DisplayName("Scenario: Interface interativa existe apenas em dev e demo (dev)")
        void interfaceHabilitadaEAlcancavelEmDev() {
            assertThat(graphql.getGraphiql().isEnabled()).isTrue();
            assertThat(caminhos.publicosAdicionais()).contains("/graphiql");
            assertThat(rest.getForEntity(CAMINHO, String.class).getStatusCode().value())
                    .as("habilitada e liberada, a pagina responde sem token")
                    .isEqualTo(200);
        }
    }

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
            properties = {"logging.level.root=ERROR",
                    // Mesma razao de GraphqlITBase: nao competir pela fila compartilhada.
                    "spring.rabbitmq.listener.simple.auto-startup=false"})
    @ActiveProfiles("demo")
    @DisplayName("profile demo")
    class ProfileDemo {

        @DynamicPropertySource
        static void propriedades(DynamicPropertyRegistry registro) {
            ContainersDoHistorico.registrar(registro);
        }

        @Autowired TestRestTemplate rest;
        @Autowired CaminhosDeSeguranca caminhos;
        @Autowired org.springframework.boot.autoconfigure.graphql.GraphQlProperties graphql;

        @Test
        @DisplayName("Scenario: Interface interativa existe apenas em dev e demo (demo)")
        void interfaceHabilitadaEAlcancavelEmDemo() {
            assertThat(graphql.getGraphiql().isEnabled()).isTrue();
            assertThat(caminhos.publicosAdicionais()).contains("/graphiql");
            assertThat(rest.getForEntity(CAMINHO, String.class).getStatusCode().value())
                    .isEqualTo(200);
        }
    }
}
