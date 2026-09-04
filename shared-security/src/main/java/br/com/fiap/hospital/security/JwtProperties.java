package br.com.fiap.hospital.security;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Segredo compartilhado e validade do token.
 *
 * @param secret segredo HS256, vindo de JWT_SECRET. Minimo de 32 bytes
 * @param expiracao por quanto tempo o token vale
 * @param emissor identificador de quem emitiu
 */
@ConfigurationProperties(prefix = "hospital.jwt")
public record JwtProperties(String secret, Duration expiracao, String emissor) {

    public JwtProperties {
        expiracao = expiracao == null ? Duration.ofHours(8) : expiracao;
        emissor = emissor == null || emissor.isBlank() ? "hospital-agendamento" : emissor;
        if (secret == null || secret.getBytes().length < 32) {
            throw new IllegalStateException(
                    "JWT_SECRET ausente ou curto demais: HS256 exige ao menos 32 bytes");
        }
    }
}
