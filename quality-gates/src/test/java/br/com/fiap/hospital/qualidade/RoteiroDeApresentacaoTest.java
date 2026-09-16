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
 * Guarda do roteiro de demonstracao: {@code docs/roteiro-demo.md}.
 *
 * <p>Nao confundir com o {@code RoteiroDeDemonstracaoTest}, que exercita as funcoes Bash de
 * {@code scripts/demo.sh}. Este aqui verifica o documento que a banca assiste.
 *
 * <p>Duas garantias sustentam o resto. A primeira e a janela: a soma das duracoes dos blocos
 * cronometrados fica entre 5 e 8 minutos, e a preparacao do ambiente vive fora dessa conta —
 * construir imagens pode levar mais que a apresentacao inteira, e prometer 8 minutos incluindo
 * o build seria promessa falsa. A segunda e a fidelidade: endereco, credencial e comando sao
 * conferidos contra os arquivos reais, entao trocar uma porta no Compose quebra este teste.
 */
@DisplayName("Roteiro de apresentação")
class RoteiroDeApresentacaoTest {

    private static final Path RAIZ = PomsDoReactor.RAIZ;
    private static final Path ROTEIRO = RAIZ.resolve("docs/roteiro-demo.md");

    static final int MINIMO_DE_MINUTOS = 5;
    static final int MAXIMO_DE_MINUTOS = 8;

    private static final String TITULO_PRE = "## Pré-demonstração";
    private static final String TITULO_CRONOMETRADO = "## Apresentação cronometrada";

    /** Linha de bloco: | 1 | 0:45 | ação | comando/URL | o que aparece | alternativa | */
    private static final Pattern LINHA_DE_BLOCO =
            Pattern.compile("^\\|\\s*(\\d+)\\s*\\|\\s*(\\d+):(\\d{2})\\s*\\|(.+)$");

    /** As cinco superficies obrigatorias, com o endereco que o ambiente completo publica. */
    static final List<String> SUPERFICIES = List.of(
            "http://localhost:8081/swagger-ui.html",
            "http://localhost:8083/graphiql",
            "http://localhost:15672",
            "http://localhost:8025",
            "correlationId");

    /** Credenciais do profile demo, conforme docs/02 secao 5. */
    static final List<String> CREDENCIAIS = List.of(
            "medico@hospital.com", "enfermeiro@hospital.com",
            "paciente@hospital.com", "Senha@123");

    /** Comandos que o roteiro pode citar; cada um precisa existir no repositorio. */
    static final List<String> COMANDOS = List.of("make demo", "make ps", "make logs");

    /** Nada no roteiro pode destruir o ambiente do Gabriel. */
    static final List<String> DESTRUTIVOS =
            List.of("down -v", "make reset", "volume rm", "volume prune", "system prune");

    // ------------------------------------------------------------------ garantias reais

    @Test
    @DisplayName("Scenario: Duração total cabe na janela de 5 a 8 minutos")
    void duracaoCronometradaFicaEntreCincoEOito() {
        String texto = ler(ROTEIRO);
        assertThat(violacoes(texto)).isEmpty();

        List<Bloco> blocos = blocosCronometrados(texto);
        assertThat(blocos).as("nenhum bloco cronometrado encontrado").isNotEmpty();
        assertThat(blocos).allSatisfy(b -> assertThat(b.segundos()).isPositive());

        int total = blocos.stream().mapToInt(Bloco::segundos).sum();
        assertThat(total)
                .as("soma das durações declaradas: %d s (%s)", total, comoMinutos(total))
                .isBetween(MINIMO_DE_MINUTOS * 60, MAXIMO_DE_MINUTOS * 60);
    }

    @Test
    @DisplayName("Scenario: As cinco superfícies obrigatórias aparecem com endereço real")
    void roteiroCobreAsCincoSuperficies() {
        String texto = ler(ROTEIRO);
        SUPERFICIES.forEach(superficie ->
                assertThat(texto).as("superfície obrigatória ausente: %s", superficie).contains(superficie));
    }

    @Test
    @DisplayName("Scenario: Comandos e credenciais conferem com os artefatos reais")
    void comandosECredenciaisConferemComOsArtefatos() {
        String texto = ler(ROTEIRO);
        String especificacao = ler(RAIZ.resolve("docs/02-especificacao-funcional.md"));
        String compose = ler(RAIZ.resolve("docker-compose.yml"));
        String makefile = ler(RAIZ.resolve("Makefile"));

        CREDENCIAIS.forEach(credencial -> {
            assertThat(texto).as("credencial ausente do roteiro: %s", credencial).contains(credencial);
            assertThat(especificacao)
                    .as("credencial do roteiro não confere com docs/02 §5: %s", credencial)
                    .contains(credencial);
        });

        for (String comando : COMANDOS) {
            if (texto.contains(comando)) {
                assertThat(makefile)
                        .as("o roteiro cita %s, que não existe no Makefile", comando)
                        .contains(comando.replace("make ", "") + ":");
            }
        }

        assertThat(compose)
                .as("as portas citadas pelo roteiro precisam ser as publicadas pelo Compose")
                .contains("8081:8081").contains("8083:8083").contains("8025:8025");

        DESTRUTIVOS.forEach(destrutivo -> assertThat(texto)
                .as("o roteiro não pode mandar destruir o ambiente: %s", destrutivo)
                .doesNotContain(destrutivo));
    }

    @Test
    @DisplayName("Scenario: Correlação é demonstrada por um único identificador")
    void blocoDeLogsExigeCorrelacaoUnica() {
        String texto = ler(ROTEIRO);
        assertThat(texto).contains("correlationId");
        assertThat(texto)
                .as("o bloco de logs precisa nomear os três serviços")
                .contains("agendamento").contains("notificacao").contains("historico");
    }

    @Test
    @DisplayName("Scenario: Falha de uma interface tem alternativa curta")
    void cadaBlocoTemAlternativaCurta() {
        List<Bloco> blocos = blocosCronometrados(ler(ROTEIRO));
        assertThat(blocos).isNotEmpty();
        blocos.forEach(bloco -> assertThat(bloco.alternativa())
                .as("bloco %d sem alternativa curta", bloco.numero())
                .isNotBlank());
    }

    @Test
    @DisplayName("a pré-demonstração existe e fica fora da contagem")
    void preDemonstracaoFicaForaDaContagem() {
        String texto = ler(ROTEIRO);
        assertThat(texto).contains(TITULO_PRE).contains(TITULO_CRONOMETRADO);
        assertThat(texto.indexOf(TITULO_PRE))
                .as("a preparação vem antes do relógio")
                .isLessThan(texto.indexOf(TITULO_CRONOMETRADO));
        assertThat(blocosCronometrados(texto))
                .as("nenhum bloco cronometrado pode estar na seção de preparação")
                .isNotEmpty();
    }

    // ------------------------------------------------------------------ 3.2 negativos

    @Test
    @DisplayName("cada desvio do roteiro reprova com diagnóstico próprio")
    void negativosDoRoteiroReprovamFechado() {
        String texto = ler(ROTEIRO);
        List<Bloco> blocos = blocosCronometrados(texto);
        Bloco primeiro = blocos.getFirst();

        String semDuracao = texto.replace(primeiro.linha(),
                primeiro.linha().replaceFirst("\\|\\s*\\d+:\\d{2}\\s*\\|", "| |"));
        assertThat(violacoes(semDuracao)).anySatisfy(v -> assertThat(v).contains("bloco sem duração"));

        assertThat(violacoes(comTotalDe(texto, 4 * 60)))
                .anySatisfy(v -> assertThat(v).contains("janela de 5 a 8 minutos"));
        assertThat(violacoes(comTotalDe(texto, 9 * 60)))
                .anySatisfy(v -> assertThat(v).contains("janela de 5 a 8 minutos"));

        assertThat(violacoes(texto.replace(TITULO_PRE, "## Preparação")))
                .anySatisfy(v -> assertThat(v).contains("pré-demonstração"));

        for (String superficie : SUPERFICIES) {
            assertThat(violacoes(texto.replace(superficie, "http://exemplo.invalido")))
                    .as("superfície %s", superficie)
                    .anySatisfy(v -> assertThat(v).contains("superfície obrigatória ausente"));
        }

        assertThat(violacoes(texto.replace("Senha@123", "Senha@999")))
                .anySatisfy(v -> assertThat(v).contains("credencial"));

        assertThat(violacoes(texto + "\n| 9 | 0:30 | limpar | `make reset` | nada | nenhuma |\n"))
                .anySatisfy(v -> assertThat(v).contains("comando destrutivo"));

        assertThat(violacoes(texto.replace(primeiro.linha(), semAlternativa(primeiro.linha()))))
                .anySatisfy(v -> assertThat(v).contains("sem alternativa"));
    }

    // ------------------------------------------------------------------ regras

    record Bloco(int numero, int segundos, String alternativa, String linha) {}

    static List<String> violacoes(String texto) {
        List<String> violacoes = new ArrayList<>();

        if (!texto.contains(TITULO_PRE)) {
            violacoes.add("seção de pré-demonstração ausente");
        }
        if (!texto.contains(TITULO_CRONOMETRADO)) {
            violacoes.add("seção de apresentação cronometrada ausente");
        }
        for (String superficie : SUPERFICIES) {
            if (!texto.contains(superficie)) {
                violacoes.add("superfície obrigatória ausente: " + superficie);
            }
        }
        for (String credencial : CREDENCIAIS) {
            if (!texto.contains(credencial)) {
                violacoes.add("credencial ausente ou divergente de docs/02 §5: " + credencial);
            }
        }
        for (String destrutivo : DESTRUTIVOS) {
            if (texto.contains(destrutivo)) {
                violacoes.add("comando destrutivo no roteiro: " + destrutivo);
            }
        }

        List<Bloco> blocos = blocosCronometrados(texto);
        if (blocos.isEmpty()) {
            violacoes.add("nenhum bloco cronometrado");
            return List.copyOf(violacoes);
        }
        for (String linha : linhasDeBlocoSemDuracao(texto)) {
            violacoes.add("bloco sem duração: " + linha);
        }
        for (Bloco bloco : blocos) {
            if (bloco.alternativa().isBlank()) {
                violacoes.add("bloco " + bloco.numero() + " sem alternativa curta");
            }
        }
        int total = blocos.stream().mapToInt(Bloco::segundos).sum();
        if (total < MINIMO_DE_MINUTOS * 60 || total > MAXIMO_DE_MINUTOS * 60) {
            violacoes.add("fora da janela de 5 a 8 minutos: " + comoMinutos(total));
        }
        return List.copyOf(violacoes);
    }

    static List<Bloco> blocosCronometrados(String texto) {
        List<Bloco> blocos = new ArrayList<>();
        for (String linha : trechoCronometrado(texto).split("\n", -1)) {
            Matcher casamento = LINHA_DE_BLOCO.matcher(linha.strip());
            if (!casamento.matches()) {
                continue;
            }
            List<String> colunas = colunasDe(linha);
            blocos.add(new Bloco(
                    Integer.parseInt(casamento.group(1)),
                    Integer.parseInt(casamento.group(2)) * 60 + Integer.parseInt(casamento.group(3)),
                    colunas.isEmpty() ? "" : colunas.getLast(),
                    linha.strip()));
        }
        return List.copyOf(blocos);
    }

    /** Linhas numeradas da tabela cronometrada cuja coluna de duracao nao esta preenchida. */
    private static List<String> linhasDeBlocoSemDuracao(String texto) {
        List<String> sem = new ArrayList<>();
        for (String linha : trechoCronometrado(texto).split("\n", -1)) {
            String conteudo = linha.strip();
            if (!conteudo.matches("^\\|\\s*\\d+\\s*\\|.*")) {
                continue;
            }
            if (!LINHA_DE_BLOCO.matcher(conteudo).matches()) {
                sem.add(conteudo);
            }
        }
        return List.copyOf(sem);
    }

    private static String trechoCronometrado(String texto) {
        int inicio = texto.indexOf(TITULO_CRONOMETRADO);
        return inicio < 0 ? "" : texto.substring(inicio);
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

    /**
     * O mesmo roteiro, com o total cronometrado forcado ao valor desejado.
     *
     * <p>Zera todos os blocos e concentra o total no primeiro. Encolher so o primeiro nao
     * alcanca valores abaixo da janela quando os demais ja somam mais que o minimo.
     */
    private static String comTotalDe(String texto, int segundosDesejados) {
        String mutado = texto;
        for (Bloco bloco : blocosCronometrados(texto)) {
            mutado = mutado.replace(bloco.linha(), comDuracao(bloco.linha(), 0));
        }
        Bloco primeiro = blocosCronometrados(mutado).getFirst();
        return mutado.replace(primeiro.linha(), comDuracao(primeiro.linha(), segundosDesejados));
    }

    /** A mesma linha com a ultima coluna — a alternativa curta — esvaziada. */
    private static String semAlternativa(String linha) {
        String conteudo = linha.strip();
        int ultimo = conteudo.lastIndexOf('|');
        int penultimo = conteudo.lastIndexOf('|', ultimo - 1);
        return conteudo.substring(0, penultimo + 1) + "   |";
    }

    private static String comDuracao(String linha, int segundos) {
        return linha.replaceFirst(
                "\\|\\s*\\d+:\\d{2}\\s*\\|", "| %d:%02d |".formatted(segundos / 60, segundos % 60));
    }

    static String comoMinutos(int segundos) {
        return "%d:%02d".formatted(segundos / 60, segundos % 60);
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
