package br.com.fiap.hospital.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Cadeia de filtros compartilhada pelos servicos.
 *
 * <p>Reaproveitavel, mas so o agendamento a consome hoje: notificacao e historico ainda
 * nao expoem endpoint algum, e configurar seguranca sobre nada seria construir para um
 * consumidor imaginario. O que os torna consumiveis depois e a forma desta classe, nao
 * codigo extra — basta a dependencia.
 *
 * <p>O padrao da cadeia e <b>negar</b>, e nao apenas exigir autenticacao: um caminho
 * novo que ninguem liberou fica inacessivel, o que e falha visivel em vez de brecha.
 *
 * <p>Mas negar tudo nao pode ser literal. A autorizacao por perfil vive em
 * {@code @PreAuthorize} no metodo, e o interceptador de metodo so roda depois que a
 * cadeia de filtros deixa a requisicao passar — {@code denyAll} em tudo faria as
 * anotacoes nunca serem avaliadas. Listar cada endpoint aqui tambem nao serve: duplicaria
 * a matriz em dois lugares que podem divergir.
 *
 * <p>A divisao e por nivel. A cadeia libera o caminho da API para quem esta autenticado e
 * <b>nega todo o resto</b>; dentro da API, quem decide o perfil e a anotacao no metodo.
 * Cada nivel tem sua propria protecao contra esquecimento: caminho novo fora da API cai
 * no {@code denyAll}; metodo novo sem anotacao e pego pelo teste estrutural que varre o
 * controller.
 */
@Configuration
@EnableMethodSecurity
@EnableConfigurationProperties(JwtProperties.class)
public class SegurancaAutoConfiguration {

    /** Caminhos abertos, conforme docs/01-arquitetura.md secao 7. */
    private static final String[] PUBLICOS = {
        "/auth/login",
        "/actuator/health",
        "/actuator/health/**",
        "/v3/api-docs",
        "/v3/api-docs/**",
        "/swagger-ui.html",
        "/swagger-ui/**"
    };

    /**
     * O despacho de erro do container, que <b>precisa</b> passar.
     *
     * <p>Excecao lancada dentro de um filtro escapa do {@code DispatcherServlet} — o
     * tratador global nunca a ve — e o Tomcat re-despacha a requisicao para {@code
     * /error}. Esse despacho atravessa a cadeia de seguranca de novo, sem autenticacao
     * no contexto; se {@code /error} cair no {@code denyAll}, o cliente recebe <b>401 no
     * lugar do 500</b>.
     *
     * <p>Duas consequencias, e a segunda e pior. A primeira e diagnostica: uma falha de
     * servidor se disfarca de problema de credencial, e quem depura vai atras do token.
     * A segunda e que a varredura de entradas hostis, que existe para provar que nenhuma
     * entrada vira 5xx, fica <b>cega</b> para tudo que acontece na camada de filtros —
     * ela veria 401, concluiria que a recusa foi correta, e passaria.
     *
     * <p>Liberar o caminho nao expoe nada: o corpo de erro do Boot e generico e as
     * excecoes da aplicacao continuam sendo tratadas pelo advice, que responde antes.
     */
    private static final String ERRO_DO_CONTAINER = "/error";

    @Bean
    @ConditionalOnMissingBean(PasswordEncoder.class)
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    @ConditionalOnMissingBean(JwtService.class)
    public JwtService jwtService(JwtProperties propriedades, Clock clock) {
        return new JwtService(propriedades, clock);
    }

    @Bean
    public RespostaDeSeguranca respostaDeSeguranca(ObjectMapper mapper, Clock clock) {
        return new RespostaDeSeguranca(mapper, clock);
    }

    @Bean
    public JwtAuthenticationFilter jwtAuthenticationFilter(JwtService jwtService) {
        return new JwtAuthenticationFilter(jwtService);
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            JwtAuthenticationFilter filtroJwt,
            RespostaDeSeguranca respostas) throws Exception {

        return http
                // CSRF nao se aplica: API stateless, sem cookie de sessao.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(req -> req
                        .requestMatchers(PUBLICOS).permitAll()
                        // Ver ERRO_DO_CONTAINER: sem isto, falha de servidor vira 401.
                        .requestMatchers(ERRO_DO_CONTAINER).permitAll()
                        // Dentro da API, quem decide o perfil e o @PreAuthorize do metodo.
                        .requestMatchers("/api/**").authenticated()
                        // Caminho novo que ninguem liberou fica inacessivel.
                        .anyRequest().denyAll())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(respostas)
                        .accessDeniedHandler(respostas))
                .addFilterBefore(filtroJwt, UsernamePasswordAuthenticationFilter.class)
                .build();
    }
}
