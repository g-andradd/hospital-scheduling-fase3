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
import br.com.fiap.hospital.agendamento.integracao.superficie.CatalogoDeVariantes;
import br.com.fiap.hospital.agendamento.integracao.superficie.Combinacao;
import br.com.fiap.hospital.agendamento.integracao.superficie.Dimensao;
import br.com.fiap.hospital.agendamento.integracao.superficie.Endpoint;
import br.com.fiap.hospital.agendamento.integracao.superficie.Entrada;
import br.com.fiap.hospital.agendamento.integracao.superficie.InventarioDaSuperficie;
import br.com.fiap.hospital.agendamento.integracao.superficie.Localizacao;
import br.com.fiap.hospital.security.JwtService;
import br.com.fiap.hospital.security.UsuarioAutenticado;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Date;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Ataca toda a superficie HTTP do agendamento com entradas hostis e exige que nenhuma resposta
 * seja 5xx, vaze detalhe interno ou recuse fora do Problem Detail.
 *
 * <p>Ate o M09, este teste valia exatamente o que valia a tabela de entradas dele: cinco rodadas
 * de revisao do M03 acharam, cada uma, uma dimensao que a tabela nao cobria. Aqui a tabela deixou
 * de existir. O conjunto exigido e derivado dos handlers efetivamente registrados — cada
 * parametro de caminho, de consulta, corpo e campo do corpo, classificado numa dimensao — e
 * multiplicado pelo catalogo de variantes da dimensao. O conjunto executado vem de um plano de
 * ataques declarado a parte. A comparacao dos dois, no ultimo metodo, e o que acusa endpoint
 * novo, entrada nova, ataque orfao e combinacao esquecida.
 *
 * <p>A varredura sao cinco metodos {@code @Test} comuns, ordenados — e nao uma fabrica dinamica:
 * uma fabrica que nao emitisse teste algum passaria sem deixar caso no relatorio.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
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

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping mapeamentos;

    @LocalServerPort private int porta;

    /** BCrypt de Senha@123, para que o controle do login autentique de verdade. */
    private static final String HASH_SENHA_123 =
            "$2a$10$JUU8mSXfivdwzpuhR9norOIR5JKK5EcQiWSwiultOGzapLvxFTLVW";
    private static final String SENHA = "Senha@123";

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
     * ver a entrada, e a varredura inteira passaria sem exercitar nada. Os controles e o
     * teste {@code aVarreduraAlcancaODominio} existem para provar que isso nao acontece.
     */
    private static String tokenMedico;

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
        return new UsuarioEntity(UUID.randomUUID(), "Fulano", email, HASH_SENHA_123, perfil, true,
                OffsetDateTime.now());
    }

    private HttpHeaders autenticado() {
        HttpHeaders cabecalhos = new HttpHeaders();
        cabecalhos.setContentType(MediaType.APPLICATION_JSON);
        cabecalhos.setBearerAuth(tokenMedico);
        return cabecalhos;
    }

    // ================================================================ superficie e plano

    private static final Endpoint POST_CONSULTAS = new Endpoint("POST", "/api/v1/consultas");
    private static final Endpoint PUT_CONSULTA = new Endpoint("PUT", "/api/v1/consultas/{id}");
    private static final Endpoint PATCH_CONFIRMAR =
            new Endpoint("PATCH", "/api/v1/consultas/{id}/confirmar");
    private static final Endpoint PATCH_CANCELAR =
            new Endpoint("PATCH", "/api/v1/consultas/{id}/cancelar");
    private static final Endpoint GET_CONSULTA = new Endpoint("GET", "/api/v1/consultas/{id}");
    private static final Endpoint GET_LISTA = new Endpoint("GET", "/api/v1/consultas");
    private static final Endpoint POST_LOGIN = new Endpoint("POST", "/auth/login");

    /** Endpoints atacados: os seis de consulta e o login. */
    private static final Set<Endpoint> INCLUIDOS = Set.of(POST_CONSULTAS, PUT_CONSULTA,
            PATCH_CONFIRMAR, PATCH_CANCELAR, GET_CONSULTA, GET_LISTA, POST_LOGIN);

    /** Endpoints registrados e deliberadamente fora da varredura, cada um com o motivo. */
    private static final Map<Endpoint, String> EXCLUIDOS = Map.of(
            new Endpoint("GET", "/v3/api-docs"),
            "documentacao OpenAPI publica, sem entrada de negocio",
            new Endpoint("GET", "/v3/api-docs.yaml"),
            "documentacao OpenAPI publica, sem entrada de negocio",
            new Endpoint("GET", "/v3/api-docs/swagger-config"),
            "configuracao publica do Swagger UI, sem entrada de negocio",
            new Endpoint("GET", "/swagger-ui.html"),
            "redirecionamento publico para o Swagger UI, sem entrada de negocio",
            new Endpoint("*", "/error"),
            "tratamento de erro do container, sem entrada de negocio");

    private static final CatalogoDeVariantes CATALOGO = CatalogoDeVariantes.padrao();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Base dos horarios da varredura: longe dos horarios da corrida HTTP e do alvo fixo. */
    private static final OffsetDateTime BASE_DOS_HORARIOS = OffsetDateTime
            .now(ZoneOffset.ofHours(-3)).plusDays(60).truncatedTo(ChronoUnit.MINUTES);
    private static final AtomicInteger PROXIMO_HORARIO = new AtomicInteger();

    /** Uma entrada como o plano a declara — independente da descoberta. */
    record EntradaDeclarada(Localizacao localizacao, String nome, Dimensao dimensao,
                            String valorValido) {}

    /** Um endpoint no plano: controle valido, status de sucesso e entradas atacadas. */
    record Alvo(Endpoint endpoint, boolean autenticado, int sucesso,
                Supplier<Requisicao> controle, List<EntradaDeclarada> entradas) {}

    private List<Alvo> plano() {
        return List.of(
                new Alvo(POST_CONSULTAS, true, 201,
                        () -> new Requisicao("POST", "/api/v1/consultas").corpo(corpoDeRegistro()),
                        List.of(corpo(), campo("pacienteId", Dimensao.UUID),
                                campo("medicoId", Dimensao.UUID),
                                campo("registradoPorId", Dimensao.UUID),
                                campo("dataHora", Dimensao.DATA),
                                campo("duracaoMinutos", Dimensao.INTEIRO),
                                campo("observacoes", Dimensao.TEXTO))),
                new Alvo(PUT_CONSULTA, true, 200,
                        () -> new Requisicao("PUT", "/api/v1/consultas/{id}")
                                .variavel("id", novaConsulta())
                                .corpo(MAPPER.createObjectNode().put("observacoes", "controle")),
                        List.of(caminho("id"), corpo(), campo("dataHora", Dimensao.DATA),
                                campo("duracaoMinutos", Dimensao.INTEIRO),
                                campo("medicoId", Dimensao.UUID),
                                campo("observacoes", Dimensao.TEXTO))),
                new Alvo(PATCH_CONFIRMAR, true, 200,
                        () -> new Requisicao("PATCH", "/api/v1/consultas/{id}/confirmar")
                                .variavel("id", novaConsulta()),
                        List.of(caminho("id"))),
                new Alvo(PATCH_CANCELAR, true, 200,
                        () -> new Requisicao("PATCH", "/api/v1/consultas/{id}/cancelar")
                                .variavel("id", novaConsulta())
                                .corpo(MAPPER.createObjectNode().put("motivo", "controle")),
                        List.of(caminho("id"), corpo(), campo("motivo", Dimensao.TEXTO))),
                new Alvo(GET_CONSULTA, true, 200,
                        () -> new Requisicao("GET", "/api/v1/consultas/{id}")
                                .variavel("id", consultaId.toString()),
                        List.of(caminho("id"))),
                new Alvo(GET_LISTA, true, 200,
                        () -> new Requisicao("GET", "/api/v1/consultas")
                                .consulta("pagina", "0").consulta("tamanho", "100"),
                        List.of(consulta("pacienteId", Dimensao.UUID, pacienteId.toString()),
                                consulta("medicoId", Dimensao.UUID, medicoId.toString()),
                                consulta("status", Dimensao.ENUM, "AGENDADA"),
                                consulta("de", Dimensao.DATA, "2026-01-01T00:00:00Z"),
                                consulta("ate", Dimensao.DATA, "2031-01-01T00:00:00Z"),
                                consulta("pagina", Dimensao.INTEIRO, "0"),
                                consulta("tamanho", Dimensao.INTEIRO, "100"))),
                new Alvo(POST_LOGIN, false, 200,
                        () -> new Requisicao("POST", "/auth/login").corpo(MAPPER.createObjectNode()
                                .put("email", emailMedico).put("senha", SENHA)),
                        List.of(corpo(), campo("email", Dimensao.TEXTO),
                                campo("senha", Dimensao.TEXTO))));
    }

    private static EntradaDeclarada corpo() {
        return new EntradaDeclarada(Localizacao.CORPO, "corpo", Dimensao.CORPO, null);
    }

    private static EntradaDeclarada campo(String nome, Dimensao dimensao) {
        return new EntradaDeclarada(Localizacao.CAMPO, nome, dimensao, null);
    }

    private static EntradaDeclarada caminho(String nome) {
        return new EntradaDeclarada(Localizacao.CAMINHO, nome, Dimensao.UUID, null);
    }

    private static EntradaDeclarada consulta(String nome, Dimensao dimensao, String valido) {
        return new EntradaDeclarada(Localizacao.CONSULTA, nome, dimensao, valido);
    }

    private ObjectNode corpoDeRegistro() {
        return MAPPER.createObjectNode()
                .put("pacienteId", pacienteId.toString())
                .put("medicoId", medicoId.toString())
                .put("registradoPorId", registranteId.toString())
                .put("dataHora", proximoHorario().toString())
                .put("duracaoMinutos", 30)
                .put("observacoes", "controle");
    }

    /** Horarios de 40 em 40 minutos: nenhum alvo novo se sobrepoe a outro. */
    private static OffsetDateTime proximoHorario() {
        return BASE_DOS_HORARIOS.plusMinutes(40L * PROXIMO_HORARIO.getAndIncrement());
    }

    /** Consultas criadas por uma combinacao, descartadas assim que ela e verificada. */
    private final List<UUID> alvosDaCombinacao = new ArrayList<>();

    /** Alvo novo para operacoes que mudam estado: um valor hostil aceito nao contamina o proximo. */
    private String novaConsulta() {
        ConsultaEntity c = new ConsultaEntity(UUID.randomUUID(), pacienteId, medicoId, registranteId);
        c.copiarDe(medicoId, proximoHorario(), 30, StatusConsulta.AGENDADA, null, null,
                OffsetDateTime.now(), OffsetDateTime.now());
        ConsultaEntity salva = consultaJpa.saveAndFlush(c);
        alvosDaCombinacao.add(salva.getId());
        return salva.getId().toString();
    }

    /**
     * Descarta o que a combinacao deixou no banco, depois de a resposta ja ter sido verificada.
     *
     * <p>Um valor hostil <b>aceito</b> muda a agenda, e algumas dessas mudancas impedem as
     * combinacoes seguintes: {@code duracaoMinutos} no teto do inteiro cria uma consulta que
     * ocupa a agenda do medico por milenios, e a restricao de sobreposicao passa a recusar
     * qualquer alvo novo. Sem o descarte, a varredura para de varrer no meio — e para por um
     * efeito colateral do proprio ataque anterior, nao por defeito do endpoint sob ataque.
     */
    private void descartarAlvos(Alvo alvo, HttpResponse<String> resposta) {
        if (alvo.endpoint().equals(POST_CONSULTAS) && resposta.statusCode() / 100 == 2) {
            try {
                JsonNode criada = MAPPER.readTree(resposta.body());
                if (criada.hasNonNull("id")) {
                    alvosDaCombinacao.add(UUID.fromString(criada.get("id").asText()));
                }
            } catch (Exception semJsonUtilizavel) {
                // resposta 2xx sem identificador legivel: nao ha o que descartar
            }
        }
        if (!alvosDaCombinacao.isEmpty()) {
            consultaJpa.deleteAllById(List.copyOf(alvosDaCombinacao));
            alvosDaCombinacao.clear();
        }
    }

    // ================================================================ transporte

    /** Uma requisicao montada a partir do controle, com a entrada atacada substituida. */
    static final class Requisicao {
        final String metodo;
        final String rota;
        final Map<String, String> variaveis = new LinkedHashMap<>();
        final Map<String, String> consulta = new LinkedHashMap<>();
        ObjectNode corpo;
        boolean corpoBrutoDefinido;
        String corpoBruto;
        String contentType = MediaType.APPLICATION_JSON_VALUE;

        Requisicao(String metodo, String rota) {
            this.metodo = metodo;
            this.rota = rota;
        }

        Requisicao variavel(String nome, String valor) {
            variaveis.put(nome, valor);
            return this;
        }

        Requisicao consulta(String nome, String valor) {
            consulta.put(nome, valor);
            return this;
        }

        Requisicao corpo(ObjectNode corpo) {
            this.corpo = corpo;
            return this;
        }

        String caminho() {
            String caminho = rota;
            for (var variavel : variaveis.entrySet()) {
                caminho = caminho.replace("{" + variavel.getKey() + "}", variavel.getValue());
            }
            if (consulta.isEmpty()) {
                return caminho;
            }
            return caminho + "?" + consulta.entrySet().stream()
                    .map(p -> p.getKey() + "=" + URLEncoder.encode(p.getValue(), StandardCharsets.UTF_8)
                            .replace("+", "%20"))
                    .collect(Collectors.joining("&"));
        }

        String corpoEnviado() {
            if (corpoBrutoDefinido) {
                return corpoBruto;
            }
            return corpo == null ? null : corpo.toString();
        }
    }

    private final HttpClient cliente = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /** Envia os bytes exatos: a URI ja vai codificada, e nada a normaliza no caminho. */
    private HttpResponse<String> enviar(Alvo alvo, Requisicao requisicao) {
        String corpo = requisicao.corpoEnviado();
        HttpRequest.Builder construtor = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + porta + requisicao.caminho()))
                .timeout(Duration.ofSeconds(30))
                .method(requisicao.metodo, corpo == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(corpo, StandardCharsets.UTF_8));
        if (corpo != null) {
            construtor.header("Content-Type", requisicao.contentType);
        }
        if (alvo.autenticado()) {
            construtor.header("Authorization", "Bearer " + tokenMedico);
        }
        try {
            return cliente.send(construtor.build(), HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new IllegalStateException("falha de envio: " + requisicao.metodo + " "
                    + requisicao.caminho(), e);
        }
    }

    // ================================================================ varredura

    private static final List<String> MARCADORES_DE_VAZAMENTO = List.of(
            "org.springframework", "org.hibernate", "com.fasterxml", "java.lang.", "java.util.",
            "Exception", "SQL", "constraint");

    /** Executado nas duas passagens; vazio antes da primeira. */
    private final Set<Combinacao> executadasNaPassagem1 = new LinkedHashSet<>();
    private final Set<Combinacao> executadasNaPassagem2 = new LinkedHashSet<>();

    private Map<String, Entrada> entradasDescobertas;

    @Test
    @Order(1)
    @DisplayName("Scenario: Controle valido prova que o ataque alcanca a camada pretendida")
    void controles() {
        assertThat(executarControles()).as("controles que nao responderam com sucesso").isEmpty();
    }

    @Test
    @Order(2)
    @DisplayName("Scenario: Nenhuma entrada hostil produz 5xx nem vaza detalhe interno (passagem 1)")
    void passagem1() {
        assertThat(executarPassagem(1, executadasNaPassagem1))
                .as("respostas a entradas hostis na passagem 1").isEmpty();
    }

    @Test
    @Order(3)
    @DisplayName("Scenario: Repeticao da varredura nao degrada o servico (passagem 2)")
    void passagem2() {
        assertThat(executarPassagem(2, executadasNaPassagem2))
                .as("respostas a entradas hostis na passagem 2").isEmpty();
    }

    @Test
    @Order(4)
    @DisplayName("controles continuam respondendo com sucesso depois das duas passagens")
    void controlesFinais() {
        assertThat(executarControles()).as("controles depois da varredura").isEmpty();
    }

    @Test
    @Order(5)
    @DisplayName("Scenario: Cada entrada compativel e atacada em todas as variantes da sua dimensao")
    void comparacao() {
        Map<Endpoint, java.lang.reflect.Method> descobertos = endpointsDescobertos();
        List<String> erros = new ArrayList<>(InventarioDaSuperficie.verificarClassificacao(
                descobertos.keySet(), INCLUIDOS, EXCLUIDOS));
        Set<Endpoint> planejados = plano().stream().map(Alvo::endpoint).collect(Collectors.toSet());
        if (!planejados.equals(INCLUIDOS)) {
            erros.add("plano de ataques divergente dos endpoints incluidos: " + planejados);
        }
        CATALOGO.minimosAusentes().forEach(m -> erros.add("variante minima ausente do catalogo: " + m));

        List<Entrada> entradas = List.copyOf(entradasDescobertas().values());
        Set<Combinacao> exigidas = InventarioDaSuperficie.exigidas(entradas, CATALOGO);
        Set<Dimensao> todas = EnumSet.allOf(Dimensao.class);
        InventarioDaSuperficie.comparar(entradas, exigidas, executadasNaPassagem1, todas)
                .forEach(e -> erros.add("passagem 1: " + e));
        InventarioDaSuperficie.comparar(entradas, exigidas, executadasNaPassagem2, todas)
                .forEach(e -> erros.add("passagem 2: " + e));

        System.out.printf("[varredura] endpoints descobertos=%d incluidos=%d entradas=%d "
                        + "exigidas=%d executadas(p1)=%d executadas(p2)=%d%n",
                descobertos.size(), INCLUIDOS.size(), entradas.size(), exigidas.size(),
                executadasNaPassagem1.size(), executadasNaPassagem2.size());
        exigidas.stream().collect(Collectors.groupingBy(Combinacao::dimensao, TreeMap::new,
                        Collectors.counting()))
                .forEach((d, n) -> System.out.printf("[varredura] dimensao %s: %d combinacoes%n", d, n));

        assertThat(erros).as("inventario da superficie contra a varredura executada").isEmpty();
    }

    private List<String> executarControles() {
        List<String> falhas = new ArrayList<>();
        for (Alvo alvo : plano()) {
            for (EntradaDeclarada entrada : alvo.entradas()) {
                Requisicao requisicao = alvo.controle().get();
                if (entrada.localizacao() == Localizacao.CONSULTA) {
                    requisicao.consulta(entrada.nome(), entrada.valorValido());
                }
                HttpResponse<String> resposta = enviar(alvo, requisicao);
                if (resposta.statusCode() != alvo.sucesso()) {
                    falhas.add(alvo.endpoint() + " | controle de " + entrada.localizacao() + " "
                            + entrada.nome() + " respondeu " + resposta.statusCode()
                            + " em vez de " + alvo.sucesso() + ": " + resposta.body());
                }
                descartarAlvos(alvo, resposta);
            }
        }
        return falhas;
    }

    private List<String> executarPassagem(int numero, Set<Combinacao> executadas) {
        List<String> falhas = new ArrayList<>();
        Map<String, Map<Integer, Integer>> contagens = new TreeMap<>();
        for (Alvo alvo : plano()) {
            for (EntradaDeclarada entrada : alvo.entradas()) {
                for (CatalogoDeVariantes.Variante variante
                        : CATALOGO.aplicaveis(entrada.dimensao(), entrada.localizacao())) {
                    Combinacao combinacao = new Combinacao(alvo.endpoint(), entrada.localizacao(),
                            entrada.nome(), entrada.dimensao(), variante.nome());
                    Requisicao requisicao = atacar(alvo, entrada, variante);
                    HttpResponse<String> resposta = enviar(alvo, requisicao);
                    executadas.add(combinacao);
                    contagens.computeIfAbsent(combinacao.chaveDaEntrada() + " | " + entrada.dimensao(),
                                    k -> new TreeMap<>())
                            .merge(resposta.statusCode(), 1, Integer::sum);
                    falhas.addAll(verificar(combinacao, variante, requisicao, resposta));
                    descartarAlvos(alvo, resposta);
                }
            }
        }
        contagens.forEach((entrada, porStatus) ->
                System.out.printf("[varredura] passagem %d | %s -> %s%n", numero, entrada, porStatus));
        return falhas;
    }

    private Requisicao atacar(Alvo alvo, EntradaDeclarada entrada,
                              CatalogoDeVariantes.Variante variante) {
        Requisicao requisicao = alvo.controle().get();
        Entrada descoberta = entradasDescobertas().get(
                alvo.endpoint() + " | " + entrada.localizacao() + " " + entrada.nome());
        ObjectNode corpoValido = requisicao.corpo == null
                ? MAPPER.createObjectNode() : requisicao.corpo.deepCopy();
        String valor = variante.renderizar(new CatalogoDeVariantes.Contexto(descoberta, corpoValido));
        switch (entrada.localizacao()) {
            case CAMINHO -> requisicao.variavel(entrada.nome(), valor);
            case CONSULTA -> requisicao.consulta(entrada.nome(), valor);
            case CAMPO -> requisicao.corpo.set(entrada.nome(), lerJson(valor));
            case CORPO -> {
                requisicao.corpoBrutoDefinido = true;
                requisicao.corpoBruto = valor;
                if (variante.contentType() != null) {
                    requisicao.contentType = variante.contentType();
                }
            }
        }
        return requisicao;
    }

    private static JsonNode lerJson(String literal) {
        try {
            return MAPPER.readTree(literal);
        } catch (Exception e) {
            throw new IllegalStateException("literal JSON invalido no catalogo: " + literal, e);
        }
    }

    /**
     * As invariantes de toda resposta a entrada hostil.
     *
     * <p>Nao 5xx; nada interno no corpo; e, quando a aplicacao recusa, Problem Detail completo.
     * A recusa sem Problem Detail so e aceita nas variantes que o conector ou o firewall
     * recusam antes de a requisicao chegar a aplicacao.
     */
    private List<String> verificar(Combinacao combinacao, CatalogoDeVariantes.Variante variante,
                                   Requisicao requisicao, HttpResponse<String> resposta) {
        List<String> falhas = new ArrayList<>();
        String rotulo = combinacao + " (" + requisicao.metodo + " " + requisicao.caminho() + ")";
        int status = resposta.statusCode();
        String corpo = resposta.body() == null ? "" : resposta.body();

        if (status >= 500) {
            falhas.add(rotulo + " respondeu " + status + ": " + corpo);
            return falhas;
        }
        if (status >= 300 && status < 400) {
            falhas.add(rotulo + " respondeu redirecionamento " + status);
        }
        for (String marcador : MARCADORES_DE_VAZAMENTO) {
            if (corpo.contains(marcador)) {
                falhas.add(rotulo + " vazou '" + marcador + "': " + corpo);
            }
        }
        if (status >= 400) {
            String tipo = resposta.headers().firstValue("Content-Type").orElse("");
            if (tipo.startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE)) {
                falhas.addAll(exigirProblemDetail(rotulo, corpo));
            } else if (!variante.recusavelPeloServidor()) {
                falhas.add(rotulo + " recusou com " + status + " sem Problem Detail ("
                        + tipo + "): " + corpo);
            }
        }
        return falhas;
    }

    private static List<String> exigirProblemDetail(String rotulo, String corpo) {
        List<String> falhas = new ArrayList<>();
        try {
            JsonNode problema = MAPPER.readTree(corpo);
            for (String campo : List.of("type", "title", "status", "correlationId", "timestamp")) {
                if (!problema.hasNonNull(campo) || problema.get(campo).asText().isBlank()) {
                    falhas.add(rotulo + " Problem Detail sem '" + campo + "': " + corpo);
                }
            }
        } catch (Exception e) {
            falhas.add(rotulo + " Problem Detail ilegivel: " + corpo);
        }
        return falhas;
    }

    // ================================================================ descoberta

    private Map<Endpoint, java.lang.reflect.Method> endpointsDescobertos() {
        List<InventarioDaSuperficie.Handler> handlers = new ArrayList<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> registro
                : mapeamentos.getHandlerMethods().entrySet()) {
            RequestMappingInfo info = registro.getKey();
            Set<String> metodos = info.getMethodsCondition().getMethods().stream()
                    .map(Enum::name).collect(Collectors.toSet());
            handlers.add(new InventarioDaSuperficie.Handler(metodos, info.getPatternValues(),
                    registro.getValue().getMethod()));
        }
        return InventarioDaSuperficie.endpoints(handlers);
    }

    private Map<String, Entrada> entradasDescobertas() {
        if (entradasDescobertas == null) {
            Map<Endpoint, java.lang.reflect.Method> descobertos = endpointsDescobertos();
            Map<String, Entrada> mapa = new LinkedHashMap<>();
            for (Endpoint endpoint : INCLUIDOS) {
                java.lang.reflect.Method metodo = descobertos.get(endpoint);
                if (metodo != null) {
                    InventarioDaSuperficie.entradasDe(endpoint, metodo)
                            .forEach(e -> mapa.put(e.chave(), e));
                }
            }
            entradasDescobertas = mapa;
        }
        return entradasDescobertas;
    }

    // ================================================================ guarda contra vacuidade

    /**
     * Sem esta assercao a varredura inteira poderia estar batendo em 401.
     *
     * <p>Foi o que aconteceu ao ligar a cadeia de seguranca no M04: os ataques
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

    // ------------------------------------------------- corrida HTTP do M05

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
