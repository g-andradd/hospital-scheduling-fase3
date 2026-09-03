package br.com.fiap.hospital.agendamento.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Modifier;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.web.bind.annotation.ExceptionHandler;

/**
 * Impede que o mapa de erros e os tratadores divirjam.
 *
 * <p>Tres vezes na historia deste projeto uma excecao chegou ao mapa depois de ja poder
 * acontecer: {@code IllegalArgumentException} teria virado 500,
 * {@code MotivoDeCancelamentoObrigatorio} estava classificada como erro de formato, e
 * {@code AlteracaoConcorrente} nem existia. Revisao de PR nao pegou nenhuma das tres.
 *
 * <p>A lista de excecoes NAO e escrita a mao aqui: ela e descoberta varrendo o pacote
 * {@code domain.exception}. Escreve-la a mao teria o mesmo defeito que se quer evitar —
 * quem cria a excecao esquece de atualizar a lista pelo mesmo motivo que esqueceu o
 * tratador. Assim, uma excecao nova sem tratador quebra este teste no momento em que e
 * criada.
 */
@DisplayName("Cobertura do mapa de erros")
class CoberturaDoMapaDeErrosTest {

    private static final String PACOTE_EXCECOES =
            "br.com.fiap.hospital.agendamento.domain.exception";

    /**
     * O catch-all NAO conta como cobertura.
     *
     * <p>{@code @ExceptionHandler(Exception.class)} e assinavel por qualquer excecao, e
     * incluir esses tipos genericos na conta tornaria esta verificacao vazia: toda
     * excecao pareceria coberta e o teste passaria mesmo com o furo que ele existe para
     * pegar. Cobertura aqui significa tratador MAIS ESPECIFICO que a rede de seguranca.
     */
    private static final Set<Class<?>> GENERICOS_QUE_NAO_CONTAM =
            Set.of(Exception.class, RuntimeException.class, Throwable.class);

    @Test
    @DisplayName("toda excecao de dominio tem tratador declarado no advice")
    void todaExcecaoDeDominioTemTratador() {
        Set<Class<?>> tratadas = excecoesComTratador().stream()
                .filter(t -> !GENERICOS_QUE_NAO_CONTAM.contains(t))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());

        List<Class<?>> semTratador = excecoesDeDominio().stream()
                .filter(excecao -> tratadas.stream().noneMatch(t -> t.isAssignableFrom(excecao)))
                .toList();

        assertThat(semTratador)
                .as("excecao de dominio sem @ExceptionHandler vira 500 silencioso; "
                        + "declare um tratador em TratadorGlobalDeErros")
                .isEmpty();
    }

    @Test
    @DisplayName("a varredura encontra as excecoes de dominio existentes")
    void varreduraEncontraAsExcecoes() {
        assertThat(excecoesDeDominio())
                .as("se esta lista vier vazia, a varredura quebrou e o teste acima "
                        + "passaria a nao verificar nada")
                .hasSizeGreaterThanOrEqualTo(7)
                .anyMatch(c -> c.getSimpleName().equals("AlteracaoConcorrenteException"))
                .anyMatch(c -> c.getSimpleName().equals("MotivoDeCancelamentoObrigatorioException"));
    }

    @Test
    @DisplayName("as entradas do Spring e do JDK tambem tem tratador")
    void entradasDeForaDoDominioTemTratador() {
        Set<Class<?>> tratadas = excecoesComTratador();

        assertThat(tratadas)
                .contains(
                        IllegalArgumentException.class,
                        org.springframework.web.bind.MethodArgumentNotValidException.class,
                        Exception.class);
    }

    @ParameterizedTest
    @EnumSource(TipoDeErro.class)
    @DisplayName("toda categoria de erro tem type, titulo e status coerentes")
    void categoriaDeErroECoerente(TipoDeErro tipo) {
        assertThat(tipo.type().toString())
                .startsWith("https://hospital.fiap.br/erros/")
                .doesNotEndWith("/");
        assertThat(tipo.titulo()).isNotBlank();
        assertThat(tipo.status().value()).isGreaterThanOrEqualTo(400);
    }

    @Test
    @DisplayName("os dois 409 tem type distinto")
    void osDois409TemTypeDistinto() {
        assertThat(TipoDeErro.CONFLITO_DE_AGENDA.status())
                .isEqualTo(TipoDeErro.ALTERACAO_CONCORRENTE.status());
        assertThat(TipoDeErro.CONFLITO_DE_AGENDA.type())
                .as("o status nao distingue o que o cliente deve fazer; o type sim")
                .isNotEqualTo(TipoDeErro.ALTERACAO_CONCORRENTE.type());
    }

    @Test
    @DisplayName("nenhum type se repete entre categorias")
    void nenhumTypeSeRepete() {
        assertThat(Arrays.stream(TipoDeErro.values()).map(TipoDeErro::type).toList())
                .doesNotHaveDuplicates();
    }

    /** Tipos de excecao declarados em algum {@code @ExceptionHandler} do advice. */
    private static Set<Class<?>> excecoesComTratador() {
        return Arrays.stream(TratadorGlobalDeErros.class.getDeclaredMethods())
                .map(metodo -> metodo.getAnnotation(ExceptionHandler.class))
                .filter(java.util.Objects::nonNull)
                .flatMap(anotacao -> Arrays.stream(anotacao.value()))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /** Varre o pacote de excecoes do dominio, sem lista escrita a mao. */
    private static List<Class<?>> excecoesDeDominio() {
        try {
            Path diretorio = Path.of(CoberturaDoMapaDeErrosTest.class
                    .getClassLoader()
                    .getResource(PACOTE_EXCECOES.replace('.', '/'))
                    .toURI());

            try (Stream<Path> arquivos = Files.list(diretorio)) {
                return arquivos
                        .map(Path::getFileName)
                        .map(Path::toString)
                        .filter(nome -> nome.endsWith(".class"))
                        .filter(nome -> !nome.contains("$"))
                        .map(nome -> nome.substring(0, nome.length() - ".class".length()))
                        .map(nome -> classe(PACOTE_EXCECOES + "." + nome))
                        .filter(RuntimeException.class::isAssignableFrom)
                        .filter(c -> !Modifier.isAbstract(c.getModifiers()))
                        .<Class<?>>map(c -> c)
                        .toList();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (URISyntaxException e) {
            throw new IllegalStateException("nao foi possivel varrer " + PACOTE_EXCECOES, e);
        }
    }

    private static Class<?> classe(String nome) {
        try {
            return Class.forName(nome);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }
}
