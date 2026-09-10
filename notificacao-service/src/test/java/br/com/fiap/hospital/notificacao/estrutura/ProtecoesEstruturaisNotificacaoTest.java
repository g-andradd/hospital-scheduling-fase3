package br.com.fiap.hospital.notificacao.estrutura;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.fiap.hospital.notificacao.consumer.ConsumidorDeNotificacoes;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.annotation.RabbitListeners;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * As garantias que nao podem depender de alguem lembrar delas.
 *
 * <p>Um listener novo, um cliente HTTP, um segredo literal ou uma leitura direta de relogio
 * sao coisas que se escreve sem ma intencao e que nenhuma suite de comportamento pega. Estas
 * varreduras existem para que apareçam no dia em que forem escritas.
 */
@DisplayName("Protecoes estruturais do notificacao-service")
class ProtecoesEstruturaisNotificacaoTest {

    private static final Path RAIZ = Files.exists(Path.of("src/main/java"))
            ? Path.of("") : Path.of("notificacao-service");

    private static final List<Class<? extends Annotation>> MAPEAMENTOS = List.of();

    // --- fronteira unica ----------------------------------------------------------------

    @Test
    @DisplayName("existe uma unica fronteira AMQP, transacional")
    void fronteiraUnicaETransacional() {
        validar(classesDeProducao());
    }

    @Test
    @DisplayName("a varredura recusa listener adicional, em classe, meta-anotacao ou heranca")
    void varreduraRecusaInfratores() {
        for (Class<?> infrator : List.of(InfratorMetodo.class, InfratorClasse.class,
                InfratorMeta.class, InfratorRepetido.class, InfratorDeInterface.class,
                InfratorDeSuperclasse.class)) {
            assertThatThrownBy(() -> validar(List.of(ConsumidorDeNotificacoes.class, infrator)))
                    .as("%s precisa ser recusado", infrator.getSimpleName())
                    .isInstanceOf(AssertionError.class);
        }
    }

    /**
     * A ordem efeito -> marca, lida da sequencia real do metodo.
     *
     * <p>Verificada aqui, e nao por teste de rollback: dentro de uma unica transacao o
     * rollback desfaz a marca nas duas ordens, entao o teste comportamental passaria
     * igualmente e nao protegeria nada. O que a ordem protege e contra a marca sair desta
     * transacao numa refatoracao futura.
     */
    @Test
    @DisplayName("a marca de processado e gravada depois do efeito")
    void marcaVemDepoisDoEfeito() throws IOException {
        String fonte = Files.readString(RAIZ.resolve(
                "src/main/java/br/com/fiap/hospital/notificacao/consumer/ConsumidorDeNotificacoes.java"));

        int consulta = fonte.indexOf("processados.existsById");
        int efeito = fonte.indexOf("processador.processar(evento)");
        int marca = fonte.indexOf("processados.saveAndFlush");

        assertThat(consulta).as("a consulta de duplicata precisa existir").isNotNegative();
        assertThat(efeito).as("o efeito precisa existir").isNotNegative();
        assertThat(marca).as("a marca precisa existir").isNotNegative();
        assertThat(consulta).isLessThan(efeito);
        assertThat(efeito)
                .as("marcar antes do efeito abre caminho para a marca sair da transacao")
                .isLessThan(marca);
    }

    @Test
    @DisplayName("o contexto de log e limpo em finally")
    void contextoDeLogELimpoEmFinally() throws IOException {
        String fonte = Files.readString(RAIZ.resolve(
                "src/main/java/br/com/fiap/hospital/notificacao/consumer/ConsumidorDeNotificacoes.java"));

        assertThat(fonte).contains("finally");
        assertThat(fonte.indexOf("MDC.clear()"))
                .as("o contrato normativo pede clear(), e depois do finally")
                .isGreaterThan(fonte.indexOf("finally"));
    }

    // --- desacoplamento -----------------------------------------------------------------

    @Test
    @DisplayName("o servico nao declara cliente HTTP para o agendamento")
    void naoDeclaraClienteHttp() throws IOException {
        String texto = Files.readString(RAIZ.resolve("pom.xml"))
                + lerArvore(RAIZ.resolve("src/main/java"))
                + lerArvore(RAIZ.resolve("src/main/resources"));

        assertThat(texto).doesNotContain("RestClient", "RestTemplate", "WebClient", "Feign",
                "java.net.http", "HttpURLConnection", "OkHttp", "okhttp3", "org.apache.hc",
                "AGENDAMENTO_URL", "AGENDAMENTO_HOST", "AGENDAMENTO_PORT", "localhost:8081",
                "agendamento-service");
    }

    // --- relogio ------------------------------------------------------------------------

    /**
     * Nenhuma leitura direta de relogio.
     *
     * <p>A convencao do projeto e {@code Clock} injetado. Uma chamada solta a
     * {@code Instant.now()} nao quebra nada em producao e torna o teste de instantes
     * nao-deterministico — falha barulhenta em CI, atribuida a "flakiness".
     */
    @Test
    @DisplayName("nenhuma classe le o relogio diretamente")
    void nenhumaClasseLeORelogioDiretamente() throws IOException {
        String codigo = lerArvore(RAIZ.resolve("src/main/java"));

        assertThat(codigo).doesNotContain("Instant.now()", "LocalDateTime.now()",
                "OffsetDateTime.now()", "System.currentTimeMillis()");
        assertThat(lerArvore(RAIZ.resolve("src/main/java")))
                .as("e o SQL tambem nao pode carimbar a hora do banco")
                .doesNotContain("now()," , "now())");
    }

    // --- segredo ------------------------------------------------------------------------

    /**
     * Recusa <b>valores</b> sensiveis, e nao nomes de propriedade.
     *
     * <p>Uma guarda que recusasse "password" ou "host" acusaria
     * {@code spring.rabbitmq.password}, que e nome legitimo e ja existe no projeto. Uma
     * cobertura que acusa o que e correto ensina o time a ignora-la, e ai ela deixa de valer
     * para o caso que importa.
     */
    @Test
    @DisplayName("nenhum valor sensivel literal no codigo ou na configuracao")
    void nenhumValorSensivelLiteral() throws IOException {
        String texto = lerArvore(RAIZ.resolve("src/main/java"))
                + lerArvore(RAIZ.resolve("src/main/resources"));

        assertThat(literaisSensiveis(texto))
                .as("credencial vai por variavel de ambiente; fallback local nao secreto e permitido")
                .isEmpty();
    }

    @Test
    @DisplayName("a guarda de segredo aceita nome de propriedade e placeholder, e recusa literal")
    void guardaDeSegredoDistingueNomeDeValor() {
        assertThat(literaisSensiveis("password: ${SMTP_PASSWORD}"))
                .as("referencia a variavel de ambiente e o caminho correto")
                .isEmpty();
        assertThat(literaisSensiveis("host: ${SMTP_HOST:localhost}"))
                .as("fallback local nao e segredo")
                .isEmpty();
        assertThat(literaisSensiveis("spring.rabbitmq.password"))
                .as("nome de propriedade nao e valor")
                .isEmpty();
        assertThat(literaisSensiveis("password: ${NOTIFICACAO_DB_PASSWORD:hospital}"))
                .as("o nome da variavel contem 'password' e o fallback e local: nao e segredo")
                .isEmpty();

        assertThat(literaisSensiveis("password: s3nh4Secreta"))
                .as("senha literal atribuida precisa ser recusada")
                .isNotEmpty();
        assertThat(literaisSensiveis("host: smtp.sendgrid.net"))
                .as("host fixo de provedor precisa ser recusado")
                .isNotEmpty();
        assertThat(literaisSensiveis("api-key: SG.abcdefghijklmnop"))
                .as("chave de API precisa ser recusada")
                .isNotEmpty();

        // Segredo escondido no fallback: o lugar mais facil de esquecer, e por isso o que a
        // guarda precisa alcancar.
        assertThat(literaisSensiveis("password: ${SMTP_PASSWORD:s3nh4Secreta}"))
                .as("senha real no fallback nao pode passar por ser placeholder")
                .isNotEmpty();
        assertThat(literaisSensiveis("api-key: ${API_KEY:SG.abcdefghijklmnop}"))
                .as("chave de API no fallback tambem e segredo")
                .isNotEmpty();
        assertThat(literaisSensiveis("host: ${SMTP_HOST:smtp.sendgrid.net}"))
                .as("host de provedor no fallback tambem e endereco fixo")
                .isNotEmpty();
    }

    private static final java.util.regex.Pattern PLACEHOLDER =
            java.util.regex.Pattern.compile("\\$\\{([A-Za-z0-9_.-]+)(?::([^}]*))?\\}");

    private static final java.util.regex.Pattern CHAVE_SENSIVEL =
            java.util.regex.Pattern.compile("(?i)(password|senha|secret|token|api[-_]?key)");

    private static final java.util.regex.Pattern PROVEDOR = java.util.regex.Pattern.compile(
            "(?i)smtp\\.(sendgrid|gmail|office365|mailgun|sparkpostmail|zoho)\\.[a-z]+");

    /**
     * Valor local aceitavel num fallback de chave sensivel.
     *
     * <p>Minusculas, digitos e hifen, comecando por letra: {@code hospital}, {@code localhost}.
     * Segredo de verdade quase sempre quebra essa forma — mistura de caixa, ponto, cifra ou
     * comprimento de token. A regra e conservadora de proposito: na duvida, acusa.
     */
    private static final java.util.regex.Pattern FALLBACK_LOCAL =
            java.util.regex.Pattern.compile("[a-z][a-z0-9-]{0,30}");

    /**
     * Recusa <b>valores</b> sensiveis, e nao nomes de propriedade.
     *
     * <p>O fallback de um placeholder e inspecionado, e nao descartado junto com ele.
     * Apagar {@code ${...}} inteiro tornaria a guarda cega para
     * {@code ${SMTP_PASSWORD:s3nh4Secreta}} — um segredo de verdade, escrito no lugar mais
     * facil de esquecer. O nome da variavel continua fora da varredura: e la que "password"
     * aparece legitimamente.
     */
    private static List<String> literaisSensiveis(String texto) {
        List<String> achados = new ArrayList<>();

        var placeholders = PLACEHOLDER.matcher(texto);
        while (placeholders.find()) {
            String variavel = placeholders.group(1);
            String fallback = placeholders.group(2);
            if (fallback == null || fallback.isBlank()) continue;

            if (PROVEDOR.matcher(fallback).find()) {
                achados.add(placeholders.group());
                continue;
            }
            if (CHAVE_SENSIVEL.matcher(variavel).find()
                    && !FALLBACK_LOCAL.matcher(fallback).matches()) {
                achados.add(placeholders.group());
            }
        }

        // Fora dos placeholders, qualquer atribuicao a chave sensivel e literal.
        // [ \t]* e nao \s*: sem os placeholders sobra "password:" seguido de quebra de
        // linha, e \s* atravessaria a quebra para capturar a linha seguinte.
        String semPlaceholders = PLACEHOLDER.matcher(texto).replaceAll("");
        var atribuicao = java.util.regex.Pattern.compile(
                "(?i)(password|senha|secret|token|api[-_]?key)[ \\t]*[:=][ \\t]*\"?([^\\s\"$#]+)");
        var m = atribuicao.matcher(semPlaceholders);
        while (m.find()) achados.add(m.group());

        var pr = PROVEDOR.matcher(semPlaceholders);
        while (pr.find()) achados.add(pr.group());

        return achados;
    }

    // --- apoio --------------------------------------------------------------------------

    private void validar(List<Class<?>> classes) {
        List<Method> fronteiras = new ArrayList<>();
        for (Class<?> tipo : classes) {
            if (listener(tipo)) fronteiras.add(null);
            for (Method metodo : metodosDaHierarquia(tipo)) if (listener(metodo)) fronteiras.add(metodo);
        }
        assertThat(fronteiras)
                .as("so pode existir uma entrada AMQP no modulo")
                .hasSize(1);
        Method fronteira = fronteiras.getFirst();
        assertThat(fronteira).as("a fronteira nao pode estar anotada na classe").isNotNull();
        assertThat(fronteira.getDeclaringClass()).isEqualTo(ConsumidorDeNotificacoes.class);
        assertThat(fronteira.getName()).isEqualTo("consumir");

        Transactional tx = MergedAnnotations.from(fronteira,
                        MergedAnnotations.SearchStrategy.TYPE_HIERARCHY)
                .get(Transactional.class).synthesize();
        assertThat(tx.propagation()).isEqualTo(Propagation.REQUIRED);
        assertThat(tx.readOnly()).isFalse();
    }

    private boolean listener(AnnotatedElement elemento) {
        return MergedAnnotations.from(elemento, MergedAnnotations.SearchStrategy.TYPE_HIERARCHY)
                .isPresent(RabbitListener.class);
    }

    private List<Method> metodosDaHierarquia(Class<?> tipo) {
        List<Method> metodos = new ArrayList<>();
        for (Class<?> atual = tipo; atual != null; atual = atual.getSuperclass()) {
            metodos.addAll(Arrays.asList(atual.getDeclaredMethods()));
            for (Class<?> face : atual.getInterfaces()) adicionarDaInterface(face, metodos);
        }
        return metodos;
    }

    private void adicionarDaInterface(Class<?> tipo, List<Method> metodos) {
        metodos.addAll(Arrays.asList(tipo.getDeclaredMethods()));
        for (Class<?> superior : tipo.getInterfaces()) adicionarDaInterface(superior, metodos);
    }

    private List<Class<?>> classesDeProducao() {
        try {
            Path base = Path.of(ConsumidorDeNotificacoes.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            try (Stream<Path> arquivos = Files.walk(base.resolve("br/com/fiap/hospital/notificacao"))) {
                return arquivos.filter(p -> p.toString().endsWith(".class"))
                        .map(p -> nome(base, p)).map(this::carregar).toList();
            }
        } catch (Exception e) {
            throw new IllegalStateException("varredura das classes de producao", e);
        }
    }

    private String nome(Path base, Path arquivo) {
        String n = base.relativize(arquivo).toString()
                .replace(java.io.File.separatorChar, '.').replace('/', '.');
        return n.substring(0, n.length() - ".class".length());
    }

    private Class<?> carregar(String nome) {
        try {
            return Class.forName(nome, false, getClass().getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(nome, e);
        }
    }

    private String lerArvore(Path raiz) throws IOException {
        if (!Files.exists(raiz)) return "";
        try (Stream<Path> p = Files.walk(raiz)) {
            return p.filter(Files::isRegularFile).map(this::ler).reduce("", String::concat);
        }
    }

    private String ler(Path p) {
        try {
            return Files.readString(p);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    // --- infratores de mentira ----------------------------------------------------------

    static class InfratorMetodo { @RabbitListener(queues = "x") void consumir() { } }
    @ListenerMeta static class InfratorClasse { }
    static class InfratorMeta { @ListenerMeta void consumir() { } }
    static class InfratorRepetido {
        @RabbitListeners({@RabbitListener(queues = "x"), @RabbitListener(queues = "y")})
        void consumir() { }
    }
    interface ListenerDeInterface { @RabbitListener(queues = "x") void consumir(); }
    static class InfratorDeInterface implements ListenerDeInterface { public void consumir() { } }
    static class SuperclasseComListener { @RabbitListener(queues = "x") void consumir() { } }
    static class InfratorDeSuperclasse extends SuperclasseComListener { }

    @Target({ElementType.TYPE, ElementType.METHOD})
    @Retention(RetentionPolicy.RUNTIME)
    @RabbitListener(queues = "x")
    @interface ListenerMeta { }
}
