package br.com.fiap.hospital.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.ExceptionTranslationFilter;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.context.SecurityContextPersistenceFilter;
import org.springframework.security.web.csrf.CsrfFilter;

/**
 * A cadeia montada no proprio modulo, sem subir servico algum.
 *
 * <p>Hoje so o agendamento consome esta auto-configuracao, entao toda a evidencia de que
 * ela funciona viria dos testes de integracao <b>de outro modulo</b>. Isso e frágil em
 * duas direcoes: o {@code shared-security} pode ser reusado por um servico que nao tenha
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
