package br.com.fiap.hospital.historico.estrutura;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.fiap.hospital.historico.infrastructure.graphql.ConsultaHistoricoController;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.security.access.prepost.PreAuthorize;

/**
 * Toda operacao GraphQL exposta tem decisao de autorizacao explicita.
 *
 * <p>A cadeia de filtros exige token em {@code /graphql}, mas nao distingue perfil — isso e
 * trabalho do {@code @PreAuthorize} no metodo. Uma operacao nova sem anotacao fica,
 * portanto, aberta a <b>qualquer usuario autenticado</b>: o paciente alcanca o que era de
 * medico, e nada falha. A cadeia nao pega, porque a requisicao esta autenticada; a matriz
 * da secao 3 nao pega, porque a linha correspondente ainda nao existe no documento.
 *
 * <p>A varredura percorre o diretorio de classes compiladas, e nao uma lista escrita aqui.
 * Uma lista teria o defeito que esta classe existe para evitar: quem cria um resolver novo
 * e esquece o {@code @PreAuthorize} tambem esqueceria de acrescenta-lo a lista, e a
 * cobertura passaria verde ignorando exatamente a operacao desprotegida.
 */
@DisplayName("Cobertura de autorizacao dos resolvers")
class CoberturaDeAutorizacaoGraphqlTest {

    private static final String PACOTE = "br/com/fiap/hospital/historico";

    private static final List<Class<? extends Annotation>> MAPEAMENTOS =
            List.of(QueryMapping.class, MutationMapping.class, SchemaMapping.class);

    /** Operacoes de producao: o diretorio de onde a aplicacao carrega suas classes. */
    static Stream<Method> operacoes() {
        return operacoesEm(raizDe(ConsultaHistoricoController.class));
    }

    /**
     * Varre um diretorio de classes compiladas e devolve as operacoes GraphQL publicas.
     *
     * <p>Receber a raiz como parametro e o que permite apontar a mesma varredura para as
     * classes de teste na prova de sensibilidade: o infrator e <b>encontrado</b> ali, e nao
     * entregue de bandeja ao verificador.
     */
    private static Stream<Method> operacoesEm(Path raiz) {
        try (Stream<Path> arquivos = Files.walk(raiz.resolve(PACOTE))) {
            return arquivos.filter(p -> p.toString().endsWith(".class"))
                    .map(p -> nomeDaClasse(raiz, p))
                    .map(CoberturaDeAutorizacaoGraphqlTest::carregar)
                    .flatMap(c -> Arrays.stream(c.getDeclaredMethods()))
                    .filter(m -> Modifier.isPublic(m.getModifiers()))
                    .filter(m -> !m.isSynthetic())
                    .filter(CoberturaDeAutorizacaoGraphqlTest::eOperacaoGraphql)
                    .sorted(Comparator.comparing(Method::getName))
                    .toList().stream();
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
            return Class.forName(nome, false,
                    CoberturaDeAutorizacaoGraphqlTest.class.getClassLoader());
        } catch (ClassNotFoundException | NoClassDefFoundError e) {
            throw new IllegalStateException(nome, e);
        }
    }

    private static boolean eOperacaoGraphql(Method metodo) {
        return MAPEAMENTOS.stream().anyMatch(metodo::isAnnotationPresent);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("operacoes")
    @DisplayName("Scenario: Operacao sem decisao de autorizacao e recusada pela cobertura")
    void operacaoDeclaraDecisaoDeAutorizacao(Method operacao) {
        verificar(operacao);
    }

    /**
     * A varredura precisa achar as cinco, senao ela nao esta varrendo nada.
     *
     * <p>Se o filtro de anotacoes parasse de casar — por troca de biblioteca ou de pacote
     * —, {@code operacoes()} devolveria vazio, nenhum caso seria gerado e a suite passaria
     * verde tendo verificado zero operacoes.
     */
    @Test
    @DisplayName("a varredura encontra as cinco operacoes expostas")
    void varreduraEncontraAsCincoOperacoes() {
        assertThat(operacoes().map(Method::getName).toList())
                .containsExactlyInAnyOrder(
                        "consultasDoPaciente", "minhasConsultas", "consultasDoMedico",
                        "consulta", "corrigirRegistroHistorico");
    }

    /**
     * Prova que a cobertura falha de verdade — e que a varredura acha o infrator sozinha.
     *
     * <p>A mesma varredura roda sobre as classes de teste, onde vive {@link ResolverInfrator}.
     * Se ela deixasse de enxergar operacoes, este teste falharia por nao encontrar nenhuma
     * desprotegida, em vez de passar em silencio.
     */
    @Test
    @DisplayName("um resolver sem anotacao faz a cobertura falhar")
    void resolverSemAnotacaoFalha() {
        List<Method> desprotegidas = operacoesEm(raizDe(CoberturaDeAutorizacaoGraphqlTest.class))
                .filter(m -> m.getAnnotation(PreAuthorize.class) == null)
                .toList();

        assertThat(desprotegidas)
                .as("a varredura precisa achar o infrator por conta propria")
                .isNotEmpty();
        desprotegidas.forEach(operacao ->
                assertThatThrownBy(() -> verificar(operacao)).isInstanceOf(AssertionError.class));
    }

    private void verificar(Method operacao) {
        PreAuthorize decisao = operacao.getAnnotation(PreAuthorize.class);
        assertThat(decisao)
                .as("%s e alcancavel por qualquer usuario autenticado sem @PreAuthorize",
                        operacao.getName())
                .isNotNull();
        assertThat(decisao.value())
                .as("%s declara uma decisao vazia, que nao restringe nada", operacao.getName())
                .isNotBlank();
    }

    /** Resolver de mentira, para provar que a varredura tem dentes. */
    static class ResolverInfrator {
        @QueryMapping
        public String consultaSemDecisao() {
            return "aberto a qualquer autenticado";
        }
    }
}
