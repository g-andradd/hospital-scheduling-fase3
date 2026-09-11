package br.com.fiap.hospital.notificacao.estrutura;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.fiap.hospital.notificacao.lembrete.ServicoDeLembretes;
import br.com.fiap.hospital.notificacao.scheduler.AgendadorDeLembretes;
import br.com.fiap.hospital.notificacao.web.LembreteController;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Job e endpoint sao gatilhos, e so gatilhos.
 *
 * <p>Se um dos dois ganhasse regra propria — um filtro a mais, uma janela diferente —, o
 * lembrete automatico e o da demonstracao passariam a divergir, e a demonstracao deixaria de
 * mostrar o que o sistema faz. A prova tem duas partes: os dois chamam a mesma operacao, e
 * nenhum dos dois tem com o que construir regra.
 */
@DisplayName("Delegacao do lembrete D-1")
class DelegacaoDoLembreteTest {

    private static final Path RAIZ = Files.exists(Path.of("src/main/java"))
            ? Path.of("") : Path.of("notificacao-service");

    private static final List<Class<?>> GATILHOS =
            List.of(AgendadorDeLembretes.class, LembreteController.class);

    @Test
    @DisplayName("Scenario: Execucao automatica e disparo manual aplicam a mesma regra")
    void automaticoEManualAplicamAMesmaRegra() throws IOException {
        ServicoDeLembretes casoDeUso = Mockito.mock(ServicoDeLembretes.class);
        Mockito.when(casoDeUso.executar()).thenReturn(3);

        new AgendadorDeLembretes(casoDeUso).executar();
        var resultado = new LembreteController(casoDeUso).executar();

        Mockito.verify(casoDeUso, Mockito.times(2)).executar();
        Mockito.verifyNoMoreInteractions(casoDeUso);
        assertThat(resultado.lembretesEnviados())
                .as("o endpoint responde o que o caso de uso devolveu, sem recalcular")
                .isEqualTo(3);

        for (Class<?> gatilho : GATILHOS) {
            assertThat(dependencias(gatilho))
                    .as("%s depende so do caso de uso", gatilho.getSimpleName())
                    .containsExactly(ServicoDeLembretes.class);
            for (Constructor<?> construtor : gatilho.getDeclaredConstructors()) {
                assertThat(construtor.getParameterTypes()).containsExactly(ServicoDeLembretes.class);
            }
            assertThat(fonte(gatilho))
                    .as("%s nao tem com o que construir regra propria", gatilho.getSimpleName())
                    .doesNotContain("JdbcTemplate", "Clock", "NotificationSenderPort",
                            "SELECT", "INSERT", "agenda_local");
        }
    }

    private static List<Class<?>> dependencias(Class<?> tipo) {
        return Arrays.stream(tipo.getDeclaredFields())
                .filter(campo -> !Modifier.isStatic(campo.getModifiers()))
                .map(Field::getType)
                .<Class<?>>map(c -> c)
                .toList();
    }

    /**
     * O codigo do gatilho, sem comentarios.
     *
     * <p>Um Javadoc que explica de onde vem a janela precisa poder citar o {@code Clock}; o que
     * a guarda recusa e o gatilho <b>usar</b> um.
     */
    private static String fonte(Class<?> tipo) throws IOException {
        String fonte = Files.readString(RAIZ.resolve("src/main/java")
                .resolve(tipo.getName().replace('.', '/') + ".java"));
        return fonte.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
    }
}
