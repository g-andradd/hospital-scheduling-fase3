package br.com.fiap.hospital.agendamento.infrastructure.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    private static final String ESQUEMA = "bearerAuth";

    /**
     * O esquema de seguranca e declarado como requisito <b>global</b>.
     *
     * <p>Declarar operacao por operacao daria no mesmo hoje e divergiria amanha: o
     * endpoint acrescentado sem a anotacao apareceria no Swagger UI como se fosse
     * publico, e quem testasse pela interface receberia 401 sem entender por que. O
     * requisito global descreve a regra real da cadeia — tudo exige token — e o login,
     * que e a excecao, se anuncia sozinho por nao precisar de credencial para responder.
     */
    @Bean
    public OpenAPI openApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Agendamento de consultas")
                        .version("v1")
                        .description("""
                                API de agendamento do sistema hospitalar.

                                Autentique em POST /auth/login e use o token no botao \
                                Authorize. Com o profile demo ativo, as credenciais de \
                                demonstracao estao no README do repositorio.

                                As permissoes por perfil seguem a matriz da secao 3 de \
                                docs/02-especificacao-funcional.md."""))
                .addSecurityItem(new SecurityRequirement().addList(ESQUEMA))
                .components(new Components().addSecuritySchemes(ESQUEMA, new SecurityScheme()
                        .name(ESQUEMA)
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")
                        .description("Token devolvido por POST /auth/login. Vale 8 horas.")));
    }
}
