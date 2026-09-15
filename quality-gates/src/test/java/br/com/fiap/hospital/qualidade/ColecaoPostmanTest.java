package br.com.fiap.hospital.qualidade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Contrato estrutural dos exports Postman entregues para a avaliação. */
@DisplayName("Pacote Postman")
class ColecaoPostmanTest {

    private static final Path COLLECTION = PomsDoReactor.RAIZ.resolve(
            "postman/Hospital-Scheduling-Fase3.postman_collection.json");
    private static final Path ENVIRONMENT = PomsDoReactor.RAIZ.resolve(
            "postman/Hospital-Scheduling-Local.postman_environment.json");
    private static final String SCHEMA =
            "https://schema.getpostman.com/json/collection/v2.1.0/collection.json";
    private static final List<String> PASTAS = List.of(
            "00-Auth", "01-Agendamento", "02-Historico-GraphQL",
            "03-Cenarios-de-Seguranca", "04-Cenarios-de-Erro");
    private static final Set<String> DERIVADAS = Set.of(
            "medicoToken", "enfermeiroToken", "pacienteToken", "paciente2Token",
            "runId", "slotBaseEpoch", "slotTentativa", "consultaId", "consultaDataHora",
            "consultaObservacoes", "consultaObservacoesAtualizada", "historicoObservacoes",
            "historicoTentativas", "correcaoTentativas");
    private static final Pattern ENV_VALUE = Pattern.compile(
            "\\{\\s*\"key\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"value\"\\s*:\\s*\"([^\"]*)\"",
            Pattern.DOTALL);
    private static final String COLLECTION_TEXT = lerObrigatorio(COLLECTION);
    private static final String ENVIRONMENT_TEXT = lerObrigatorio(ENVIRONMENT);

    @Test
    @DisplayName("collection e environment reais existem e têm conteúdo")
    void artefatosReaisSaoImportaveis() {
        assertThat(COLLECTION_TEXT).isNotBlank();
        assertThat(ENVIRONMENT_TEXT).isNotBlank();
        validarCollection(COLLECTION_TEXT);
        validarEnvironment(ENVIRONMENT_TEXT);
    }

    @Test
    @DisplayName("pastas obrigatórias têm ordem exata")
    void pastasObrigatoriasTemOrdemExata() {
        int anterior = -1;
        for (String pasta : PASTAS) {
            int atual = COLLECTION_TEXT.indexOf("\"name\": \"" + pasta + "\"");
            assertThat(atual).as("pasta %s", pasta).isGreaterThan(anterior);
            assertThat(ocorrencias(COLLECTION_TEXT, "\"name\": \"" + pasta + "\"")).isOne();
            anterior = atual;
        }
    }

    @Test
    @DisplayName("quatro logins salvam tokens independentes")
    void quatroLoginsSalvamTokensIndependentes() {
        for (String token : List.of("medicoToken", "enfermeiroToken", "pacienteToken", "paciente2Token")) {
            assertThat(COLLECTION_TEXT).contains("pm.environment.set('" + token + "', b.accessToken)");
        }
        assertThat(COLLECTION_TEXT)
                .contains("perfil).to.eql('MEDICO')", "perfil).to.eql('ENFERMEIRO')", "perfil).to.eql('PACIENTE')")
                .contains("['medicoToken','enfermeiroToken','pacienteToken','paciente2Token'");
    }

    @Test
    @DisplayName("todo request testa status e campos")
    void todoRequestTestaStatusECampos() {
        List<String> requests = blocosDeRequest(COLLECTION_TEXT);
        assertThat(requests).hasSize(22);
        requests.forEach(bloco -> {
            String nome = primeiroGrupo(bloco, Pattern.compile("\"name\"\\s*:\\s*\"([^\"]+)\""));
            assertThat(ocorrencias(bloco, "pm.test(")).as(nome).isGreaterThanOrEqualTo(2);
            assertThat(bloco).as(nome + " sem asserção de status")
                    .containsPattern("status [0-9]|status HTTP|status 201 ou");
            assertThat(bloco).as(nome + " sem asserção de campo")
                    .containsAnyOf("pm.response.json()", "pm.response.text()");
        });
    }

    @Test
    @DisplayName("jornada REST encadeia uma única consulta")
    void jornadaRestEncadeiaConsulta() {
        assertThat(COLLECTION_TEXT)
                .contains("pm.environment.set('consultaId', b.id)", "/api/v1/consultas/{{consultaId}}")
                .contains("registradoPorId", "consultaObservacoesAtualizada")
                .contains("\"method\": \"PATCH\"", "/api/v1/consultas/{{consultaId}}/confirmar")
                .contains("status).to.eql('CONFIRMADA')", "/api/v1/consultas/{{consultaId}}/cancelar")
                .contains("status).to.eql('CANCELADA')", "tentativa < 24");
    }

    @Test
    @DisplayName("leitura GraphQL tem retry limitado e exige snapshot final")
    void leituraGraphqlTemRetryLimitado() {
        assertThat(COLLECTION_TEXT)
                .contains("historicoTentativas", "setTimeout(function () {}, 250)", "n < 20")
                .contains("extensions.code === 'NOT_FOUND'", "c.status === 'CONFIRMADA'")
                .contains("c.observacoes === pm.environment.get('consultaObservacoesAtualizada')");
    }

    @Test
    @DisplayName("correção GraphQL usa médico e comprova o valor")
    void correcaoGraphqlUsaMedicoEComprovaValor() {
        assertThat(blocoNomeado(COLLECTION_TEXT, "02.2 Corrigir registro histórico"))
                .contains("{{medicoToken}}", "CorrigirRegistroHistoricoInput", "corrigirRegistroHistorico", "historicoObservacoes")
                .contains("b.errors).to.be.undefined");
        assertThat(COLLECTION_TEXT).contains("c.observacoes !== pm.environment.get('historicoObservacoes')");
    }

    @Test
    @DisplayName("lembrete usa token permitido")
    void lembreteUsaTokenPermitido() {
        assertThat(blocoNomeado(COLLECTION_TEXT, "02.4 Executar lembretes manualmente"))
                .contains("{{enfermeiroToken}}", "/internal/lembretes/executar", "lembretesEnviados")
                .doesNotContain("to.be.above(0)");
    }

    @Test
    @DisplayName("cenário sem token afirma 401")
    void cenarioSemTokenAfirma401() {
        assertProblemDetail("03.1 Consulta protegida sem token - 401", 401, "nao-autenticado");
        assertThat(blocoNomeado(COLLECTION_TEXT, "03.1 Consulta protegida sem token - 401"))
                .contains("\"type\": \"noauth\"");
    }

    @Test
    @DisplayName("cenários de perfil afirmam 403")
    void cenariosDePerfilAfirmam403() {
        assertProblemDetail("03.2 Paciente não cria consulta - 403", 403, "acesso-negado");
        assertProblemDetail("03.3 Outro paciente não lê consulta - 403", 403, "acesso-negado");
    }

    @Test
    @DisplayName("cenário de negócio afirma 422")
    void cenarioDeNegocioAfirma422() {
        assertProblemDetail("04.1 Data passada - 422", 422, "agendamento-no-passado");
    }

    @Test
    @DisplayName("cenário de conflito afirma 409 e preserva o original")
    void cenarioDeConflitoAfirma409() {
        assertProblemDetail("04.2 Sobreposição - 409", 409, "conflito-de-agenda");
        assertThat(blocoNomeado(COLLECTION_TEXT, "04.3 Consulta original permanece intacta"))
                .contains("consultaDataHora", "duracaoMinutos).to.eql(30)", "status).to.eql('CONFIRMADA')");
    }

    @Test
    @DisplayName("cenário de ausência afirma 404")
    void cenarioDeAusenciaAfirma404() {
        assertProblemDetail("04.4 UUID inexistente - 404", 404, "recurso-nao-encontrado");
    }

    @Test
    @DisplayName("cenário malformado afirma 400")
    void cenarioMalformadoAfirma400() {
        assertProblemDetail("04.5 UUID malformado - 400", 400, "parametro-invalido");
    }

    @Test
    @DisplayName("environment contém somente seed público e derivados vazios")
    void environmentTemSeedEValoresDerivadosVazios() {
        Map<String, String> valores = valoresDoEnvironment(ENVIRONMENT_TEXT);
        assertThat(valores)
                .containsEntry("agendamentoBaseUrl", "http://localhost:8081")
                .containsEntry("notificacaoBaseUrl", "http://localhost:8082")
                .containsEntry("historicoBaseUrl", "http://localhost:8083")
                .containsEntry("medicoEmail", "medico@hospital.com")
                .containsEntry("enfermeiroEmail", "enfermeiro@hospital.com")
                .containsEntry("pacienteEmail", "paciente@hospital.com")
                .containsEntry("paciente2Email", "paciente2@hospital.com")
                .containsEntry("medicoId", "aaaaaaaa-0000-0000-0000-000000000001")
                .containsEntry("pacienteId", "bbbbbbbb-0000-0000-0000-000000000001")
                .containsEntry("paciente2Id", "bbbbbbbb-0000-0000-0000-000000000002");
        DERIVADAS.forEach(nome -> assertThat(valores).containsEntry(nome, ""));
        assertThat(ENVIRONMENT_TEXT)
                .doesNotContain("JWT_SECRET", "POSTGRES_PASSWORD", "RABBITMQ_PASSWORD")
                .doesNotContainPattern("eyJ[a-zA-Z0-9_-]+\\.eyJ");
    }

    @Test
    @DisplayName("negativos sintéticos nomeiam a invariante violada")
    void negativosSinteticosFalhamFechado() {
        assertViolacao(COLLECTION_TEXT.replaceFirst("\\{\\s*\"name\"\\s*:\\s*\"04-Cenarios-de-Erro\"", "{\"name\":\"04-Erros\""), ENVIRONMENT_TEXT, "pastas obrigatórias");
        assertViolacao(COLLECTION_TEXT.replace("pm.environment.set('medicoToken', b.accessToken)", "void 0"), ENVIRONMENT_TEXT, "quatro tokens");
        assertViolacao(COLLECTION_TEXT.replace("['medicoToken','enfermeiroToken','pacienteToken','paciente2Token'", "['medicoToken'"), ENVIRONMENT_TEXT, "limpeza inicial");
        assertViolacao(COLLECTION_TEXT.replace("{{paciente2Token}}", "{{pacienteToken}}"), ENVIRONMENT_TEXT, "token compartilhado");
        assertViolacao(COLLECTION_TEXT.replaceFirst("pm\\.test\\('status 200'", "pm.check('status 200'"), ENVIRONMENT_TEXT, "duas asserções");
        assertViolacao(COLLECTION_TEXT.replaceFirst("pm\\.response\\.json\\(\\)", "{}"), ENVIRONMENT_TEXT, "asserção de campo");
        assertViolacao(COLLECTION_TEXT.replace("status 422", "status esperado"), ENVIRONMENT_TEXT, "código 422");
        assertViolacao(COLLECTION_TEXT.replace("n < 20", "true"), ENVIRONMENT_TEXT, "retry limitado");
        assertViolacao(COLLECTION_TEXT.replaceFirst("\\{\\{agendamentoBaseUrl}}/auth/login", "https://example.com/auth/login"), ENVIRONMENT_TEXT, "URL externa");
        assertViolacao(COLLECTION_TEXT, ENVIRONMENT_TEXT.replace("\"key\": \"consultaId\", \"value\": \"\"", "\"key\": \"consultaId\", \"value\": \"persistido\""), "valor derivado");
        validarCollection(COLLECTION_TEXT);
        assertThat(COLLECTION_TEXT).contains(SCHEMA);
    }

    private static void assertProblemDetail(String nome, int status, String tipo) {
        assertThat(blocoNomeado(COLLECTION_TEXT, nome))
                .contains("status " + status, "to.have.status(" + status + ")", tipo, "b.title", "b.correlationId");
    }

    private static void assertViolacao(String collection, String environment, String mensagem) {
        assertThatThrownBy(() -> {
                    validarCollection(collection);
                    validarEnvironment(environment);
                })
                .hasMessageContaining(mensagem);
    }

    private static void validarCollection(String texto) {
        exigir(texto.contains("\"schema\": \"" + SCHEMA + "\""), "schema v2.1");
        int cursor = -1;
        for (String pasta : PASTAS) {
            int posicao = texto.indexOf("\"name\": \"" + pasta + "\"");
            exigir(posicao > cursor && ocorrencias(texto, "\"name\": \"" + pasta + "\"") == 1, "pastas obrigatórias");
            cursor = posicao;
        }
        exigir(ocorrencias(texto, "/auth/login") == 4, "quatro logins");
        for (String token : List.of("medicoToken", "enfermeiroToken", "pacienteToken", "paciente2Token")) {
            exigir(texto.contains("pm.environment.set('" + token + "', b.accessToken)"), "quatro tokens");
        }
        exigir(texto.contains("['medicoToken','enfermeiroToken','pacienteToken','paciente2Token'"), "limpeza inicial");
        exigir(texto.contains("{{paciente2Token}}"), "token compartilhado");
        for (int codigo : List.of(401, 403, 422, 409, 404, 400)) {
            exigir(texto.contains("status " + codigo), "código " + codigo);
        }
        List<String> requests = blocosDeRequest(texto);
        exigir(requests.size() == 22, "22 requests");
        for (String request : requests) {
            exigir(ocorrencias(request, "pm.test(") >= 2, "duas asserções");
            exigir(request.matches("(?s).*status (?:HTTP )?[0-9].*|(?s).*status 201 ou.*"), "asserção de status");
            exigir(request.contains("pm.response.json()") || request.contains("pm.response.text()"), "asserção de campo");
            exigir(request.contains("\"url\""), "URL do request");
        }
        exigir(ocorrencias(texto, "n < 20") >= 2, "retry limitado");
        Matcher urls = Pattern.compile("\"url\"\\s*:\\s*\"([^\"]+)\"").matcher(texto);
        while (urls.find()) exigir(!urls.group(1).matches("https?://.*"), "URL externa");
        exigir(!texto.matches("(?s).*Bearer\\s+eyJ.*"), "token literal");
    }

    private static void validarEnvironment(String texto) {
        Map<String, String> valores = valoresDoEnvironment(texto);
        DERIVADAS.forEach(nome -> exigir(valores.containsKey(nome) && valores.get(nome).isEmpty(), "valor derivado"));
        exigir(!texto.contains("JWT_SECRET") && !texto.contains("POSTGRES_PASSWORD")
                && !texto.contains("RABBITMQ_PASSWORD") && !texto.matches("(?s).*eyJ[^\"]+\\.eyJ.*"), "segredo");
    }

    private static List<String> blocosDeRequest(String texto) {
        List<Integer> inicios = new ArrayList<>();
        Matcher matcher = Pattern.compile("(?m)^\\s*\\{?\\s*\"name\"\\s*:\\s*\"[0-9]{2}\\.[0-9]+[^\"]*\"").matcher(texto);
        while (matcher.find()) inicios.add(matcher.start());
        List<String> blocos = new ArrayList<>();
        for (int i = 0; i < inicios.size(); i++) {
            int fim = i + 1 < inicios.size() ? inicios.get(i + 1) : texto.length();
            blocos.add(texto.substring(inicios.get(i), fim));
        }
        return blocos;
    }

    private static String blocoNomeado(String texto, String nome) {
        return blocosDeRequest(texto).stream()
                .filter(bloco -> bloco.contains("\"name\": \"" + nome + "\""))
                .findFirst().orElseThrow(() -> new AssertionError("request ausente: " + nome));
    }

    private static Map<String, String> valoresDoEnvironment(String texto) {
        Map<String, String> valores = new LinkedHashMap<>();
        Matcher matcher = ENV_VALUE.matcher(texto);
        while (matcher.find()) valores.put(matcher.group(1), matcher.group(2));
        return Map.copyOf(valores);
    }

    private static String primeiroGrupo(String texto, Pattern pattern) {
        Matcher matcher = pattern.matcher(texto);
        return matcher.find() ? matcher.group(1) : "request sem nome";
    }

    private static int ocorrencias(String texto, String trecho) {
        int quantidade = 0;
        int indice = 0;
        while ((indice = texto.indexOf(trecho, indice)) >= 0) {
            quantidade++;
            indice += trecho.length();
        }
        return quantidade;
    }

    private static void exigir(boolean condicao, String violacao) {
        if (!condicao) throw new IllegalArgumentException("violação: " + violacao);
    }

    private static String lerObrigatorio(Path caminho) {
        if (!Files.isRegularFile(caminho)) throw new AssertionError("artefato Postman ausente: " + caminho);
        try {
            String conteudo = Files.readString(caminho, StandardCharsets.UTF_8);
            if (conteudo.isBlank()) throw new AssertionError("artefato Postman vazio: " + caminho);
            return conteudo;
        } catch (IOException e) {
            throw new UncheckedIOException("não foi possível ler " + caminho, e);
        }
    }
}
