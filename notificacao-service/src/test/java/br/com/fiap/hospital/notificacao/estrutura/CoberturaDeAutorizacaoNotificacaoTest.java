package br.com.fiap.hospital.notificacao.estrutura;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.fiap.hospital.notificacao.integracao.MatrizDeAutorizacaoNotificacao;
import br.com.fiap.hospital.notificacao.web.LembreteController;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Todo endpoint HTTP do servico tem decisao de autorizacao explicita, e esta na matriz.
 *
 * <p>A cadeia compartilhada exige token em {@code /internal/**}, mas nao distingue perfil —
 * isso e trabalho do {@code @PreAuthorize}. Um endpoint novo sem anotacao ficaria aberto a
 * <b>qualquer usuario autenticado</b>, paciente inclusive, e nada falharia: a cadeia deixa
 * passar porque ha token, e a matriz nao acusa porque a linha nao existe.
 *
 * <p>A varredura percorre o diretorio de classes compiladas, e nao uma lista escrita aqui:
 * quem esquece a anotacao tambem esqueceria de atualizar a lista.
 */
@DisplayName("Cobertura de autorizacao do notificacao-service")
class CoberturaDeAutorizacaoNotificacaoTest {

    private static final String PACOTE = "br/com/fiap/hospital/notificacao";

    @Test
    @DisplayName("todo endpoint declara @PreAuthorize de metodo, sem permitAll")
    void todoEndpointDeclaraDecisaoDeAutorizacao() {
        endpointsEm(raizDe(LembreteController.class)).forEach(
                CoberturaDeAutorizacaoNotificacaoTest::verificar);
    }

    /**
     * A varredura precisa achar o endpoint, senao ela nao esta varrendo nada; e o que ela
     * acha precisa ser o que docs/02 secao 3 lista.
     */
    @Test
    @DisplayName("a varredura encontra exatamente os endpoints da tabela do notificacao em docs/02")
    void varreduraEncontraOsEndpointsDaMatriz() {
        Set<String> encontrados = new TreeSet<>();
        endpointsEm(raizDe(LembreteController.class)).forEach(m -> encontrados.add(descricao(m)));

        assertThat(encontrados)
                .as("nunca zero: uma varredura vazia passaria verde sem verificar nada")
                .containsExactly("POST /internal/lembretes/executar");
        assertThat(encontrados)
                .as("endpoint sem linha na matriz normativa nao tem decisao documentada")
                .isEqualTo(MatrizDeAutorizacaoNotificacao.endpoints());
    }

    /**
     * O infrator e <b>encontrado</b> pela mesma varredura, apontada para as classes de teste,
     * e nao entregue de bandeja ao verificador.
     */
    @Test
    @DisplayName("um endpoint sem @PreAuthorize nas classes de teste e encontrado e recusado")
    void infratorEEncontradoERecusado() {
        List<Method> doTeste = endpointsEm(raizDe(CoberturaDeAutorizacaoNotificacaoTest.class));

        assertThat(doTeste).extracting(Method::getDeclaringClass)
                .contains(InfratorSemDecisao.class, InfratorAberto.class);
        Method infrator = doTeste.stream()
                .filter(m -> m.getDeclaringClass() == InfratorSemDecisao.class)
                .findFirst().orElseThrow();
        assertThatThrownBy(() -> verificar(infrator)).isInstanceOf(AssertionError.class);
        Method aberto = doTeste.stream()
                .filter(m -> m.getDeclaringClass() == InfratorAberto.class)
                .findFirst().orElseThrow();
        assertThatThrownBy(() -> verificar(aberto))
                .as("permitAll e decisao de abrir, e nao de autorizar")
                .isInstanceOf(AssertionError.class);
    }

    private static void verificar(Method endpoint) {
        PreAuthorize decisao = endpoint.getAnnotation(PreAuthorize.class);
        assertThat(decisao)
                .as("%s precisa de @PreAuthorize no proprio metodo", descricao(endpoint))
                .isNotNull();
        assertThat(decisao.value())
                .as("%s nao pode ser aberto por permitAll", descricao(endpoint))
                .doesNotContainIgnoringCase("permitAll");
    }

    private static String descricao(Method endpoint) {
        RequestMapping mapeamento =
                AnnotatedElementUtils.findMergedAnnotation(endpoint, RequestMapping.class);
        String metodo = Arrays.stream(mapeamento.method()).map(Enum::name).findFirst().orElse("*");
        String caminho = mapeamento.path().length > 0 ? mapeamento.path()[0] : "";
        return metodo + " " + caminho;
    }

    private static List<Method> endpointsEm(Path raiz) {
        try (Stream<Path> arquivos = Files.walk(raiz.resolve(PACOTE))) {
            return arquivos.filter(p -> p.toString().endsWith(".class"))
                    .map(p -> carregar(nomeDaClasse(raiz, p)))
                    .flatMap(c -> Arrays.stream(c.getDeclaredMethods()))
                    .filter(m -> Modifier.isPublic(m.getModifiers()) && !m.isSynthetic())
                    .filter(m -> AnnotatedElementUtils.hasAnnotation(m, RequestMapping.class))
                    .sorted(Comparator.comparing(Method::toString))
                    .toList();
        } catch (Exception e) {
            throw new IllegalStateException("a varredura nao conseguiu ler " + raiz, e);
        }
    }

    private static Path raizDe(Class<?> tipo) {
        try {
            return Path.of(tipo.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String nomeDaClasse(Path raiz, Path arquivo) {
        String relativo = raiz.relativize(arquivo).toString()
                .replace(java.io.File.separatorChar, '.').replace('/', '.');
        return relativo.substring(0, relativo.length() - ".class".length());
    }

    private static Class<?> carregar(String nome) {
        try {
            return Class.forName(nome, false, CoberturaDeAutorizacaoNotificacaoTest.class.getClassLoader());
        } catch (ClassNotFoundException | NoClassDefFoundError e) {
            throw new IllegalStateException(nome, e);
        }
    }

    // --- infratores de mentira: nao sao beans, entao nenhum contexto os registra ------------

    static class InfratorSemDecisao {
        @PostMapping("/internal/infrator")
        public void disparar() { }
    }

    static class InfratorAberto {
        @PostMapping("/internal/aberto")
        @PreAuthorize("permitAll()")
        public void disparar() { }
    }
}
