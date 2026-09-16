package br.com.fiap.hospital.qualidade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
 * Guarda da auditoria de requisitos do M14, sobre {@code scripts/auditoria.sh}.
 *
 * <p>Nao confundir com a {@link AuditoriaDeExecucao}, que audita a execucao das suites durante
 * o {@code verify}. Esta classe cuida da auditoria de <b>requisitos</b>: um roteiro estatico
 * que percorre os RF/RNF de {@code docs/02-especificacao-funcional.md} e confere, para cada um,
 * se a evidencia versionada existe e esta ancorada.
 *
 * <p>O inventario e lido aqui por um parser <b>independente</b> do que o roteiro usa. Os dois
 * teriam de errar do mesmo jeito para uma omissao passar. E a contagem exata e afirmada, porque
 * um parser que para de achar a tabela passaria verificando nada — foi o que ja aconteceu com a
 * primeira varredura de excecoes do M03, e a licao esta registrada na MatrizDeAutorizacao.
 */
@DisplayName("Auditoria de requisitos")
class AuditoriaDeRequisitosTest {

    private static final Path RAIZ = PomsDoReactor.RAIZ;
    private static final Path ESPECIFICACAO = RAIZ.resolve("docs/02-especificacao-funcional.md");

    private static final String INICIO_RF = "## 1. Requisitos funcionais";
    private static final String INICIO_RNF = "## 2. Requisitos não funcionais";
    private static final String FIM_RNF = "## 3. Matriz de autorização";

    private static final int TOTAL_RF = 20;
    private static final int TOTAL_RNF = 10;

    private static final Pattern LINHA_DE_REQUISITO =
            Pattern.compile("^\\|\\s*(RF|RNF)-(\\d{2})\\s*\\|");

    // ------------------------------------------------------------------ 1.1 inventario

    @Test
    @DisplayName("Scenario: Inventário completo é percorrido — o documento declara 20 RF e 10 RNF")
    void inventarioCobreOsTrintaRequisitos() {
        List<String> rf = identificadores("RF");
        List<String> rnf = identificadores("RNF");

        assertThat(rf)
                .as("sem os 20 RF lidos do documento, a auditoria passaria verificando nada")
                .hasSize(TOTAL_RF)
                .containsExactlyElementsOf(sequencia("RF", TOTAL_RF));
        assertThat(rnf)
                .as("sem os 10 RNF lidos do documento, a auditoria passaria verificando nada")
                .hasSize(TOTAL_RNF)
                .containsExactlyElementsOf(sequencia("RNF", TOTAL_RNF));

        assertThat(inventario()).hasSize(TOTAL_RF + TOTAL_RNF).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("a leitura do documento falha fechado quando a tabela some")
    void parserVazioReprova() {
        assertThatThrownBy(() -> exigirSequencia("RF", List.of(), TOTAL_RF))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("RF");

        assertThatThrownBy(() -> exigirSequencia("RF", sequencia("RF", TOTAL_RF - 1), TOTAL_RF))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("RF");
    }

    // ------------------------------------------------------------------ 1.2 execucao real

    @Test
    @DisplayName("Scenario: Repositório íntegro termina com código zero")
    void execucaoRealTerminaVerde() {
        Execucao execucao = auditar(RAIZ);

        assertThat(execucao.codigo())
                .as("auditoria reprovou:%n%s%n%s", execucao.saida(), execucao.erro())
                .isZero();
        assertThat(linhasDeRequisito(execucao.saida()))
                .allSatisfy(linha -> assertThat(campos(linha).get(1)).isEqualTo(STATUS_APROVADO));
    }

    @Test
    @DisplayName("Scenario: Relatório usa o formato de três campos")
    void relatorioTemCabecalhoETresCampos() {
        String saida = auditar(RAIZ).saida();

        assertThat(saida).contains(CABECALHO).contains(SEMANTICA_DO_OK);

        List<String> linhas = linhasDeRequisito(saida);
        assertThat(linhas).hasSize(TOTAL_RF + TOTAL_RNF);
        assertThat(linhas.stream().map(l -> campos(l).getFirst()).toList())
                .as("o relatório precisa seguir a ordem do documento")
                .containsExactlyElementsOf(inventario());
        linhas.forEach(linha -> assertThat(campos(linha))
                .as("linha sem os três campos: %s", linha)
                .hasSize(3)
                .noneMatch(String::isBlank));

        assertThat(saida)
                .as("o sumário precisa fechar com o inventário")
                .contains("inventariados=" + (TOTAL_RF + TOTAL_RNF))
                .contains("aprovados=" + (TOTAL_RF + TOTAL_RNF))
                .contains("reprovados=0");
    }

    // ------------------------------------------------------------------ 1.3 catalogo

    @Test
    @DisplayName("Scenario: Evidência aponta para artefato e elemento")
    void catalogoApontaArtefatoEAncora() {
        List<Entrada> catalogo = catalogo();

        assertThat(catalogo).hasSize(TOTAL_DE_PARES);
        assertThat(catalogo).allSatisfy(entrada -> {
            assertThat(entrada.artefato()).isNotBlank();
            assertThat(entrada.ancora()).isNotBlank();
            assertThat(RAIZ.resolve(entrada.artefato()))
                    .as("artefato do %s", entrada.requisito())
                    .isRegularFile();
        });
        assertThat(violacoesDoCatalogo(catalogo, inventario())).isEmpty();
    }

    @Test
    @DisplayName("Scenario: Evidência aponta para artefato e elemento — cada cláusula tem seu par")
    void conjuntoDeAncorasCobreAsClausulas() {
        var porRequisito = catalogo().stream()
                .collect(java.util.stream.Collectors.groupingBy(Entrada::requisito));

        assertThat(porRequisito.keySet()).containsExactlyInAnyOrderElementsOf(inventario());
        MULTIANCORA.forEach((requisito, minimo) -> assertThat(porRequisito.get(requisito))
                .as("%s tem mais de uma cláusula normativa e precisa de um par por cláusula", requisito)
                .hasSizeGreaterThanOrEqualTo(minimo));
    }

    @Test
    @DisplayName("Scenario: Menção textual do identificador não aprova o requisito")
    void mencaoTextualNaoAprovaORequisito() {
        assertThat(violacoesDoCatalogo(
                        List.of(new Entrada("RF-09", "docs/02-especificacao-funcional.md", "RF-09", "")),
                        List.of("RF-09")))
                .anySatisfy(v -> assertThat(v).contains("âncora cita o identificador").contains("RF-09"));

        assertThat(violacoesDoCatalogo(
                        List.of(new Entrada("RF-09", "docs/04-roadmap.md", "Fecha: RF-09 e RF-10", "")),
                        List.of("RF-09")))
                .anySatisfy(v -> assertThat(v).contains("âncora cita o identificador"));
    }

    @Test
    @DisplayName("âncora que é só nome de comando ou ferramenta não aprova o requisito")
    void nomeDeComandoNaoEhAncora() {
        for (String nome : List.of("mvn", "docker", "Testcontainers", "docker compose", "make")) {
            assertThat(violacoesDoCatalogo(
                            List.of(new Entrada("RNF-06", "pom.xml", nome, "")), List.of("RNF-06")))
                    .as("âncora %s", nome)
                    .anySatisfy(v -> assertThat(v).contains("âncora é apenas nome de comando"));
        }
    }

    @Test
    @DisplayName("Scenario: Requisito sem evidência é recusado")
    void requisitoSemEvidenciaEhRecusado() {
        assertThat(violacoesDoCatalogo(List.of(), List.of("RF-05")))
                .anySatisfy(v -> assertThat(v).contains("requisito sem evidência").contains("RF-05"));
    }

    @Test
    @DisplayName("entrada do catálogo sem requisito no documento é recusada")
    void entradaOrfaEhRecusada() {
        assertThat(violacoesDoCatalogo(
                        List.of(new Entrada("RF-99", "pom.xml", "<modelVersion>", "")), List.of("RF-01")))
                .anySatisfy(v -> assertThat(v).contains("entrada órfã").contains("RF-99"));
    }

    @Test
    @DisplayName("Scenario: Evidência compartilhada sem declaração é recusada")
    void parRepetidoExigeCompartilhamentoDeclarado() {
        Entrada primeira = new Entrada("RF-05", "pom.xml", "<modelVersion>", "");
        Entrada segunda = new Entrada("RF-06", "pom.xml", "<modelVersion>", "");
        assertThat(violacoesDoCatalogo(List.of(primeira, segunda), List.of("RF-05", "RF-06")))
                .anySatisfy(v -> assertThat(v).contains("evidência compartilhada sem declaração"));

        Entrada declarada = new Entrada("RF-06", "pom.xml", "<modelVersion>", "compartilhado com RF-05");
        assertThat(violacoesDoCatalogo(List.of(primeira, declarada), List.of("RF-05", "RF-06")))
                .as("compartilhamento declarado é legítimo")
                .noneSatisfy(v -> assertThat(v).contains("evidência compartilhada sem declaração"));
    }

    @Test
    @DisplayName("Scenario: Requisito garantido por execução aponta para quem a exige")
    void requisitosDeExecucaoAncoramNoGate() {
        var porRequisito = catalogo().stream()
                .collect(java.util.stream.Collectors.groupingBy(Entrada::requisito));

        ANCORAS_DE_EXECUCAO.forEach((requisito, esperado) -> {
            List<String> artefatos = porRequisito.getOrDefault(requisito, List.of())
                    .stream().map(Entrada::artefato).toList();
            assertThat(artefatos).as("%s precisa ancorar em quem exige a execução", requisito)
                    .contains(esperado);
        });

        assertThat(violacoesDoCatalogo(
                        List.of(new Entrada("RNF-04", "README.md", "mvn -q clean verify", "")),
                        List.of("RNF-04")))
                .as("citar a linha de comando na documentação não pode aprovar um requisito de execução")
                .anySatisfy(v -> assertThat(v).contains("evidência em documento de prosa"));
    }

    // ------------------------------------------------------------------ 1.4 regras estruturais

    @Test
    @DisplayName("Scenario: Auditoria não altera o ambiente — o roteiro não invoca nada que alcance o ambiente")
    void roteiroNaoAlcancaAmbienteNemEscreve() {
        assertThat(violacoesEstruturais(ler(ROTEIRO))).isEmpty();
    }

    @Test
    @DisplayName("cada regra estrutural reprova a sua própria violação")
    void regrasEstruturaisReprovamFechado() {
        String roteiro = ler(ROTEIRO);

        assertThat(violacoesEstruturais(roteiro.replace(MODO_ESTRITO, "set -e")))
                .anySatisfy(v -> assertThat(v).contains("modo estrito"));
        assertThat(violacoesEstruturais(roteiro.replace(GUARDA_BASH_SOURCE, "true")))
                .anySatisfy(v -> assertThat(v).contains("guarda de execução"));

        for (String comando : COMANDOS_PROIBIDOS) {
            assertThat(violacoesEstruturais(roteiro + "\n" + comando + " ps\n"))
                    .as("invocação direta de %s", comando)
                    .anySatisfy(v -> assertThat(v).contains("invocação proibida").contains(comando));
            assertThat(violacoesEstruturais(roteiro + "\ntrue && " + comando + " ps\n"))
                    .as("invocação de %s após separador", comando)
                    .anySatisfy(v -> assertThat(v).contains("invocação proibida").contains(comando));
            assertThat(violacoesEstruturais(roteiro + "\nsaida=$(" + comando + " ps)\n"))
                    .as("invocação de %s em subshell", comando)
                    .anySatisfy(v -> assertThat(v).contains("invocação proibida").contains(comando));
            assertThat(violacoesEstruturais(roteiro + "\n/usr/bin/" + comando + " ps\n"))
                    .as("invocação de %s por caminho absoluto", comando)
                    .anySatisfy(v -> assertThat(v).contains("invocação proibida").contains(comando));
        }

        assertThat(violacoesEstruturais(roteiro + "\nprintf 'x' > \"$RAIZ/docs/gerado.txt\"\n"))
                .anySatisfy(v -> assertThat(v).contains("redirecionamento de escrita"));
    }

    @Test
    @DisplayName("os mesmos nomes em comentário e em valor do catálogo não são invocação")
    void comentarioECatalogoNaoSaoInvocacao() {
        String roteiro = ler(ROTEIRO);

        String comComentario = roteiro + "\n# este roteiro nao roda docker, mvn, psql nem curl\n";
        assertThat(violacoesEstruturais(comComentario))
                .as("nome de comando em comentário não é invocação")
                .isEmpty();

        String comEntrada = roteiro.replace(
                CATALOGO_FIM, "RNF-07|Makefile|mktemp psql curl pkill killall\n" + CATALOGO_FIM);
        assertThat(violacoesEstruturais(comEntrada))
                .as("valor literal do catálogo é dado, não código")
                .isEmpty();

        assertThat(ler(ROTEIRO))
                .as("o catálogo real carrega os literais que a regra por ocorrência recusaria")
                .contains("docker compose up -d --build --wait")
                .contains("docker/postgres/init.sql");
    }

    // ------------------------------------------------------------------ 1.5 negativos sinteticos

    @Test
    @DisplayName("Scenario: Inventário fora da sequência fechada é recusado")
    void sequenciaIncompletaEhRecusada() {
        Execucao semRf07 = mutar("sem-rf-07", copia -> {
            Path especificacao = copia.resolve("docs/02-especificacao-funcional.md");
            escrever(especificacao, removerLinha(ler(especificacao), "| RF-07 |"));
        });
        assertThat(semRf07.codigo()).isEqualTo(CODIGO_INVENTARIO);
        assertThat(semRf07.erro() + semRf07.saida()).contains("RF-07");
    }

    @Test
    @DisplayName("Scenario: Identificador duplicado é recusado")
    void identificadorDuplicadoEhRecusado() {
        Execucao duplicado = mutar("rf-07-duplicado", copia -> {
            Path especificacao = copia.resolve("docs/02-especificacao-funcional.md");
            String texto = ler(especificacao);
            String linha = linhaQueComeca(texto, "| RF-07 |");
            escrever(especificacao, texto.replace(linha, linha + "\n" + linha));
        });
        assertThat(duplicado.codigo()).isEqualTo(CODIGO_INVENTARIO);
        assertThat(duplicado.erro() + duplicado.saida()).contains("RF-07");
    }

    @Test
    @DisplayName("requisito sem entrada no catálogo é recusado pelo roteiro")
    void requisitoSemEntradaNoCatalogoEhRecusadoEmExecucao() {
        Execucao semEntrada = mutar("rf-05-sem-entrada", copia -> {
            Path roteiro = copia.resolve("scripts/auditoria.sh");
            escrever(roteiro, removerLinha(ler(roteiro), "RF-05|"));
        });
        assertThat(semEntrada.codigo()).isEqualTo(CODIGO_INVENTARIO);
        assertThat(semEntrada.erro() + semEntrada.saida()).contains("RF-05");
    }

    @Test
    @DisplayName("entrada órfã no catálogo é recusada pelo roteiro")
    void entradaOrfaEhRecusadaEmExecucao() {
        Execucao orfa = mutar("entrada-orfa", copia -> {
            Path roteiro = copia.resolve("scripts/auditoria.sh");
            // So a linha do terminador: a abertura `<<'FIM_DO_CATALOGO'` nao pode ser tocada,
            // senao o heredoc quebra e o roteiro morre antes de avaliar a entrada orfa.
            String terminador = "\n" + FIM_DO_HEREDOC + "\n";
            escrever(roteiro, ler(roteiro).replace(
                    terminador, "\nRF-99|pom.xml|<modelVersion>" + terminador));
        });
        assertThat(orfa.codigo()).isEqualTo(CODIGO_INVENTARIO);
        assertThat(orfa.erro() + orfa.saida()).contains("RF-99");
    }

    @Test
    @DisplayName("Scenario: Evidência inexistente é recusada")
    void artefatoAusenteEhRecusado() {
        Entrada alvo = catalogo().stream().filter(e -> e.requisito().equals("RF-02")).findFirst().orElseThrow();
        Execucao semArtefato = mutar("artefato-ausente", copia -> apagar(copia.resolve(alvo.artefato())));

        assertThat(semArtefato.codigo()).isEqualTo(CODIGO_REPROVADO);
        assertThat(semArtefato.saida()).contains("RF-02").contains(STATUS_REPROVADO);
        assertThat(semArtefato.erro() + semArtefato.saida()).contains(alvo.artefato());
    }

    @Test
    @DisplayName("Scenario: Âncora ausente do artefato existente é recusada")
    void ancoraAusenteReprovaORequisito() {
        Entrada alvo = catalogo().stream().filter(e -> e.requisito().equals("RF-02")).findFirst().orElseThrow();
        Execucao semAncora = mutar("ancora-ausente", copia -> {
            Path artefato = copia.resolve(alvo.artefato());
            escrever(artefato, ler(artefato).replace(alvo.ancora(), "void outroNomeQualquer()"));
        });

        assertThat(semAncora.codigo()).isEqualTo(CODIGO_REPROVADO);
        assertThat(semAncora.saida()).contains("RF-02").contains(STATUS_REPROVADO);
        assertThat(semAncora.erro() + semAncora.saida()).contains(alvo.ancora());
    }

    @Test
    @DisplayName("Scenario: Âncora ausente — uma âncora faltante reprova o requisito multiâncora inteiro")
    void ancoraFaltanteReprovaRequisitoMultiAncora() {
        List<Entrada> doRf11 = catalogo().stream().filter(e -> e.requisito().equals("RF-11")).toList();
        assertThat(doRf11).hasSizeGreaterThan(1);
        Entrada alvo = doRf11.getLast();

        Execucao execucao = mutar("rf-11-uma-ancora-a-menos", copia -> {
            Path artefato = copia.resolve(alvo.artefato());
            escrever(artefato, ler(artefato).replace(alvo.ancora(), "void periodoRemovidoDaSuite()"));
        });

        assertThat(execucao.codigo()).isEqualTo(CODIGO_REPROVADO);
        String linha = linhasDeRequisito(execucao.saida()).stream()
                .filter(l -> l.startsWith("RF-11")).findFirst().orElseThrow();
        assertThat(campos(linha).get(1))
                .as("faltando uma âncora, o requisito inteiro reprova — as outras três não o salvam")
                .isEqualTo(STATUS_REPROVADO);
        assertThat(execucao.saida()).contains("aprovados=29").contains("reprovados=1");
    }

    @Test
    @DisplayName("falso positivo: trocar a âncora pela menção textual do identificador é recusado")
    void mencaoTextualComoAncoraEhRecusadaPeloGate() {
        List<Entrada> mutado = new ArrayList<>(catalogo());
        Entrada alvo = mutado.stream().filter(e -> e.requisito().equals("RF-09")).findFirst().orElseThrow();
        mutado.set(mutado.indexOf(alvo),
                new Entrada("RF-09", "docs/02-especificacao-funcional.md", "| RF-09 |", ""));

        assertThat(violacoesDoCatalogo(mutado, inventario()))
                .anySatisfy(v -> assertThat(v).contains("âncora cita o identificador").contains("RF-09"));
    }

    // ------------------------------------------------------------------ 1.6 controle de invocacao

    @Test
    @DisplayName("Scenario: Auditoria não altera o ambiente — nenhum sentinela do PATH é invocado")
    void nenhumSentinelaDePathEhInvocado() {
        Path trabalho = preparar("sentinelas");
        Path binario = trabalho.resolve("bin");
        Path registro = trabalho.resolve("chamadas.txt");
        criarSentinelas(binario, registro);

        Execucao execucao = executarBash(
                List.of(ROTEIRO.toString(), RAIZ.toString()),
                java.util.Map.of("PATH", binario + java.io.File.pathSeparator + System.getenv("PATH")));

        assertThat(execucao.codigo())
                .as("auditoria reprovou com os sentinelas no PATH:%n%s%n%s", execucao.saida(), execucao.erro())
                .isZero();
        assertThat(Files.exists(registro))
                .as("sentinelas chamados: %s", Files.exists(registro) ? ler(registro) : "")
                .isFalse();
    }

    @Test
    @DisplayName("o próprio controle de sentinelas é sensível")
    void sentinelaRegistraQuemOChama() {
        Path trabalho = preparar("sentinelas-controle");
        Path binario = trabalho.resolve("bin");
        Path registro = trabalho.resolve("chamadas.txt");
        criarSentinelas(binario, registro);

        Path sonda = trabalho.resolve("sonda.sh");
        escrever(sonda, "#!/usr/bin/env bash\ndocker ps >/dev/null 2>&1 || true\nmvn -v >/dev/null 2>&1 || true\n");

        Execucao execucao = executarBash(
                List.of(sonda.toString()),
                java.util.Map.of("PATH", binario + java.io.File.pathSeparator + System.getenv("PATH")));

        assertThat(execucao.codigo()).isZero();
        assertThat(ler(registro)).contains("docker").contains("mvn");
    }

    private static void criarSentinelas(Path binario, Path registro) {
        try {
            Files.createDirectories(binario);
            Files.deleteIfExists(registro);
            for (String comando : COMANDOS_PROIBIDOS) {
                Path sentinela = binario.resolve(comando);
                escrever(sentinela, "#!/usr/bin/env bash\n"
                        + "printf '%s %s\\n' \"" + comando + "\" \"$*\" >> \"" + registro.toString().replace("\\", "/") + "\"\n"
                        + "exit 97\n");
                sentinela.toFile().setExecutable(true);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ------------------------------------------------------------------ copias mutaveis

    static final int CODIGO_REPROVADO = 1;
    static final int CODIGO_INVENTARIO = 3;

    /** Copia o que a auditoria le para um diretorio de trabalho, aplica a mutacao e executa. */
    private static Execucao mutar(String caso, java.util.function.Consumer<Path> mutacao) {
        Path copia = preparar("mutacao-" + caso).resolve("repo");
        copiarArvoreLida(copia);
        mutacao.accept(copia);
        return executarBash(List.of(copia.resolve("scripts/auditoria.sh").toString(), copia.toString()), null);
    }

    /** Somente o que o roteiro abre: ele proprio, a especificacao e os artefatos do catalogo. */
    private static void copiarArvoreLida(Path destino) {
        List<String> relativos = new ArrayList<>(List.of(
                "scripts/auditoria.sh", "docs/02-especificacao-funcional.md", "pom.xml"));
        catalogo().stream().map(Entrada::artefato).distinct().forEach(relativos::add);
        try {
            for (String relativo : relativos.stream().distinct().toList()) {
                Path origem = RAIZ.resolve(relativo);
                if (!Files.isRegularFile(origem)) {
                    continue;
                }
                Path alvo = destino.resolve(relativo);
                Files.createDirectories(alvo.getParent());
                Files.copy(origem, alvo, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path preparar(String nome) {
        try {
            Path trabalho = RAIZ.resolve("quality-gates/target/auditoria-de-requisitos").resolve(nome);
            if (Files.exists(trabalho)) {
                try (var caminhos = Files.walk(trabalho)) {
                    caminhos.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
                }
            }
            Files.createDirectories(trabalho);
            return trabalho;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void escrever(Path caminho, String conteudo) {
        try {
            Files.createDirectories(caminho.getParent());
            Files.writeString(caminho, conteudo, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void apagar(Path caminho) {
        try {
            Files.deleteIfExists(caminho);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String removerLinha(String texto, String prefixo) {
        return texto.lines().filter(l -> !l.strip().startsWith(prefixo)).collect(
                java.util.stream.Collectors.joining("\n")) + "\n";
    }

    private static String linhaQueComeca(String texto, String prefixo) {
        return texto.lines().filter(l -> l.strip().startsWith(prefixo)).findFirst()
                .orElseThrow(() -> new AssertionError("linha não encontrada: " + prefixo));
    }

    // ------------------------------------------------------------------ analise estrutural

    static final String MODO_ESTRITO = "set -Eeuo pipefail";
    static final String GUARDA_BASH_SOURCE = "${BASH_SOURCE[0]}";
    static final List<String> COMANDOS_PROIBIDOS =
            List.of("docker", "mvn", "psql", "curl", "pkill", "killall", "mktemp");

    private static final Pattern ATRIBUICAO_DE_AMBIENTE = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*=\\S*$");
    private static final Pattern REDIRECIONAMENTO = Pattern.compile(">{1,2}\\s*(?![&])(\\S+)");

    /**
     * Violacoes estruturais do roteiro, sobre o que a shell executaria.
     *
     * <p>Comentarios e o conteudo literal do catalogo sao descartados antes da analise (D8):
     * {@code docker compose up -d --build --wait} e a ancora do RNF-07 e {@code docker/postgres/init.sql}
     * e o artefato do RNF-10 — sao dados, nao chamadas de sistema. O que a regra recusa e a
     * <b>invocacao</b>: inicio de comando, apos separador da shell, em subshell e por caminho absoluto.
     */
    static List<String> violacoesEstruturais(String roteiro) {
        List<String> codigo = linhasDeCodigo(roteiro);
        List<String> violacoes = new ArrayList<>();

        if (codigo.isEmpty() || !codigo.getFirst().equals(MODO_ESTRITO)) {
            violacoes.add("modo estrito ausente: o primeiro comando deve ser " + MODO_ESTRITO);
        }
        if (codigo.stream().noneMatch(l -> l.contains(GUARDA_BASH_SOURCE))) {
            violacoes.add("guarda de execução ausente: o fluxo precisa rodar só sob " + GUARDA_BASH_SOURCE);
        }
        if (codigo.stream().noneMatch(l -> l.contains("RAIZ=") && l.contains("${1"))) {
            violacoes.add("raiz não aceita por argumento: o roteiro precisa receber a raiz em $1");
        }
        for (String linha : codigo) {
            for (String comando : comandosInvocados(linha)) {
                if (COMANDOS_PROIBIDOS.contains(comando)) {
                    violacoes.add("invocação proibida de " + comando + " em: " + linha);
                }
            }
            Matcher redirecionamento = REDIRECIONAMENTO.matcher(linha);
            while (redirecionamento.find()) {
                String destino = redirecionamento.group(1);
                if (!destino.startsWith("/dev/null") && !destino.startsWith("&")) {
                    violacoes.add("redirecionamento de escrita em: " + linha);
                }
            }
        }
        return List.copyOf(violacoes);
    }

    /** Linhas que a shell executa: sem comentarios, sem linhas vazias e sem o bloco do catalogo. */
    private static List<String> linhasDeCodigo(String roteiro) {
        String texto = roteiro.replace("\r", "");
        int inicio = texto.indexOf(CATALOGO_INICIO);
        int fim = texto.indexOf(CATALOGO_FIM);
        if (inicio >= 0 && fim > inicio) {
            texto = texto.substring(0, inicio) + texto.substring(fim);
        }
        List<String> linhas = new ArrayList<>();
        for (String bruta : texto.split("\n", -1)) {
            String linha = bruta.strip();
            if (linha.isEmpty() || linha.startsWith("#")) {
                continue;
            }
            linhas.add(linha);
        }
        return List.copyOf(linhas);
    }

    /** Nomes em posicao de comando na linha, ja sem diretorio. */
    private static List<String> comandosInvocados(String linha) {
        String normalizada = linha
                .replace("$(", " ; ")
                .replace("`", " ; ")
                .replace(")", " ; ")
                .replace("{", " ; ")
                .replace("}", " ; ");
        List<String> nomes = new ArrayList<>();
        for (String segmento : normalizada.split("[;&|]+")) {
            List<String> palavras = java.util.Arrays.stream(segmento.strip().split("\\s+"))
                    .filter(p -> !p.isBlank())
                    .toList();
            int indice = 0;
            while (indice < palavras.size() && ATRIBUICAO_DE_AMBIENTE.matcher(palavras.get(indice)).matches()) {
                indice++;
            }
            if (indice >= palavras.size()) {
                continue;
            }
            String primeira = palavras.get(indice).replaceAll("^[\"'(]+", "");
            int barra = Math.max(primeira.lastIndexOf('/'), primeira.lastIndexOf('\\'));
            nomes.add(barra >= 0 ? primeira.substring(barra + 1) : primeira);
        }
        return List.copyOf(nomes);
    }

    // ------------------------------------------------------------------ regras do catalogo

    static final int TOTAL_DE_PARES = 75;

    /** Requisitos cujo texto tem mais de uma clausula normativa, com o minimo de pares exigido. */
    static final java.util.Map<String, Integer> MULTIANCORA = java.util.Map.ofEntries(
            java.util.Map.entry("RF-01", 2), java.util.Map.entry("RF-03", 2), java.util.Map.entry("RF-04", 3),
            java.util.Map.entry("RF-05", 2), java.util.Map.entry("RF-06", 3), java.util.Map.entry("RF-07", 2),
            java.util.Map.entry("RF-08", 2), java.util.Map.entry("RF-09", 3), java.util.Map.entry("RF-10", 2),
            java.util.Map.entry("RF-11", 4), java.util.Map.entry("RF-12", 3), java.util.Map.entry("RF-13", 2),
            java.util.Map.entry("RF-14", 2), java.util.Map.entry("RF-15", 3), java.util.Map.entry("RF-16", 2),
            java.util.Map.entry("RF-17", 3), java.util.Map.entry("RF-18", 2), java.util.Map.entry("RF-19", 2),
            java.util.Map.entry("RF-20", 2), java.util.Map.entry("RNF-01", 4), java.util.Map.entry("RNF-02", 2),
            java.util.Map.entry("RNF-03", 3), java.util.Map.entry("RNF-04", 3), java.util.Map.entry("RNF-05", 3),
            java.util.Map.entry("RNF-06", 3), java.util.Map.entry("RNF-07", 2), java.util.Map.entry("RNF-08", 4),
            java.util.Map.entry("RNF-09", 2), java.util.Map.entry("RNF-10", 2));

    /** Requisitos que so se materializam em execucao: o artefato que a torna obrigatoria. */
    static final java.util.Map<String, String> ANCORAS_DE_EXECUCAO = java.util.Map.of(
            "RNF-04", "quality-gates/src/main/java/br/com/fiap/hospital/qualidade/VerificadorDeCobertura.java",
            "RNF-06", "quality-gates/src/test/java/br/com/fiap/hospital/qualidade/InfraestruturaRealTest.java",
            "RNF-07", "Makefile");

    /** Nomes que, sozinhos, nao provam requisito algum. */
    private static final List<String> SO_NOME_DE_COMANDO = List.of(
            "mvn", "maven", "docker", "docker compose", "docker-compose", "psql", "curl", "make", "bash",
            "git", "jq", "newman", "npm", "node", "testcontainers", "jacoco", "archunit", "flyway", "rabbitmq",
            "postgres", "postgresql", "spring", "junit");

    record Entrada(String requisito, String artefato, String ancora, String compartilhamento) {}

    static List<String> violacoesDoCatalogo(List<Entrada> entradas, List<String> inventario) {
        List<String> violacoes = new ArrayList<>();

        for (String requisito : inventario) {
            if (entradas.stream().noneMatch(e -> e.requisito().equals(requisito))) {
                violacoes.add("requisito sem evidência: " + requisito);
            }
        }
        for (Entrada entrada : entradas) {
            if (!inventario.contains(entrada.requisito())) {
                violacoes.add("entrada órfã: " + entrada.requisito());
            }
            if (entrada.ancora().isBlank()) {
                violacoes.add("âncora vazia: " + entrada.requisito());
                continue;
            }
            if (entrada.ancora().contains(entrada.requisito())) {
                violacoes.add("âncora cita o identificador: " + entrada.requisito()
                        + " -> " + entrada.ancora());
            }
            if (SO_NOME_DE_COMANDO.contains(entrada.ancora().strip().toLowerCase())) {
                violacoes.add("âncora é apenas nome de comando: " + entrada.requisito()
                        + " -> " + entrada.ancora());
            }
            if (entrada.artefato().toLowerCase().endsWith(".md")) {
                violacoes.add("evidência em documento de prosa: " + entrada.requisito()
                        + " -> " + entrada.artefato()
                        + " (documentação descreve o requisito, não o implementa nem o verifica)");
            }
        }
        java.util.Map<String, String> donoDoPar = new java.util.LinkedHashMap<>();
        for (Entrada entrada : entradas) {
            String par = entrada.artefato() + ENTRE_ARTEFATO_E_ANCORA + entrada.ancora();
            String dono = donoDoPar.putIfAbsent(par, entrada.requisito());
            if (dono != null && !dono.equals(entrada.requisito())
                    && entrada.compartilhamento().isBlank()) {
                violacoes.add("evidência compartilhada sem declaração: " + dono
                        + " e " + entrada.requisito() + " -> " + par);
            }
        }
        return List.copyOf(violacoes);
    }

    /** Le o catalogo declarado no proprio roteiro, entre os marcadores. */
    static List<Entrada> catalogo() {
        String texto = ler(ROTEIRO);
        int inicio = texto.indexOf(CATALOGO_INICIO);
        int fim = texto.indexOf(CATALOGO_FIM);
        if (inicio < 0 || fim < 0 || fim < inicio) {
            throw new AssertionError("catálogo não delimitado em " + ROTEIRO);
        }
        List<Entrada> entradas = new ArrayList<>();
        for (String linha : texto.substring(inicio + CATALOGO_INICIO.length(), fim).split("\n", -1)) {
            String conteudo = linha.strip();
            if (conteudo.isEmpty() || conteudo.startsWith("#") || !conteudo.contains("|")) {
                continue;
            }
            String[] campos = conteudo.split("\\|", -1);
            if (campos.length < 3 || !campos[0].strip().matches("(RF|RNF)-\\d{2}")) {
                continue;
            }
            entradas.add(new Entrada(
                    campos[0].strip(), campos[1].strip(), campos[2].strip(),
                    campos.length > 3 ? campos[3].strip() : ""));
        }
        return List.copyOf(entradas);
    }

    static final String CATALOGO_INICIO = "# CATALOGO-INICIO";
    static final String CATALOGO_FIM = "# CATALOGO-FIM";
    /** Terminador do heredoc que carrega o catalogo: depois dele, a linha ja nao e dado. */
    static final String FIM_DO_HEREDOC = "FIM_DO_CATALOGO";

    // ------------------------------------------------------------------ contrato da saida

    static final String CABECALHO = "REQUISITO | STATUS | EVIDÊNCIA";
    static final String SEMANTICA_DO_OK = "evidência versionada presente e ancorada";
    static final String STATUS_APROVADO = "OK";
    static final String STATUS_REPROVADO = "FALHA";
    /** Separa as âncoras dentro do campo de evidência, sem colidir com o `|` das colunas. */
    static final String ENTRE_ANCORAS = ";;";
    /** Separa artefato e âncora dentro de uma evidência. */
    static final String ENTRE_ARTEFATO_E_ANCORA = "::";

    private static final Pattern LINHA_DE_SAIDA = Pattern.compile("^(RF|RNF)-\\d{2}\\s*\\|.*");

    static List<String> linhasDeRequisito(String saida) {
        return saida.lines().map(String::strip).filter(l -> LINHA_DE_SAIDA.matcher(l).matches()).toList();
    }

    static List<String> campos(String linha) {
        return java.util.Arrays.stream(linha.split("\\|", -1)).map(String::strip).toList();
    }

    // ------------------------------------------------------------------ execucao do roteiro

    record Execucao(int codigo, String saida, String erro) {}

    static Execucao auditar(Path raiz) {
        return executarBash(List.of(ROTEIRO.toString(), raiz.toString()), null);
    }

    static final Path ROTEIRO = RAIZ.resolve("scripts/auditoria.sh");

    static Execucao executarBash(List<String> argumentos, java.util.Map<String, String> ambiente) {
        try {
            Path trabalho = RAIZ.resolve("quality-gates/target/auditoria-de-requisitos");
            Files.createDirectories(trabalho);
            Path saida = Files.createTempFile(trabalho, "saida", ".txt");
            Path erro = Files.createTempFile(trabalho, "erro", ".txt");

            List<String> comando = new ArrayList<>();
            comando.add(executavelDoBash());
            comando.addAll(argumentos);
            ProcessBuilder construtor = new ProcessBuilder(comando)
                    .directory(RAIZ.toFile())
                    .redirectOutput(saida.toFile())
                    .redirectError(erro.toFile());
            if (ambiente != null) {
                construtor.environment().putAll(ambiente);
            }
            Process processo = construtor.start();
            if (!processo.waitFor(120, java.util.concurrent.TimeUnit.SECONDS)) {
                processo.destroyForcibly();
                throw new AssertionError("a auditoria não terminou em 120 segundos");
            }
            return new Execucao(
                    processo.exitValue(),
                    Files.readString(saida, StandardCharsets.UTF_8).replace("\r", ""),
                    Files.readString(erro, StandardCharsets.UTF_8).replace("\r", ""));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** Mesma estrategia do RoteiroDeDemonstracaoTest: o Bash nativo, nunca o do System32. */
    static String executavelDoBash() {
        if (!System.getProperty("os.name").toLowerCase().contains("windows")) {
            return "bash";
        }
        List<Path> diretorios = java.util.stream.Stream.of(
                        System.getenv("PATH").split(java.io.File.pathSeparator))
                .filter(d -> !d.isBlank())
                .map(Path::of)
                .filter(d -> !d.toString().toLowerCase().contains("\\system32")
                        && !d.toString().toLowerCase().contains("windowsapps"))
                .toList();
        for (Path diretorio : diretorios) {
            if (Files.isRegularFile(diretorio.resolve("bash.exe"))) {
                return diretorio.resolve("bash.exe").toString();
            }
        }
        for (Path diretorio : diretorios) {
            Path bash = diretorio.resolveSibling("bin").resolve("bash.exe");
            if (Files.isRegularFile(diretorio.resolve("git.exe")) && Files.isRegularFile(bash)) {
                return bash.toString();
            }
        }
        throw new AssertionError("Bash nativo não encontrado no PATH");
    }

    // ------------------------------------------------------------------ leitura do documento

    /** Identificadores do tipo, na ordem do documento, lidos das tabelas da secao correspondente. */
    static List<String> identificadores(String tipo) {
        String trecho = "RF".equals(tipo)
                ? trecho(INICIO_RF, INICIO_RNF)
                : trecho(INICIO_RNF, FIM_RNF);
        List<String> encontrados = new ArrayList<>();
        for (String linha : trecho.split("\n", -1)) {
            Matcher casamento = LINHA_DE_REQUISITO.matcher(linha.strip());
            if (casamento.find() && casamento.group(1).equals(tipo)) {
                encontrados.add(casamento.group(1) + "-" + casamento.group(2));
            }
        }
        return List.copyOf(encontrados);
    }

    /** Os 30 identificadores, RF antes de RNF, na ordem do documento. */
    static List<String> inventario() {
        List<String> todos = new ArrayList<>(identificadores("RF"));
        todos.addAll(identificadores("RNF"));
        return List.copyOf(todos);
    }

    private static String trecho(String inicio, String fim) {
        String texto = ler(ESPECIFICACAO);
        int comeco = texto.indexOf(inicio);
        if (comeco < 0) {
            throw new AssertionError("seção não encontrada em " + ESPECIFICACAO + ": " + inicio);
        }
        int termino = texto.indexOf(fim, comeco);
        if (termino < 0) {
            throw new AssertionError("fim de seção não encontrado em " + ESPECIFICACAO + ": " + fim);
        }
        return texto.substring(comeco, termino);
    }

    static List<String> sequencia(String tipo, int total) {
        List<String> esperada = new ArrayList<>();
        for (int i = 1; i <= total; i++) {
            esperada.add("%s-%02d".formatted(tipo, i));
        }
        return List.copyOf(esperada);
    }

    private static void exigirSequencia(String tipo, List<String> lidos, int total) {
        assertThat(lidos).as("inventário de %s", tipo).containsExactlyElementsOf(sequencia(tipo, total));
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
