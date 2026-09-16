package br.com.fiap.hospital.notificacao.estrutura;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * O servico usa o exemplar canonico do contrato, e so ele.
 *
 * <p>Tres coisas precisam valer ao mesmo tempo: o recurso resolvido e unico e vem do
 * shared-contracts, seus bytes sao os do arquivo canonico, e nenhum teste deste servico passa
 * a depender de auxiliares internos da suite do shared-contracts.
 */
@DisplayName("Fixture canonica compartilhada")
class FixtureCompartilhadaTest {

    private static final String PACOTE_DO_CONTRATO = "br.com.fiap.hospital.contracts";

    @Test
    @DisplayName("Scenario: Existe uma unica copia fisica do exemplar")
    void resolveUmUnicoExemplarDoContratoIdenticoAoCanonico() throws IOException {
        List<URL> ocorrencias = FixtureDoContrato.ocorrencias();

        assertThat(ocorrencias)
                .as("exatamente um %s no classpath de testes", FixtureDoContrato.RECURSO)
                .hasSize(1);
        URL url = ocorrencias.getFirst();
        assertThat(FixtureDoContrato.vemDoTestJar(url)
                        || FixtureDoContrato.vemDoDiretorioDeTestesDoContrato(url))
                .as("o exemplar precisa vir do shared-contracts — o test-jar ou, em mvn test, "
                        + "a saida de testes dele —, e veio de %s", url)
                .isTrue();
        assertThat(FixtureDoContrato.vemDoProprioModulo(url))
                .as("o servico nao pode resolver uma copia propria do exemplar")
                .isFalse();
        assertThat(FixtureDoContrato.sha256(FixtureDoContrato.bytes()))
                .as("os bytes resolvidos sao os do arquivo canonico %s", FixtureDoContrato.canonico())
                .isEqualTo(FixtureDoContrato.sha256(Files.readAllBytes(FixtureDoContrato.canonico())));

        FixtureDoContrato.registrarOrigem(url);
    }

    @Test
    @DisplayName("Scenario: Copia ou sombreamento do exemplar em um servico e recusado")
    void servicoNaoMantemCopiaPropriaDoExemplar() throws IOException {
        Path canonico = FixtureDoContrato.canonico();
        long tamanho = Files.size(canonico);

        try (Stream<Path> arquivos = Files.walk(FixtureDoContrato.moduloAtual().resolve("src"))) {
            List<Path> copias = arquivos
                    .filter(Files::isRegularFile)
                    .filter(arquivo -> arquivo.getFileName().toString().equals(FixtureDoContrato.RECURSO)
                            || mesmoConteudo(arquivo, canonico, tamanho))
                    .toList();

            assertThat(copias)
                    .as("o servico usa o exemplar pelo test-jar; uma copia nas fontes dele "
                            + "sombrearia o canonico")
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("nenhum teste do servico usa tipo declarado nas fontes de teste do shared-contracts")
    void naoReferenciaAuxiliaresDeTesteDoContrato() throws IOException {
        Set<String> tiposDeTesteDoContrato = tiposDeclarados(
                FixtureDoContrato.raizDoRepositorio().resolve("shared-contracts/src/test/java"));
        assertThat(tiposDeTesteDoContrato)
                .as("a varredura precisa enxergar os auxiliares de teste do contrato; vazia, "
                        + "ela passaria sem verificar nada")
                .contains("EntradasAmqp", "RabbitITBase");

        Pattern importCuringa = Pattern.compile(
                "import\\s+" + Pattern.quote(PACOTE_DO_CONTRATO) + "\\.\\*\\s*;");
        List<String> violacoes = new ArrayList<>();
        try (Stream<Path> fontes = Files.walk(
                FixtureDoContrato.moduloAtual().resolve("src/test/java"))) {
            for (Path fonte : fontes.filter(p -> p.toString().endsWith(".java")).toList()) {
                String texto = Files.readString(fonte);
                boolean curinga = importCuringa.matcher(texto).find();
                for (String tipo : tiposDeTesteDoContrato) {
                    boolean qualificado = Pattern.compile(
                            "\\b" + Pattern.quote(PACOTE_DO_CONTRATO + "." + tipo) + "\\b")
                            .matcher(texto).find();
                    boolean simples = curinga
                            && Pattern.compile("\\b" + Pattern.quote(tipo) + "\\b").matcher(texto).find();
                    if (qualificado || simples) {
                        violacoes.add(fonte.getFileName() + " -> " + tipo);
                    }
                }
            }
        }

        assertThat(violacoes)
                .as("o test-jar publica so o exemplar; auxiliares de teste do shared-contracts "
                        + "nao sao API deste servico")
                .isEmpty();
    }

    private static Set<String> tiposDeclarados(Path fontes) throws IOException {
        try (Stream<Path> arquivos = Files.walk(fontes)) {
            return arquivos
                    .map(p -> p.getFileName().toString())
                    .filter(nome -> nome.endsWith(".java"))
                    .map(nome -> nome.substring(0, nome.length() - ".java".length()))
                    .collect(Collectors.toUnmodifiableSet());
        }
    }

    private static boolean mesmoConteudo(Path arquivo, Path canonico, long tamanho) {
        try {
            return Files.size(arquivo) == tamanho && Files.mismatch(arquivo, canonico) == -1L;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
