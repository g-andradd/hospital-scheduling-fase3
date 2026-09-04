package br.com.fiap.hospital.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * O filtro autentica, e nao decide acesso.
 *
 * <p>A separacao importa: se o filtro recusasse por conta propria, endpoint publico
 * exigiria token, e o motivo da recusa vazaria a diferenca entre "ausente" e "vencido".
 */
@DisplayName("JwtAuthenticationFilter")
class JwtAuthenticationFilterTest {

    private static final String SEGREDO = "segredo-de-teste-com-pelo-menos-32-bytes";
    private static final Instant AGORA = Instant.parse("2026-09-04T12:00:00Z");

    private final JwtService jwtService = new JwtService(
            new JwtProperties(SEGREDO, Duration.ofHours(8), "emissor"),
            Clock.fixed(AGORA, ZoneOffset.UTC));

    private final JwtAuthenticationFilter filtro = new JwtAuthenticationFilter(jwtService);

    @AfterEach
    void limparContexto() {
        SecurityContextHolder.clearContext();
    }

    private Authentication autenticacaoApos(String cabecalho) throws Exception {
        MockHttpServletRequest requisicao = new MockHttpServletRequest("GET", "/api/v1/consultas");
        if (cabecalho != null) {
            requisicao.addHeader("Authorization", cabecalho);
        }
        MockFilterChain cadeia = new MockFilterChain();

        filtro.doFilter(requisicao, new MockHttpServletResponse(), cadeia);

        assertThat(cadeia.getRequest())
                .as("o filtro sempre segue a cadeia; recusar e trabalho de quem vem depois")
                .isNotNull();
        return SecurityContextHolder.getContext().getAuthentication();
    }

    @Test
    @DisplayName("token valido popula o contexto com o perfil como autoridade")
    void tokenValidoPopulaOContexto() throws Exception {
        UUID usuarioId = UUID.randomUUID();
        String token = jwtService.emitir(new UsuarioAutenticado(
                usuarioId, "joao@hospital.com", "MEDICO", null, UUID.randomUUID()));

        Authentication autenticacao = autenticacaoApos("Bearer " + token);

        assertThat(autenticacao).isNotNull();
        assertThat(autenticacao.getPrincipal())
                .isInstanceOfSatisfying(UsuarioAutenticado.class,
                        u -> assertThat(u.usuarioId()).isEqualTo(usuarioId));
        assertThat(autenticacao.getAuthorities())
                .extracting(Object::toString)
                .as("o prefixo ROLE_ e o que permite hasRole nas anotacoes de metodo")
                .containsExactly("ROLE_MEDICO");
    }

    @Test
    @DisplayName("a credencial nao fica no contexto depois da autenticacao")
    void credencialNaoFicaNoContexto() throws Exception {
        String token = jwtService.emitir(new UsuarioAutenticado(
                UUID.randomUUID(), "joao@hospital.com", "MEDICO", null, null));

        Authentication autenticacao = autenticacaoApos("Bearer " + token);

        assertThat(autenticacao.getCredentials())
                .as("guardar o token no contexto o exporia a qualquer log de contexto")
                .isNull();
    }

    /**
     * Cabecalho ausente ou estranho e ausencia de credencial, nunca erro.
     *
     * <p>Erro aqui viraria excecao dentro do filtro, fora do alcance do tratador global,
     * e 500 — a familia de defeito que este change mais precisou conter.
     */
    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"", "   ", "Bearer", "Bearer ", "Bearer    ", "Basic YWRtaW46eA==",
            "Token abc", "bearer minusculo", "Bearer nao-e-token", "Bearer a.b.c",
            "Bearer ../../etc/passwd"})
    @DisplayName("cabecalho ausente, vazio ou estranho nao autentica ninguem")
    void cabecalhoEstranhoNaoAutentica(String cabecalho) throws Exception {
        assertThat(autenticacaoApos(cabecalho)).isNull();
    }

    @Test
    @DisplayName("sem cabecalho Authorization, ninguem e autenticado")
    void semCabecalhoNinguemEAutenticado() throws Exception {
        assertThat(autenticacaoApos(null)).isNull();
    }

    @Test
    @DisplayName("token expirado nao autentica, e nao interrompe a cadeia")
    void tokenExpiradoNaoAutentica() throws Exception {
        JwtService ontem = new JwtService(
                new JwtProperties(SEGREDO, Duration.ofHours(8), "emissor"),
                Clock.fixed(AGORA.minus(Duration.ofDays(1)), ZoneOffset.UTC));
        String vencido = ontem.emitir(new UsuarioAutenticado(
                UUID.randomUUID(), "a@b.com", "MEDICO", null, null));

        assertThat(autenticacaoApos("Bearer " + vencido)).isNull();
    }

    @Test
    @DisplayName("um perfil desconhecido autentica, mas nao alcanca nenhuma autoridade util")
    void perfilDesconhecidoAutenticaSemAlcance() throws Exception {
        String token = jwtService.emitir(new UsuarioAutenticado(
                UUID.randomUUID(), "a@b.com", "SUPERUSUARIO", null, null));

        Authentication autenticacao = autenticacaoApos("Bearer " + token);

        assertThat(autenticacao).isNotNull();
        assertThat(autenticacao.getAuthorities())
                .extracting(Object::toString)
                .as("a autoridade existe, mas nenhum @PreAuthorize a menciona: negar por "
                        + "padrao continua valendo")
                .containsExactly("ROLE_SUPERUSUARIO");
    }
}
