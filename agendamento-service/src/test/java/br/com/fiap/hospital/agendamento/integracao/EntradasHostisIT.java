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
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
 * Ataca cada endpoint com entradas hostis e exige que <b>nenhuma</b> resposta seja 5xx.
 *
 * <p>Quatro rodadas de revisao deste change acharam o mesmo tipo de defeito: entrada nao
 * validada chegando fundo e virando 500. O tratamento por categoria protege as excecoes
 * conhecidas, mas nao garante que toda falha alcancavel produza excecao mapeada — o
 * tratador generico responde educadamente e, ao fazer isso, esconde o bug.
 *
 * <p>Este teste inverte o onus: em vez de enumerar defeitos ja vistos, varre o espaco de
 * entradas invalidas e falha em qualquer 5xx. Roda contra Postgres real de proposito —
 * violacao de chave estrangeira, que foi um dos defeitos encontrados, so acontece no
 * flush e nao apareceria com dubles.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Entradas hostis")
class EntradasHostisIT {

    @DynamicPropertySource
    static void propriedades(DynamicPropertyRegistry registro) {
        ContainerPostgres.registrarPropriedades(registro);
    }

    @Autowired private TestRestTemplate rest;
    @Autowired private ConsultaJpaRepository consultaJpa;
    @Autowired private PacienteJpaRepository pacienteJpa;
    @Autowired private MedicoJpaRepository medicoJpa;
    @Autowired private UsuarioJpaRepository usuarioJpa;

    private static UUID pacienteId;
    private static UUID medicoId;
    private static UUID registranteId;
    private static UUID consultaId;

    /** Um caso de ataque: como chamar, e com o quê. */
    record Ataque(String descricao, HttpMethod metodo, String caminho, String corpo) {
        @Override
        public String toString() {
            return descricao;
        }
    }

    @BeforeEach
    void preparar() {
        if (consultaId != null && consultaJpa.existsById(consultaId)) {
            return;
        }
        consultaJpa.deleteAll();
        pacienteJpa.deleteAll();
        medicoJpa.deleteAll();
        usuarioJpa.deleteAll();

        UsuarioEntity up = usuarioJpa.save(usuario(PerfilUsuario.PACIENTE, "hostil.p@hospital.com"));
        pacienteId = pacienteJpa.save(new PacienteEntity(UUID.randomUUID(), up, "52998224725",
                LocalDate.of(1990, 5, 12), "+5561999990000")).getId();

        UsuarioEntity um = usuarioJpa.save(usuario(PerfilUsuario.MEDICO, "hostil.m@hospital.com"));
        medicoId = medicoJpa.save(new MedicoEntity(UUID.randomUUID(), um, "DF-77777",
                "Cardiologia")).getId();

        registranteId = usuarioJpa.save(
                usuario(PerfilUsuario.ENFERMEIRO, "hostil.e@hospital.com")).getId();

        ConsultaEntity c = new ConsultaEntity(
                UUID.randomUUID(), pacienteId, medicoId, registranteId);
        c.copiarDe(medicoId, OffsetDateTime.now().plusDays(5), 30, StatusConsulta.AGENDADA,
                null, null, OffsetDateTime.now(), OffsetDateTime.now());
        consultaId = consultaJpa.saveAndFlush(c).getId();
    }

    private static UsuarioEntity usuario(PerfilUsuario perfil, String email) {
        return new UsuarioEntity(UUID.randomUUID(), "Fulano", email, "$2a$10$h", perfil, true,
                OffsetDateTime.now());
    }

    // ------------------------------------------------------------------ casos

    private static final String INEXISTENTE = "11111111-1111-1111-1111-111111111111";
    private static final String MALFORMADO = "nao-e-um-uuid";
    private static final String FUTURO = "2030-01-01T10:00:00-03:00";

    /**
     * Bordas de data e hora.
     *
     * <p>A primeira versao desta tabela nao tinha nenhuma, e por isso nao pegou o quinto
     * defeito da mesma familia: data absurda atravessava o dominio e so estourava na
     * aritmetica, virando 500. Todo campo de data e hora dos endpoints passa por elas.
     */
    private static final List<String> DATAS_HOSTIS = List.of(
            "+999999999-12-31T23:59:59.999999999-18:00",  // OffsetDateTime.MAX
            "-999999999-01-01T00:00:00+18:00",            // OffsetDateTime.MIN
            "0000-01-01T00:00:00Z",                       // ano zero
            "999999999-12-31T23:59:59Z",                  // ano maximo
            "9999-12-31T23:59:59Z",                       // muito alem do horizonte
            "2020-01-01T10:00:00-03:00",                  // passado
            "2029-12-31T10:00:00-03:00",                  // logo alem dos 24 meses
            "2026-02-30T10:00:00-03:00",                  // dia inexistente
            "2026-13-01T10:00:00-03:00",                  // mes inexistente
            "2026-09-10T25:00:00-03:00",                  // hora inexistente
            "2026-09-10T10:00:00+99:00",                  // offset impossivel
            "");

    Stream<Ataque> ataques() {
        List<Ataque> casos = new ArrayList<>();

        // --- identificadores no caminho: malformado, inexistente, vazio, estranho
        for (String id : List.of(MALFORMADO, INEXISTENTE, "0", "%20", "../../etc/passwd",
                "00000000-0000-0000-0000-000000000000")) {
            casos.add(new Ataque("GET /{id} com id=" + id, HttpMethod.GET,
                    "/api/v1/consultas/" + id, null));
            casos.add(new Ataque("PATCH confirmar com id=" + id, HttpMethod.PATCH,
                    "/api/v1/consultas/" + id + "/confirmar", null));
            casos.add(new Ataque("PATCH cancelar com id=" + id, HttpMethod.PATCH,
                    "/api/v1/consultas/" + id + "/cancelar", "{\"motivo\":\"x\"}"));
            casos.add(new Ataque("PUT com id=" + id, HttpMethod.PUT,
                    "/api/v1/consultas/" + id, "{\"observacoes\":\"x\"}"));
        }

        // --- corpo do registro: campos faltando, nulos, tipos errados, bordas
        for (String corpo : List.of(
                "",
                "{}",
                "   ",
                "[]",
                "{\"pacienteId\": ",
                "{\"pacienteId\": null, \"medicoId\": null, \"registradoPorId\": null}",
                registro(MALFORMADO, "%s", "%s", FUTURO, null),
                registro(INEXISTENTE, "%s", "%s", FUTURO, null),
                registro("%s", INEXISTENTE, "%s", FUTURO, null),
                registro("%s", "%s", INEXISTENTE, FUTURO, null),
                registro("%s", "%s", "%s", "data-invalida", null),
                registro("%s", "%s", "%s", "2020-01-01T10:00:00-03:00", null),
                registro("%s", "%s", "%s", FUTURO, "0"),
                registro("%s", "%s", "%s", FUTURO, "-1"),
                registro("%s", "%s", "%s", FUTURO, "2147483647"),
                registro("%s", "%s", "%s", FUTURO, "9999999999999"),
                registro("%s", "%s", "%s", FUTURO, "\"texto\""))) {
            casos.add(new Ataque("POST com corpo " + resumo(corpo), HttpMethod.POST,
                    "/api/v1/consultas", corpo));
        }

        // --- corpo da alteracao e do cancelamento
        for (String corpo : List.of(
                "", "{}", "[]", "{\"dataHora\": \"nao-e-data\"}",
                "{\"duracaoMinutos\": -1}", "{\"duracaoMinutos\": 2147483647}",
                "{\"medicoId\": \"" + MALFORMADO + "\"}",
                "{\"medicoId\": \"" + INEXISTENTE + "\"}",
                "{\"observacoes\": null}")) {
            casos.add(new Ataque("PUT com corpo " + resumo(corpo), HttpMethod.PUT,
                    "/api/v1/consultas/" + consultaId, corpo));
        }
        for (String corpo : List.of("", "{}", "[]", "{\"motivo\": null}", "{\"motivo\": \"\"}",
                "{\"motivo\": \"   \"}", "{\"motivo\": 42}")) {
            casos.add(new Ataque("PATCH cancelar com corpo " + resumo(corpo), HttpMethod.PATCH,
                    "/api/v1/consultas/" + consultaId + "/cancelar", corpo));
        }

        // --- bordas de data e hora em todo campo que as aceita
        for (String data : DATAS_HOSTIS) {
            casos.add(new Ataque("POST com dataHora=" + resumo(data), HttpMethod.POST,
                    "/api/v1/consultas", registro("%s", "%s", "%s", data, null)));
            casos.add(new Ataque("PUT com dataHora=" + resumo(data), HttpMethod.PUT,
                    "/api/v1/consultas/" + consultaId,
                    "{\"dataHora\":\"" + data + "\"}"));
            casos.add(new Ataque("GET lista com de=" + resumo(data), HttpMethod.GET,
                    "/api/v1/consultas?de=" + data.replace("+", "%2B"), null));
            casos.add(new Ataque("GET lista com ate=" + resumo(data), HttpMethod.GET,
                    "/api/v1/consultas?ate=" + data.replace("+", "%2B"), null));
        }

        // --- parametros de consulta: enum, data, numeros de borda, uuid
        for (String query : List.of(
                "?status=NAO_EXISTE", "?status=", "?status=agendada",
                "?de=10/09/2026", "?de=", "?ate=nao-e-data",
                "?pacienteId=" + MALFORMADO, "?medicoId=" + MALFORMADO,
                "?pagina=-1", "?pagina=2147483647", "?pagina=9999999999",
                "?tamanho=0", "?tamanho=-1", "?tamanho=2147483647", "?tamanho=9999999999",
                "?pagina=2147483647&tamanho=100", "?pagina=abc", "?tamanho=abc")) {
            casos.add(new Ataque("GET lista " + query, HttpMethod.GET,
                    "/api/v1/consultas" + query, null));
        }

        return casos.stream();
    }

    private static String registro(String paciente, String medico, String registrante,
            String dataHora, String duracao) {
        String base = """
                {"pacienteId":"%s","medicoId":"%s","registradoPorId":"%s","dataHora":"%s"
                """.formatted(paciente, medico, registrante, dataHora).strip();
        return duracao == null ? base + "}" : base + ",\"duracaoMinutos\":" + duracao + "}";
    }

    private static String resumo(String corpo) {
        String limpo = corpo.replace("\n", " ").trim();
        return limpo.length() <= 60 ? "'" + limpo + "'" : "'" + limpo.substring(0, 57) + "...'";
    }

    // ----------------------------------------------------------------- ataque

    @ParameterizedTest(name = "{0}")
    @MethodSource("ataques")
    @DisplayName("nenhuma entrada hostil produz 5xx")
    void nenhumaEntradaHostilProduz5xx(Ataque ataque) {
        String caminho = ataque.caminho()
                .replaceFirst("%s", pacienteId.toString())
                .replaceFirst("%s", medicoId.toString());

        String corpo = ataque.corpo() == null ? null : ataque.corpo()
                .replaceFirst("%s", pacienteId.toString())
                .replaceFirst("%s", medicoId.toString())
                .replaceFirst("%s", registranteId.toString());

        HttpHeaders cabecalhos = new HttpHeaders();
        cabecalhos.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<String> resposta = rest.exchange(
                caminho, ataque.metodo(), new HttpEntity<>(corpo, cabecalhos), String.class);

        assertThat(resposta.getStatusCode().is5xxServerError())
                .as("%s respondeu %s com corpo %s — entrada invalida do cliente nao pode "
                        + "virar falha de servidor", ataque.descricao(),
                        resposta.getStatusCode(), resposta.getBody())
                .isFalse();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("ataques")
    @DisplayName("nenhuma resposta a entrada hostil vaza detalhe interno")
    void nenhumaRespostaVazaInterno(Ataque ataque) {
        String caminho = ataque.caminho()
                .replaceFirst("%s", pacienteId.toString())
                .replaceFirst("%s", medicoId.toString());

        String corpo = ataque.corpo() == null ? null : ataque.corpo()
                .replaceFirst("%s", pacienteId.toString())
                .replaceFirst("%s", medicoId.toString())
                .replaceFirst("%s", registranteId.toString());

        HttpHeaders cabecalhos = new HttpHeaders();
        cabecalhos.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<String> resposta = rest.exchange(
                caminho, ataque.metodo(), new HttpEntity<>(corpo, cabecalhos), String.class);

        if (resposta.getBody() != null && !resposta.getBody().isBlank()) {
            assertThat(resposta.getBody())
                    .as("%s vazou detalhe interno", ataque.descricao())
                    .doesNotContain("org.springframework", "org.hibernate", "com.fasterxml",
                            "java.lang.", "java.util.", "Exception", "SQL", "constraint");
        }
    }
}
