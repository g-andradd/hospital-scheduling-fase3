package br.com.fiap.hospital.agendamento.integracao;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.fiap.hospital.agendamento.infrastructure.persistence.repository.UsuarioJpaRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Com o perfil demo, os quatro usuarios da §5 existem e autenticam de verdade.
 *
 * <p>O par deste teste e {@link SemSeedDemoIT}, que sobe sem o perfil e exige o oposto.
 * Um sozinho nao serve: este provaria que o seed carrega sem provar que ele fica fora de
 * producao, e o outro provaria a ausencia sem provar que o seed funciona.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("demo")
@DisplayName("Seed de demonstracao, com o perfil demo")
class SeedDemoIT {

    @DynamicPropertySource
    static void propriedades(DynamicPropertyRegistry registro) {
        ContainerPostgres.registrarPropriedadesEmEsquema(registro, "seed_com_demo");
    }

    /** A senha em claro vive so aqui e em docs/02-especificacao-funcional.md §5. */
    private static final String SENHA = "Senha@123";

    @Autowired private TestRestTemplate rest;
    @Autowired private UsuarioJpaRepository usuarioJpa;

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"medico@hospital.com", "enfermeiro@hospital.com",
            "paciente@hospital.com", "paciente2@hospital.com"})
    @DisplayName("cada usuario de demonstracao existe no banco")
    void usuarioDeDemonstracaoExiste(String email) {
        assertThat(usuarioJpa.findByEmail(email))
                .as("o seed de db/demo deveria ter carregado %s", email)
                .isPresent();
    }

    /**
     * Existir no banco nao basta: o hash precisa ser o da senha documentada.
     *
     * <p>Um hash invalido — ou um placeholder que alguem colou sem gerar — deixaria as
     * linhas no banco e o login recusando, e o teste de existencia continuaria verde.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"medico@hospital.com", "enfermeiro@hospital.com",
            "paciente@hospital.com", "paciente2@hospital.com"})
    @DisplayName("Scenario: Ambiente de demonstracao tem os usuarios")
    void usuarioDeDemonstracaoAutentica(String email) {
        ResponseEntity<String> resposta = login(email, SENHA);

        assertThat(resposta.getStatusCode().value())
                .as("login de %s respondeu %s com corpo %s", email,
                        resposta.getStatusCode(), resposta.getBody())
                .isEqualTo(200);
        assertThat(resposta.getBody()).contains("accessToken", "expiresIn", "perfil");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"medico@hospital.com", "paciente@hospital.com"})
    @DisplayName("a senha errada continua sendo recusada, mesmo para usuario do seed")
    void senhaErradaERecusada(String email) {
        assertThat(login(email, "Senha@124").getStatusCode().value()).isEqualTo(401);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"medico@hospital.com", "paciente@hospital.com"})
    @DisplayName("nem a resposta nem o token trazem a senha ou o hash")
    void respostaNaoTrazSenhaNemHash(String email) {
        String corpo = login(email, SENHA).getBody();

        assertThat(corpo)
                .doesNotContain(SENHA)
                .doesNotContain("$2a$")
                .doesNotContain("senhaHash", "senha_hash");
    }

    private ResponseEntity<String> login(String email, String senha) {
        HttpHeaders cabecalhos = new HttpHeaders();
        cabecalhos.setContentType(MediaType.APPLICATION_JSON);
        String corpo = "{\"email\":\"%s\",\"senha\":\"%s\"}".formatted(email, senha);

        return rest.postForEntity("/auth/login", new HttpEntity<>(corpo, cabecalhos),
                String.class);
    }
}
