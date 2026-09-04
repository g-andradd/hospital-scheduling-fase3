package br.com.fiap.hospital.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * O token verificado no proprio modulo, sem Spring e sem banco.
 *
 * <p>Deliberado: se a unica prova de que um token adulterado e recusado estivesse nos
 * testes de integracao do agendamento, o {@code shared-security} poderia ser reusado por
 * outro servico levando junto um defeito que ninguem exercitou ali.
 */
@DisplayName("JwtService")
class JwtServiceTest {

    private static final String SEGREDO = "segredo-de-teste-com-pelo-menos-32-bytes";
    private static final String SEGREDO_ALHEIO = "outro-segredo-de-teste-com-32-bytes-ok";
    private static final Instant AGORA = Instant.parse("2026-09-04T12:00:00Z");

    private static JwtService servicoEm(Instant instante) {
        return new JwtService(
                new JwtProperties(SEGREDO, Duration.ofHours(8), "hospital-agendamento"),
                Clock.fixed(instante, ZoneOffset.UTC));
    }

    private final JwtService servico = servicoEm(AGORA);

    @Nested
    @DisplayName("Requirement: Emissao do token")
    class Emissao {

        @Test
        @DisplayName("Scenario: Token carrega a identidade do usuario")
        void tokenCarregaAIdentidade() {
            UUID usuarioId = UUID.randomUUID();
            UUID pacienteId = UUID.randomUUID();

            String token = servico.emitir(new UsuarioAutenticado(
                    usuarioId, "maria@hospital.com", "PACIENTE", pacienteId, null));

            UsuarioAutenticado lido = servico.validar(token).orElseThrow();
            assertThat(lido.usuarioId()).isEqualTo(usuarioId);
            assertThat(lido.email()).isEqualTo("maria@hospital.com");
            assertThat(lido.perfil()).isEqualTo("PACIENTE");
            assertThat(lido.pacienteId()).isEqualTo(pacienteId);
            assertThat(lido.medicoId()).isNull();
        }

        @Test
        @DisplayName("o medico viaja com o proprio identificador, e sem o de paciente")
        void medicoViajaComOProprioIdentificador() {
            UUID medicoId = UUID.randomUUID();

            String token = servico.emitir(new UsuarioAutenticado(
                    UUID.randomUUID(), "joao@hospital.com", "MEDICO", null, medicoId));

            UsuarioAutenticado lido = servico.validar(token).orElseThrow();
            assertThat(lido.medicoId()).isEqualTo(medicoId);
            assertThat(lido.pacienteId()).isNull();
        }

        @Test
        @DisplayName("o prazo divulgado e o prazo configurado")
        void prazoDivulgadoEOConfigurado() {
            assertThat(servico.expiracaoEmSegundos()).isEqualTo(Duration.ofHours(8).toSeconds());
        }

        /**
         * O algoritmo nao pode depender do comprimento do segredo.
         *
         * <p>A jjwt escolhe o mais forte que a chave comporta quando nao se diz qual
         * usar. Com o segredo de 32 bytes sairia HS256; com um de 64, HS512 — e o token
         * mudaria de algoritmo por causa de uma variavel de ambiente, divergindo do que
         * a secao 7 documenta sem nenhum sinal.
         */
        @ParameterizedTest(name = "segredo de {0} bytes")
        @ValueSource(ints = {32, 48, 64, 96})
        @DisplayName("o algoritmo e HS256 qualquer que seja o tamanho do segredo")
        void algoritmoEHs256(int tamanho) {
            JwtService comSegredoDe = new JwtService(
                    new JwtProperties("s".repeat(tamanho), Duration.ofHours(8), "emissor"),
                    Clock.fixed(AGORA, ZoneOffset.UTC));

            String token = comSegredoDe.emitir(new UsuarioAutenticado(
                    UUID.randomUUID(), "a@b.com", "MEDICO", null, null));
            String cabecalho = new String(java.util.Base64.getUrlDecoder().decode(
                    token.substring(0, token.indexOf('.'))), StandardCharsets.UTF_8);

            assertThat(cabecalho).contains("\"HS256\"");
        }

        @Test
        @DisplayName("o token nao carrega a senha nem o hash")
        void tokenNaoCarregaSenha() {
            String token = servico.emitir(new UsuarioAutenticado(
                    UUID.randomUUID(), "maria@hospital.com", "PACIENTE", UUID.randomUUID(), null));

            String corpo = new String(java.util.Base64.getUrlDecoder().decode(
                    token.split("\\.")[1]), StandardCharsets.UTF_8);

            assertThat(corpo).doesNotContain("senha", "hash", "$2a$", "password");
        }
    }

    @Nested
    @DisplayName("Requirement: Recusa de token invalido")
    class Recusa {

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   ", "nao-e-um-token", "a.b", "a.b.c", "....",
                "eyJhbGciOiJub25lIn0..", "Bearer token"})
        @DisplayName("Scenario: Token malformado e recusado")
        void tokenMalformadoERecusado(String token) {
            assertThat(servico.validar(token)).isEmpty();
        }

        @Test
        @DisplayName("Scenario: Token expirado e recusado")
        void tokenExpiradoERecusado() {
            String token = servicoEm(AGORA.minus(Duration.ofDays(1))).emitir(
                    new UsuarioAutenticado(UUID.randomUUID(), "a@b.com", "MEDICO", null, null));

            assertThat(servico.validar(token))
                    .as("emitido ontem, com validade de 8 horas")
                    .isEmpty();
        }

        @Test
        @DisplayName("o token vale ate o limite, e nao um instante alem")
        void tokenValeAteOLimite() {
            String token = servico.emitir(new UsuarioAutenticado(
                    UUID.randomUUID(), "a@b.com", "MEDICO", null, null));

            assertThat(servicoEm(AGORA.plus(Duration.ofHours(7))).validar(token))
                    .as("dentro do prazo")
                    .isPresent();
            assertThat(servicoEm(AGORA.plus(Duration.ofHours(9))).validar(token))
                    .as("passado o prazo")
                    .isEmpty();
        }

        @Test
        @DisplayName("Scenario: Assinatura adulterada")
        void tokenDeOutroSegredoERecusado() {
            String forjado = Jwts.builder()
                    .subject(UUID.randomUUID().toString())
                    .claim("perfil", "MEDICO")
                    .issuedAt(Date.from(AGORA))
                    .expiration(Date.from(AGORA.plusSeconds(3600)))
                    .signWith(Keys.hmacShaKeyFor(SEGREDO_ALHEIO.getBytes(StandardCharsets.UTF_8)))
                    .compact();

            assertThat(servico.validar(forjado))
                    .as("assinatura de outro segredo nao pode autenticar ninguem")
                    .isEmpty();
        }

        @Test
        @DisplayName("Scenario: Token sem informacao de identidade")
        void tokenSemPerfilERecusado() {
            assertThat(servico.validar(assinadoBem(null, UUID.randomUUID().toString()))).isEmpty();
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "   "})
        @DisplayName("perfil em branco e tao inutil quanto perfil ausente")
        void perfilEmBrancoERecusado(String perfil) {
            assertThat(servico.validar(assinadoBem(perfil, UUID.randomUUID().toString())))
                    .isEmpty();
        }

        @Test
        @DisplayName("token sem sujeito e recusado")
        void tokenSemSujeitoERecusado() {
            assertThat(servico.validar(assinadoBem("MEDICO", null))).isEmpty();
        }

        @Test
        @DisplayName("sujeito que nao e UUID e recusado")
        void sujeitoMalformadoERecusado() {
            assertThat(servico.validar(assinadoBem("MEDICO", "nao-e-uuid"))).isEmpty();
        }

        /**
         * O identificador de paciente ilegivel nao derruba a validacao — vira nulo.
         *
         * <p>Nao e leniencia: sem identificador de paciente, a regra de propriedade nao
         * encontra consulta alguma da qual o solicitante seja titular, e o acesso e
         * negado adiante. Recusar aqui apenas trocaria 403 por 401.
         */
        @Test
        @DisplayName("pacienteId ilegivel vira nulo, e nao explode")
        void pacienteIlegivelViraNulo() {
            String token = Jwts.builder()
                    .subject(UUID.randomUUID().toString())
                    .claim("perfil", "PACIENTE")
                    .claim("pacienteId", "nao-e-uuid")
                    .issuedAt(Date.from(AGORA))
                    .expiration(Date.from(AGORA.plusSeconds(3600)))
                    .signWith(Keys.hmacShaKeyFor(SEGREDO.getBytes(StandardCharsets.UTF_8)))
                    .compact();

            Optional<UsuarioAutenticado> lido = servico.validar(token);

            assertThat(lido).isPresent();
            assertThat(lido.orElseThrow().pacienteId()).isNull();
        }

        /**
         * Nenhuma entrada faz {@code validar} lancar.
         *
         * <p>Excecao aqui aconteceria dentro do filtro, fora do alcance do tratador
         * global, e viraria 500 — token invalido virando falha de servidor.
         */
        @ParameterizedTest
        @ValueSource(strings = {"...", " ", "%%%", "../../etc/passwd",
                "eyJ.eyJ.", "\n\t"})
        @DisplayName("nenhuma entrada faz a validacao lancar")
        void validacaoNuncaLanca(String entrada) {
            assertThat(servico.validar(entrada)).isEmpty();
        }

        @Test
        @DisplayName("token absurdamente longo e recusado sem lancar")
        void tokenLonguissimoERecusado() {
            assertThat(servico.validar("a".repeat(100_000))).isEmpty();
        }
    }

    @Nested
    @DisplayName("Configuracao do segredo")
    class Configuracao {

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"curto", "menos-de-32-bytes-aqui"})
        @DisplayName("segredo ausente ou curto demais impede a aplicacao de subir")
        void segredoInsuficienteImpedeSubir(String segredo) {
            assertThatThrownBy(() -> new JwtProperties(segredo, Duration.ofHours(8), "emissor"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("32 bytes");
        }

        @Test
        @DisplayName("prazo e emissor tem padrao, o segredo nao")
        void prazoEEmissorTemPadrao() {
            JwtProperties propriedades = new JwtProperties(SEGREDO, null, null);

            assertThat(propriedades.expiracao()).isEqualTo(Duration.ofHours(8));
            assertThat(propriedades.emissor()).isNotBlank();
        }
    }

    private static String assinadoBem(String perfil, String sujeito) {
        var construtor = Jwts.builder()
                .issuedAt(Date.from(AGORA))
                .expiration(Date.from(AGORA.plusSeconds(3600)));
        if (sujeito != null) {
            construtor.subject(sujeito);
        }
        if (perfil != null) {
            construtor.claim("perfil", perfil);
        }
        return construtor
                .signWith(Keys.hmacShaKeyFor(SEGREDO.getBytes(StandardCharsets.UTF_8)))
                .compact();
    }
}
