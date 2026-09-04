package br.com.fiap.hospital.agendamento.integracao;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.fiap.hospital.agendamento.domain.PerfilUsuario;
import br.com.fiap.hospital.agendamento.domain.StatusConsulta;
import br.com.fiap.hospital.agendamento.infrastructure.persistence.entity.ConsultaEntity;
import br.com.fiap.hospital.agendamento.infrastructure.persistence.entity.MedicoEntity;
import br.com.fiap.hospital.agendamento.infrastructure.persistence.entity.PacienteEntity;
import br.com.fiap.hospital.agendamento.infrastructure.persistence.entity.UsuarioEntity;
import br.com.fiap.hospital.agendamento.infrastructure.persistence.repository.ConsultaJpaRepository;
import br.com.fiap.hospital.agendamento.infrastructure.persistence.repository.MedicoJpaRepository;
import br.com.fiap.hospital.agendamento.infrastructure.persistence.repository.PacienteJpaRepository;
import br.com.fiap.hospital.agendamento.infrastructure.persistence.repository.UsuarioJpaRepository;
import br.com.fiap.hospital.security.JwtService;
import br.com.fiap.hospital.security.UsuarioAutenticado;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Cada celula da matriz de docs/02-especificacao-funcional.md secao 3 vira um teste.
 *
 * <p>Os casos vem da leitura da tabela do documento, nao de uma lista escrita aqui:
 * acrescentar uma linha la produz tres casos novos que falham ate serem implementados.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Matriz de autorizacao")
class MatrizDeAutorizacaoIT {

    @DynamicPropertySource
    static void propriedades(DynamicPropertyRegistry registro) {
        ContainerPostgres.registrarPropriedades(registro);
    }

    @Autowired private TestRestTemplate rest;
    @Autowired private JwtService jwtService;
    @Autowired private ConsultaJpaRepository consultaJpa;
    @Autowired private PacienteJpaRepository pacienteJpa;
    @Autowired private MedicoJpaRepository medicoJpa;
    @Autowired private UsuarioJpaRepository usuarioJpa;

    /** BCrypt de Senha@123, para que a linha publica do login responda de verdade. */
    private static final String HASH_SENHA_123 =
            "$2a$10$JUU8mSXfivdwzpuhR9norOIR5JKK5EcQiWSwiultOGzapLvxFTLVW";

    private static UUID pacienteId;
    private static UUID medicoId;
    private static UUID registranteId;
    private static UUID consultaId;
    private static String tokenMedico;
    private static String tokenEnfermeiro;
    private static String tokenPaciente;

    @BeforeEach
    void preparar() {
        consultaJpa.deleteAll();
        pacienteJpa.deleteAll();
        medicoJpa.deleteAll();
        usuarioJpa.deleteAll();

        UsuarioEntity um = usuarioJpa.save(usuario(PerfilUsuario.MEDICO, "matriz.m@hospital.com"));
        MedicoEntity medico = medicoJpa.save(new MedicoEntity(
                UUID.randomUUID(), um, "DF-33333", "Cardiologia"));
        medicoId = medico.getId();

        UsuarioEntity up = usuarioJpa.save(usuario(PerfilUsuario.PACIENTE, "matriz.p@hospital.com"));
        PacienteEntity paciente = pacienteJpa.save(new PacienteEntity(
                UUID.randomUUID(), up, "52998224725", LocalDate.of(1990, 5, 12), "+5561999990000"));
        pacienteId = paciente.getId();

        UsuarioEntity ue = usuarioJpa.save(
                usuario(PerfilUsuario.ENFERMEIRO, "matriz.e@hospital.com"));
        registranteId = ue.getId();

        ConsultaEntity c = new ConsultaEntity(
                UUID.randomUUID(), pacienteId, medicoId, registranteId);
        c.copiarDe(medicoId, OffsetDateTime.now().plusDays(7), 30, StatusConsulta.AGENDADA,
                null, null, OffsetDateTime.now(), OffsetDateTime.now());
        consultaId = consultaJpa.saveAndFlush(c).getId();

        tokenMedico = jwtService.emitir(new UsuarioAutenticado(
                um.getId(), um.getEmail(), "MEDICO", null, medicoId));
        tokenEnfermeiro = jwtService.emitir(new UsuarioAutenticado(
                ue.getId(), ue.getEmail(), "ENFERMEIRO", null, null));
        tokenPaciente = jwtService.emitir(new UsuarioAutenticado(
                up.getId(), up.getEmail(), "PACIENTE", pacienteId, null));
    }

    private static UsuarioEntity usuario(PerfilUsuario perfil, String email) {
        return new UsuarioEntity(UUID.randomUUID(), "Fulano", email, HASH_SENHA_123, perfil, true,
                OffsetDateTime.now());
    }

    // ------------------------------------------------------- guarda da leitura

    @Test
    @DisplayName("a leitura da tabela encontrou a matriz esperada")
    void leituraEncontrouAMatriz() {
        assertThat(MatrizDeAutorizacao.quantidadeDeEndpoints())
                .as("a secao 3 tem 7 linhas de endpoint; se a leitura falhar, o teste "
                        + "parametrizado abaixo passaria sem verificar nada")
                .isEqualTo(7);
        assertThat(MatrizDeAutorizacao.perfis())
                .containsExactly("MEDICO", "ENFERMEIRO", "PACIENTE");
        assertThat(MatrizDeAutorizacao.celulas()).hasSize(21);
    }

    @Test
    @DisplayName("toda celula da matriz tem uma expectativa reconhecida")
    void todaCelulaTemExpectativa() {
        assertThat(MatrizDeAutorizacao.celulas())
                .allSatisfy(c -> assertThat(c.expectativa()).isNotNull());
    }

    // ------------------------------------------------------- um teste por celula

    static Stream<MatrizDeAutorizacao.Celula> celulas() {
        return MatrizDeAutorizacao.celulas().stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("celulas")
    @DisplayName("cada celula da matriz responde conforme o documento")
    void celulaRespondeConformeODocumento(MatrizDeAutorizacao.Celula celula) {
        ResponseEntity<String> resposta = chamar(celula);
        int status = resposta.getStatusCode().value();

        switch (celula.expectativa()) {
            case PUBLICO -> assertThat(status)
                    .as("%s deveria ser publico", celula)
                    .isNotIn(401, 403);
            case PROIBIDO -> assertThat(status)
                    .as("%s deveria ser recusado por perfil", celula)
                    .isEqualTo(403);
            case PERMITIDO, PERMITIDO_COM_RECORTE -> assertThat(status)
                    .as("%s deveria ser permitido, respondeu %s com corpo %s",
                            celula, status, resposta.getBody())
                    .isNotIn(401, 403);
        }
    }

    private ResponseEntity<String> chamar(MatrizDeAutorizacao.Celula celula) {
        String caminho = celula.endpoint().replace("{id}", consultaId.toString());
        HttpMethod metodo = HttpMethod.valueOf(celula.metodo());

        HttpHeaders cabecalhos = new HttpHeaders();
        cabecalhos.setContentType(MediaType.APPLICATION_JSON);
        String token = switch (celula.perfil()) {
            case "MEDICO" -> tokenMedico;
            case "ENFERMEIRO" -> tokenEnfermeiro;
            default -> tokenPaciente;
        };
        cabecalhos.setBearerAuth(token);

        return rest.exchange(caminho, metodo, new HttpEntity<>(corpoDe(celula), cabecalhos),
                String.class);
    }

    /** Corpo minimo valido para o endpoint, para que a recusa seja de perfil e nao de forma. */
    private String corpoDe(MatrizDeAutorizacao.Celula celula) {
        if (celula.endpoint().endsWith("/auth/login")) {
            return "{\"email\":\"matriz.m@hospital.com\",\"senha\":\"Senha@123\"}";
        }
        if ("POST".equals(celula.metodo())) {
            return """
                    {"pacienteId":"%s","medicoId":"%s","registradoPorId":"%s","dataHora":"%s"}
                    """.formatted(pacienteId, medicoId, registranteId,
                    OffsetDateTime.now().plusDays(30).withNano(0));
        }
        if ("PUT".equals(celula.metodo())) {
            return "{\"observacoes\":\"ajuste\"}";
        }
        if (celula.endpoint().endsWith("/cancelar")) {
            return "{\"motivo\":\"paciente desistiu\"}";
        }
        return null;
    }

    @Test
    @DisplayName("Scenario: Autenticacao e publica para os tres perfis")
    void autenticacaoEPublicaParaOsTresPerfis() {
        assertThat(MatrizDeAutorizacao.celulas())
                .filteredOn(c -> c.endpoint().endsWith("/auth/login"))
                .as("o login precisa estar na matriz para os tres perfis; sem isso o "
                        + "teste abaixo nao verificaria nada")
                .hasSize(3)
                .allSatisfy(c -> assertThat(c.expectativa())
                        .isEqualTo(MatrizDeAutorizacao.Expectativa.PUBLICO));
    }

    @Test
    @DisplayName("a matriz cobre as 21 celulas, sem lacuna")
    void matrizCobreVinteEUmaCelulas() {
        List<MatrizDeAutorizacao.Celula> celulas = MatrizDeAutorizacao.celulas();

        assertThat(celulas).hasSize(21);
        assertThat(celulas).extracting(MatrizDeAutorizacao.Celula::endpoint)
                .doesNotContainNull();
        assertThat(celulas.stream().map(MatrizDeAutorizacao.Celula::perfil).distinct().toList())
                .hasSize(3);
    }
}
