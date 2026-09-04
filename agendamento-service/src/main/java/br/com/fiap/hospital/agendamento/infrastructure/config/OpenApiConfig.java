package br.com.fiap.hospital.agendamento.infrastructure.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI openApi() {
        return new OpenAPI().info(new Info()
                .title("Agendamento de consultas")
                .version("v1")
                .description("""
                        API de agendamento do sistema hospitalar.

                        ATENCAO: nesta versao os endpoints estao ABERTOS. A autenticacao \
                        por JWT e a autorizacao por perfil entram na proxima entrega."""));
    }
}
