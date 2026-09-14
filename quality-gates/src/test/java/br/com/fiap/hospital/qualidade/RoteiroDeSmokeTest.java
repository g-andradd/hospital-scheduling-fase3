package br.com.fiap.hospital.qualidade;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Roteiro de smoke (D10, D11 e D13): regras estruturais sobre scripts sinteticos e sobre o real, e
 * as funcoes do script real carregadas por {@code source}, sem executar o fluxo.
 *
 * <p>O Bash roda com o diretorio de trabalho na raiz do reactor e so recebe caminhos relativos a ela.
 */
class RoteiroDeSmokeTest {

    private static final Path ROTEIRO = PomsDoReactor.RAIZ.resolve("scripts").resolve("smoke-test.sh");
    private static final String TRABALHO = "quality-gates/target/roteiro-de-smoke";

    /** Roteiro sintetico que respeita as regras; cada negativo muda uma unica coisa. */
    private static final String VALIDO = """
            #!/usr/bin/env bash
            # sleep, docker compose, pkill e mktemp em comentario nao contam
            set -Eeuo pipefail

            aguardar() {
                sleep 0.25
            }

            preflight() {
                command -v jq >/dev/null
            }

            criar_runtime() {
                RUNTIME="$(mktemp -d)"
            }

            main() {
                trap on_exit EXIT
                preflight
                criar_runtime
            }

            main "$@"
            """;

    @Nested
    @DisplayName("Scenario: Espera assíncrona é condicional — regras estruturais do roteiro")
    class Estrutura {

        @Test
        @DisplayName("o roteiro real passa")
        void roteiroReal() throws IOException {
            assertThat(RoteiroDeSmoke.verificar(Files.readString(ROTEIRO, StandardCharsets.UTF_8))).isEmpty();
        }

        @Test
        @DisplayName("o roteiro sintético válido passa")
        void sinteticoValido() {
            assertThat(RoteiroDeSmoke.verificar(VALIDO)).isEmpty();
        }

        static Stream<Arguments> negativos() {
            return Stream.of(
                    Arguments.of("sem modo estrito", VALIDO.replace("set -Eeuo pipefail\n", ""),
                            "roteiro sem modo estrito: o primeiro comando deve ser set -euo pipefail"),
                    Arguments.of("sleep fora de aguardar", VALIDO.replace("    preflight\n", "    preflight\n    sleep 5\n"),
                            "pausa fixa fora de aguardar, linha 20: sleep 5"),
                    Arguments.of("docker compose", VALIDO.replace("    criar_runtime\n", "    criar_runtime\n    docker compose up -d\n"),
                            "docker compose no roteiro, linha 21: docker compose up -d"),
                    Arguments.of("pkill", VALIDO.replace("    criar_runtime\n", "    criar_runtime\n    pkill -f exec.jar\n"),
                            "encerramento de processo por nome, linha 21: pkill -f exec.jar"),
                    Arguments.of("killall", VALIDO.replace("    criar_runtime\n", "    criar_runtime\n    killall java\n"),
                            "encerramento de processo por nome, linha 21: killall java"),
                    Arguments.of("mktemp antes do preflight",
                            VALIDO.replace("    preflight\n    criar_runtime\n", "    criar_runtime\n    preflight\n"),
                            "mktemp antes do preflight, linha 19: criar_runtime"));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("negativos")
        @DisplayName("negativo estrutural é recusado")
        void negativo(String caso, String roteiro, String violacao) {
            assertThat(RoteiroDeSmoke.verificar(roteiro)).containsExactly(violacao);
        }
    }

    /** Resultado de um Bash: codigo, saida e erro. */
    private record Execucao(int codigo, String saida, String erro) {}

    /**
     * O Bash do PATH. No Windows, o {@code CreateProcess} procuraria antes em System32, onde o
     * {@code bash.exe} e o lancador do WSL; por isso os lancadores sao ignorados e, sem Bash no PATH,
     * o do Git ao lado do {@code git.exe} e usado.
     */
    private static String executavelDoBash() {
        if (!System.getProperty("os.name").toLowerCase().contains("windows")) {
            return "bash";
        }
        List<Path> diretorios = Stream.of(System.getenv("PATH").split(java.io.File.pathSeparator))
                .filter(d -> !d.isBlank())
                .map(Path::of)
                .filter(d -> !d.toString().toLowerCase().contains("\\system32") && !d.toString().toLowerCase().contains("windowsapps"))
                .toList();
        for (Path diretorio : diretorios) {
            if (Files.isRegularFile(diretorio.resolve("bash.exe"))) {
                return diretorio.resolve("bash.exe").toString();
            }
        }
        for (Path diretorio : diretorios) {
            Path git = diretorio.resolve("git.exe");
            Path bash = diretorio.resolveSibling("bin").resolve("bash.exe");
            if (Files.isRegularFile(git) && Files.isRegularFile(bash)) {
                return bash.toString();
            }
        }
        throw new AssertionError("Bash nao encontrado no PATH: o roteiro de smoke exige Bash 4 ou superior (no Windows, o do Git)");
    }

    private static Execucao bash(String comando) {
        try {
            Path saida = PomsDoReactor.RAIZ.resolve(TRABALHO).resolve("saida.txt");
            Path erro = PomsDoReactor.RAIZ.resolve(TRABALHO).resolve("erro.txt");
            // Por arquivo, e nao por -c: no Windows, aspas embutidas num argumento nao chegam intactas.
            escrever(TRABALHO + "/comando.sh", comando + "\n");
            Process processo = new ProcessBuilder(executavelDoBash(), TRABALHO + "/comando.sh")
                    .directory(PomsDoReactor.RAIZ.toFile())
                    .redirectOutput(saida.toFile())
                    .redirectError(erro.toFile())
                    .start();
            if (!processo.waitFor(120, TimeUnit.SECONDS)) {
                processo.destroyForcibly();
                throw new AssertionError("bash nao terminou em 120 s: " + comando);
            }
            return new Execucao(processo.exitValue(), Files.readString(saida, StandardCharsets.UTF_8).replace("\r", ""),
                    Files.readString(erro, StandardCharsets.UTF_8).replace("\r", ""));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static void escrever(String relativo, String conteudo) {
        try {
            Path arquivo = PomsDoReactor.RAIZ.resolve(relativo);
            Files.createDirectories(arquivo.getParent());
            Files.writeString(arquivo, conteudo, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @BeforeEach
    void preparar() throws IOException {
        Files.createDirectories(PomsDoReactor.RAIZ.resolve(TRABALHO));
    }

    @Nested
    @DisplayName("Scenario: Porta efetiva é identificada sem ambiguidade")
    class Porta {

        private static final String LINHA = "2026-09-13T19:38:09.264-03:00  INFO 43264 --- [agendamento-service] [main] "
                + "o.s.b.w.embedded.tomcat.TomcatWebServer  : Tomcat started on port %d (http) with context path '/'";

        static Stream<Arguments> logs() {
            String inicio = "  .   ____          _\nStarting AgendamentoApplication\n";
            return Stream.of(
                    Arguments.of("LF", inicio + LINHA.formatted(8123) + "\nStarted\n", "8123|0"),
                    Arguments.of("CRLF", (inicio + LINHA.formatted(8123) + "\nStarted\n").replace("\n", "\r\n"), "8123|0"),
                    Arguments.of("sem a linha", inicio + "Started\n", "|1"),
                    Arguments.of("duas portas distintas", inicio + LINHA.formatted(8123) + "\n" + LINHA.formatted(8124) + "\n", "|2"),
                    Arguments.of("JSON do profile docker", inicio + LINHA_JSON.formatted(8123) + "\nStarted\n", "8123|0"));
        }

        /** A mesma linha do Tomcat no formato logstash do profile docker (M11, D7). */
        private static final String LINHA_JSON = "{\"@timestamp\":\"2026-09-14T10:33:37.009-03:00\",\"@version\":\"1\","
                + "\"message\":\"Tomcat started on port %d (http) with context path '/'\","
                + "\"logger_name\":\"org.springframework.boot.web.embedded.tomcat.TomcatWebServer\",\"thread_name\":\"main\","
                + "\"level\":\"INFO\",\"level_value\":20000,\"service\":\"agendamento-service\"}";

        @ParameterizedTest(name = "{0}")
        @MethodSource("logs")
        @DisplayName("o parser do roteiro exige exatamente uma porta válida, com qualquer fim de linha")
        void parser(String caso, String log, String esperado) {
            String arquivo = TRABALHO + "/porta-" + caso.replace(' ', '-') + ".log";
            escrever(arquivo, log);
            Execucao execucao = bash("source scripts/smoke-test.sh && { saida=\"$(porta_unica " + arquivo
                    + ")\" && codigo=0 || codigo=$?; printf '%s|%s' \"$saida\" \"$codigo\"; }");
            assertThat(execucao.erro()).isEmpty();
            assertThat(execucao.saida()).isEqualTo(esperado);
        }
    }

    /**
     * A verificacao de log do roteiro recusa registro ausente e registro divergente, sem jq instalado.
     *
     * <p>O {@code jq} e injetado por {@code JQ_BIN} como um script de saida fixa que registra os
     * argumentos: prova a decisao do roteiro (codigo 3 ou 1) e que a consulta, o logger e a correlacao
     * da execucao chegam ao filtro. O filtro real e exercido pela execucao real do smoke. O PATH do
     * Bash e montado sem nenhum diretorio que contenha jq.
     */
    @Nested
    @DisplayName("Scenario: Registro ausente ou com correlação divergente reprova o smoke")
    class RegistroCorrelacionado {

        private static final String LOGGER = "br.com.fiap.hospital.historico.infrastructure.messaging.ConsumidorTransacionalDoHistorico";
        private static final String CONSULTA = "0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0";
        private static final String CORRELACAO = "smoke-20260914000000-abcdef";

        private Execucao verificarCom(String caso, String saidaDoJq) {
            String jq = TRABALHO + "/jq-" + caso;
            String argumentos = TRABALHO + "/jq-" + caso + ".argumentos";
            String log = TRABALHO + "/registro-" + caso + ".log";
            escrever(jq, "#!/usr/bin/env bash\nprintf '%s\\n' \"$@\" > " + argumentos + "\nprintf '%s' '" + saidaDoJq + "'\n");
            escrever(log, "{\"message\":\"qualquer\"}\n");
            return bash("chmod +x " + jq + "; caminho=''; IFS=: read -ra partes <<< \"$PATH\";"
                    + " for d in \"${partes[@]}\"; do [[ -x \"$d/jq\" || -x \"$d/jq.exe\" ]] || caminho+=\"${caminho:+:}$d\"; done;"
                    + " PATH=\"$caminho\"; command -v jq >/dev/null && { echo jq-no-path; exit 99; };"
                    + " source scripts/smoke-test.sh && { JQ_BIN=" + jq + "; inicio=$SECONDS;"
                    + " ( registro_correlacionado " + log + " " + LOGGER + " " + CONSULTA + " " + CORRELACAO + " 2 )"
                    + " && codigo=0 || codigo=$?; echo \"codigo=$codigo duracao=$((SECONDS - inicio))\"; }");
        }

        private static List<String> argumentos(String caso) throws IOException {
            return Files.readAllLines(PomsDoReactor.RAIZ.resolve(TRABALHO + "/jq-" + caso + ".argumentos"), StandardCharsets.UTF_8)
                    .stream().map(l -> l.replace("\r", "")).toList();
        }

        private static void exigirArgumentosDaExecucao(String caso) throws IOException {
            List<String> recebidos = argumentos(caso);
            assertThat(recebidos).containsSubsequence("--arg", "logger", LOGGER)
                    .containsSubsequence("--arg", "consulta", CONSULTA)
                    .containsSubsequence("--arg", "correlacao", CORRELACAO);
            assertThat(recebidos.getLast()).isEqualTo(TRABALHO + "/registro-" + caso + ".log");
        }

        @Test
        @DisplayName("registro do serviço ausente esgota o prazo curto e termina com código 3")
        void ausenteTerminaComPrazo() throws IOException {
            Execucao execucao = verificarCom("ausente", "");

            assertThat(execucao.saida()).startsWith("codigo=3 duracao=");
            int duracao = Integer.parseInt(execucao.saida().strip().replaceAll(".*duracao=", ""));
            assertThat(duracao).as("prazo curto do teste, e nao o timeout real do smoke").isBetween(1, 15);
            assertThat(execucao.erro()).contains("prazo de 2s esgotado aguardando registro JSON de ConsumidorTransacionalDoHistorico da consulta "
                    + CONSULTA + " com correlationId " + CORRELACAO);
            exigirArgumentosDaExecucao("ausente");
        }

        @Test
        @DisplayName("registro do fluxo com correlationId divergente termina com código 1")
        void divergenteTerminaComDivergencia() throws IOException {
            Execucao execucao = verificarCom("divergente", "divergente|true");

            assertThat(execucao.saida()).startsWith("codigo=1 duracao=");
            assertThat(execucao.erro()).contains("registro de " + LOGGER + " da consulta " + CONSULTA
                    + " com correlationId diferente de " + CORRELACAO);
            exigirArgumentosDaExecucao("divergente");
        }
    }

    @Nested
    @DisplayName("Scenario: Somente processos da própria execução são encerrados")
    class Processos {

        @Test
        @DisplayName("PID alheio injetado na lista não é sinalizado")
        void pidAlheio() {
            Execucao execucao = bash("source scripts/smoke-test.sh && {"
                    + " ( sleep 60 & echo $! > " + TRABALHO + "/alheio.pid );"
                    + " alheio=\"$(cat " + TRABALHO + "/alheio.pid)\";"
                    + " PIDS=(\"$alheio\"); NOME_DO_PID[$alheio]=alheio; JAR_DO_PID[$alheio]=servico-1.0.0-exec.jar;"
                    + " encerrar_processos;"
                    + " if kill -0 \"$alheio\" 2>/dev/null; then echo vivo; kill \"$alheio\"; else echo sinalizado; fi; }");
            assertThat(execucao.saida()).isEqualTo("vivo\n");
            assertThat(execucao.erro()).contains("nao confirmado como desta execucao: nao sinalizado");
        }
    }

    @Nested
    @DisplayName("Scenario: Pré-requisito ausente é recusado antes de criar recursos")
    class Preflight {

        @Test
        @DisplayName("PATH sem jq termina com código 2, sem diretório de runtime, container ou rede")
        void semJq() throws IOException {
            String temporario = TRABALHO + "/tmp-sem-jq";
            Files.createDirectories(PomsDoReactor.RAIZ.resolve(temporario));
            Execucao execucao = bash("contar() { docker ps -aq --filter label=br.com.fiap.hospital.smoke | wc -l;"
                    + " docker network ls -q --filter label=br.com.fiap.hospital.smoke | wc -l; };"
                    + " antes=\"$(contar)\"; caminho=''; IFS=: read -ra partes <<< \"$PATH\";"
                    + " for d in \"${partes[@]}\"; do [[ -x \"$d/jq\" || -x \"$d/jq.exe\" ]] || caminho+=\"${caminho:+:}$d\"; done;"
                    + " TMPDIR=\"$(pwd)/" + temporario + "\" PATH=\"$caminho\" \"$BASH\" scripts/smoke-test.sh; codigo=$?;"
                    + " [[ \"$(contar)\" == \"$antes\" ]] && echo recursos-inalterados; echo \"codigo=$codigo\"");
            assertThat(execucao.saida()).doesNotContain("RUN_ID").endsWith("recursos-inalterados\ncodigo=2\n");
            assertThat(execucao.erro()).contains("[smoke] pre-requisito ausente: jq 1.6 ou superior");
            try (Stream<Path> entradas = Files.list(PomsDoReactor.RAIZ.resolve(temporario))) {
                assertThat(entradas.map(p -> p.getFileName().toString()).filter(n -> n.startsWith("hospital-smoke")).toList())
                        .isEqualTo(List.of());
            }
        }
    }
}
