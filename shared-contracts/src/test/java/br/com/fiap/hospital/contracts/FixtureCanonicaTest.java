package br.com.fiap.hospital.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * O exemplar canonico do contrato existe uma vez so, e e um envelope valido.
 *
 * <p>Produtor e consumidores exercitam o exemplar pelo test-jar deste modulo. Uma segunda
 * copia fisica — num servico, por exemplo — reabriria exatamente a divergencia que o exemplar
 * existe para impedir: cada lado passaria a testar contra a propria versao do contrato.
 */
@DisplayName("Fixture canonica do contrato")
class FixtureCanonicaTest {

    private static final String RECURSO = "evento-consulta.json";

    /** Diretorios que nao sao fonte: saidas de build, Git e dependencias de ferramentas. */
    private static final Set<String> IGNORADOS = Set.of("target", ".git", "node_modules", ".idea");

    @Test
    @DisplayName("Scenario: Exemplar canonico e envelope valido do contrato")
    void exemplarEAceitoPeloValidadorNormativo() {
        var evento = new EventoJson().ler(EntradasAmqp.fixture());

        assertThat(evento.eventType()).isEqualTo(TipoEvento.CONSULTA_CRIADA);
        assertThat(evento.version()).isEqualTo(1);
        assertThat(evento.aggregateId())
                .as("o agregado do envelope e a consulta do snapshot")
                .isEqualTo(evento.payload().consultaId());
    }

    @Test
    @DisplayName("Scenario: Existe uma unica copia fisica do exemplar")
    void existeUmaUnicaCopiaFisicaNoRepositorio() throws IOException {
        Path modulo = Path.of(System.getProperty("basedir", System.getProperty("user.dir")))
                .toAbsolutePath().normalize();
        Path raiz = modulo.getParent();
        Path canonico = modulo.resolve("src/test/resources/" + RECURSO);
        long tamanho = Files.size(canonico);

        List<Path> porNome = new ArrayList<>();
        List<Path> porConteudo = new ArrayList<>();
        Files.walkFileTree(raiz, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                return IGNORADOS.contains(String.valueOf(dir.getFileName()))
                        ? FileVisitResult.SKIP_SUBTREE
                        : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path arquivo, BasicFileAttributes attrs)
                    throws IOException {
                if (arquivo.getFileName().toString().equals(RECURSO)) {
                    porNome.add(arquivo);
                }
                if (attrs.size() == tamanho && Files.mismatch(arquivo, canonico) == -1L) {
                    porConteudo.add(arquivo);
                }
                return FileVisitResult.CONTINUE;
            }
        });

        assertThat(porNome)
                .as("um unico %s no repositorio, no modulo de contratos", RECURSO)
                .containsExactly(canonico);
        assertThat(porConteudo)
                .as("nenhum outro arquivo com o mesmo conteudo do exemplar, com qualquer nome")
                .containsExactly(canonico);
    }
}
