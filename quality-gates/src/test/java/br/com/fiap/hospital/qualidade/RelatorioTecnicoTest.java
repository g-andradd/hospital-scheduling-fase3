package br.com.fiap.hospital.qualidade;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Guarda do relatorio tecnico da entrega: {@code docs/relatorio-tecnico.md}.
 *
 * <p>A regra que sustenta o resto e a da origem: todo numero do relatorio vive numa tabela cuja
 * linha nomeia o comando ou artefato que o produziu. Sem isso, "cobertura de 97%" e afirmacao,
 * nao evidencia — e afirmacao nao se distingue de metrica inventada depois que o build passou.
 *
 * <p>O teste tambem exige que os numeros declarem vir do gate de medicao. Uma execucao posterior
 * de verificacao mede uma arvore diferente, porque inclui este proprio arquivo; a diferenca e
 * esperada e nao reescreve o relatorio.
 */
@DisplayName("Relatório técnico")
class RelatorioTecnicoTest {

    private static final Path RAIZ = PomsDoReactor.RAIZ;
    private static final Path RELATORIO = RAIZ.resolve("docs/relatorio-tecnico.md");

    /** As oito secoes obrigatorias, na ordem em que precisam aparecer. */
    static final List<String> SECOES = List.of(
            "## 1. Contexto e problema",
            "## 2. Arquitetura",
            "## 3. Decisões e trade-offs",
            "## 4. Segurança",
            "## 5. Mensageria e garantias de entrega",
            "## 6. Estratégia de testes",
            "## 7. Limites conhecidos e o que ficou fora",
            "## 8. Aprendizados");

    private static final String TITULO_DA_TABELA = "| Número | Valor | Origem |";
    private static final String GATE_DE_MEDICAO = "gate de medição do M14";

    /** Extensoes de escritorio que esta entrega nao produz nem versiona. */
    private static final List<String> BINARIOS = List.of(".docx", ".doc", ".odt", ".pdf");

    // ------------------------------------------------------------------ garantias reais

    @Test
    @DisplayName("Scenario: Seções obrigatórias estão presentes")
    void relatorioTemAsOitoSecoesNaOrdem() {
        String texto = ler(RELATORIO);
        assertThat(violacoes(texto)).isEmpty();

        int anterior = -1;
        for (String secao : SECOES) {
            assertThat(ocorrencias(texto, secao)).as("seção %s", secao).isOne();
            int atual = texto.indexOf(secao);
            assertThat(atual).as("seção fora de ordem: %s", secao).isGreaterThan(anterior);
            anterior = atual;
        }
    }

    @Test
    @DisplayName("Scenario: Cada número nomeia sua origem")
    void numerosDoRelatorioNomeiamOrigem() {
        String texto = ler(RELATORIO);
        List<String> linhas = linhasDaTabelaDeNumeros(texto);

        assertThat(linhas).as("tabela de números vazia").isNotEmpty();
        linhas.forEach(linha -> {
            List<String> colunas = colunasDe(linha);
            assertThat(colunas).as("linha incompleta: %s", linha).hasSizeGreaterThanOrEqualTo(3);
            assertThat(colunas.get(1)).as("valor vazio em: %s", linha).isNotBlank();
            assertThat(colunas.get(2)).as("origem vazia em: %s", linha).isNotBlank();
        });
    }

    @Test
    @DisplayName("Scenario: Cada número nomeia sua origem — a seção de testes cita o gate de medição")
    void secaoDeTestesCitaOGateDeMedicao() {
        String texto = ler(RELATORIO);
        String secao = trechoDaSecao(texto, "## 6. Estratégia de testes");

        assertThat(secao)
                .as("a seção de testes precisa declarar de qual execução vieram os números")
                .contains(GATE_DE_MEDICAO);
        assertThat(secao)
                .as("a seção precisa dizer que execuções posteriores não reescrevem os números")
                .containsIgnoringCase("não são reescritos");
    }

    @Test
    @DisplayName("Scenario: Limites e cortes são declarados")
    void relatorioDeclaraLimitesEAmbiguidades() {
        String secao = trechoDaSecao(ler(RELATORIO), "## 7. Limites conhecidos e o que ficou fora");

        assertThat(secao)
                .as("a entrega ao-menos-uma-vez precisa estar declarada")
                .containsIgnoringCase("ao menos uma vez");
        assertThat(secao)
                .as("a ambiguidade do enunciado resolvida na ADR-004 precisa estar declarada")
                .contains("ADR-004");
        assertThat(secao)
                .as("o alcance da auditoria estática precisa estar declarado")
                .containsIgnoringCase("estática");
    }

    @Test
    @DisplayName("Scenario: Links locais do relatório são válidos")
    void linksLocaisDoRelatorioExistem() {
        assertThat(linksQuebrados(RELATORIO, ler(RELATORIO))).isEmpty();
    }

    @Test
    @DisplayName("Scenario: Relatório permanece um documento de texto versionado")
    void nenhumBinarioDeEscritorioEhVersionado() {
        assertThat(RELATORIO).isRegularFile();
        assertThat(RELATORIO.getFileName().toString()).endsWith(".md");

        List<String> encontrados = new ArrayList<>();
        try (var caminhos = Files.walk(RAIZ.resolve("docs"))) {
            caminhos.filter(Files::isRegularFile)
                    .map(caminho -> caminho.getFileName().toString().toLowerCase())
                    .filter(nome -> BINARIOS.stream().anyMatch(nome::endsWith))
                    .forEach(encontrados::add);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        assertThat(encontrados).as("esta entrega não gera nem versiona binário de escritório").isEmpty();
    }

    // ------------------------------------------------------------------ negativos

    @Test
    @DisplayName("cada desvio do relatório reprova com diagnóstico próprio")
    void negativosDoRelatorioReprovamFechado() {
        String texto = ler(RELATORIO);

        assertThat(violacoes(texto.replace(SECOES.get(3), "## 4. Seguranca do sistema")))
                .anySatisfy(v -> assertThat(v).contains("seção ausente"));
        assertThat(violacoes(texto.replace(SECOES.get(2), "## 3. Decisões")))
                .anySatisfy(v -> assertThat(v).contains("seção ausente"));
        assertThat(violacoes(texto + "\n" + SECOES.get(1) + "\n"))
                .anySatisfy(v -> assertThat(v).contains("seção duplicada"));

        String trocadas = texto
                .replace(SECOES.get(1), "@@TEMP@@")
                .replace(SECOES.get(2), SECOES.get(1))
                .replace("@@TEMP@@", SECOES.get(2));
        assertThat(violacoes(trocadas)).anySatisfy(v -> assertThat(v).contains("fora de ordem"));

        String semOrigem = texto.replace(primeiraLinhaDeNumero(texto),
                semUltimaColuna(primeiraLinhaDeNumero(texto)));
        assertThat(violacoes(semOrigem)).anySatisfy(v -> assertThat(v).contains("número sem origem"));

        assertThat(linksQuebrados(RELATORIO, texto + "\n[quebrado](inexistente-de-proposito.md)\n"))
                .anySatisfy(v -> assertThat(v).contains("link local quebrado"));

        assertThat(violacoes(texto.replace(GATE_DE_MEDICAO, "alguma execução")))
                .anySatisfy(v -> assertThat(v).contains("origem dos números"));
    }

    // ------------------------------------------------------------------ regras

    static List<String> violacoes(String texto) {
        List<String> violacoes = new ArrayList<>();

        int anterior = -1;
        for (String secao : SECOES) {
            int quantas = ocorrencias(texto, secao);
            if (quantas == 0) {
                violacoes.add("seção ausente ou renomeada: " + secao);
                continue;
            }
            if (quantas > 1) {
                violacoes.add("seção duplicada: " + secao);
            }
            int atual = texto.indexOf(secao);
            if (atual < anterior) {
                violacoes.add("seção fora de ordem: " + secao);
            }
            anterior = atual;
        }

        List<String> linhas = linhasDaTabelaDeNumeros(texto);
        if (linhas.isEmpty()) {
            violacoes.add("tabela de números ausente ou vazia");
        }
        for (String linha : linhas) {
            List<String> colunas = colunasDe(linha);
            if (colunas.size() < 3 || colunas.get(2).isBlank()) {
                violacoes.add("número sem origem declarada: " + linha);
            }
        }
        if (!texto.contains(GATE_DE_MEDICAO)) {
            violacoes.add("origem dos números não declarada: falta citar o " + GATE_DE_MEDICAO);
        }
        return List.copyOf(violacoes);
    }

    private static List<String> linhasDaTabelaDeNumeros(String texto) {
        int inicio = texto.indexOf(TITULO_DA_TABELA);
        if (inicio < 0) {
            return List.of();
        }
        List<String> linhas = new ArrayList<>();
        for (String linha : texto.substring(inicio).split("\n", -1)) {
            String conteudo = linha.strip();
            if (!conteudo.startsWith("|")) {
                break;
            }
            if (conteudo.equals(TITULO_DA_TABELA) || conteudo.matches("^\\|[\\s:|-]+\\|$")) {
                continue;
            }
            linhas.add(conteudo);
        }
        return List.copyOf(linhas);
    }

    private static String primeiraLinhaDeNumero(String texto) {
        return linhasDaTabelaDeNumeros(texto).getFirst();
    }

    private static String semUltimaColuna(String linha) {
        int ultimo = linha.lastIndexOf('|');
        int penultimo = linha.lastIndexOf('|', ultimo - 1);
        return linha.substring(0, penultimo + 1) + "   |";
    }

    private static String trechoDaSecao(String texto, String titulo) {
        int inicio = texto.indexOf(titulo);
        if (inicio < 0) {
            throw new AssertionError("seção não encontrada: " + titulo);
        }
        int proxima = texto.indexOf("\n## ", inicio + titulo.length());
        return proxima < 0 ? texto.substring(inicio) : texto.substring(inicio, proxima);
    }

    static List<String> linksQuebrados(Path origem, String texto) {
        List<String> quebrados = new ArrayList<>();
        Matcher matcher = Pattern.compile("(?<!!)\\[[^]]+\\]\\(([^)]+)\\)").matcher(texto);
        while (matcher.find()) {
            String destino = matcher.group(1).trim();
            if (destino.startsWith("http://") || destino.startsWith("https://")
                    || destino.startsWith("#") || destino.startsWith("mailto:")) {
                continue;
            }
            String semAncora = destino.split("#", 2)[0];
            if (semAncora.isBlank()) {
                continue;
            }
            Path resolvido = origem.getParent().resolve(semAncora).normalize();
            if (!Files.exists(resolvido)) {
                quebrados.add("link local quebrado: " + destino);
            } else if (!resolvido.startsWith(RAIZ)) {
                quebrados.add("link local fora do repositório: " + destino);
            }
        }
        return List.copyOf(quebrados);
    }

    private static List<String> colunasDe(String linha) {
        String conteudo = linha.strip();
        if (conteudo.startsWith("|")) {
            conteudo = conteudo.substring(1);
        }
        if (conteudo.endsWith("|")) {
            conteudo = conteudo.substring(0, conteudo.length() - 1);
        }
        return java.util.Arrays.stream(conteudo.split("\\|", -1)).map(String::strip).toList();
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

    static String ler(Path caminho) {
        try {
            if (!Files.isRegularFile(caminho)) {
                throw new AssertionError("arquivo ausente: " + caminho);
            }
            return Files.readString(caminho, StandardCharsets.UTF_8).replace("\r", "");
        } catch (IOException e) {
            throw new UncheckedIOException("não foi possível ler " + caminho, e);
        }
    }
}
