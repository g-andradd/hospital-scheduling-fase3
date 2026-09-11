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
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Value;
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
@org.springframework.context.annotation.Import(EntradasHostisIT.CorridaConfig.class)
@DisplayName("Entradas hostis")
class EntradasHostisIT {

    @DynamicPropertySource
    static void propriedades(DynamicPropertyRegistry registro) {
        ContainerPostgres.registrarPropriedades(registro);
    }

    @Autowired private TestRestTemplate rest;
    @Autowired private JwtService jwtService;

    @Value("${hospital.jwt.secret}")
    private String segredoDaAplicacao;
    @Autowired private ConsultaJpaRepository consultaJpa;
    @Autowired private PacienteJpaRepository pacienteJpa;
    @Autowired private MedicoJpaRepository medicoJpa;
    @Autowired private UsuarioJpaRepository usuarioJpa;

    private static UUID pacienteId;
    private static UUID medicoId;
    private static UUID registranteId;
    private static UUID consultaId;
    private static UUID usuarioMedicoId;
    private static String emailMedico;

    /**
     * Credencial valida de MEDICO para toda a varredura.
     *
     * <p>Sem ela a cadeia de seguranca recusaria cada ataque com 401 antes de o dominio
     * ver a entrada, e a tabela inteira passaria sem exercitar nada. O teste
     * {@code aVarreduraAlcancaODominio} existe para provar que isso nao acontece.
     */
    private static String tokenMedico;

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
            tokenMedico = tokenValido();
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
        usuarioMedicoId = um.getId();
        emailMedico = um.getEmail();

        registranteId = usuarioJpa.save(
                usuario(PerfilUsuario.ENFERMEIRO, "hostil.e@hospital.com")).getId();

        ConsultaEntity c = new ConsultaEntity(
                UUID.randomUUID(), pacienteId, medicoId, registranteId);
        c.copiarDe(medicoId, OffsetDateTime.now().plusDays(5), 30, StatusConsulta.AGENDADA,
                null, null, OffsetDateTime.now(), OffsetDateTime.now());
        consultaId = consultaJpa.saveAndFlush(c).getId();
        tokenMedico = tokenValido();
    }

    private String tokenValido() {
        return jwtService.emitir(new UsuarioAutenticado(
                usuarioMedicoId, emailMedico, "MEDICO", null, medicoId));
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

    private HttpHeaders autenticado() {
        HttpHeaders cabecalhos = new HttpHeaders();
        cabecalhos.setContentType(MediaType.APPLICATION_JSON);
        cabecalhos.setBearerAuth(tokenMedico);
        return cabecalhos;
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

        ResponseEntity<String> resposta = rest.exchange(
                caminho, ataque.metodo(), new HttpEntity<>(corpo, autenticado()), String.class);

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

        ResponseEntity<String> resposta = rest.exchange(
                caminho, ataque.metodo(), new HttpEntity<>(corpo, autenticado()), String.class);

        if (resposta.getBody() != null && !resposta.getBody().isBlank()) {
            assertThat(resposta.getBody())
                    .as("%s vazou detalhe interno", ataque.descricao())
                    .doesNotContain("org.springframework", "org.hibernate", "com.fasterxml",
                            "java.lang.", "java.util.", "Exception", "SQL", "constraint");
        }
    }

    // ------------------------------------------------- guarda contra vacuidade

    /**
     * Sem esta assercao a varredura inteira poderia estar batendo em 401.
     *
     * <p>Foi o que aconteceu ao ligar a cadeia de seguranca neste change: os ataques
     * continuaram verdes porque nenhum chegava ao dominio. Um teste que nao pode falhar
     * nao protege.
     */
    @Test
    @DisplayName("a varredura alcanca o dominio, e nao para na cadeia de seguranca")
    void aVarreduraAlcancaODominio() {
        ResponseEntity<String> controle = rest.exchange(
                "/api/v1/consultas/" + consultaId, HttpMethod.GET,
                new HttpEntity<>(null, autenticado()), String.class);

        assertThat(controle.getStatusCode().value())
                .as("a credencial usada pela varredura precisa autenticar de verdade; se nao "
                        + "autenticar, todo ataque para no 401 e nada e exercitado")
                .isEqualTo(200);
    }

    // --------------------------------------------- superficie de autenticacao

    /**
     * Como a credencial hostil precisa ser recusada.
     *
     * <p>Nem toda recusa e 401, e distinguir importa. Um token bem assinado com um perfil
     * que nao existe <b>autentica</b> — a assinatura confere — mas nao autoriza nada, e a
     * resposta certa e 403. Colapsar os tres casos em "qualquer 4xx" esconderia justamente
     * a diferenca entre "nao sei quem e" e "sei quem e, e essa pessoa nao pode".
     */
    enum Recusa {
        /** Nao autentica: nao ha identidade utilizavel no token. */
        SEM_IDENTIDADE(401),
        /** Autentica, mas a identidade nao alcanca nenhuma operacao. */
        SEM_PERMISSAO(403),
        /** Recusado pelo servidor antes de a aplicacao ver a requisicao. */
        ANTES_DA_APLICACAO(0);

        private final int esperado;

        Recusa(int esperado) {
            this.esperado = esperado;
        }
    }

    /** Um cabecalho Authorization hostil. Valor nulo significa ausencia do cabecalho. */
    record CredencialHostil(String descricao, String valor, Recusa recusa) {
        CredencialHostil(String descricao, String valor) {
            this(descricao, valor, Recusa.SEM_IDENTIDADE);
        }

        @Override
        public String toString() {
            return descricao;
        }
    }

    private static final String SEGREDO_ALHEIO =
            "outro-segredo-de-testes-com-mais-de-32-bytes";

    Stream<CredencialHostil> credenciaisHostis() {
        // Forjado aqui, e nao reaproveitado do @BeforeEach: os argumentos do teste
        // parametrizado sao resolvidos antes de qualquer @BeforeEach rodar.
        String valido = assinadoCom(segredoDaAplicacao, Instant.now(), "MEDICO", null);

        return Stream.of(
                new CredencialHostil("sem cabecalho Authorization", null),
                new CredencialHostil("cabecalho vazio", ""),
                new CredencialHostil("so espacos", "   "),
                new CredencialHostil("Bearer sem espaco nem token", "Bearer"),
                new CredencialHostil("Bearer sem token", "Bearer "),
                new CredencialHostil("esquema errado", "Basic YWRtaW46YWRtaW4="),
                new CredencialHostil("esquema inexistente", "Token abc.def.ghi"),
                new CredencialHostil("texto aleatorio", "Bearer nao-e-um-token"),
                new CredencialHostil("token truncado", "Bearer " + valido.substring(0, 20)),
                new CredencialHostil("token sem assinatura",
                        "Bearer " + valido.substring(0, valido.lastIndexOf('.') + 1)),
                new CredencialHostil("assinatura de outro segredo",
                        "Bearer " + assinadoCom(SEGREDO_ALHEIO, Instant.now(), "MEDICO", null)),
                new CredencialHostil("token expirado",
                        "Bearer " + assinadoCom(segredoDaAplicacao,
                                Instant.now().minusSeconds(86_400), "MEDICO", null)),
                new CredencialHostil("sem claim de perfil",
                        "Bearer " + assinadoCom(segredoDaAplicacao, Instant.now(), null, null)),
                new CredencialHostil("perfil em branco",
                        "Bearer " + assinadoCom(segredoDaAplicacao, Instant.now(), "   ", null)),
                // Assinatura valida: autentica. Mas SUPERUSUARIO nao aparece em nenhum
                // @PreAuthorize, entao nao alcanca nada — negar por padrao funcionando.
                new CredencialHostil("perfil inexistente",
                        "Bearer " + assinadoCom(segredoDaAplicacao, Instant.now(),
                                "SUPERUSUARIO", null),
                        Recusa.SEM_PERMISSAO),
                new CredencialHostil("sem sujeito",
                        "Bearer " + semSujeito()),
                new CredencialHostil("sujeito malformado",
                        "Bearer " + assinadoCom(segredoDaAplicacao, Instant.now(), "MEDICO",
                                "nao-e-uuid")),
                // Autentica como PACIENTE, mas sem identificador de paciente utilizavel
                // nao ha consulta da qual seja titular: a regra de propriedade recusa.
                new CredencialHostil("pacienteId malformado", "Bearer " + pacienteMalformado(),
                        Recusa.SEM_PERMISSAO),
                // Estoura o limite de cabecalho do Tomcat: a recusa vem do conector,
                // antes de filtro ou controller. Importa que nao vire 5xx.
                new CredencialHostil("cabecalho enorme", "Bearer " + "a".repeat(8000),
                        Recusa.ANTES_DA_APLICACAO));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("credenciaisHostis")
    @DisplayName("nenhuma credencial hostil produz 5xx nem autentica")
    void nenhumaCredencialHostilProduz5xx(CredencialHostil credencial) {
        ResponseEntity<String> resposta = chamarCom(credencial);

        int status = resposta.getStatusCode().value();

        assertThat(resposta.getStatusCode().is5xxServerError())
                .as("%s respondeu %s — token invalido e situacao esperada, nao falha de "
                        + "servidor", credencial.descricao(), resposta.getStatusCode())
                .isFalse();
        assertThat(resposta.getStatusCode().is2xxSuccessful())
                .as("%s obteve acesso", credencial.descricao())
                .isFalse();

        if (credencial.recusa().esperado != 0) {
            assertThat(status)
                    .as("%s deveria ser recusado com %s", credencial.descricao(),
                            credencial.recusa().esperado)
                    .isEqualTo(credencial.recusa().esperado);
        } else {
            assertThat(status)
                    .as("%s deveria ser recusado pelo servidor", credencial.descricao())
                    .isBetween(400, 499);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("credenciaisHostis")
    @DisplayName("a recusa de credencial nao vaza detalhe interno")
    void recusaDeCredencialNaoVazaInterno(CredencialHostil credencial) {
        ResponseEntity<String> resposta = chamarCom(credencial);

        if (resposta.getBody() != null && !resposta.getBody().isBlank()) {
            assertThat(resposta.getBody())
                    .as("%s vazou detalhe interno", credencial.descricao())
                    .doesNotContain("org.springframework", "io.jsonwebtoken", "java.lang.",
                            "Exception", "SecretKey", "signature");
        }
    }

    private ResponseEntity<String> chamarCom(CredencialHostil credencial) {
        HttpHeaders cabecalhos = new HttpHeaders();
        cabecalhos.setContentType(MediaType.APPLICATION_JSON);
        if (credencial.valor() != null) {
            cabecalhos.set(HttpHeaders.AUTHORIZATION, credencial.valor());
        }
        return rest.exchange("/api/v1/consultas/" + consultaId, HttpMethod.GET,
                new HttpEntity<>(null, cabecalhos), String.class);
    }

    // ------------------------------------------------- forja de tokens hostis

    private String semSujeito() {
        return Jwts.builder()
                .claim("perfil", "MEDICO")
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(Instant.now().plusSeconds(3600)))
                .signWith(chave(segredoDaAplicacao))
                .compact();
    }

    private String pacienteMalformado() {
        return Jwts.builder()
                .subject(UUID.randomUUID().toString())
                .claim("perfil", "PACIENTE")
                .claim("pacienteId", "nao-e-uuid")
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(Instant.now().plusSeconds(3600)))
                .signWith(chave(segredoDaAplicacao))
                .compact();
    }

    private static String assinadoCom(String segredo, Instant emissao, String perfil,
            String sujeito) {
        var construtor = Jwts.builder()
                .subject(sujeito == null ? UUID.randomUUID().toString() : sujeito)
                .issuedAt(Date.from(emissao))
                .expiration(Date.from(emissao.plusSeconds(3600)));
        if (perfil != null) {
            construtor.claim("perfil", perfil);
        }
        return construtor.signWith(chave(segredo)).compact();
    }

    private static javax.crypto.SecretKey chave(String segredo) {
        return Keys.hmacShaKeyFor(segredo.getBytes(StandardCharsets.UTF_8));
    }

    @org.springframework.boot.test.context.TestConfiguration
    static class CorridaConfig {
        @org.springframework.context.annotation.Bean
        @org.springframework.context.annotation.Primary
        M05JpaBase.ConsultaComBarreira consultaComBarreira(
                br.com.fiap.hospital.agendamento.infrastructure.persistence.ConsultaRepositoryAdapter real) {
            return new M05JpaBase.ConsultaComBarreira(real);
        }
    }
    @Autowired M05JpaBase.ConsultaComBarreira barreiraM05;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbcM05;

    @Test @DisplayName("Conflito concorrente pela API retorna Problem Detail")
    
    // Scenario: Conflito concorrente pela API retorna Problem Detail
    void corridaHttpProduzUmaConsultaUmEventoE409Sem5xx() throws Exception {
        var data=java.time.OffsetDateTime.now(java.time.Clock.systemUTC()).plusDays(10).withNano(0);
        String corpo="""
                {"pacienteId":"%s","medicoId":"%s","registradoPorId":"%s","dataHora":"%s","duracaoMinutos":30}
                """.formatted(pacienteId,medicoId,registranteId,data);
        long consultasAntes=consultaJpa.count();
        long eventosAntes=jdbcM05.queryForObject("SELECT count(*) FROM outbox_evento",Long.class);
        barreiraM05.armar();
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var f1=pool.submit(()->postCorrida(corpo,"corrida-a"));
            var f2=pool.submit(()->postCorrida(corpo,"corrida-b"));
            var respostas=List.of(f1.get(20,java.util.concurrent.TimeUnit.SECONDS),f2.get(20,java.util.concurrent.TimeUnit.SECONDS));
            assertThat(respostas).extracting(r->r.getStatusCode().value()).containsExactlyInAnyOrder(201,409);
            respostas.forEach(r->assertThat(r.getStatusCode().is5xxServerError()).isFalse());
            var erro=respostas.stream().filter(r->r.getStatusCode().value()==409).findFirst().orElseThrow();
            var tree=new com.fasterxml.jackson.databind.ObjectMapper().readTree(erro.getBody());
            assertThat(tree.path("type").asText()).isIn(
                "https://hospital.fiap.br/erros/conflito-de-agenda",
                "https://hospital.fiap.br/erros/alteracao-concorrente");
            assertThat(tree.path("correlationId").asText()).isIn("corrida-a","corrida-b");
            assertThat(tree.path("timestamp").asText()).isNotBlank();
            assertThat(erro.getBody()).doesNotContain("SQL","constraint","Exception","org.hibernate","stack");
            assertThat(consultaJpa.count()).isEqualTo(consultasAntes+1);
            assertThat(jdbcM05.queryForObject("SELECT count(*) FROM outbox_evento",Long.class)).isEqualTo(eventosAntes+1);
        } finally {barreiraM05.desarmar();}
    }
    private ResponseEntity<String> postCorrida(String corpo,String correlacao) {
        var headers=autenticado(); headers.set("X-Correlation-Id",correlacao);
        return rest.exchange("/api/v1/consultas",HttpMethod.POST,new HttpEntity<>(corpo,headers),String.class);
    }
}
