package br.com.fiap.hospital.qualidade;

import br.com.fiap.hospital.qualidade.InventarioDeTestes.Contexto;
import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreeScanner;
import com.sun.source.util.Trees;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.w3c.dom.Element;

/**
 * Infraestrutura real nos testes de integracao (RNF-06, D6), so com o JDK e a Compiler Tree API.
 *
 * <p>Em cada modulo, analisa os {@code *IT} e as bases e configuracoes de teste que eles alcancam —
 * supertipos, tipos aninhados e classes citadas por {@code X.class} nas anotacoes de tipo. Recusa
 * dublê de infraestrutura por anotacao, por {@code Mockito.mock} ou por {@code @Bean}; fatia de teste que
 * troca o banco configurado; banco em memoria ou broker embarcado; e imagem de container fora da versao
 * adotada. Nomes sao resolvidos pelos imports simples, sob demanda e pelo mesmo pacote; um nome que nao
 * se resolve, mas coincide com um tipo de infraestrutura, reprova.
 *
 * <p>Espiao, decorador que delega ao real e dublê de porta externa nao sao dublês de infraestrutura e
 * passam. A infraestrutura exigida em runtime e derivada dos containers declarados no modulo: cada uma
 * exige a prova do IT de evidencia, e prova de infraestrutura nao usada reprova.
 */
public final class InfraestruturaReal {

    /** Infraestrutura com container na suite, a imagem adotada e o metodo de prova do IT de evidencia. */
    public enum Infraestrutura {
        POSTGRESQL("PostgreSQL 16", "org.testcontainers.containers.PostgreSQLContainer", "postgres:16", "provaPostgreSql"),
        RABBITMQ("RabbitMQ 3.13", "org.testcontainers.containers.RabbitMQContainer", "rabbitmq:3.13-management", "provaRabbitMq");

        public final String descricao;
        public final String container;
        public final String imagem;
        public final String prova;

        Infraestrutura(String descricao, String container, String imagem, String prova) {
            this.descricao = descricao;
            this.container = container;
            this.imagem = imagem;
            this.prova = prova;
        }

        String containerSimples() {
            return container.substring(container.lastIndexOf('.') + 1);
        }
    }

    /** IT de evidencia runtime: {@code InfraestruturaReal<Modulo>IT}. */
    public static final String PREFIXO_DA_EVIDENCIA = "InfraestruturaReal";
    static final String SUFIXO_DE_IT = "IT";

    static final Set<String> TIPOS_DE_INFRAESTRUTURA = Set.of(
            "javax.sql.DataSource",
            "org.springframework.jdbc.core.JdbcTemplate",
            "org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate",
            "jakarta.persistence.EntityManager",
            "jakarta.persistence.EntityManagerFactory",
            "org.springframework.amqp.rabbit.connection.ConnectionFactory",
            "org.springframework.amqp.rabbit.connection.CachingConnectionFactory",
            "com.rabbitmq.client.ConnectionFactory",
            "org.springframework.amqp.rabbit.core.RabbitTemplate",
            "org.springframework.amqp.core.AmqpTemplate",
            "org.springframework.amqp.core.AmqpAdmin",
            "org.springframework.amqp.rabbit.core.RabbitAdmin");

    /** Supertipos Spring Data que fazem de uma interface das fontes principais um repositorio. */
    static final Set<String> REPOSITORIOS_SPRING_DATA = Set.of(
            "org.springframework.data.repository.Repository",
            "org.springframework.data.repository.CrudRepository",
            "org.springframework.data.jpa.repository.JpaRepository");

    static final Map<String, String> DUBLES = Map.of(
            "org.mockito.Mock", "@Mock",
            "org.springframework.boot.test.mock.mockito.MockBean", "@MockBean",
            "org.springframework.test.context.bean.override.mockito.MockitoBean", "@MockitoBean",
            "org.springframework.test.context.bean.override.convention.TestBean", "@TestBean");

    static final String BEAN = "org.springframework.context.annotation.Bean";
    static final String MOCKITO = "org.mockito.Mockito";
    static final String TESTE = "org.junit.jupiter.api.Test";
    static final Set<String> FATIAS_DE_BANCO = Set.of(
            "org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest",
            "org.springframework.boot.test.autoconfigure.jdbc.JdbcTest");
    static final String AUTO_CONFIGURE_TEST_DATABASE = "org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase";

    /** Prefixos de coordenada de banco em memoria e de broker embarcado. */
    static final List<String> DEPENDENCIAS_EMBARCADAS = List.of("com.h2database:h2", "org.hsqldb:hsqldb", "org.apache.derby:",
            "org.apache.qpid:qpid-broker", "com.github.fridujo:rabbitmq-mock");

    /** Propriedades de banco embarcado: URL JDBC de banco em memoria. */
    static final List<String> URLS_EMBARCADAS = List.of("jdbc:h2:", "jdbc:hsqldb:", "jdbc:derby:");

    /** Nomes qualificados que a resolucao sob demanda reconhece fora das fontes do modulo. */
    private static final Set<String> CONHECIDOS = conhecidos();

    public record Resultado(List<String> violacoes, Map<String, Set<Infraestrutura>> usadas,
            Map<String, Set<Infraestrutura>> provadas) {}

    private InfraestruturaReal() {}

    private static Set<String> conhecidos() {
        Set<String> nomes = new HashSet<>(TIPOS_DE_INFRAESTRUTURA);
        nomes.addAll(REPOSITORIOS_SPRING_DATA);
        nomes.addAll(DUBLES.keySet());
        nomes.addAll(FATIAS_DE_BANCO);
        nomes.addAll(List.of(BEAN, MOCKITO, TESTE, AUTO_CONFIGURE_TEST_DATABASE));
        for (Infraestrutura infraestrutura : Infraestrutura.values()) {
            nomes.add(infraestrutura.container);
        }
        return Set.copyOf(nomes);
    }

    // ---------------------------------------------------------------- modelo

    /** Tipo declarado nas fontes de teste, pelo nome canonico. */
    private record Tipo(String nome, String simples, ClassTree arvore, Contexto contexto, String envolvente) {}

    /** Nome de tipo depois da resolucao: qualificado, ou so o simples quando nao se resolve. */
    private record Nome(String valor, boolean resolvido) {

        String simples() {
            return valor.substring(valor.lastIndexOf('.') + 1);
        }
    }

    private static final class Modulo {
        final String nome;
        final Map<String, Tipo> testes = new LinkedHashMap<>();
        final List<Contexto> contextosDeTeste = new ArrayList<>();
        final Set<String> principais = new HashSet<>();
        final Set<String> repositorios = new LinkedHashSet<>();
        final List<String> violacoes = new ArrayList<>();

        Modulo(String nome) {
            this.nome = nome;
        }

        void violar(String violacao) {
            if (!violacoes.contains(violacao)) {
                violacoes.add(violacao);
            }
        }
    }

    // ---------------------------------------------------------------- verificacao

    /**
     * @param raiz raiz do reactor
     * @param modulos os modulos de codigo
     */
    public static Resultado verificar(Path raiz, List<String> modulos) {
        List<String> violacoes = new ArrayList<>();
        Map<String, Set<Infraestrutura>> usadas = new LinkedHashMap<>();
        Map<String, Set<Infraestrutura>> provadas = new LinkedHashMap<>();
        for (String nome : modulos) {
            Modulo modulo = new Modulo(nome);
            Path diretorio = raiz.resolve(nome);
            List<Contexto> principais = ler(raiz, modulo, diretorio.resolve("src").resolve("main").resolve("java"));
            principais.forEach(contexto -> registrarPrincipais(contexto, modulo));
            descobrirRepositorios(principais, modulo);
            for (Contexto contexto : ler(raiz, modulo, diretorio.resolve("src").resolve("test").resolve("java"))) {
                modulo.contextosDeTeste.add(contexto);
                for (Tree declaracao : contexto.unidade.getTypeDecls()) {
                    if (declaracao instanceof ClassTree classe) {
                        registrarTeste(classe, contexto, null, modulo);
                    }
                }
            }
            for (Tipo tipo : alcancados(modulo)) {
                verificarTipo(tipo, modulo);
            }
            usadas.put(nome, containers(modulo));
            provadas.put(nome, provas(modulo));
            verificarEvidencia(modulo, usadas.get(nome), provadas.get(nome));
            verificarDependencias(diretorio.resolve("pom.xml"), modulo);
            verificarRecursos(raiz, diretorio.resolve("src").resolve("test").resolve("resources"), modulo);
            violacoes.addAll(modulo.violacoes);
        }
        return new Resultado(List.copyOf(violacoes), usadas, provadas);
    }

    // ---------------------------------------------------------------- leitura

    private static List<Contexto> ler(Path raiz, Modulo modulo, Path fontes) {
        if (!Files.isDirectory(fontes)) {
            return List.of();
        }
        List<Path> arquivos;
        try (Stream<Path> caminhos = Files.walk(fontes)) {
            arquivos = caminhos.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (arquivos.isEmpty()) {
            return List.of();
        }
        JavaCompiler compilador = ToolProvider.getSystemJavaCompiler();
        if (compilador == null) {
            throw new IllegalStateException("a verificacao de infraestrutura exige um JDK: a Compiler Tree API nao esta disponivel");
        }
        DiagnosticCollector<JavaFileObject> diagnosticos = new DiagnosticCollector<>();
        try (StandardJavaFileManager gerenciador =
                compilador.getStandardFileManager(diagnosticos, Locale.ROOT, StandardCharsets.UTF_8)) {
            JavacTask tarefa = (JavacTask) compilador.getTask(null, gerenciador, diagnosticos, List.of("-proc:none"),
                    null, gerenciador.getJavaFileObjectsFromPaths(arquivos));
            Iterable<? extends CompilationUnitTree> unidades = tarefa.parse();
            SourcePositions posicoes = Trees.instance(tarefa).getSourcePositions();
            Set<String> comErro = new HashSet<>();
            for (Diagnostic<? extends JavaFileObject> diagnostico : diagnosticos.getDiagnostics()) {
                if (diagnostico.getKind() == Diagnostic.Kind.ERROR && diagnostico.getSource() != null) {
                    String arquivo = relativo(raiz, Path.of(diagnostico.getSource().toUri()));
                    comErro.add(arquivo);
                    modulo.violar("fonte nao interpretavel: " + arquivo + ":" + diagnostico.getLineNumber() + ": "
                            + diagnostico.getMessage(Locale.ROOT));
                }
            }
            List<Contexto> contextos = new ArrayList<>();
            for (CompilationUnitTree unidade : unidades) {
                String arquivo = relativo(raiz, Path.of(unidade.getSourceFile().toUri()));
                if (!comErro.contains(arquivo)) {
                    contextos.add(new Contexto(modulo.nome, arquivo, unidade, posicoes));
                }
            }
            return contextos;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String relativo(Path raiz, Path arquivo) {
        return raiz.toAbsolutePath().normalize().relativize(arquivo.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    private static String prefixo(Contexto contexto) {
        return contexto.pacote.isEmpty() ? "" : contexto.pacote + ".";
    }

    private static void registrarPrincipais(Contexto contexto, Modulo modulo) {
        new TreeScanner<Void, String>() {
            @Override
            public Void visitClass(ClassTree classe, String envolvente) {
                if (classe.getSimpleName().isEmpty()) {
                    return null;
                }
                String nome = (envolvente == null ? prefixo(contexto) : envolvente + ".") + classe.getSimpleName();
                modulo.principais.add(nome);
                return super.visitClass(classe, nome);
            }
        }.scan(contexto.unidade, null);
    }

    private static void registrarTeste(ClassTree classe, Contexto contexto, Tipo envolvente, Modulo modulo) {
        String simples = classe.getSimpleName().toString();
        String nome = (envolvente == null ? prefixo(contexto) : envolvente.nome() + ".") + simples;
        Tipo tipo = new Tipo(nome, simples, classe, contexto, envolvente == null ? null : envolvente.nome());
        modulo.testes.put(nome, tipo);
        for (Tree membro : classe.getMembers()) {
            if (membro instanceof ClassTree aninhada) {
                registrarTeste(aninhada, contexto, tipo, modulo);
            }
        }
    }

    /** Interfaces das fontes principais que estendem, direta ou transitivamente, um repositorio Spring Data. */
    private static void descobrirRepositorios(List<Contexto> principais, Modulo modulo) {
        boolean mudou = true;
        while (mudou) {
            mudou = false;
            for (Contexto contexto : principais) {
                for (Tree declaracao : contexto.unidade.getTypeDecls()) {
                    if (!(declaracao instanceof ClassTree classe) || classe.getKind() != Tree.Kind.INTERFACE) {
                        continue;
                    }
                    String nome = prefixo(contexto) + classe.getSimpleName();
                    if (modulo.repositorios.contains(nome)) {
                        continue;
                    }
                    for (Tree supertipo : classe.getImplementsClause()) {
                        Nome resolvido = resolver(InventarioDeTestes.nomeDoTipo(supertipo), contexto, null, modulo);
                        if (resolvido.resolvido() && (REPOSITORIOS_SPRING_DATA.contains(resolvido.valor())
                                || modulo.repositorios.contains(resolvido.valor()))) {
                            modulo.repositorios.add(nome);
                            mudou = true;
                            break;
                        }
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- resolucao

    /**
     * Resolve um nome de tipo pelo escopo envolvente, pelos imports simples, pelo mesmo pacote e pelos
     * imports sob demanda. Sem resolucao unica, devolve o nome como escrito, nao resolvido.
     */
    private static Nome resolver(String nome, Contexto contexto, String escopo, Modulo modulo) {
        int ponto = nome.indexOf('.');
        if (ponto > 0) {
            String inicio = nome.substring(0, ponto);
            Nome primeiro = resolver(inicio, contexto, escopo, modulo);
            return primeiro.resolvido() ? new Nome(primeiro.valor() + nome.substring(ponto), true) : new Nome(nome, true);
        }
        for (String atual = escopo; atual != null; atual = modulo.testes.containsKey(atual) ? modulo.testes.get(atual).envolvente() : null) {
            if (modulo.testes.containsKey(atual + "." + nome)) {
                return new Nome(atual + "." + nome, true);
            }
            if (atual.endsWith("." + nome) || atual.equals(nome)) {
                return new Nome(atual, true);
            }
        }
        String importado = contexto.importsSimples.get(nome);
        if (importado != null) {
            return new Nome(importado, true);
        }
        String mesmoPacote = prefixo(contexto) + nome;
        if (modulo.testes.containsKey(mesmoPacote) || modulo.principais.contains(mesmoPacote)) {
            return new Nome(mesmoPacote, true);
        }
        Set<String> candidatos = new LinkedHashSet<>();
        for (String pacote : contexto.importsSobDemanda) {
            String candidato = pacote + "." + nome;
            if (modulo.testes.containsKey(candidato) || modulo.principais.contains(candidato) || CONHECIDOS.contains(candidato)) {
                candidatos.add(candidato);
            }
        }
        if (candidatos.size() == 1) {
            return new Nome(candidatos.iterator().next(), true);
        }
        return new Nome(nome, false);
    }

    /** Descricao do tipo de infraestrutura que o nome designa, ou nulo quando nao designa nenhum. */
    private static String infraestrutura(Nome nome, Modulo modulo) {
        if (nome.resolvido()) {
            return TIPOS_DE_INFRAESTRUTURA.contains(nome.valor()) || modulo.repositorios.contains(nome.valor()) ? nome.valor() : null;
        }
        return Stream.concat(TIPOS_DE_INFRAESTRUTURA.stream(), modulo.repositorios.stream())
                .filter(tipo -> tipo.endsWith("." + nome.valor()))
                .findFirst()
                .map(tipo -> nome.valor() + " (nao resolvido, pode ser " + tipo + ")")
                .orElse(null);
    }

    /** A anotacao resolve para o nome qualificado; nao resolvida, vale pelo nome simples igual. */
    private static boolean designa(AnnotationTree anotacao, String qualificado, Contexto contexto, String escopo, Modulo modulo) {
        Nome nome = resolver(InventarioDeTestes.nomeDoTipo(anotacao.getAnnotationType()), contexto, escopo, modulo);
        return nome.resolvido() ? nome.valor().equals(qualificado) : qualificado.endsWith("." + nome.valor());
    }

    private static String dubleDe(List<? extends AnnotationTree> anotacoes, Contexto contexto, String escopo, Modulo modulo) {
        for (AnnotationTree anotacao : anotacoes) {
            for (Map.Entry<String, String> duble : DUBLES.entrySet()) {
                if (designa(anotacao, duble.getKey(), contexto, escopo, modulo)) {
                    return duble.getValue();
                }
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- alcance

    /** Os {@code *IT} e as bases e configuracoes de teste que eles alcancam. */
    private static Set<Tipo> alcancados(Modulo modulo) {
        Set<Tipo> alcancados = new LinkedHashSet<>();
        List<Tipo> pendentes = new ArrayList<>(modulo.testes.values().stream()
                .filter(t -> t.envolvente() == null && t.simples().endsWith(SUFIXO_DE_IT)).toList());
        while (!pendentes.isEmpty()) {
            Tipo tipo = pendentes.removeFirst();
            if (!alcancados.add(tipo)) {
                continue;
            }
            for (Tree membro : tipo.arvore().getMembers()) {
                if (membro instanceof ClassTree aninhada) {
                    pendentes.add(modulo.testes.get(tipo.nome() + "." + aninhada.getSimpleName()));
                }
            }
            List<Tree> citados = new ArrayList<>();
            if (tipo.arvore().getExtendsClause() != null) {
                citados.add(tipo.arvore().getExtendsClause());
            }
            citados.addAll(tipo.arvore().getImplementsClause());
            for (AnnotationTree anotacao : tipo.arvore().getModifiers().getAnnotations()) {
                citados.addAll(literaisDeClasse(anotacao));
            }
            for (Tree citado : citados) {
                Nome nome = resolver(InventarioDeTestes.nomeDoTipo(citado), tipo.contexto(), tipo.nome(), modulo);
                Tipo alcancado = modulo.testes.get(nome.valor());
                if (alcancado != null) {
                    pendentes.add(alcancado);
                }
            }
        }
        return alcancados;
    }

    /** Os tipos citados por {@code X.class} nos argumentos da anotacao. */
    private static List<Tree> literaisDeClasse(AnnotationTree anotacao) {
        List<Tree> tipos = new ArrayList<>();
        new TreeScanner<Void, Void>() {
            @Override
            public Void visitMemberSelect(MemberSelectTree selecao, Void nada) {
                if (selecao.getIdentifier().contentEquals("class")) {
                    tipos.add(selecao.getExpression());
                    return null;
                }
                return super.visitMemberSelect(selecao, nada);
            }
        }.scan(anotacao.getArguments(), null);
        return tipos;
    }

    // ---------------------------------------------------------------- regras por tipo

    private static void verificarTipo(Tipo tipo, Modulo modulo) {
        Contexto contexto = tipo.contexto();
        verificarFatias(tipo, modulo);
        String dubleDoTipo = dubleDe(tipo.arvore().getModifiers().getAnnotations(), contexto, tipo.nome(), modulo);
        if (dubleDoTipo != null) {
            for (AnnotationTree anotacao : tipo.arvore().getModifiers().getAnnotations()) {
                for (Tree citado : literaisDeClasse(anotacao)) {
                    violarDuble(dubleDoTipo, citado, tipo, modulo, contexto.linha(anotacao));
                }
            }
        }
        new TreeScanner<Void, Void>() {
            @Override
            public Void visitClass(ClassTree classe, Void nada) {
                // Aninhada nomeada e verificada por si; classe anonima e corpo deste tipo.
                return classe == tipo.arvore() || classe.getSimpleName().isEmpty() ? super.visitClass(classe, nada) : null;
            }

            @Override
            public Void visitVariable(VariableTree variavel, Void nada) {
                String duble = dubleDe(variavel.getModifiers().getAnnotations(), contexto, tipo.nome(), modulo);
                if (duble != null && variavel.getType() != null) {
                    violarDuble(duble, variavel.getType(), tipo, modulo, contexto.linha(variavel));
                }
                if (variavel.getInitializer() instanceof MethodInvocationTree chamada && ehMock(chamada, contexto, tipo, modulo)
                        && chamada.getArguments().isEmpty() && variavel.getType() != null) {
                    violarDuble("Mockito.mock", variavel.getType(), tipo, modulo, contexto.linha(chamada));
                }
                return super.visitVariable(variavel, nada);
            }

            @Override
            public Void visitMethod(MethodTree metodo, Void nada) {
                boolean bean = metodo.getModifiers().getAnnotations().stream()
                        .anyMatch(a -> designa(a, BEAN, contexto, tipo.nome(), modulo));
                if (bean && metodo.getReturnType() != null) {
                    String infraestrutura = infraestrutura(resolver(InventarioDeTestes.nomeDoTipo(metodo.getReturnType()),
                            contexto, tipo.nome(), modulo), modulo);
                    if (infraestrutura != null) {
                        modulo.violar("@Bean de infraestrutura em configuracao de teste: " + contexto.arquivo + ":"
                                + contexto.linha(metodo) + ": " + tipo.nome() + "#" + metodo.getName() + " devolve " + infraestrutura);
                    }
                }
                return super.visitMethod(metodo, nada);
            }

            @Override
            public Void visitMethodInvocation(MethodInvocationTree chamada, Void nada) {
                if (ehMock(chamada, contexto, tipo, modulo) && !chamada.getArguments().isEmpty()
                        && chamada.getArguments().getFirst() instanceof MemberSelectTree literal
                        && literal.getIdentifier().contentEquals("class")) {
                    violarDuble("Mockito.mock", literal.getExpression(), tipo, modulo, contexto.linha(chamada));
                }
                return super.visitMethodInvocation(chamada, nada);
            }

            @Override
            public Void visitLiteral(LiteralTree literal, Void nada) {
                if (literal.getValue() instanceof String texto) {
                    URLS_EMBARCADAS.stream().filter(texto::contains).findFirst().ifPresent(url -> modulo.violar(
                            "propriedade de banco embarcado: " + contexto.arquivo + ":" + contexto.linha(literal) + ": " + url));
                }
                return super.visitLiteral(literal, nada);
            }
        }.scan(tipo.arvore(), null);
    }

    private static void violarDuble(String duble, Tree tipoSubstituido, Tipo tipo, Modulo modulo, long linha) {
        String infraestrutura = infraestrutura(resolver(InventarioDeTestes.nomeDoTipo(tipoSubstituido), tipo.contexto(),
                tipo.nome(), modulo), modulo);
        if (infraestrutura != null) {
            modulo.violar("duble de infraestrutura em teste de integracao: " + tipo.contexto().arquivo + ":" + linha + ": "
                    + duble + " sobre " + infraestrutura);
        }
    }

    /** {@code Mockito.mock(...)}, ou {@code mock(...)} trazido por import estatico do Mockito. */
    private static boolean ehMock(MethodInvocationTree chamada, Contexto contexto, Tipo tipo, Modulo modulo) {
        return switch (chamada.getMethodSelect()) {
            case MemberSelectTree selecao -> selecao.getIdentifier().contentEquals("mock")
                    && resolver(InventarioDeTestes.nomeDoTipo(selecao.getExpression()), contexto, tipo.nome(), modulo)
                            .valor().equals(MOCKITO);
            case IdentifierTree identificador -> identificador.getName().contentEquals("mock")
                    && (contexto.importsEstaticos.getOrDefault("mock", List.of()).contains(MOCKITO)
                            || contexto.importsEstaticosSobDemanda.contains(MOCKITO));
            default -> false;
        };
    }

    /** {@code @DataJpaTest} ou {@code @JdbcTest} exigem {@code replace = NONE} no tipo ou num supertipo. */
    private static void verificarFatias(Tipo tipo, Modulo modulo) {
        Contexto contexto = tipo.contexto();
        for (AnnotationTree anotacao : tipo.arvore().getModifiers().getAnnotations()) {
            if (designa(anotacao, AUTO_CONFIGURE_TEST_DATABASE, contexto, tipo.nome(), modulo) && !substituiNada(anotacao)) {
                modulo.violar("substituicao automatica do banco: " + contexto.arquivo + ":" + contexto.linha(anotacao) + ": "
                        + tipo.nome() + " declara @AutoConfigureTestDatabase sem replace = NONE");
            }
            boolean fatia = FATIAS_DE_BANCO.stream().anyMatch(f -> designa(anotacao, f, contexto, tipo.nome(), modulo));
            if (fatia && !naCadeiaSubstituiNada(tipo, modulo, new HashSet<>())) {
                modulo.violar("substituicao automatica do banco: " + contexto.arquivo + ":" + contexto.linha(anotacao) + ": "
                        + tipo.nome() + " declara " + anotacao.getAnnotationType()
                        + " sem @AutoConfigureTestDatabase(replace = NONE)");
            }
        }
    }

    private static boolean naCadeiaSubstituiNada(Tipo tipo, Modulo modulo, Set<Tipo> visitados) {
        if (tipo == null || !visitados.add(tipo)) {
            return false;
        }
        for (AnnotationTree anotacao : tipo.arvore().getModifiers().getAnnotations()) {
            Nome nome = resolver(InventarioDeTestes.nomeDoTipo(anotacao.getAnnotationType()), tipo.contexto(), tipo.nome(), modulo);
            if (nome.resolvido() && nome.valor().equals(AUTO_CONFIGURE_TEST_DATABASE) && substituiNada(anotacao)) {
                return true;
            }
        }
        Tree supertipo = tipo.arvore().getExtendsClause();
        return supertipo != null && naCadeiaSubstituiNada(modulo.testes.get(
                resolver(InventarioDeTestes.nomeDoTipo(supertipo), tipo.contexto(), tipo.nome(), modulo).valor()), modulo, visitados);
    }

    private static boolean substituiNada(AnnotationTree anotacao) {
        for (ExpressionTree argumento : anotacao.getArguments()) {
            if (argumento instanceof AssignmentTree atribuicao && atribuicao.getVariable().toString().equals("replace")) {
                ExpressionTree valor = atribuicao.getExpression();
                return valor instanceof MemberSelectTree selecao ? selecao.getIdentifier().contentEquals("NONE")
                        : valor instanceof IdentifierTree identificador && identificador.getName().contentEquals("NONE");
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- containers e evidencia

    /** Containers declarados nas fontes de teste do modulo, com a imagem conferida. */
    private static Set<Infraestrutura> containers(Modulo modulo) {
        Set<Infraestrutura> usadas = EnumSet.noneOf(Infraestrutura.class);
        for (Contexto contexto : modulo.contextosDeTeste) {
            new TreeScanner<Void, Void>() {
                @Override
                public Void visitNewClass(NewClassTree novo, Void nada) {
                    Nome nome = resolver(InventarioDeTestes.nomeDoTipo(novo.getIdentifier()), contexto, null, modulo);
                    for (Infraestrutura infraestrutura : Infraestrutura.values()) {
                        if (nome.resolvido() ? nome.valor().equals(infraestrutura.container)
                                : nome.valor().equals(infraestrutura.containerSimples())) {
                            usadas.add(infraestrutura);
                            conferirImagem(novo, infraestrutura, contexto, modulo);
                        }
                    }
                    return super.visitNewClass(novo, nada);
                }
            }.scan(contexto.unidade, null);
        }
        return usadas;
    }

    private static void conferirImagem(NewClassTree novo, Infraestrutura infraestrutura, Contexto contexto, Modulo modulo) {
        Object imagem = !novo.getArguments().isEmpty() && novo.getArguments().getFirst() instanceof LiteralTree literal
                ? literal.getValue() : null;
        if (!infraestrutura.imagem.equals(imagem)) {
            modulo.violar("imagem de container fora da versao adotada: " + contexto.arquivo + ":" + contexto.linha(novo) + ": "
                    + infraestrutura.containerSimples() + " com " + (imagem == null ? novo.getArguments() : "\"" + imagem + "\"")
                    + ", exigida \"" + infraestrutura.imagem + "\"");
        }
    }

    /** Provas declaradas pelos ITs de evidencia do modulo: metodos {@code @Test} com o nome da prova. */
    private static Set<Infraestrutura> provas(Modulo modulo) {
        Set<Infraestrutura> provadas = EnumSet.noneOf(Infraestrutura.class);
        for (Tipo tipo : modulo.testes.values()) {
            if (tipo.envolvente() != null || !tipo.simples().startsWith(PREFIXO_DA_EVIDENCIA) || !tipo.simples().endsWith(SUFIXO_DE_IT)) {
                continue;
            }
            for (Tree membro : tipo.arvore().getMembers()) {
                if (!(membro instanceof MethodTree metodo)) {
                    continue;
                }
                boolean teste = metodo.getModifiers().getAnnotations().stream().anyMatch(a -> {
                    Nome nome = resolver(InventarioDeTestes.nomeDoTipo(a.getAnnotationType()), tipo.contexto(), tipo.nome(), modulo);
                    return nome.resolvido() && nome.valor().equals(TESTE);
                });
                for (Infraestrutura infraestrutura : Infraestrutura.values()) {
                    if (teste && metodo.getName().contentEquals(infraestrutura.prova)) {
                        provadas.add(infraestrutura);
                    }
                }
            }
        }
        return provadas;
    }

    private static void verificarEvidencia(Modulo modulo, Set<Infraestrutura> usadas, Set<Infraestrutura> provadas) {
        for (Infraestrutura infraestrutura : Infraestrutura.values()) {
            if (usadas.contains(infraestrutura) && !provadas.contains(infraestrutura)) {
                modulo.violar("modulo " + modulo.nome + " declara " + infraestrutura.containerSimples() + " e nenhum IT de evidencia"
                        + " prova " + infraestrutura.descricao + " (" + PREFIXO_DA_EVIDENCIA + "*" + SUFIXO_DE_IT + "#"
                        + infraestrutura.prova + ")");
            }
            if (provadas.contains(infraestrutura) && !usadas.contains(infraestrutura)) {
                modulo.violar("modulo " + modulo.nome + " prova " + infraestrutura.descricao + " sem declarar "
                        + infraestrutura.containerSimples() + ": prova de infraestrutura nao usada");
            }
        }
    }

    // ---------------------------------------------------------------- dependencias e recursos

    private static void verificarDependencias(Path pom, Modulo modulo) {
        if (!Files.isRegularFile(pom)) {
            return;
        }
        Element projeto;
        try {
            projeto = XmlSeguro.ler(pom).getDocumentElement();
        } catch (XmlSeguro.XmlRecusado e) {
            modulo.violar("POM nao interpretavel: " + modulo.nome + "/pom.xml: " + e.getMessage());
            return;
        }
        List<RegrasDoReactor.Dependencia> dependencias = new ArrayList<>(RegrasDoReactor.dependencias(projeto, ""));
        for (Element perfil : XmlSeguro.filhos(XmlSeguro.filho(projeto, "profiles"), "profile")) {
            dependencias.addAll(RegrasDoReactor.dependencias(perfil, ""));
        }
        for (RegrasDoReactor.Dependencia dependencia : dependencias) {
            String coordenada = dependencia.coordenada();
            if (DEPENDENCIAS_EMBARCADAS.stream().anyMatch(coordenada::startsWith)) {
                modulo.violar("banco em memoria ou broker embarcado: " + modulo.nome + "/pom.xml declara " + coordenada);
            }
        }
    }

    private static void verificarRecursos(Path raiz, Path recursos, Modulo modulo) {
        if (!Files.isDirectory(recursos)) {
            return;
        }
        try (Stream<Path> caminhos = Files.walk(recursos)) {
            for (Path arquivo : caminhos.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().matches("application.*\\.(properties|ya?ml)")).sorted().toList()) {
                String conteudo = Files.readString(arquivo, StandardCharsets.UTF_8);
                URLS_EMBARCADAS.stream().filter(conteudo::contains).forEach(url -> modulo.violar(
                        "propriedade de banco embarcado: " + relativo(raiz, arquivo) + ": " + url));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
