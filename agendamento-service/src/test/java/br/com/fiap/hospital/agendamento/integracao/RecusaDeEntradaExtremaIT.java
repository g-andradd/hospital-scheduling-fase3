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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Regressoes das quatro recusas que a varredura hostil obrigou a criar.
 *
 * <p>Cada uma nasceu de um 5xx reproduzivel: limite de intervalo fora da faixa do banco,
 * caractere de controle em texto que viaja no evento, chave JSON duplicada no corpo e
 * duracao que projeta o fim da consulta para milenios adiante. A varredura prova que
 * nenhuma resposta e 5xx; estes testes provam <b>qual</b> e a resposta e, sobretudo, que
 * a recusa acontece <b>antes de qualquer efeito</b> — nada persistido, nada no outbox,
 * nada publicado.
 *
 * <p>Cada bloco tem tambem um controle imediatamente dentro da fronteira adotada. Sem
 * ele, uma correcao que recusasse tudo passaria igual.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("Recusa de entrada extrema")
class RecusaDeEntradaExtremaIT {

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
    @Autowired private JdbcTemplate jdbc;
    @LocalServerPort private int porta;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final AtomicInteger PROXIMO = new AtomicInteger();

    private static final String NUL = String.valueOf((char) 0);
    private static final char U0001 = (char) 0x01;
    private static final char U001F = (char) 0x1F;

    private UUID pacienteId;
    private UUID medicoId;
    private UUID registranteId;
    private UUID consultaId;
    private String token;

    @BeforeEach
    void preparar() {
        consultaJpa.deleteAll();
        pacienteJpa.deleteAll();
        medicoJpa.deleteAll();
        usuarioJpa.deleteAll();

        UsuarioEntity up = usuarioJpa.save(usuario(PerfilUsuario.PACIENTE, "extrema.p@hospital.com"));
        pacienteId = pacienteJpa.save(new PacienteEntity(UUID.randomUUID(), up, "52998224725",
                LocalDate.of(1990, 5, 12), "+5561999990000")).getId();

        UsuarioEntity um = usuarioJpa.save(usuario(PerfilUsuario.MEDICO, "extrema.m@hospital.com"));
        medicoId = medicoJpa.save(new MedicoEntity(UUID.randomUUID(), um, "DF-66666",
                "Cardiologia")).getId();

        registranteId = usuarioJpa.save(
                usuario(PerfilUsuario.ENFERMEIRO, "extrema.e@hospital.com")).getId();

        consultaId = novaConsulta();
        token = jwtService.emitir(
                new UsuarioAutenticado(um.getId(), um.getEmail(), "MEDICO", null, medicoId));
    }

    private static UsuarioEntity usuario(PerfilUsuario perfil, String email) {
        return new UsuarioEntity(UUID.randomUUID(), "Fulano", email, "$2a$10$h", perfil, true,
                OffsetDateTime.now());
    }

    private UUID novaConsulta() {
        ConsultaEntity c = new ConsultaEntity(UUID.randomUUID(), pacienteId, medicoId, registranteId);
        c.copiarDe(medicoId, horarioLivre(), 30, StatusConsulta.AGENDADA, null, null,
                OffsetDateTime.now(), OffsetDateTime.now());
        return consultaJpa.saveAndFlush(c).getId();
    }

    /** Horarios de 40 em 40 minutos: nenhum alvo deste teste conflita com outro. */
    private static OffsetDateTime horarioLivre() {
        return OffsetDateTime.now(ZoneOffset.ofHours(-3)).plusDays(20)
                .truncatedTo(ChronoUnit.MINUTES)
                .plusMinutes(40L * PROXIMO.getAndIncrement());
    }

    // ---------------------------------------------------------------- D17.1

    @Test
    @DisplayName("Scenario: Intervalo de listagem fora da faixa persistível é recusado")
    void limiteDeIntervaloForaDaFaixaERecusado() {
        String maximo = "%2B999999999-12-31T23:59:59.999999999-18:00";
        String minimo = "-999999999-01-01T00:00:00%2B18:00";

        for (String consulta : new String[] {
                "?de=" + maximo, "?de=" + minimo, "?ate=" + maximo, "?ate=" + minimo,
                "?de=" + minimo + "&ate=" + maximo}) {
            ResponseEntity<String> resposta = chamarCrua("/api/v1/consultas" + consulta);

            assertThat(resposta.getStatusCode().value())
                    .as("limite fora da faixa em %s: %s", consulta, resposta.getBody())
                    .isEqualTo(400);
            assertThat(problema(resposta).path("type").asText())
                    .as("recusa de %s", consulta)
                    .startsWith("https://hospital.fiap.br/erros/");
            assertThat(resposta.getBody())
                    .doesNotContain("SQL", "constraint", "Exception", "org.hibernate");
        }
    }

    /**
     * Ano que o parser ISO aceita e o armazenamento nao representa.
     *
     * <p>Os extremos do teste acima podem ser recusados ja na conversao do parametro. Este
     * valor passa pela conversao e chega ao dominio: e ele que exercita a faixa temporal
     * suportada, e sem ele a correcao poderia estar morta sem ninguem perceber.
     */
    @Test
    @DisplayName("limite que o parser aceita e o armazenamento não representa é recusado pelo domínio")
    void limiteAlemDoArmazenamentoERecusadoPeloDominio() {
        ResponseEntity<String> resposta =
                chamarCrua("/api/v1/consultas?de=%2B300000-01-01T00:00:00Z");

        assertThat(resposta.getStatusCode().value())
                .as("resposta: %s", resposta.getBody()).isEqualTo(400);
        assertThat(problema(resposta).path("type").asText())
                .as("quem recusou: %s", resposta.getBody())
                .isEqualTo("https://hospital.fiap.br/erros/argumento-invalido");
    }

    @Test
    @DisplayName("limite imediatamente dentro da faixa continua listando")
    void limiteDentroDaFaixaEAceito() {
        ResponseEntity<String> resposta =
                chamarCrua("/api/v1/consultas?de=0001-01-01T00:00:00Z&ate=9999-12-31T23:59:59Z");

        assertThat(resposta.getStatusCode().value()).isEqualTo(200);
    }

    // ---------------------------------------------------------------- D17.2

    @Test
    @DisplayName("Scenario: Texto com caractere de controle é recusado antes de qualquer efeito")
    void textoComCaractereDeControleERecusado() {
        long consultasAntes = consultaJpa.count();
        long eventosAntes = eventos();

        for (String motivo : new String[] {NUL, NUL + "motivo", "mo" + NUL + "tivo", "motivo" + NUL}) {
            ResponseEntity<String> resposta = chamar(HttpMethod.PATCH,
                    "/api/v1/consultas/" + consultaId + "/cancelar",
                    MAPPER.createObjectNode().put("motivo", motivo).toString());

            assertThat(resposta.getStatusCode().value()).as("motivo %s", escapado(motivo))
                    .isEqualTo(422);
            assertThat(problema(resposta).path("type").asText())
                    .isEqualTo("https://hospital.fiap.br/erros/texto-com-caractere-invalido");
        }

        ResponseEntity<String> comObservacoes = chamar(HttpMethod.POST, "/api/v1/consultas",
                registro(horarioLivre(), 30).put("observacoes", "obser\0vacoes").toString());
        assertThat(comObservacoes.getStatusCode().value()).isEqualTo(422);

        assertThat(consultaJpa.findById(consultaId).orElseThrow().getStatus())
                .as("a consulta nao pode ter sido cancelada por um motivo recusado")
                .isEqualTo(StatusConsulta.AGENDADA);
        assertThat(consultaJpa.count()).isEqualTo(consultasAntes);
        assertThat(eventos()).as("nada pode ter chegado ao outbox").isEqualTo(eventosAntes);
    }

    @Test
    @DisplayName("observações com caractere de controle na alteração são recusadas sem efeito")
    void alteracaoComObservacoesInvalidasERecusada() {
        Snapshot antes = snapshot(consultaId);
        long eventosAntes = eventos();

        for (String observacoes : new String[] {NUL, NUL + "nas bordas" + NUL, "no me" + NUL + "io", "fim" + NUL}) {
            ResponseEntity<String> resposta = chamar(HttpMethod.PUT,
                    "/api/v1/consultas/" + consultaId,
                    MAPPER.createObjectNode().put("observacoes", observacoes).toString());

            assertThat(resposta.getStatusCode().value())
                    .as("observacoes %s", escapado(observacoes)).isEqualTo(422);
            assertThat(problema(resposta).path("type").asText())
                    .isEqualTo("https://hospital.fiap.br/erros/texto-com-caractere-invalido");
            assertThat(snapshot(consultaId))
                    .as("a consulta nao pode ter sido alterada por observacoes recusadas")
                    .isEqualTo(antes);
            assertThat(eventos()).as("nada pode ter chegado ao outbox").isEqualTo(eventosAntes);
        }

        ResponseEntity<String> legitima = chamar(HttpMethod.PUT,
                "/api/v1/consultas/" + consultaId,
                MAPPER.createObjectNode().put("observacoes", "Paciente com histórico de alergia")
                        .toString());

        assertThat(legitima.getStatusCode().value())
                .as("a alteracao legitima continua aceita: %s", legitima.getBody())
                .isEqualTo(200);
        assertThat(snapshot(consultaId).observacoes())
                .isEqualTo("Paciente com histórico de alergia");
    }

    @Test
    @DisplayName("credencial com caractere de controle é recusada como inválida, sem 5xx")
    void credencialComCaractereDeControleERecusada() {
        for (String corpo : new String[] {
                MAPPER.createObjectNode().put("email", "medi\0co@hospital.com")
                        .put("senha", "Senha@123").toString(),
                MAPPER.createObjectNode().put("email", "extrema.m@hospital.com")
                        .put("senha", "Se\0nha").toString()}) {

            ResponseEntity<String> resposta = chamar(HttpMethod.POST, "/auth/login", corpo);

            assertThat(resposta.getStatusCode().is5xxServerError())
                    .as("credencial nao representavel virou falha de servidor: %s",
                            resposta.getBody())
                    .isFalse();
            assertThat(resposta.getStatusCode().value())
                    .as("a recusa precisa ser indistinguivel de qualquer credencial invalida")
                    .isEqualTo(401);
        }
    }

    @Test
    @DisplayName("texto com quebra de linha e acento continua aceito")
    void textoLegitimoEAceito() {
        ResponseEntity<String> resposta = chamar(HttpMethod.PATCH,
                "/api/v1/consultas/" + consultaId + "/cancelar",
                MAPPER.createObjectNode().put("motivo", "Paciente remarcou.\nContato por telefone")
                        .toString());

        assertThat(resposta.getStatusCode().value()).isEqualTo(200);
    }

    // ---------------------------------------------------------------- D17.3

    @Test
    @DisplayName("Scenario: Corpo com chave duplicada é recusado")
    void corpoComChaveDuplicadaERecusado() {
        record Alvo(HttpMethod metodo, String caminho, String corpo) {}
        var alvos = new Alvo[] {
                new Alvo(HttpMethod.POST, "/api/v1/consultas",
                        "{\"pacienteId\":\"" + pacienteId + "\",\"pacienteId\":\"" + pacienteId
                                + "\",\"medicoId\":\"" + medicoId + "\",\"registradoPorId\":\""
                                + registranteId + "\",\"dataHora\":\"" + horarioLivre() + "\"}"),
                new Alvo(HttpMethod.PUT, "/api/v1/consultas/" + consultaId,
                        "{\"observacoes\":\"a\",\"observacoes\":\"b\"}"),
                new Alvo(HttpMethod.PATCH, "/api/v1/consultas/" + consultaId + "/cancelar",
                        "{\"motivo\":\"a\",\"motivo\":\"b\"}"),
                new Alvo(HttpMethod.POST, "/auth/login",
                        "{\"email\":\"a@b.com\",\"email\":\"c@d.com\",\"senha\":\"Senha@123\"}")};

        // O estado e conferido por alvo, imediatamente antes e depois de cada requisicao.
        // Uma unica conferencia no fim deixaria um alvo mascarar o outro: bastaria que a
        // criacao recusada e uma exclusao acidental se cancelassem na contagem.
        for (Alvo alvo : alvos) {
            Snapshot antes = snapshot(consultaId);
            long consultasAntes = consultaJpa.count();
            long eventosAntes = eventos();

            ResponseEntity<String> resposta = chamar(alvo.metodo(), alvo.caminho(), alvo.corpo());

            assertThat(resposta.getStatusCode().value())
                    .as("%s %s com chave duplicada: %s", alvo.metodo(), alvo.caminho(),
                            resposta.getBody())
                    .isEqualTo(400);
            assertThat(problema(resposta).path("type").asText())
                    .isEqualTo("https://hospital.fiap.br/erros/requisicao-malformada");
            assertThat(resposta.getBody())
                    .doesNotContain("Exception", "com.fasterxml", "org.springframework", "java.lang.");

            assertThat(consultaJpa.count())
                    .as("%s %s criou ou removeu consulta", alvo.metodo(), alvo.caminho())
                    .isEqualTo(consultasAntes);
            assertThat(snapshot(consultaId))
                    .as("%s %s alterou o alvo", alvo.metodo(), alvo.caminho())
                    .isEqualTo(antes);
            assertThat(eventos())
                    .as("%s %s escreveu no outbox", alvo.metodo(), alvo.caminho())
                    .isEqualTo(eventosAntes);
        }
    }

    // ---------------------------------------------------------------- D17.4

    @Test
    @DisplayName("Scenario: Duração que projeta o fim além do horizonte é recusada")
    void duracaoAlemDoHorizonteERecusada() {
        long consultasAntes = consultaJpa.count();
        long eventosAntes = eventos();

        for (int duracao : new int[] {Integer.MAX_VALUE, 60 * 24 * 400}) {
            ResponseEntity<String> resposta = chamar(HttpMethod.POST, "/api/v1/consultas",
                    registro(OffsetDateTime.now(ZoneOffset.ofHours(-3)).plusMonths(23), duracao)
                            .toString());

            assertThat(resposta.getStatusCode().value()).as("duracao %d", duracao).isEqualTo(422);
            assertThat(problema(resposta).path("type").asText())
                    .isEqualTo("https://hospital.fiap.br/erros/agendamento-fora-do-horizonte");
        }

        assertThat(consultaJpa.count()).as("nenhuma consulta pode ter sido criada")
                .isEqualTo(consultasAntes);
        assertThat(eventos()).isEqualTo(eventosAntes);
    }

    @Test
    @DisplayName("Scenario: Alteração que projeta o fim além do horizonte é recusada")
    void alteracaoAlemDoHorizonteERecusada() {
        var antes = consultaJpa.findById(consultaId).orElseThrow();
        int duracaoAntes = antes.getDuracaoMinutos();
        long eventosAntes = eventos();

        ResponseEntity<String> resposta = chamar(HttpMethod.PUT, "/api/v1/consultas/" + consultaId,
                MAPPER.createObjectNode().put("duracaoMinutos", Integer.MAX_VALUE).toString());

        assertThat(resposta.getStatusCode().value()).isEqualTo(422);
        assertThat(problema(resposta).path("type").asText())
                .isEqualTo("https://hospital.fiap.br/erros/agendamento-fora-do-horizonte");
        assertThat(consultaJpa.findById(consultaId).orElseThrow().getDuracaoMinutos())
                .as("a consulta nao pode ter sido alterada").isEqualTo(duracaoAntes);
        assertThat(eventos()).isEqualTo(eventosAntes);
    }

    @Test
    @DisplayName("duração cujo fim permanece dentro do horizonte continua aceita")
    void duracaoDentroDoHorizonteEAceita() {
        ResponseEntity<String> resposta = chamar(HttpMethod.POST, "/api/v1/consultas",
                registro(horarioLivre(), 30).toString());

        assertThat(resposta.getStatusCode().value()).isEqualTo(201);
    }

    // ---------------------------------------------------------------- caracterizacao de texto

    /**
     * Evidencia tecnica, por caractere e por destino, independente da politica do servico.
     *
     * <p>Os tres destinos do caminho de producao sao exercitados diretamente: a coluna
     * {@code text} da consulta, o {@code jsonb} do outbox — com o valor chegando como escape
     * JSON, que e como o Jackson o serializa — e o parametro da consulta de credencial. So
     * assim a recusa do servico pode ser justificada por incompatibilidade real, e nao por
     * suposicao sobre o que "parece" caractere invalido.
     */
    @Test
    @DisplayName("caracterização: só o NUL é incompatível com coluna, jsonb e parâmetro de consulta")
    void caracterizacaoTecnicaPorDestino() {
        String barra = String.valueOf((char) 92);

        for (char controle : new char[] {U0001, U001F}) {
            String valor = "an" + controle + "tes";
            jdbc.update("UPDATE consulta SET observacoes = ? WHERE id = ?", valor, consultaId);
            assertThat(jdbc.queryForObject("SELECT observacoes FROM consulta WHERE id = ?",
                    String.class, consultaId))
                    .as("coluna text com U+%04X", (int) controle).isEqualTo(valor);

            String json = "{\"t\":\"an" + barra + String.format("u%04x", (int) controle) + "tes\"}";
            assertThat(jdbc.queryForObject("SELECT (?::jsonb)->>'t'", String.class, json))
                    .as("jsonb com U+%04X em escape", (int) controle).isEqualTo(valor);

            assertThat(jdbc.queryForObject("SELECT count(*) FROM usuario WHERE email = ?",
                    Long.class, "medi" + controle + "co@hospital.com"))
                    .as("parametro de consulta com U+%04X", (int) controle).isZero();
        }

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update(
                        "UPDATE consulta SET observacoes = ? WHERE id = ?", "an" + NUL + "tes", consultaId))
                .as("coluna text com NUL")
                .hasStackTraceContaining("0x00");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.queryForObject(
                        "SELECT (?::jsonb)->>'t'", String.class,
                        "{\"t\":\"an" + barra + "u0000tes\"}"))
                .as("jsonb com NUL em escape")
                .hasStackTraceContaining("unsupported Unicode escape sequence");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.queryForObject(
                        "SELECT count(*) FROM usuario WHERE email = ?", Long.class,
                        "medi" + NUL + "co@hospital.com"))
                .as("parametro de consulta com NUL")
                .hasStackTraceContaining("0x00");
    }

    /**
     * Controles nao-NUL no meio do texto atravessam a API, a consulta e o evento sem perda.
     *
     * <p>Criacao, alteracao e cancelamento, cada um lido de volta da coluna e do envelope
     * gravado no outbox — que e exatamente o que o relay publica.
     */
    @Test
    @DisplayName("caracterização: U+0001 e U+001F no meio do texto chegam à consulta e ao evento")
    void controlesPersistiveisAtravessamConsultaEOutbox() {
        for (char controle : new char[] {U0001, U001F}) {
            String obs = "obs" + controle + "meio";
            ResponseEntity<String> criada = chamar(HttpMethod.POST, "/api/v1/consultas",
                    registro(horarioLivre(), 30).put("observacoes", obs).toString());
            assertThat(criada.getStatusCode().value())
                    .as("criacao com U+%04X: %s", (int) controle, criada.getBody()).isEqualTo(201);
            UUID id = UUID.fromString(problema(criada).path("id").asText());
            assertThat(coluna(id, "observacoes")).isEqualTo(obs);
            assertThat(doEvento(id, "CONSULTA_CRIADA", "observacoes")).isEqualTo(obs);

            String alterada = "alt" + controle + "meio";
            ResponseEntity<String> put = chamar(HttpMethod.PUT, "/api/v1/consultas/" + id,
                    MAPPER.createObjectNode().put("observacoes", alterada).toString());
            assertThat(put.getStatusCode().value()).as("alteracao: %s", put.getBody()).isEqualTo(200);
            assertThat(coluna(id, "observacoes")).isEqualTo(alterada);
            assertThat(doEvento(id, "CONSULTA_ATUALIZADA", "observacoes")).isEqualTo(alterada);

            String motivo = "mot" + controle + "ivo";
            ResponseEntity<String> cancelada = chamar(HttpMethod.PATCH,
                    "/api/v1/consultas/" + id + "/cancelar",
                    MAPPER.createObjectNode().put("motivo", motivo).toString());
            assertThat(cancelada.getStatusCode().value())
                    .as("cancelamento: %s", cancelada.getBody()).isEqualTo(200);
            assertThat(coluna(id, "motivo_cancelamento")).isEqualTo(motivo);
            assertThat(doEvento(id, "CONSULTA_CANCELADA", "motivoCancelamento")).isEqualTo(motivo);
        }

        String legitimo = "Ação clínica\tsegunda\nlinha";
        ResponseEntity<String> criada = chamar(HttpMethod.POST, "/api/v1/consultas",
                registro(horarioLivre(), 30).put("observacoes", legitimo).toString());
        UUID id = UUID.fromString(problema(criada).path("id").asText());
        assertThat(coluna(id, "observacoes")).isEqualTo(legitimo);
        assertThat(doEvento(id, "CONSULTA_CRIADA", "observacoes")).isEqualTo(legitimo);
    }

    @Test
    @DisplayName("caracterização: credencial com U+0001 ou U+001F é só credencial inválida, sem 5xx")
    void credencialComControlePersistivelEInvalidaSemFalha() {
        for (char controle : new char[] {U0001, U001F}) {
            for (String corpo : new String[] {
                    MAPPER.createObjectNode().put("email", "medi" + controle + "co@hospital.com")
                            .put("senha", "Senha@123").toString(),
                    MAPPER.createObjectNode().put("email", "extrema.m@hospital.com")
                            .put("senha", "Se" + controle + "nha").toString()}) {

                ResponseEntity<String> resposta = chamar(HttpMethod.POST, "/auth/login", corpo);

                assertThat(resposta.getStatusCode().is5xxServerError())
                        .as("U+%04X na credencial: %s", (int) controle, resposta.getBody()).isFalse();
                assertThat(resposta.getStatusCode().value()).isEqualTo(401);
            }
        }
    }

    /**
     * Texto so de controles e controles nas bordas seguem a normalizacao de espacos.
     *
     * <p>{@code trim()} remove tudo abaixo de U+0021, e nao so espacos. Um texto so de
     * controles fica vazio depois de aparado: como motivo, e motivo vazio — a regra de
     * sempre, com 422 e nada gravado —; como observacao, e observacao em branco, que vira
     * nula. Controles nas bordas sao aparados como os espacos ja eram: a alteracao nao e
     * silenciosa por acidente, e a mesma normalizacao de sempre, agora caracterizada.
     */
    @Test
    @DisplayName("caracterização: texto só de controles e bordas seguem a normalização de espaços")
    void textoSoDeControlesEBordasSeguemANormalizacaoDeEspacos() {
        String soControles = "" + U0001 + U001F;

        UUID alvo = novaConsulta();
        Snapshot antes = snapshot(alvo);
        long eventosAntes = eventos();
        for (String motivo : new String[] {soControles, "   "}) {
            ResponseEntity<String> resposta = chamar(HttpMethod.PATCH,
                    "/api/v1/consultas/" + alvo + "/cancelar",
                    MAPPER.createObjectNode().put("motivo", motivo).toString());
            assertThat(resposta.getStatusCode().value()).as("motivo %s", escapado(motivo)).isEqualTo(422);
            assertThat(problema(resposta).path("type").asText())
                    .isEqualTo("https://hospital.fiap.br/erros/motivo-de-cancelamento-obrigatorio");
            assertThat(snapshot(alvo)).isEqualTo(antes);
            assertThat(eventos()).isEqualTo(eventosAntes);
        }

        for (String obs : new String[] {soControles, "   "}) {
            ResponseEntity<String> criada = chamar(HttpMethod.POST, "/api/v1/consultas",
                    registro(horarioLivre(), 30).put("observacoes", obs).toString());
            assertThat(criada.getStatusCode().value())
                    .as("observacoes %s: %s", escapado(obs), criada.getBody()).isEqualTo(201);
            UUID id = UUID.fromString(problema(criada).path("id").asText());
            assertThat(coluna(id, "observacoes")).as("em branco vira nulo").isNull();
            assertThat(doEvento(id, "CONSULTA_CRIADA", "observacoes")).isNull();
        }

        ResponseEntity<String> comObservacao = chamar(HttpMethod.POST, "/api/v1/consultas",
                registro(horarioLivre(), 30).put("observacoes", "antes").toString());
        UUID alterado = UUID.fromString(problema(comObservacao).path("id").asText());
        ResponseEntity<String> apagar = chamar(HttpMethod.PUT, "/api/v1/consultas/" + alterado,
                MAPPER.createObjectNode().put("observacoes", soControles).toString());
        assertThat(apagar.getStatusCode().value()).isEqualTo(200);
        assertThat(coluna(alterado, "observacoes")).as("apaga, como string em branco").isNull();
        assertThat(doEvento(alterado, "CONSULTA_ATUALIZADA", "observacoes")).isNull();

        ResponseEntity<String> bordas = chamar(HttpMethod.POST, "/api/v1/consultas",
                registro(horarioLivre(), 30).put("observacoes", U0001 + "texto" + U001F).toString());
        UUID comBordas = UUID.fromString(problema(bordas).path("id").asText());
        assertThat(coluna(comBordas, "observacoes")).isEqualTo("texto");
        assertThat(doEvento(comBordas, "CONSULTA_CRIADA", "observacoes")).isEqualTo("texto");

        UUID cancelavel = novaConsulta();
        chamar(HttpMethod.PATCH, "/api/v1/consultas/" + cancelavel + "/cancelar",
                MAPPER.createObjectNode().put("motivo", " " + U0001 + "motivo" + U001F + " ").toString());
        assertThat(coluna(cancelavel, "motivo_cancelamento")).isEqualTo("motivo");
    }

    private String coluna(UUID consulta, String nome) {
        return jdbc.queryForObject("SELECT " + nome + " FROM consulta WHERE id = ?", String.class, consulta);
    }

    /** O campo do payload no envelope gravado no outbox — o que o relay publica. */
    private String doEvento(UUID consulta, String tipo, String campo) {
        return jdbc.queryForObject("SELECT payload->'payload'->>? FROM outbox_evento"
                        + " WHERE agregado_id = ? AND payload->>'eventType' = ?"
                        + " ORDER BY criado_em DESC LIMIT 1",
                String.class, campo, consulta, tipo);
    }

    // ---------------------------------------------------------------- apoio

    private com.fasterxml.jackson.databind.node.ObjectNode registro(
            OffsetDateTime dataHora, int duracao) {
        return MAPPER.createObjectNode()
                .put("pacienteId", pacienteId.toString())
                .put("medicoId", medicoId.toString())
                .put("registradoPorId", registranteId.toString())
                .put("dataHora", dataHora.toString())
                .put("duracaoMinutos", duracao);
    }

    private long eventos() {
        return jdbc.queryForObject("SELECT count(*) FROM outbox_evento", Long.class);
    }

    /**
     * Estado persistido inteiro da consulta.
     *
     * <p>Comparar so o status deixaria passar a alteracao que este teste existe para
     * recusar: o periodo, o medico e as observacoes mudam sem mexer no status. As datas
     * viram {@link Instant} porque o banco preserva o instante, e nao o deslocamento.
     */
    private record Snapshot(UUID pacienteId, UUID medicoId, UUID registradoPorId, Instant dataHora,
                            int duracaoMinutos, StatusConsulta status, String observacoes,
                            String motivoCancelamento, Instant atualizadoEm, long versao) {}

    private Snapshot snapshot(UUID id) {
        ConsultaEntity e = consultaJpa.findById(id).orElseThrow();
        return new Snapshot(e.getPacienteId(), e.getMedicoId(), e.getRegistradoPorId(),
                e.getDataHora().toInstant(), e.getDuracaoMinutos(), e.getStatus(),
                e.getObservacoes(), e.getMotivoCancelamento(), e.getAtualizadoEm().toInstant(),
                e.getVersao());
    }

    /**
     * Chamada com a URI exatamente como escrita, sem expansao de template.
     *
     * <p>O {@code TestRestTemplate} trata a string como template e re-codifica o que ja
     * esta percent-encoded: um {@code %2B} vira {@code %252B}, e o servico recebe o
     * literal em vez do sinal de mais. Com isso, o valor nem chega a ser convertido para
     * data, e o teste passaria a exercitar a conversao de parametro em vez da regra que
     * ele existe para verificar.
     */
    private ResponseEntity<String> chamarCrua(String caminho) {
        HttpHeaders cabecalhos = new HttpHeaders();
        cabecalhos.setContentType(MediaType.APPLICATION_JSON);
        cabecalhos.setBearerAuth(token);
        return rest.exchange(java.net.URI.create("http://localhost:" + porta + caminho),
                HttpMethod.GET, new HttpEntity<>(null, cabecalhos), String.class);
    }

    private ResponseEntity<String> chamar(HttpMethod metodo, String caminho, String corpo) {
        HttpHeaders cabecalhos = new HttpHeaders();
        cabecalhos.setContentType(MediaType.APPLICATION_JSON);
        cabecalhos.setBearerAuth(token);
        return rest.exchange(caminho, metodo, new HttpEntity<>(corpo, cabecalhos), String.class);
    }

    private static JsonNode problema(ResponseEntity<String> resposta) {
        try {
            return MAPPER.readTree(resposta.getBody());
        } catch (Exception e) {
            throw new AssertionError("resposta sem Problem Detail legivel: " + resposta.getBody(), e);
        }
    }

    private static String escapado(String texto) {
        StringBuilder saida = new StringBuilder();
        texto.chars().forEach(c -> saida.append(
                Character.isISOControl(c) ? "\\u%04x".formatted(c) : (char) c));
        return saida.toString();
    }
}
