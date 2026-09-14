package br.com.fiap.hospital.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.ExceptionTranslationFilter;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.context.SecurityContextPersistenceFilter;
import org.springframework.security.web.csrf.CsrfFilter;

/**
 * A cadeia montada no proprio modulo, sem subir servico algum.
 *
 * <p>Tres servicos consomem esta auto-configuracao — agendamento, historico e notificacao —,
 * e sem estes testes toda a evidencia de que ela funciona viria dos testes de integracao
 * <b>de outros modulos</b>. Isso e frágil em duas direcoes: o {@code shared-security} pode ser reusado por um servico que nao tenha
 * essa suite, e uma quebra aqui apareceria como falha longe da causa.
 */
@DisplayName("SegurancaAutoConfiguration")
class SegurancaAutoConfigurationTest {

    private final WebApplicationContextRunner contexto = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    WebMvcAutoConfiguration.class,
                    SecurityAutoConfiguration.class,
                    SegurancaAutoConfiguration.class))
            .withUserConfiguration(Colaboradores.class)
            .withPropertyValues(
                    "hospital.jwt.secret=segredo-de-teste-com-pelo-menos-32-bytes",
                    "hospital.jwt.expiracao=8h");

    @Test
    @DisplayName("registra os beans que a cadeia precisa")
    void registraOsBeansDaCadeia() {
        contexto.run(ctx -> assertThat(ctx)
                .hasNotFailed()
                .hasSingleBean(JwtService.class)
                .hasSingleBean(JwtAuthenticationFilter.class)
                .hasSingleBean(RespostaDeSeguranca.class)
                .hasSingleBean(PasswordEncoder.class)
                .hasSingleBean(SecurityFilterChain.class));
    }

    @Test
    @DisplayName("a aplicacao nao sobe com JWT_SECRET ausente")
    void naoSobeSemSegredo() {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        WebMvcAutoConfiguration.class,
                        SecurityAutoConfiguration.class,
                        SegurancaAutoConfiguration.class))
                .withUserConfiguration(Colaboradores.class)
                .run(ctx -> {
                    assertThat(ctx)
                            .as("segredo ausente tem de derrubar a partida, e nao gerar um "
                                    + "token que qualquer um consegue assinar")
                            .hasFailed();
                    // Sem checar a causa, este teste passaria por qualquer erro de fiacao.
                    assertThat(ctx.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalStateException.class)
                            .rootCause()
                            .hasMessageContaining("32 bytes");
                });
    }

    /**
     * O consumidor manda mais que o padrao.
     *
     * <p>Sem {@code @ConditionalOnMissingBean}, um servico que ja tivesse o proprio
     * {@code PasswordEncoder} — com outro custo de BCrypt, por exemplo — receberia dois
     * beans e o contexto nem subiria.
     */
    @Test
    @DisplayName("o PasswordEncoder do consumidor prevalece sobre o padrao")
    void encoderDoConsumidorPrevalece() {
        contexto.withUserConfiguration(EncoderProprio.class)
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(PasswordEncoder.class);
                    assertThat(ctx.getBean(PasswordEncoder.class).encode("x"))
                            .isEqualTo("codificado-pelo-consumidor");
                });
    }

    @Test
    @DisplayName("o filtro de JWT vem antes da decisao de autorizacao")
    void filtroDeJwtVemAntesDaAutorizacao() {
        contexto.run(ctx -> {
            List<Class<?>> filtros = tiposDosFiltros(ctx.getBean(SecurityFilterChain.class));

            assertThat(filtros).contains(JwtAuthenticationFilter.class);
            assertThat(filtros.indexOf(JwtAuthenticationFilter.class))
                    .as("autenticar depois de autorizar deixaria toda requisicao anonima")
                    .isLessThan(filtros.indexOf(AuthorizationFilter.class));
        });
    }

    @Test
    @DisplayName("a cadeia e stateless e sem CSRF")
    void cadeiaEStatelessESemCsrf() {
        contexto.run(ctx -> {
            List<Class<?>> filtros = tiposDosFiltros(ctx.getBean(SecurityFilterChain.class));

            assertThat(filtros)
                    .as("API sem cookie de sessao nao tem o que proteger contra CSRF, e o "
                            + "filtro so recusaria requisicoes legitimas")
                    .doesNotContain(CsrfFilter.class)
                    .doesNotContain(SecurityContextPersistenceFilter.class);
        });
    }

    @Test
    @DisplayName("as recusas da cadeia usam a RespostaDeSeguranca")
    void recusasUsamARespostaDeSeguranca() {
        contexto.run(ctx -> {
            ExceptionTranslationFilter tradutor = ctx.getBean(SecurityFilterChain.class)
                    .getFilters().stream()
                    .filter(ExceptionTranslationFilter.class::isInstance)
                    .map(ExceptionTranslationFilter.class::cast)
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            "sem ExceptionTranslationFilter, 401 e 403 sairiam no formato "
                                    + "padrao do container"));

            assertThat(tradutor).isNotNull();
            assertThat(ctx).hasSingleBean(RespostaDeSeguranca.class);
        });
    }

    /**
     * O padrao existe para que o agendamento nao mude quando o historico precisa de mais.
     *
     * <p>Se o padrao dos caminhos autenticados deixasse de ser {@code /api/**}, ou se a
     * lista publica adicional nascesse com algo dentro, um servico que nao configurou nada
     * passaria a expor ou a negar caminho sem que ninguem tivesse pedido — e a mudanca
     * apareceria como falha de outro modulo, longe da causa.
     */
    @Test
    @DisplayName("sem configuracao, os caminhos sao exatamente os de antes")
    void padraoPreservaACadeiaAnterior() {
        contexto.run(ctx -> {
            CaminhosDeSeguranca caminhos = ctx.getBean(CaminhosDeSeguranca.class);

            assertThat(caminhos.autenticados()).containsExactly("/api/**");
            assertThat(caminhos.publicosAdicionais())
                    .as("publico adicional nasce vazio: quem abre caminho e o servico, por profile")
                    .isEmpty();
            assertThat(ctx).hasSingleBean(SecurityFilterChain.class);
        });
    }

    @Test
    @DisplayName("o servico acrescenta caminhos sem ganhar uma segunda cadeia")
    void servicoAcrescentaCaminhosSemSegundaCadeia() {
        contexto.withPropertyValues(
                        "hospital.security.caminhos.autenticados=/api/**,/graphql",
                        "hospital.security.caminhos.publicos-adicionais=/graphiql**")
                .run(ctx -> {
                    CaminhosDeSeguranca caminhos = ctx.getBean(CaminhosDeSeguranca.class);

                    assertThat(caminhos.autenticados()).containsExactly("/api/**", "/graphql");
                    assertThat(caminhos.publicosAdicionais()).containsExactly("/graphiql**");
                    assertThat(ctx)
                            .as("caminho novo entra por configuracao; cadeia concorrente "
                                    + "faria a ordem decidir quem atende /graphql")
                            .hasSingleBean(SecurityFilterChain.class);
                });
    }

    /**
     * A recusa por omissao continua sendo o padrao.
     *
     * <p>Tornar a lista configuravel poderia ter trocado {@code denyAll} por
     * "autenticado", e a diferenca so apareceria no dia em que alguem criasse um caminho
     * novo — que passaria a responder a qualquer usuario logado em vez de nao existir.
     */
    @Test
    @DisplayName("caminho fora das listas continua caindo no denyAll")
    void caminhoForaDasListasContinuaNegado() throws Exception {
        contexto.withPropertyValues("hospital.security.caminhos.autenticados=/api/**,/graphql")
                .run(ctx -> {
                    MockHttpServletRequest requisicao =
                            new MockHttpServletRequest("GET", "/caminho-que-ninguem-liberou");
                    MockHttpServletResponse resposta = new MockHttpServletResponse();

                    filtrar(ctx.getBean(SecurityFilterChain.class), requisicao, resposta);

                    assertThat(resposta.getStatus())
                            .as("sem denyAll, caminho novo ficaria aberto a qualquer autenticado")
                            .isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
                });
    }

    /**
     * O terceiro consumidor, a notificacao, troca a lista autenticada por {@code /internal/**}.
     *
     * <p>Com token valido, {@code /internal} atravessa a cadeia ate o alvo, e {@code /api} —
     * que deixou de estar na lista — cai no {@code denyAll}. A prova usa token de proposito:
     * sem ele, os dois caminhos devolveriam 401 e a diferenca entre "autenticado" e "negado
     * por omissao" ficaria invisivel.
     */
    @Test
    @DisplayName("a notificacao autentica /internal/** sem segunda cadeia, e /api/** substituido e negado")
    void notificacaoAutenticaInternalSemSegundaCadeia() {
        contexto.withPropertyValues("hospital.security.caminhos.autenticados=/internal/**")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(SecurityFilterChain.class);
                    assertThat(ctx.getBean(CaminhosDeSeguranca.class).autenticados())
                            .containsExactly("/internal/**");

                    SecurityFilterChain cadeia = ctx.getBean(SecurityFilterChain.class);
                    String token = ctx.getBean(JwtService.class).emitir(new UsuarioAutenticado(
                            java.util.UUID.randomUUID(), "medico@hospital.com", "MEDICO", null,
                            java.util.UUID.randomUUID()));

                    MockHttpServletResponse interna = new MockHttpServletResponse();
                    assertThat(alcancaOAlvo(cadeia,
                            comToken("POST", "/internal/lembretes/executar", token), interna))
                            .as("/internal/** autenticado deixa a requisicao seguir ate o metodo")
                            .isTrue();

                    MockHttpServletResponse api = new MockHttpServletResponse();
                    assertThat(alcancaOAlvo(cadeia, comToken("GET", "/api/v1/consultas", token), api))
                            .as("/api/** saiu da lista e nao pode alcancar handler algum")
                            .isFalse();
                    assertThat(api.getStatus()).isEqualTo(HttpServletResponse.SC_FORBIDDEN);
                });
    }

    /**
     * Os caminhos operacionais: health publico, os quatro demais com qualquer token, e todo
     * outro actuator negado mesmo com token.
     *
     * <p>A prova de {@code env} e {@code beans} usa token de proposito: sem ele os dois
     * responderiam 401 tanto negados quanto apenas autenticados, e um curinga
     * {@code /actuator/**} passaria despercebido.
     */
    @Test
    @DisplayName("health e publico, operacionais exigem token e o resto do actuator e negado")
    void caminhosOperacionaisNaCadeiaCompartilhada() {
        contexto.run(ctx -> {
            SecurityFilterChain cadeia = ctx.getBean(SecurityFilterChain.class);
            String token = ctx.getBean(JwtService.class).emitir(new UsuarioAutenticado(
                    java.util.UUID.randomUUID(), "paciente@hospital.com", "PACIENTE",
                    java.util.UUID.randomUUID(), null));

            for (String publico : List.of("/actuator/health", "/actuator/health/liveness")) {
                assertThat(alcancaOAlvo(cadeia, semToken(publico), new MockHttpServletResponse()))
                        .as("%s e publico", publico).isTrue();
            }
            assertThat(SegurancaAutoConfiguration.OPERACIONAIS_AUTENTICADOS).containsExactly(
                    "/actuator", "/actuator/info", "/actuator/metrics", "/actuator/metrics/**",
                    "/actuator/prometheus");
            for (String operacional : List.of("/actuator", "/actuator/info", "/actuator/metrics",
                    "/actuator/metrics/jvm.memory.used", "/actuator/prometheus")) {
                MockHttpServletResponse semCredencial = new MockHttpServletResponse();
                assertThat(alcancaOAlvo(cadeia, semToken(operacional), semCredencial))
                        .as("%s sem token", operacional).isFalse();
                assertThat(semCredencial.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
                assertThat(alcancaOAlvo(cadeia, comToken("GET", operacional, token), new MockHttpServletResponse()))
                        .as("%s com token de qualquer perfil", operacional).isTrue();
            }
            for (String negado : List.of("/actuator/env", "/actuator/beans", "/actuator/configprops")) {
                MockHttpServletResponse comCredencial = new MockHttpServletResponse();
                assertThat(alcancaOAlvo(cadeia, comToken("GET", negado, token), comCredencial))
                        .as("%s nao pode alcancar handler nem com token", negado).isFalse();
                assertThat(comCredencial.getStatus()).isEqualTo(HttpServletResponse.SC_FORBIDDEN);
            }
        });
    }

    private static MockHttpServletRequest semToken(String caminho) {
        MockHttpServletRequest requisicao = new MockHttpServletRequest("GET", caminho);
        requisicao.setServletPath(caminho);
        return requisicao;
    }

    private static MockHttpServletRequest comToken(String metodo, String caminho, String token) {
        MockHttpServletRequest requisicao = new MockHttpServletRequest(metodo, caminho);
        // Com o DispatcherServlet mapeado em "/", o container poe o caminho inteiro no
        // servletPath, e e dele que o matcher de caminhos le. Sem isto o caminho seria vazio
        // e toda requisicao cairia no denyAll, casando ou nao com a lista.
        requisicao.setServletPath(caminho);
        requisicao.addHeader("Authorization", "Bearer " + token);
        return requisicao;
    }

    /** Como {@link #filtrar}, mas informando se a requisicao chegou ao fim da cadeia. */
    private static boolean alcancaOAlvo(SecurityFilterChain cadeia, MockHttpServletRequest requisicao,
                                        MockHttpServletResponse resposta) throws Exception {
        var alcancou = new java.util.concurrent.atomic.AtomicBoolean();
        List<Filter> filtros = cadeia.getFilters();
        FilterChain atual = (req, res) -> alcancou.set(true);
        for (int i = filtros.size() - 1; i >= 0; i--) {
            Filter filtro = filtros.get(i);
            FilterChain proxima = atual;
            atual = (req, res) -> filtro.doFilter(req, res, proxima);
        }
        try {
            atual.doFilter(requisicao, resposta);
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
        return alcancou.get();
    }

    /**
     * Roda a requisicao pela cadeia real, do primeiro filtro ao ultimo.
     *
     * <p>O fim da cadeia e um alvo que registra que foi alcancado. Se a autorizacao
     * deixasse a requisicao passar, o teste veria 200 com {@code alcancou} verdadeiro em
     * vez de 401 — a diferenca entre "negado" e "seguiu adiante sem handler".
     */
    private static void filtrar(SecurityFilterChain cadeia, MockHttpServletRequest requisicao,
                                MockHttpServletResponse resposta) throws Exception {
        List<Filter> filtros = cadeia.getFilters();
        FilterChain alvo = (req, res) -> { };
        FilterChain atual = alvo;
        for (int i = filtros.size() - 1; i >= 0; i--) {
            Filter filtro = filtros.get(i);
            FilterChain proxima = atual;
            atual = (req, res) -> filtro.doFilter(req, res, proxima);
        }
        atual.doFilter(requisicao, resposta);
    }

    private static List<Class<?>> tiposDosFiltros(SecurityFilterChain cadeia) {
        return cadeia.getFilters().stream()
                .map(f -> (Class<?>) f.getClass())
                .collect(Collectors.toList());
    }

    @Configuration(proxyBeanMethods = false)
    static class Colaboradores {

        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class EncoderProprio {

        @Bean
        PasswordEncoder passwordEncoder() {
            return new PasswordEncoder() {
                @Override
                public String encode(CharSequence senha) {
                    return "codificado-pelo-consumidor";
                }

                @Override
                public boolean matches(CharSequence senha, String hash) {
                    return false;
                }
            };
        }
    }
}
