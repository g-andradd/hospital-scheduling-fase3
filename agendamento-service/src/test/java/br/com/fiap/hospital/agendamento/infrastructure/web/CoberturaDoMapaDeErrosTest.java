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
    @DisplayName("as entradas do JDK tem tratador nominal no advice")
    void entradasDoJdkTemTratadorNominal() {
        assertThat(excecoesComTratador())
                .contains(IllegalArgumentException.class, Exception.class);
    }

    @Test
    @DisplayName("a validacao de corpo e coberta pela familia do MVC, com os campos preservados")
    void validacaoDeCorpoECoberta() {
        Class<?> validacao = org.springframework.web.bind.MethodArgumentNotValidException.class;

        assertThat(cobertaPeloAdvice(validacao))
                .as("herdada da superclasse, e nao mais um @ExceptionHandler proprio")
                .isTrue();
        assertThat(Arrays.stream(TratadorGlobalDeErros.class.getDeclaredMethods())
                        .anyMatch(m -> m.getName().equals("handleMethodArgumentNotValid")))
                .as("o override e o que acrescenta a relacao de campos invalidos ao corpo")
                .isTrue();
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

    /**
     * Terceira familia, alem do dominio e do MVC.
     *
     * <p>O AccessDeniedHandler da cadeia so alcanca recusas que acontecem no filtro. A
     * recusa do {@code @PreAuthorize} sobe pela invocacao do controller, e sem tratador
     * nominal cairia no catch-all: 403 virando 500. Aconteceu de verdade neste change.
     */
    @Test
    @DisplayName("as excecoes de seguranca tem tratador nominal")
    void excecoesDeSegurancaTemTratadorNominal() {
        assertThat(excecoesComTratador())
                .as("sem tratador nominal, a recusa por perfil vira 500")
                .contains(
                        org.springframework.security.access.AccessDeniedException.class,
                        org.springframework.security.core.AuthenticationException.class);
    }

    @Test
    @DisplayName("o advice herda o tratamento da familia de excecoes do Spring MVC")
    void herdaOTratamentoDoSpringMvc() {
        assertThat(org.springframework.web.servlet.mvc.method.annotation
                        .ResponseEntityExceptionHandler.class)
                .as("sem herdar, JSON malformado, UUID invalido no path e metodo nao "
                        + "suportado caem no tratador generico e viram 500 — erro de "
                        + "cliente respondido como falha de servidor")
                .isAssignableFrom(TratadorGlobalDeErros.class);
    }

    @Test
    @DisplayName("o formato do ProblemDetail e imposto tambem as excecoes do MVC")
    void formatoImpostoAsExcecoesDoMvc() {
        boolean sobrescreve = Arrays.stream(TratadorGlobalDeErros.class.getDeclaredMethods())
                .anyMatch(m -> m.getName().equals("handleExceptionInternal"));

        assertThat(sobrescreve)
                .as("sem sobrescrever handleExceptionInternal, as respostas herdadas saem "
                        + "sem type, correlationId nem timestamp")
                .isTrue();
    }

    @Test
    @DisplayName("toda excecao de MVC tratada pela superclasse esta coberta")
    void todaExcecaoDeMvcEstaCoberta() {
        List<Class<?>> semCobertura = excecoesDoSpringMvc().stream()
                .filter(excecao -> !cobertaPeloAdvice(excecao))
                .toList();

        assertThat(semCobertura)
                .as("excecao de requisicao sem cobertura vira 500")
                .isEmpty();
    }

    @Test
    @DisplayName("a varredura do MVC encontra as excecoes esperadas")
    void varreduraDoMvcEncontraExcecoes() {
        assertThat(excecoesDoSpringMvc())
                .as("se vier vazia, o teste acima passaria a nao verificar nada")
                .hasSizeGreaterThanOrEqualTo(8)
                .anyMatch(c -> c.getSimpleName().equals("HttpMessageNotReadableException"))
                .anyMatch(c -> c.getSimpleName().equals("HttpRequestMethodNotSupportedException"));
    }

    @Test
    @DisplayName("as categorias de requisicao invalida sao todas 4xx")
    void categoriasDeRequisicaoSao4xx() {
        List<TipoDeErro> deRequisicao = List.of(
                TipoDeErro.REQUISICAO_MALFORMADA,
                TipoDeErro.PARAMETRO_INVALIDO,
                TipoDeErro.PARAMETRO_AUSENTE,
                TipoDeErro.METODO_NAO_SUPORTADO,
                TipoDeErro.MIDIA_NAO_SUPORTADA,
                TipoDeErro.ROTA_NAO_ENCONTRADA,
                TipoDeErro.REQUISICAO_INVALIDA);

        assertThat(deRequisicao)
                .allSatisfy(tipo -> assertThat(tipo.status().is4xxClientError())
                        .as("%s precisa ser 4xx: e erro do cliente", tipo)
                        .isTrue());
    }

    /**
     * Uma excecao de MVC esta coberta se o advice a trata nominalmente ou se herda o
     * tratamento da superclasse.
     */
    private static boolean cobertaPeloAdvice(Class<?> excecao) {
        if (!org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler
                .class.isAssignableFrom(TratadorGlobalDeErros.class)) {
            return false;
        }
        return excecoesComTratador().stream().anyMatch(t -> t.isAssignableFrom(excecao))
                || excecoesDoSpringMvc().contains(excecao);
    }

    /** Excecoes declaradas no {@code @ExceptionHandler} da superclasse do Spring. */
    private static List<Class<?>> excecoesDoSpringMvc() {
        return Arrays.stream(
                        org.springframework.web.servlet.mvc.method.annotation
                                .ResponseEntityExceptionHandler.class.getDeclaredMethods())
                .map(m -> m.getAnnotation(ExceptionHandler.class))
                .filter(java.util.Objects::nonNull)
                .flatMap(anotacao -> Arrays.stream(anotacao.value()))
                .distinct()
                .<Class<?>>map(c -> c)
                .toList();
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
