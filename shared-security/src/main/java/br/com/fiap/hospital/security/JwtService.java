package br.com.fiap.hospital.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.SecretKey;

/**
 * Emite e valida o token de acesso, em HS256.
 *
 * <p>O algoritmo e <b>fixado</b>, e nao deduzido da chave. A jjwt escolhe o mais forte
 * que o segredo comporta, entao um segredo de 64 bytes produziria HS512 e um de 32
 * produziria HS256 — o algoritmo do token passaria a depender do comprimento de uma
 * variavel de ambiente, divergindo em silencio do que a secao 7 documenta.
 *
 * <p>As claims sao as de docs/01-arquitetura.md secao 7: sujeito, e-mail, perfil,
 * identificador de paciente ou de medico quando aplicavel, emissao e expiracao.
 *
 * <p>A validacao devolve {@link Optional} vazio em vez de lancar. Token invalido e
 * situacao esperada — cliente sem credencial, token vencido, tentativa de adulteracao —
 * e nao excecao. Quem decide o que fazer com a ausencia e o filtro.
 */
public class JwtService {

    private static final String CLAIM_EMAIL = "email";
    private static final String CLAIM_PERFIL = "perfil";
    private static final String CLAIM_PACIENTE = "pacienteId";
    private static final String CLAIM_MEDICO = "medicoId";

    private final SecretKey chave;
    private final JwtProperties propriedades;
    private final Clock clock;

    public JwtService(JwtProperties propriedades, Clock clock) {
        this.propriedades = propriedades;
        this.clock = clock;
        this.chave = io.jsonwebtoken.security.Keys.hmacShaKeyFor(
                propriedades.secret().getBytes(StandardCharsets.UTF_8));
    }

    public String emitir(UsuarioAutenticado usuario) {
        Instant agora = clock.instant();
        Instant expiraEm = agora.plus(propriedades.expiracao());

        var construtor = Jwts.builder()
                .subject(usuario.usuarioId().toString())
                .issuer(propriedades.emissor())
                .claim(CLAIM_EMAIL, usuario.email())
                .claim(CLAIM_PERFIL, usuario.perfil())
                .issuedAt(Date.from(agora))
                .expiration(Date.from(expiraEm));

        if (usuario.pacienteId() != null) {
            construtor.claim(CLAIM_PACIENTE, usuario.pacienteId().toString());
        }
        if (usuario.medicoId() != null) {
            construtor.claim(CLAIM_MEDICO, usuario.medicoId().toString());
        }
        return construtor.signWith(chave, Jwts.SIG.HS256).compact();
    }

    public long expiracaoEmSegundos() {
        return propriedades.expiracao().toSeconds();
    }

    /**
     * Valida assinatura, prazo e presenca das claims de identidade.
     *
     * <p>Devolve vazio para token expirado, assinado com outro segredo, malformado, ou
     * assinado corretamente mas sem o que identifique o usuario. Um token sem perfil e
     * tao inutil quanto um adulterado: nao ha o que autorizar.
     */
    public Optional<UsuarioAutenticado> validar(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(chave)
                    .clock(() -> Date.from(clock.instant()))
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            UUID usuarioId = uuidDe(claims.getSubject());
            String perfil = claims.get(CLAIM_PERFIL, String.class);
            if (usuarioId == null || perfil == null || perfil.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(new UsuarioAutenticado(
                    usuarioId,
                    claims.get(CLAIM_EMAIL, String.class),
                    perfil,
                    uuidDe(claims.get(CLAIM_PACIENTE, String.class)),
                    uuidDe(claims.get(CLAIM_MEDICO, String.class))));

        } catch (JwtException | IllegalArgumentException e) {
            // Assinatura, formato e prazo caem todos aqui, de proposito: distinguir os
            // motivos para o cliente so ajudaria quem esta tentando adivinhar o segredo.
            return Optional.empty();
        }
    }

    private static UUID uuidDe(String valor) {
        if (valor == null || valor.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(valor);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
