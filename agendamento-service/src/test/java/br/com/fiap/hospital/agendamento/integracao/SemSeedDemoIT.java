package br.com.fiap.hospital.agendamento.integracao;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.fiap.hospital.agendamento.infrastructure.persistence.repository.UsuarioJpaRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Sem o perfil demo, os usuarios de demonstracao nao existem em lugar nenhum.
 *
 * <p>E a metade que importa do par com {@link SeedDemoIT}. Credencial conhecida
 * publicamente que sobrevive fora do ambiente de demonstracao e uma porta dos fundos com
 * senha documentada — e o modo de falha e silencioso: tudo funciona, e o sistema aceita
 * <code>medico@hospital.com</code> em producao.
 *
 * <p>A protecao nao e uma condicional no codigo de seed: e o Flyway nao enxergar o
 * diretorio. So <code>application-demo.yml</code> acrescenta <code>classpath:db/demo</code>
 * a <code>spring.flyway.locations</code>, entao fora do perfil nao ha caminho por onde as
 * linhas entrem — nao existe <code>if</code> que alguem possa errar.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("Seed de demonstracao, sem o perfil demo")
class SemSeedDemoIT {

    @DynamicPropertySource
    static void propriedades(DynamicPropertyRegistry registro) {
        ContainerPostgres.registrarPropriedadesEmEsquema(registro, "seed_sem_demo");
    }

    @Autowired private TestRestTemplate rest;
    @Autowired private UsuarioJpaRepository usuarioJpa;

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"medico@hospital.com", "enfermeiro@hospital.com",
            "paciente@hospital.com", "paciente2@hospital.com"})
    @DisplayName("nenhum usuario de demonstracao existe no banco")
    void usuarioDeDemonstracaoNaoExiste(String email) {
        assertThat(usuarioJpa.findByEmail(email))
                .as("%s so pode existir sob o perfil demo", email)
                .isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"medico@hospital.com", "enfermeiro@hospital.com",
            "paciente@hospital.com", "paciente2@hospital.com"})
    @DisplayName("Scenario: Ambiente padrao nao tem os usuarios")
    void credencialDeDemonstracaoERecusada(String email) {
        HttpHeaders cabecalhos = new HttpHeaders();
        cabecalhos.setContentType(MediaType.APPLICATION_JSON);
        String corpo = "{\"email\":\"%s\",\"senha\":\"Senha@123\"}".formatted(email);

        ResponseEntity<String> resposta = rest.postForEntity(
                "/auth/login", new HttpEntity<>(corpo, cabecalhos), String.class);

        assertThat(resposta.getStatusCode().value())
                .as("credencial publicamente documentada nao pode autenticar fora da demo")
                .isEqualTo(401);
    }

    /**
     * Nenhum usuario, e nao apenas nenhum usuario de demonstracao.
     *
     * <p>Este esquema e exclusivo desta classe, entao a contagem so pode subir se o seed
     * tiver rodado. Sem o isolamento, o Flyway do {@link SeedDemoIT} deixaria as linhas
     * no banco compartilhado e este teste passaria ou falharia conforme a ordem de
     * execucao — que ninguem controla.
     */
    @Test
    @DisplayName("o banco nao recebeu usuario algum")
    void bancoNaoRecebeuUsuarioAlgum() {
        assertThat(usuarioJpa.count())
                .as("sem o perfil demo, db/demo nao entra em spring.flyway.locations e "
                        + "nao ha caminho por onde as linhas do seed entrem")
                .isZero();
    }
}
