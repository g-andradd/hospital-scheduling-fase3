package br.com.fiap.hospital.qualidade;

import com.sun.source.tree.AnnotatedTypeTree;
import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ParameterizedTypeTree;
import com.sun.source.tree.Tree;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import javax.lang.model.element.Modifier;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/**
 * Inventario das fontes de teste (D5), so com o JDK e a Compiler Tree API.
 *
 * <p>Todo {@code .java} de {@code src/test/java} de cada modulo e analisado sintaticamente. Nomes de
 * tipo e de anotacao sao resolvidos pelos escopos da propria arvore e pelos imports — declaracao
 * simples, mesmo pacote, imports sob demanda e {@code java.lang} —, nunca por expressao regular. Um
 * nome relevante que nao possa ser resolvido reprova identificando o arquivo e o simbolo.
 *
 * <p>Classificacao: interface, enum, record e anotacao nao sao suites; classe abstrata e base ou
 * contrato; classe concreta de nivel superior que declara ou herda metodo de teste — inclusive por
 * {@code @Nested} herdado, transitivamente — e suite executavel; classe concreta sem teste e
 * auxiliar. Interfaces com metodos de teste {@code default} sao herdadas como qualquer supertipo.
 */
public final class InventarioDeTestes {

    /** Plugin que executa a suite, pelo sufixo do include normativo. */
    public enum Plugin {
        SUREFIRE("surefire-reports", "Test"),
        FAILSAFE("failsafe-reports", "IT");

        public final String diretorio;
        public final String sufixo;

        Plugin(String diretorio, String sufixo) {
            this.diretorio = diretorio;
            this.sufixo = sufixo;
        }
    }

    /**
     * Suite executavel e sua familia esperada de relatorios, por nome binario.
     *
     * @param obrigatorios o externo e toda aninhada alcancavel com testes
     * @param familia o externo e toda aninhada alcancavel, com ou sem testes
     */
    public record Suite(String modulo, String nome, Plugin plugin, String arquivo,
            Set<String> obrigatorios, Set<String> familia) {}

    public record Resultado(List<Suite> suites, List<String> violacoes, Map<String, Map<String, Integer>> classificacao) {}

    static final String TESTE = "teste";
    static final String NESTED = "nested";
    static final String DESABILITACAO = "desabilitacao";

    /** Anotacoes normativas, pelo nome qualificado. */
    static final Map<String, String> ANOTACOES = Map.of(
            "org.junit.jupiter.api.Test", TESTE,
            "org.junit.jupiter.api.RepeatedTest", TESTE,
            "org.junit.jupiter.api.TestFactory", TESTE,
            "org.junit.jupiter.api.TestTemplate", TESTE,
            "org.junit.jupiter.params.ParameterizedTest", TESTE,
            "org.junit.Test", TESTE,
            "org.junit.jupiter.api.Nested", NESTED,
            "org.junit.jupiter.api.Disabled", DESABILITACAO,
            "org.junit.Ignore", DESABILITACAO);

    static final String TESTCONTAINERS = "org.testcontainers.junit.jupiter.Testcontainers";

    /** Anotacoes condicionais do pacote org.junit.jupiter.api.condition do JUnit 5.12.2. */
    static final Set<String> CONDICOES = Set.of(
            "EnabledOnOs", "DisabledOnOs", "EnabledOnJre", "DisabledOnJre", "EnabledForJreRange", "DisabledForJreRange",
            "EnabledInNativeImage", "DisabledInNativeImage", "EnabledIf", "DisabledIf",
            "EnabledIfSystemProperty", "DisabledIfSystemProperty", "EnabledIfSystemProperties", "DisabledIfSystemProperties",
            "EnabledIfEnvironmentVariable", "DisabledIfEnvironmentVariable", "EnabledIfEnvironmentVariables",
            "DisabledIfEnvironmentVariables").stream()
            .map(c -> "org.junit.jupiter.api.condition." + c)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());

    /** Condicoes de execucao do Spring TestContext para JUnit Jupiter. */
    static final Set<String> CONDICOES_SPRING = Set.of(
            "org.springframework.test.context.junit.jupiter.EnabledIf",
            "org.springframework.test.context.junit.jupiter.DisabledIf");

    /**
     * APIs de suposicao do classpath de teste e seus metodos estaticos publicos de suposicao, pelas
     * versoes efetivas. {@code setPreferredAssumptionException} do AssertJ fica de fora: so configura a
     * excecao de uma suposicao posterior e nao aborta, pula nem condiciona o teste.
     */
    static final Map<String, Set<String>> APIS_DE_SUPOSICAO = Map.of(
            "org.junit.jupiter.api.Assumptions", Set.of("assumeTrue", "assumeFalse", "assumingThat", "abort"),
            "org.junit.Assume", Set.of("assumeTrue", "assumeFalse", "assumeNotNull", "assumeThat", "assumeNoException"),
            "org.assertj.core.api.Assumptions", Set.of("assumeThat", "assumeThatCharSequence", "assumeThatCode",
                    "assumeThatCollection", "assumeThatComparable", "assumeThatException", "assumeThatExceptionOfType",
                    "assumeThatIllegalArgumentException", "assumeThatIndexOutOfBoundsException", "assumeThatIOException",
                    "assumeThatIterable", "assumeThatIterator", "assumeThatList", "assumeThatNullPointerException",
                    "assumeThatObject", "assumeThatPath", "assumeThatPredicate", "assumeThatReflectiveOperationException",
                    "assumeThatRuntimeException", "assumeThatStream", "assumeThatTemporal", "assumeThatThrownBy"),
            "org.assertj.core.api.BDDAssumptions", Set.of("given", "givenCharSequence", "givenCode", "givenCollection",
                    "givenComparable", "givenException", "givenExceptionOfType", "givenIllegalArgumentException",
                    "givenIndexOutOfBoundsException", "givenIOException", "givenIterable", "givenIterator", "givenList",
                    "givenNullPointerException", "givenObject", "givenPath", "givenPredicate",
                    "givenReflectiveOperationException", "givenRuntimeException", "givenStream"));

    private InventarioDeTestes() {}

    // ---------------------------------------------------------------- modelo interno

    enum Genero { CLASSE, INTERFACE, ENUM, RECORD, ANOTACAO }

    /** Uma unidade de compilacao: arquivo, pacote e imports. */
    static final class Contexto {
        final String modulo;
        final String arquivo;
        final String pacote;
        final Map<String, String> importsSimples = new LinkedHashMap<>();
        final List<String> importsSobDemanda = new ArrayList<>();
        /** Membro importado estaticamente -> classes que o declaram. */
        final Map<String, List<String>> importsEstaticos = new LinkedHashMap<>();
        final List<String> importsEstaticosSobDemanda = new ArrayList<>();
        final CompilationUnitTree unidade;
        final SourcePositions posicoes;

        Contexto(String modulo, String arquivo, CompilationUnitTree unidade, SourcePositions posicoes) {
            this.modulo = modulo;
            this.arquivo = arquivo;
            this.unidade = unidade;
            this.posicoes = posicoes;
            this.pacote = unidade.getPackageName() == null ? "" : unidade.getPackageName().toString();
            for (ImportTree importacao : unidade.getImports()) {
                String nome = importacao.getQualifiedIdentifier().toString();
                if (importacao.isStatic()) {
                    String classe = nome.substring(0, nome.lastIndexOf('.'));
                    String membro = nome.substring(nome.lastIndexOf('.') + 1);
                    if (membro.equals("*")) {
                        importsEstaticosSobDemanda.add(classe);
                    } else {
                        importsEstaticos.computeIfAbsent(membro, m -> new ArrayList<>()).add(classe);
                    }
                    continue;
                }
                if (nome.endsWith(".*")) {
                    importsSobDemanda.add(nome.substring(0, nome.length() - 2));
                } else {
                    importsSimples.put(nome.substring(nome.lastIndexOf('.') + 1), nome);
                }
            }
        }

        long linha(Tree arvore) {
            return unidade.getLineMap().getLineNumber(posicoes.getStartPosition(unidade, arvore));
        }
    }

    /** Tipo declarado nas fontes de teste. */
    static final class Tipo {
        final Contexto contexto;
        final ClassTree arvore;
        final Tipo envolvente;
        final String nomeBinario;
        final String simples;
        final Genero genero;
        final List<Tipo> membros = new ArrayList<>();
        final List<Tipo> supertipos = new ArrayList<>();
        final Set<String> anotacoes = new HashSet<>();
        boolean declaraTestes;

        Tipo(Contexto contexto, ClassTree arvore, Tipo envolvente) {
            this.contexto = contexto;
            this.arvore = arvore;
            this.envolvente = envolvente;
            this.simples = arvore.getSimpleName().toString();
            String prefixo = envolvente != null ? envolvente.nomeBinario + "$"
                    : contexto.pacote.isEmpty() ? "" : contexto.pacote + ".";
            this.nomeBinario = prefixo + simples;
            this.genero = switch (arvore.getKind()) {
                case INTERFACE -> Genero.INTERFACE;
                case ENUM -> Genero.ENUM;
                case RECORD -> Genero.RECORD;
                case ANNOTATION_TYPE -> Genero.ANOTACAO;
                default -> Genero.CLASSE;
            };
        }

        boolean abstrato() {
            return arvore.getModifiers().getFlags().contains(Modifier.ABSTRACT);
        }

        boolean estatico() {
            return arvore.getModifiers().getFlags().contains(Modifier.STATIC);
        }

        String nomeCanonico() {
            return nomeBinario.replace('$', '.');
        }
    }

    sealed interface Resolucao permits NoInventario, Externo, NaoResolvido {}

    record NoInventario(Tipo tipo) implements Resolucao {}

    record Externo(String nome) implements Resolucao {}

    record NaoResolvido(String motivo) implements Resolucao {}

    /** Estado de uma analise. */
    private static final class Analise {
        final List<String> violacoes = new ArrayList<>();
        final Map<String, Map<String, Tipo>> tiposPorModulo = new LinkedHashMap<>();
        final Map<String, Set<String>> principaisPorModulo = new LinkedHashMap<>();
        final List<Tipo> todos = new ArrayList<>();
        final Map<String, String> customizadas = new LinkedHashMap<>();
        final Map<Tipo, List<Tipo>> supertipos = new LinkedHashMap<>();
        final Set<Tipo> resolvendo = new HashSet<>();
        final Set<String> violacoesVistas = new HashSet<>();

        void violar(String violacao) {
            if (violacoesVistas.add(violacao)) {
                violacoes.add(violacao);
            }
        }
    }

    /**
     * @param sufixos o sufixo de nome de cada plugin, derivado dos includes normativos
     */
    public static Resultado inventariar(Path raiz, List<String> modulos, Map<Plugin, String> sufixos) {
        Analise analise = new Analise();
        List<Contexto> contextos = new ArrayList<>();
        for (String modulo : modulos) {
            analise.tiposPorModulo.put(modulo, new LinkedHashMap<>());
            analise.principaisPorModulo.put(modulo, principais(raiz.resolve(modulo).resolve("src").resolve("main").resolve("java")));
            contextos.addAll(analisar(raiz, modulo, analise));
        }
        for (Tipo tipo : analise.todos) {
            supertipos(tipo, analise);
        }
        resolverMetaAnotacoes(analise);
        for (Tipo tipo : analise.todos) {
            anotar(tipo, analise);
        }
        for (Contexto contexto : contextos) {
            varrerSuposicoes(contexto, analise);
        }
        return classificar(modulos, sufixos, analise);
    }

    // ---------------------------------------------------------------- leitura

    private static List<Contexto> analisar(Path raiz, String modulo, Analise analise) {
        Path fontes = raiz.resolve(modulo).resolve("src").resolve("test").resolve("java");
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
            throw new IllegalStateException("a auditoria exige um JDK: a Compiler Tree API nao esta disponivel");
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
                if (diagnostico.getKind() != Diagnostic.Kind.ERROR) {
                    continue;
                }
                String arquivo = diagnostico.getSource() == null ? modulo
                        : relativo(raiz, Path.of(diagnostico.getSource().toUri()));
                comErro.add(arquivo);
                analise.violar("fonte de teste nao interpretavel: " + arquivo + ":" + diagnostico.getLineNumber()
                        + ": " + diagnostico.getMessage(Locale.ROOT));
            }
            List<Contexto> contextos = new ArrayList<>();
            for (CompilationUnitTree unidade : unidades) {
                String arquivo = relativo(raiz, Path.of(unidade.getSourceFile().toUri()));
                if (comErro.contains(arquivo)) {
                    continue;
                }
                Contexto contexto = new Contexto(modulo, arquivo, unidade, posicoes);
                contextos.add(contexto);
                for (Tree declaracao : unidade.getTypeDecls()) {
                    if (declaracao instanceof ClassTree classe) {
                        registrar(new Tipo(contexto, classe, null), analise);
                    }
                }
            }
            return contextos;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void registrar(Tipo tipo, Analise analise) {
        analise.todos.add(tipo);
        analise.tiposPorModulo.get(tipo.contexto.modulo).put(tipo.nomeBinario, tipo);
        for (Tree membro : tipo.arvore.getMembers()) {
            if (membro instanceof ClassTree classe) {
                Tipo aninhado = new Tipo(tipo.contexto, classe, tipo);
                tipo.membros.add(aninhado);
                registrar(aninhado, analise);
            }
        }
    }

    /** Nomes qualificados das fontes principais do modulo, pelo caminho: bastam para saber que sao externas. */
    private static Set<String> principais(Path fontes) {
        if (!Files.isDirectory(fontes)) {
            return Set.of();
        }
        try (Stream<Path> caminhos = Files.walk(fontes)) {
            Set<String> nomes = new HashSet<>();
            caminhos.filter(p -> p.toString().endsWith(".java")).forEach(p -> {
                String relativo = fontes.relativize(p).toString().replace('\\', '/');
                nomes.add(relativo.substring(0, relativo.length() - ".java".length()).replace('/', '.'));
            });
            return nomes;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String relativo(Path raiz, Path arquivo) {
        return raiz.toAbsolutePath().normalize().relativize(arquivo.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    // ---------------------------------------------------------------- resolucao de nomes

    static String nomeDoTipo(Tree arvore) {
        return switch (arvore) {
            case ParameterizedTypeTree parametrizado -> nomeDoTipo(parametrizado.getType());
            case AnnotatedTypeTree anotado -> nomeDoTipo(anotado.getUnderlyingType());
            case IdentifierTree identificador -> identificador.getName().toString();
            case MemberSelectTree selecao -> nomeDoTipo(selecao.getExpression()) + "." + selecao.getIdentifier();
            default -> arvore.toString();
        };
    }

    private static String prefixo(Contexto contexto) {
        return contexto.pacote.isEmpty() ? "" : contexto.pacote + ".";
    }

    /** Busca pelo nome canonico: {@code pacote.Externo.Aninhado} vira {@code pacote.Externo$Aninhado}. */
    private static Tipo porNomeCanonico(String canonico, Map<String, Tipo> doModulo) {
        Tipo direto = doModulo.get(canonico);
        if (direto != null) {
            return direto;
        }
        String candidato = canonico;
        for (int ponto = candidato.lastIndexOf('.'); ponto > 0; ponto = candidato.lastIndexOf('.', ponto - 1)) {
            candidato = candidato.substring(0, ponto) + "$" + candidato.substring(ponto + 1);
            Tipo tipo = doModulo.get(candidato);
            if (tipo != null) {
                return tipo;
            }
        }
        return null;
    }

    private static boolean pacoteConhecido(String pacote, Contexto contexto, Analise analise) {
        String prefixo = pacote + ".";
        return analise.tiposPorModulo.get(contexto.modulo).keySet().stream().anyMatch(n -> n.startsWith(prefixo))
                || analise.principaisPorModulo.get(contexto.modulo).stream().anyMatch(n -> n.startsWith(prefixo))
                || porNomeCanonico(pacote, analise.tiposPorModulo.get(contexto.modulo)) != null;
    }

    private static boolean javaLang(String simples) {
        try {
            Class.forName("java.lang." + simples, false, ClassLoader.getPlatformClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    private static Resolucao resolverTipo(String nome, Tipo escopo, Contexto contexto, Analise analise) {
        int ponto = nome.indexOf('.');
        if (ponto < 0) {
            return resolverSimples(nome, escopo, contexto, analise);
        }
        Resolucao inicio = resolverSimples(nome.substring(0, ponto), escopo, contexto, analise);
        if (inicio instanceof NoInventario(Tipo tipo)) {
            Tipo atual = tipo;
            for (String segmento : nome.substring(ponto + 1).split("\\.")) {
                atual = membro(atual, segmento, analise, new HashSet<>());
                if (atual == null) {
                    return new NaoResolvido("membro " + segmento + " inexistente");
                }
            }
            return new NoInventario(atual);
        }
        if (inicio instanceof Externo) {
            return new Externo(nome);
        }
        Tipo qualificado = porNomeCanonico(nome, analise.tiposPorModulo.get(contexto.modulo));
        return qualificado != null ? new NoInventario(qualificado) : new Externo(nome);
    }

    private static Resolucao resolverSimples(String nome, Tipo escopo, Contexto contexto, Analise analise) {
        Tipo local = resolverLocal(nome, escopo, contexto, analise);
        if (local != null) {
            return new NoInventario(local);
        }
        Map<String, Tipo> doModulo = analise.tiposPorModulo.get(contexto.modulo);
        String importado = contexto.importsSimples.get(nome);
        if (importado != null) {
            Tipo tipo = porNomeCanonico(importado, doModulo);
            return tipo != null ? new NoInventario(tipo) : new Externo(importado);
        }
        if (analise.principaisPorModulo.get(contexto.modulo).contains(prefixo(contexto) + nome)) {
            return new Externo(prefixo(contexto) + nome);
        }
        List<Tipo> candidatos = new ArrayList<>();
        boolean sobDemandaExterno = false;
        for (String pacote : contexto.importsSobDemanda) {
            Tipo tipo = porNomeCanonico(pacote + "." + nome, doModulo);
            if (tipo != null) {
                if (!candidatos.contains(tipo)) {
                    candidatos.add(tipo);
                }
            } else if (analise.principaisPorModulo.get(contexto.modulo).contains(pacote + "." + nome)) {
                return new Externo(pacote + "." + nome);
            } else if (!pacoteConhecido(pacote, contexto, analise)) {
                sobDemandaExterno = true;
            }
        }
        if (candidatos.size() == 1) {
            return new NoInventario(candidatos.getFirst());
        }
        if (candidatos.size() > 1) {
            return new NaoResolvido("ambiguo entre imports sob demanda");
        }
        if (javaLang(nome)) {
            return new Externo("java.lang." + nome);
        }
        if (sobDemandaExterno) {
            // So pode vir de um pacote externo importado sob demanda: nao e fonte de teste.
            return new Externo(nome);
        }
        return new NaoResolvido("sem import, fora do pacote e de java.lang");
    }

    /** Escopos envolventes, tipos do mesmo arquivo e do mesmo pacote nas fontes de teste. */
    private static Tipo resolverLocal(String nome, Tipo escopo, Contexto contexto, Analise analise) {
        for (Tipo atual = escopo; atual != null; atual = atual.envolvente) {
            if (atual.simples.equals(nome)) {
                return atual;
            }
            Tipo membro = membro(atual, nome, analise, new HashSet<>());
            if (membro != null) {
                return membro;
            }
        }
        return analise.tiposPorModulo.get(contexto.modulo).get(prefixo(contexto) + nome);
    }

    private static Tipo membro(Tipo tipo, String simples, Analise analise, Set<Tipo> visitados) {
        if (!visitados.add(tipo)) {
            return null;
        }
        for (Tipo membro : tipo.membros) {
            if (membro.simples.equals(simples)) {
                return membro;
            }
        }
        for (Tipo supertipo : supertipos(tipo, analise)) {
            Tipo membro = membro(supertipo, simples, analise, visitados);
            if (membro != null) {
                return membro;
            }
        }
        return null;
    }

    private static List<Tipo> supertipos(Tipo tipo, Analise analise) {
        List<Tipo> resolvidos = analise.supertipos.get(tipo);
        if (resolvidos != null) {
            return resolvidos;
        }
        if (!analise.resolvendo.add(tipo)) {
            return List.of();
        }
        List<Tipo> encontrados = new ArrayList<>();
        List<Tree> declarados = new ArrayList<>();
        if (tipo.arvore.getExtendsClause() != null) {
            declarados.add(tipo.arvore.getExtendsClause());
        }
        declarados.addAll(tipo.arvore.getImplementsClause());
        for (Tree declarado : declarados) {
            String nome = nomeDoTipo(declarado);
            Resolucao resolucao = resolverTipo(nome, tipo.envolvente, tipo.contexto, analise);
            if (resolucao instanceof NoInventario(Tipo supertipo)) {
                encontrados.add(supertipo);
            } else if (resolucao instanceof NaoResolvido(String motivo)) {
                analise.violar("supertipo nao resolvido: " + tipo.contexto.arquivo + ": " + tipo.nomeCanonico()
                        + " estende " + nome + " (" + motivo + ")");
            }
        }
        analise.resolvendo.remove(tipo);
        analise.supertipos.put(tipo, encontrados);
        return encontrados;
    }

    // ---------------------------------------------------------------- anotacoes

    private static boolean catalogada(String qualificado) {
        return ANOTACOES.containsKey(qualificado) || qualificado.equals(TESTCONTAINERS)
                || CONDICOES.contains(qualificado) || CONDICOES_SPRING.contains(qualificado);
    }

    /** Nomes simples das anotacoes normativas: um deles sem resolucao reprova. */
    private static Set<String> nomesRelevantes(Analise analise) {
        Set<String> nomes = new HashSet<>();
        Stream.of(ANOTACOES.keySet(), CONDICOES, CONDICOES_SPRING, Set.of(TESTCONTAINERS))
                .flatMap(Set::stream)
                .forEach(n -> nomes.add(n.substring(n.lastIndexOf('.') + 1)));
        analise.customizadas.keySet().forEach(n -> nomes.add(n.substring(Math.max(n.lastIndexOf('.'), n.lastIndexOf('$')) + 1)));
        return nomes;
    }

    /** Nome qualificado da anotacao, ou nulo quando ela nao e relevante. */
    private static String resolverAnotacao(String nome, Tipo escopo, Analise analise, long linha) {
        Contexto contexto = escopo.contexto;
        Map<String, Tipo> doModulo = analise.tiposPorModulo.get(contexto.modulo);
        if (nome.contains(".")) {
            Resolucao resolucao = resolverTipo(nome, escopo, contexto, analise);
            return resolucao instanceof NoInventario(Tipo tipo) ? tipo.nomeBinario : nome;
        }
        String importado = contexto.importsSimples.get(nome);
        if (importado != null) {
            Tipo tipo = porNomeCanonico(importado, doModulo);
            return tipo != null ? tipo.nomeBinario : importado;
        }
        Tipo local = resolverLocal(nome, escopo, contexto, analise);
        if (local != null) {
            return local.nomeBinario;
        }
        List<String> candidatos = new ArrayList<>();
        for (String pacote : contexto.importsSobDemanda) {
            Tipo tipo = porNomeCanonico(pacote + "." + nome, doModulo);
            if (tipo != null) {
                candidatos.add(tipo.nomeBinario);
            } else if (catalogada(pacote + "." + nome)) {
                candidatos.add(pacote + "." + nome);
            }
        }
        if (candidatos.size() == 1) {
            return candidatos.getFirst();
        }
        boolean relevante = nomesRelevantes(analise).contains(nome);
        if (candidatos.size() > 1) {
            if (relevante) {
                analise.violar("anotacao nao resolvida: " + contexto.arquivo + ":" + linha + ": @" + nome
                        + " (ambigua entre imports sob demanda " + candidatos + ")");
            }
            return null;
        }
        if (javaLang(nome)) {
            return "java.lang." + nome;
        }
        if (relevante) {
            analise.violar("anotacao nao resolvida: " + contexto.arquivo + ":" + linha + ": @" + nome
                    + " (sem import que a identifique)");
        }
        return null;
    }

    /**
     * Categoria normativa da anotacao, decidida so depois da resolucao do nome: nome parecido nao
     * prova semantica. Anotacao local vale pela meta-anotacao que carrega, transitivamente.
     */
    private static String categoria(AnnotationTree anotacao, Tipo escopo, Analise analise) {
        String nome = nomeDoTipo(anotacao.getAnnotationType());
        String qualificado = resolverAnotacao(nome, escopo, analise, escopo.contexto.linha(anotacao));
        if (qualificado == null) {
            return null;
        }
        if (ANOTACOES.containsKey(qualificado)) {
            return ANOTACOES.get(qualificado);
        }
        if (CONDICOES.contains(qualificado) || CONDICOES_SPRING.contains(qualificado)) {
            return DESABILITACAO;
        }
        if (qualificado.equals(TESTCONTAINERS)) {
            return desabilitaSemDocker(anotacao) ? DESABILITACAO : null;
        }
        return analise.customizadas.get(qualificado);
    }

    private static boolean desabilitaSemDocker(AnnotationTree anotacao) {
        for (ExpressionTree argumento : anotacao.getArguments()) {
            if (argumento instanceof AssignmentTree atribuicao
                    && atribuicao.getVariable().toString().equals("disabledWithoutDocker")
                    && atribuicao.getExpression().toString().equals("true")) {
                return true;
            }
        }
        return false;
    }

    /** Anotacoes das fontes de teste meta-anotadas com teste ou desabilitacao, ate o ponto fixo. */
    private static void resolverMetaAnotacoes(Analise analise) {
        boolean mudou = true;
        while (mudou) {
            mudou = false;
            for (Tipo tipo : analise.todos) {
                if (tipo.genero != Genero.ANOTACAO || analise.customizadas.containsKey(tipo.nomeBinario)) {
                    continue;
                }
                for (AnnotationTree meta : tipo.arvore.getModifiers().getAnnotations()) {
                    String categoria = categoriaSemViolacao(meta, tipo, analise);
                    if (TESTE.equals(categoria) || DESABILITACAO.equals(categoria)) {
                        analise.customizadas.put(tipo.nomeBinario, categoria);
                        mudou = true;
                        break;
                    }
                }
            }
        }
    }

    /** Na busca de meta-anotacoes, um nome nao resolvido ainda sera reportado por {@link #anotar}. */
    private static String categoriaSemViolacao(AnnotationTree anotacao, Tipo escopo, Analise analise) {
        List<String> antes = new ArrayList<>(analise.violacoes);
        Set<String> vistasAntes = new HashSet<>(analise.violacoesVistas);
        String categoria = categoria(anotacao, escopo, analise);
        analise.violacoes.retainAll(antes);
        analise.violacoesVistas.retainAll(vistasAntes);
        return categoria;
    }

    private static void anotar(Tipo tipo, Analise analise) {
        for (AnnotationTree anotacao : tipo.arvore.getModifiers().getAnnotations()) {
            String categoria = categoria(anotacao, tipo, analise);
            if (NESTED.equals(categoria)) {
                tipo.anotacoes.add(NESTED);
            } else if (DESABILITACAO.equals(categoria) && tipo.genero != Genero.ANOTACAO) {
                // Declarar uma meta-anotacao condicional nao desabilita nada: o uso dela e que reprova.
                analise.violar("desabilitacao declarada no codigo de teste: " + tipo.contexto.arquivo + ":"
                        + tipo.contexto.linha(anotacao) + ": " + anotacao.getAnnotationType() + " em " + tipo.nomeCanonico());
            }
        }
        for (Tree membro : tipo.arvore.getMembers()) {
            if (!(membro instanceof MethodTree metodo)) {
                continue;
            }
            for (AnnotationTree anotacao : metodo.getModifiers().getAnnotations()) {
                String categoria = categoria(anotacao, tipo, analise);
                if (TESTE.equals(categoria)) {
                    tipo.declaraTestes = true;
                } else if (DESABILITACAO.equals(categoria)) {
                    analise.violar("desabilitacao declarada no codigo de teste: " + tipo.contexto.arquivo + ":"
                            + tipo.contexto.linha(anotacao) + ": " + anotacao.getAnnotationType() + " em "
                            + tipo.nomeCanonico() + "#" + metodo.getName());
                }
            }
        }
    }

    /**
     * Chamadas e referencias de metodo resolvidas as APIs de suposicao. A chamada qualificada vale
     * pelo tipo resolvido; a nao qualificada, por import estatico da API, salvo se um metodo de mesmo
     * nome da propria classe, das envolventes ou dos supertipos a sombrear.
     */
    private static void varrerSuposicoes(Contexto contexto, Analise analise) {
        Map<ClassTree, Tipo> tipos = new java.util.IdentityHashMap<>();
        analise.todos.stream().filter(t -> t.contexto == contexto).forEach(t -> tipos.put(t.arvore, t));
        new TreeScanner<Void, Tipo>() {
            @Override
            public Void visitClass(ClassTree classe, Tipo envolvente) {
                return super.visitClass(classe, tipos.getOrDefault(classe, envolvente));
            }

            @Override
            public Void visitMethodInvocation(MethodInvocationTree chamada, Tipo escopo) {
                String api = switch (chamada.getMethodSelect()) {
                    case MemberSelectTree selecao -> apiQualificada(nomeDoTipo(selecao.getExpression()),
                            selecao.getIdentifier().toString(), escopo, contexto, analise);
                    case IdentifierTree identificador -> apiPorImportEstatico(identificador.getName().toString(), escopo, contexto, analise);
                    default -> null;
                };
                if (api != null) {
                    analise.violar("suposicao condicional no codigo de teste: " + contexto.arquivo + ":"
                            + contexto.linha(chamada) + ": " + chamada.getMethodSelect() + " (" + api + ")");
                }
                return super.visitMethodInvocation(chamada, escopo);
            }

            @Override
            public Void visitMemberReference(com.sun.source.tree.MemberReferenceTree referencia, Tipo escopo) {
                String api = apiQualificada(nomeDoTipo(referencia.getQualifierExpression()), referencia.getName().toString(),
                        escopo, contexto, analise);
                if (api != null) {
                    analise.violar("suposicao condicional no codigo de teste: " + contexto.arquivo + ":"
                            + contexto.linha(referencia) + ": " + referencia + " (" + api + ")");
                }
                return super.visitMemberReference(referencia, escopo);
            }
        }.scan(contexto.unidade, null);
    }

    /** API de suposicao do tipo qualificador, se ele resolve para uma e o metodo e dela. */
    private static String apiQualificada(String tipo, String metodo, Tipo escopo, Contexto contexto, Analise analise) {
        String api;
        if (tipo.contains(".")) {
            api = tipo;
        } else if (escopo != null && resolverLocal(tipo, escopo, contexto, analise) != null
                || analise.tiposPorModulo.get(contexto.modulo).containsKey(prefixo(contexto) + tipo)) {
            return null;
        } else if (contexto.importsSimples.containsKey(tipo)) {
            api = contexto.importsSimples.get(tipo);
        } else {
            api = contexto.importsSobDemanda.stream().map(p -> p + "." + tipo)
                    .filter(APIS_DE_SUPOSICAO::containsKey).findFirst().orElse(null);
        }
        return api != null && APIS_DE_SUPOSICAO.getOrDefault(api, Set.of()).contains(metodo) ? api : null;
    }

    /** API de suposicao que um import estatico traz para o nome, se nenhum metodo local o sombreia. */
    private static String apiPorImportEstatico(String metodo, Tipo escopo, Contexto contexto, Analise analise) {
        if (sombreado(metodo, escopo, analise)) {
            return null;
        }
        for (String classe : contexto.importsEstaticos.getOrDefault(metodo, List.of())) {
            if (APIS_DE_SUPOSICAO.getOrDefault(classe, Set.of()).contains(metodo)) {
                return classe;
            }
        }
        if (contexto.importsEstaticos.containsKey(metodo)) {
            return null;
        }
        return contexto.importsEstaticosSobDemanda.stream()
                .filter(classe -> APIS_DE_SUPOSICAO.getOrDefault(classe, Set.of()).contains(metodo))
                .findFirst().orElse(null);
    }

    private static boolean sombreado(String metodo, Tipo escopo, Analise analise) {
        for (Tipo atual = escopo; atual != null; atual = atual.envolvente) {
            for (Tipo elo : cadeia(atual, analise)) {
                for (Tree membro : elo.arvore.getMembers()) {
                    if (membro instanceof MethodTree declarado && declarado.getName().contentEquals(metodo)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- classificacao

    private static boolean temTestes(Tipo tipo, Analise analise, Set<Tipo> visitados) {
        if (!visitados.add(tipo)) {
            return false;
        }
        if (tipo.declaraTestes) {
            return true;
        }
        for (Tipo supertipo : supertipos(tipo, analise)) {
            if (temTestes(supertipo, analise, visitados)) {
                return true;
            }
        }
        return false;
    }

    private static Set<Tipo> cadeia(Tipo tipo, Analise analise) {
        Set<Tipo> cadeia = new LinkedHashSet<>();
        List<Tipo> pendentes = new ArrayList<>(List.of(tipo));
        while (!pendentes.isEmpty()) {
            Tipo atual = pendentes.removeFirst();
            if (cadeia.add(atual)) {
                pendentes.addAll(supertipos(atual, analise));
            }
        }
        return cadeia;
    }

    /** Toda {@code @Nested} alcancavel a partir do tipo e de seus supertipos, transitivamente. */
    private static Set<Tipo> aninhadas(Tipo tipo, Analise analise, Set<Tipo> visitadas) {
        Set<Tipo> encontradas = new LinkedHashSet<>();
        for (Tipo elo : cadeia(tipo, analise)) {
            for (Tipo membro : elo.membros) {
                if (membro.genero == Genero.CLASSE && membro.anotacoes.contains(NESTED) && !membro.estatico()
                        && !membro.abstrato() && visitadas.add(membro)) {
                    encontradas.add(membro);
                    encontradas.addAll(aninhadas(membro, analise, visitadas));
                }
            }
        }
        return encontradas;
    }

    private static Resultado classificar(List<String> modulos, Map<Plugin, String> sufixos, Analise analise) {
        Map<String, Map<String, Integer>> classificacao = new LinkedHashMap<>();
        for (String modulo : modulos) {
            Map<String, Integer> contagem = new LinkedHashMap<>();
            for (String categoria : List.of("fontes", "nao suite (interface, enum, record, anotacao)", "abstrata",
                    "auxiliar", "aninhada @Nested", "suite surefire", "suite failsafe")) {
                contagem.put(categoria, 0);
            }
            classificacao.put(modulo, contagem);
        }
        Set<String> arquivos = new HashSet<>();
        List<Suite> suites = new ArrayList<>();
        for (Tipo tipo : analise.todos) {
            Map<String, Integer> contagem = classificacao.get(tipo.contexto.modulo);
            if (arquivos.add(tipo.contexto.arquivo)) {
                contagem.merge("fontes", 1, Integer::sum);
            }
            if (tipo.envolvente != null) {
                classificarAninhada(tipo, contagem, analise);
                continue;
            }
            if (tipo.genero != Genero.CLASSE) {
                contagem.merge("nao suite (interface, enum, record, anotacao)", 1, Integer::sum);
                continue;
            }
            if (tipo.abstrato()) {
                contagem.merge("abstrata", 1, Integer::sum);
                continue;
            }
            Set<Tipo> aninhadas = aninhadas(tipo, analise, new HashSet<>());
            boolean executavel = temTestes(tipo, analise, new HashSet<>())
                    || aninhadas.stream().anyMatch(a -> temTestes(a, analise, new HashSet<>()));
            if (!executavel) {
                contagem.merge("auxiliar", 1, Integer::sum);
                continue;
            }
            Plugin plugin = tipo.simples.endsWith(sufixos.get(Plugin.FAILSAFE)) ? Plugin.FAILSAFE
                    : tipo.simples.endsWith(sufixos.get(Plugin.SUREFIRE)) ? Plugin.SUREFIRE : null;
            if (plugin == null) {
                analise.violar("teste fora da convencao: " + tipo.contexto.modulo + ": " + tipo.nomeCanonico() + " ("
                        + tipo.contexto.arquivo + ") declara ou herda testes e nao termina em "
                        + sufixos.get(Plugin.SUREFIRE) + " nem " + sufixos.get(Plugin.FAILSAFE) + ": nunca seria executado");
                continue;
            }
            contagem.merge(plugin == Plugin.SUREFIRE ? "suite surefire" : "suite failsafe", 1, Integer::sum);
            Set<String> obrigatorios = new LinkedHashSet<>(List.of(tipo.nomeBinario));
            Set<String> familia = new LinkedHashSet<>(List.of(tipo.nomeBinario));
            for (Tipo aninhada : aninhadas) {
                familia.add(aninhada.nomeBinario);
                if (temTestes(aninhada, analise, new HashSet<>())) {
                    obrigatorios.add(aninhada.nomeBinario);
                }
            }
            suites.add(new Suite(tipo.contexto.modulo, tipo.nomeBinario, plugin, tipo.contexto.arquivo,
                    java.util.Collections.unmodifiableSet(obrigatorios), java.util.Collections.unmodifiableSet(familia)));
        }
        suites.sort(java.util.Comparator.comparing(Suite::modulo).thenComparing(Suite::nome));
        return new Resultado(List.copyOf(suites), List.copyOf(analise.violacoes), classificacao);
    }

    private static void classificarAninhada(Tipo tipo, Map<String, Integer> contagem, Analise analise) {
        if (tipo.genero != Genero.CLASSE) {
            return;
        }
        boolean nested = tipo.anotacoes.contains(NESTED);
        if (nested) {
            contagem.merge("aninhada @Nested", 1, Integer::sum);
            if (tipo.estatico() && temTestes(tipo, analise, new HashSet<>())) {
                analise.violar("@Nested em classe static nunca executa: " + tipo.contexto.arquivo + ": " + tipo.nomeCanonico());
            }
        } else if (temTestes(tipo, analise, new HashSet<>())) {
            analise.violar("classe aninhada com testes sem @Nested nunca executa: " + tipo.contexto.arquivo + ": "
                    + tipo.nomeCanonico());
        }
    }
}
