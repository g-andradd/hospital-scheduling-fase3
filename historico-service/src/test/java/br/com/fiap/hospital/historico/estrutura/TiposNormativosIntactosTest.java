package br.com.fiap.hospital.historico.estrutura;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.fiap.hospital.contracts.TipoEvento;
import br.com.fiap.hospital.historico.infrastructure.correcao.CorrecaoDeRegistroHistorico;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * O tipo local da correcao nao pode virar um sexto evento do contrato.
 *
 * <p>{@code CORRECAO_MANUAL} existe so na coluna {@code tipo_evento} do historico. Se um
 * dia alguem o acrescentasse a {@code TipoEvento} "para ficar consistente", ele ganharia
 * routing key, seria publicado no exchange e o historico viraria produtor — invertendo a
 * direcao do fluxo da ADR-002 sem que nenhum teste de comportamento reclamasse.
 */
@DisplayName("Tipos normativos de evento")
class TiposNormativosIntactosTest {

    private static final List<String> NORMATIVOS = List.of(
            "CONSULTA_CRIADA", "CONSULTA_ATUALIZADA", "CONSULTA_CONFIRMADA",
            "CONSULTA_CANCELADA", "CONSULTA_REALIZADA");

    @Test
    @DisplayName("o contrato continua com exatamente os cinco tipos")
    void contratoContinuaComOsCincoTipos() {
        assertThat(Arrays.stream(TipoEvento.values()).map(Enum::name).toList())
                .containsExactlyElementsOf(NORMATIVOS);
    }

    @Test
    @DisplayName("o tipo local da correcao nao entrou no contrato")
    void tipoLocalNaoEntrouNoContrato() {
        assertThat(NORMATIVOS)
                .doesNotContain(CorrecaoDeRegistroHistorico.TIPO_CORRECAO_MANUAL);
        assertThat(Arrays.stream(TipoEvento.values()).map(TipoEvento::routingKey).toList())
                .as("nem como routing key")
                .noneMatch(chave -> chave.toLowerCase().contains("correcao"));
    }

    /**
     * O historico consome; nao publica.
     *
     * <p>Uma publicacao introduzida no modulo apareceria como uso de {@code convertAndSend}
     * — e nenhum teste de correcao notaria, porque a correcao continuaria funcionando.
     */
    @Test
    @DisplayName("o historico nao publica evento algum")
    void historicoNaoPublicaEvento() throws IOException {
        Path raiz = Files.exists(Path.of("src/main/java"))
                ? Path.of("src/main/java")
                : Path.of("historico-service/src/main/java");

        try (var arquivos = Files.walk(raiz)) {
            List<String> ofensores = arquivos
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> conteudo(p).contains("convertAndSend")
                            || conteudo(p).contains("RabbitTemplate"))
                    .map(Path::toString)
                    .toList();

            assertThat(ofensores)
                    .as("publicar do historico inverteria a direcao do fluxo da ADR-002")
                    .isEmpty();
        }
    }

    private String conteudo(Path arquivo) {
        try {
            return Files.readString(arquivo);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
