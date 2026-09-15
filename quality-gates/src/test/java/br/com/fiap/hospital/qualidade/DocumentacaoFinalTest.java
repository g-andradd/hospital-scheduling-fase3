package br.com.fiap.hospital.qualidade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Navegação e rastreabilidade da documentação entregue à banca. */
@DisplayName("Documentação final navegável")
class DocumentacaoFinalTest {

    private static final Path ROOT = PomsDoReactor.RAIZ;
    private static final Path README = ROOT.resolve("README.md");
    private static final Path ADR_DIR = ROOT.resolve("docs/adr");
    private static final List<String> SECOES_ADR =
            List.of("Contexto", "Decisão", "Alternativas", "Consequências", "Status");
    private static final Map<String, String> ORIGENS = Map.of(
            "ADR-001-rabbitmq.md", "2026-09-04-add-event-publishing-outbox",
            "ADR-002-tres-servicos.md", "2026-09-02-bootstrap-monorepo",
            "ADR-003-monorepo-multimodulo.md", "2026-09-02-bootstrap-monorepo",
            "ADR-004-matriz-de-autorizacao.md", "2026-09-04-add-autenticacao-jwt",
            "ADR-005-jwt-stateless.md", "2026-09-04-add-autenticacao-jwt",
            "ADR-006-transactional-outbox.md", "2026-09-04-add-event-publishing-outbox",
            "ADR-007-clean-architecture-so-no-core.md", "2026-09-02-add-agendamento-domain");

    @Test
    @DisplayName("README oferece um caminho completo do zero")
    void readmeOfereceCaminhoCompleto() {
        String texto = ler(README);
        validarReadme(texto);
        assertThat(texto)
                .contains("make demo", "medico@hospital.com", "enfermeiro@hospital.com")
                .contains("paciente@hospital.com", "paciente2@hospital.com")
                .contains("http://localhost:8081", "http://localhost:8082", "http://localhost:8083")
                .contains("Hospital-Scheduling-Fase3.postman_collection.json")
                .contains("Hospital-Scheduling-Local.postman_environment.json")
                .containsIgnoringCase("Collection Runner")
                .containsIgnoringCase("Newman");
    }

    @Test
    @DisplayName("README cataloga REST, GraphQL e endpoint interno")
    void readmeCatalogaSuperficiePublica() {
        String texto = ler(README);
        assertThat(texto)
                .contains("POST `/auth/login`")
                .contains("POST `/api/v1/consultas`")
                .contains("GET `/api/v1/consultas`")
                .contains("GET `/api/v1/consultas/{id}`")
                .contains("PUT `/api/v1/consultas/{id}`")
                .contains("PATCH `/api/v1/consultas/{id}/confirmar`")
                .contains("PATCH `/api/v1/consultas/{id}/cancelar`")
                .contains("POST `/internal/lembretes/executar`")
                .contains("consultasDoPaciente", "minhasConsultas", "consultasDoMedico")
                .contains("consulta(id:)", "corrigirRegistroHistorico");
    }

    @Test
    @DisplayName("README tem dois diagramas Mermaid coerentes")
    void readmeTemDoisDiagramasMermaidCoerentes() {
        String texto = ler(README);
        validarMermaid(texto);
        assertThat(texto)
                .contains("Cliente", "agendamento", "PostgreSQL", "outbox", "RabbitMQ")
                .contains("notificacao", "Mailpit", "historico", "GraphQL");
    }

    @Test
    @DisplayName("links locais do README e índice existem")
    void linksLocaisExistem() {
        validarLinksLocais(README, ler(README));
        Path indice = ADR_DIR.resolve("README.md");
        validarLinksLocais(indice, ler(indice));
        for (String arquivo : ORIGENS.keySet()) {
            Path adr = ADR_DIR.resolve(arquivo);
            validarLinksLocais(adr, ler(adr));
        }
    }

    @Test
    @DisplayName("índice contém exatamente os sete ADRs")
    void indiceContemSeteAdrs() {
        String texto = ler(ADR_DIR.resolve("README.md"));
        for (int i = 1; i <= 7; i++) {
            String numero = "ADR-%03d".formatted(i);
            assertThat(ocorrencias(texto, "[" + numero)).as(numero).isOne();
        }
        assertThat(texto).doesNotContain("ADR-008");
    }

    @Test
    @DisplayName("ADRs têm formato uniforme")
    void adrsTemFormatoUniforme() {
        ORIGENS.keySet().forEach(arquivo -> validarAdr(ler(ADR_DIR.resolve(arquivo))));
    }

    @Test
    @DisplayName("ADRs apontam para archives de origem")
    void adrsApontamParaArchivesDeOrigem() {
        ORIGENS.forEach((arquivo, origem) -> {
            String texto = ler(ADR_DIR.resolve(arquivo));
            assertThat(texto).as(arquivo).contains("../../openspec/changes/archive/" + origem + "/");
            assertThat(Files.isDirectory(ROOT.resolve("openspec/changes/archive/" + origem))).isTrue();
        });
    }

    @Test
    @DisplayName("negativos documentais falham com diagnóstico específico")
    void negativosDocumentaisFalhamFechado() {
        String readme = ler(README);
        assertThatThrownBy(() -> validarReadme(readme.replace("Collection Runner", "Executor")))
                .hasMessageContaining("instrução Postman");
        assertThatThrownBy(() -> validarMermaid(readme.replace("sequenceDiagram", "sequencia")))
                .hasMessageContaining("sequenceDiagram");
        assertThatThrownBy(() -> validarLinksLocais(README, readme + "\n[quebrado](docs/inexistente.md)"))
                .hasMessageContaining("link local quebrado");

        String adr = ler(ADR_DIR.resolve("ADR-001-rabbitmq.md"));
        assertThatThrownBy(() -> validarAdr(adr.replace("## Alternativas", "## Opções")))
                .hasMessageContaining("seção ADR");
        assertThatThrownBy(() -> validarAdr(adr.replace("## Status", "## Alternativas\n\n## Status")))
                .hasMessageContaining("seção ADR");
        assertThatThrownBy(() -> validarAdr(adr.replace("## Decisão", "## TEMP").replace("## Contexto", "## Decisão").replace("## TEMP", "## Contexto")))
                .hasMessageContaining("ordem ADR");
    }

    private static void validarReadme(String texto) {
        exigir(texto.contains("## Início rápido"), "início rápido");
        exigir(texto.contains("make demo"), "make demo");
        exigir(texto.contains("Collection Runner") && texto.contains("Newman"), "instrução Postman");
        exigir(texto.contains("Hospital-Scheduling-Fase3.postman_collection.json")
                && texto.contains("Hospital-Scheduling-Local.postman_environment.json"), "arquivos Postman");
    }

    private static void validarMermaid(String texto) {
        exigir(ocorrencias(texto, "```mermaid") >= 2, "dois blocos Mermaid");
        exigir(texto.contains("flowchart"), "flowchart");
        exigir(texto.contains("sequenceDiagram"), "sequenceDiagram");
        for (String participante : List.of("Cliente", "agendamento", "PostgreSQL", "RabbitMQ", "notificacao", "historico")) {
            exigir(texto.contains(participante), "participante Mermaid " + participante);
        }
        exigir(texto.contains("->>") && texto.contains("-->>"), "relações Mermaid");
    }

    private static void validarAdr(String texto) {
        int anterior = -1;
        for (String secao : SECOES_ADR) {
            String titulo = "## " + secao;
            exigir(ocorrencias(texto, titulo) == 1, "seção ADR " + secao);
            int atual = texto.indexOf(titulo);
            exigir(atual > anterior, "ordem ADR");
            anterior = atual;
        }
    }

    private static void validarLinksLocais(Path origem, String texto) {
        Matcher matcher = Pattern.compile("(?<!!)\\[[^]]+\\]\\(([^)]+)\\)").matcher(texto);
        while (matcher.find()) {
            String destino = matcher.group(1).trim();
            if (destino.startsWith("http://") || destino.startsWith("https://")
                    || destino.startsWith("#") || destino.startsWith("mailto:")) continue;
            String semAncora = destino.split("#", 2)[0];
            if (semAncora.isBlank()) continue;
            Path resolvido = origem.getParent().resolve(semAncora).normalize();
            exigir(Files.exists(resolvido), "link local quebrado: " + destino);
        }
    }

    private static int ocorrencias(String texto, String trecho) {
        int total = 0;
        int indice = 0;
        while ((indice = texto.indexOf(trecho, indice)) >= 0) {
            total++;
            indice += trecho.length();
        }
        return total;
    }

    private static void exigir(boolean condicao, String mensagem) {
        if (!condicao) throw new IllegalArgumentException("violação: " + mensagem);
    }

    private static String ler(Path caminho) {
        try {
            if (!Files.isRegularFile(caminho)) throw new AssertionError("arquivo ausente: " + caminho);
            return Files.readString(caminho, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("não foi possível ler " + caminho, e);
        }
    }
}
