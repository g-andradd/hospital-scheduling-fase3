package br.com.fiap.hospital.qualidade;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Exercita somente as funcoes locais do roteiro de demonstracao, sem rede nem containers. */
@DisplayName("Roteiro de demonstração")
class RoteiroDeDemonstracaoTest {

    private static final String TRABALHO = "quality-gates/target/roteiro-de-demonstracao";

    private record Execucao(int codigo, String saida, String erro) {}

    @BeforeEach
    void preparar() throws IOException {
        Files.createDirectories(PomsDoReactor.RAIZ.resolve(TRABALHO));
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-09-14T12:00:00Z", "2026-09-14T12:30:00Z", "2026-09-14T23:30:00Z"})
    @DisplayName("Scenario: Consulta de demonstração fica na janela do lembrete")
    void horarioFicaEntreDuasETresHorasDepois(String agoraIso) {
        long agora = Instant.parse(agoraIso).getEpochSecond();
        Execucao execucao = bash("source scripts/demo.sh; horario_da_consulta " + agora);

        assertThat(execucao.codigo()).isZero();
        assertThat(execucao.erro()).isEmpty();
        Instant horario = Instant.parse(execucao.saida().strip());
        long segundos = ChronoUnit.SECONDS.between(Instant.ofEpochSecond(agora), horario);
        assertThat(segundos).isGreaterThanOrEqualTo(2 * 3600L).isLessThanOrEqualTo(3 * 3600L);
        assertThat(horario).isAfter(Instant.ofEpochSecond(agora))
                .isBeforeOrEqualTo(Instant.ofEpochSecond(agora + 86400));
    }

    @Test
    @DisplayName("Scenario: Pré-requisito curl ausente é recusado antes de alterar o ambiente")
    void curlAusenteERecusadoSemCriarEnv() {
        assertarPreRequisitoAusente("curl", "curl");
    }

    @Test
    @DisplayName("Scenario: Pré-requisito jq ausente é recusado antes de alterar o ambiente")
    void jqAusenteERecusadoSemCriarEnv() {
        assertarPreRequisitoAusente("jq", "jq 1.6 ou superior");
    }

    private static void assertarPreRequisitoAusente(String executavel, String mensagem) {
        String caso = TRABALHO + "/sem-" + executavel;
        Execucao execucao = bash("source scripts/demo.sh; mkdir -p " + caso + "; cd " + caso + "; "
                + "caminho=''; IFS=: read -ra partes <<< \"$PATH\"; "
                + "for d in \"${partes[@]}\"; do "
                + "[[ -x \"$d/" + executavel + "\" || -x \"$d/" + executavel + ".exe\" ]] "
                + "|| caminho+=\"${caminho:+:}$d\"; done; "
                + "PATH=\"$caminho\"; command -v " + executavel + " >/dev/null && exit 99; "
                + "set +e; preflight; codigo=$?; printf '\\ncodigo=%s env=%s' \"$codigo\" \"$([[ -e .env ]] && echo sim || echo nao)\"; exit 0");

        assertThat(execucao.codigo()).isZero();
        assertThat(execucao.saida()).contains("codigo=2 env=nao");
        assertThat(execucao.erro()).contains("pre-requisito ausente: " + mensagem);
    }

    private static Execucao bash(String comando) {
        try {
            Path pasta = PomsDoReactor.RAIZ.resolve(TRABALHO);
            Path script = pasta.resolve("comando.sh");
            Path saida = pasta.resolve("saida.txt");
            Path erro = pasta.resolve("erro.txt");
            Files.writeString(script, comando + "\n", StandardCharsets.UTF_8);
            Process processo = new ProcessBuilder(executavelDoBash(), TRABALHO + "/comando.sh")
                    .directory(PomsDoReactor.RAIZ.toFile())
                    .redirectOutput(saida.toFile())
                    .redirectError(erro.toFile())
                    .start();
            if (!processo.waitFor(30, TimeUnit.SECONDS)) {
                processo.destroyForcibly();
                throw new AssertionError("bash nao terminou em 30 segundos");
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

    private static String executavelDoBash() {
        if (!System.getProperty("os.name").toLowerCase().contains("windows")) return "bash";
        List<Path> diretorios = Stream.of(System.getenv("PATH").split(java.io.File.pathSeparator))
                .filter(d -> !d.isBlank())
                .map(Path::of)
                .filter(d -> !d.toString().toLowerCase().contains("\\system32")
                        && !d.toString().toLowerCase().contains("windowsapps"))
                .toList();
        for (Path diretorio : diretorios) {
            if (Files.isRegularFile(diretorio.resolve("bash.exe"))) return diretorio.resolve("bash.exe").toString();
        }
        for (Path diretorio : diretorios) {
            Path bash = diretorio.resolveSibling("bin").resolve("bash.exe");
            if (Files.isRegularFile(diretorio.resolve("git.exe")) && Files.isRegularFile(bash)) return bash.toString();
        }
        throw new AssertionError("Bash nativo nao encontrado no PATH");
    }
}
