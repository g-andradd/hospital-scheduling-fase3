package br.com.fiap.hospital.historico.integracao.superficie;

import graphql.schema.GraphQLEnumType;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLInputObjectField;
import graphql.schema.GraphQLInputObjectType;
import graphql.schema.GraphQLList;
import graphql.schema.GraphQLNamedType;
import graphql.schema.GraphQLNonNull;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLScalarType;
import graphql.schema.GraphQLSchema;
import graphql.schema.GraphQLType;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Descobre a superficie GraphQL a partir do schema <b>servido</b> e confronta o descoberto
 * com o atacado.
 *
 * <p>A fonte e {@code GraphQlSource.schema()}, e nao o arquivo {@code .graphqls}: o que
 * responde ao cliente e o schema montado, com o que o wiring acrescentou ou deixou de
 * acrescentar. Ler o arquivo mediria a intencao; ler o schema mede o que existe.
 *
 * <p>O mapeamento de tipo para dimensao <b>falha fechado</b>. Um escalar sem dimensao
 * normativa reprova em vez de cair num padrao, porque um padrao silencioso e exatamente
 * como uma entrada nova deixa de ser atacada.
 *
 * <p><b>Tres naturezas.</b> Uma combinacao pertence a uma entrada do schema, ao documento
 * de uma operacao, ou a fronteira de transporte. Elas nao se misturam: o documento pertence
 * a operacao que ele realmente executa, e o corpo HTTP e fronteira unica — contar o mesmo
 * corpo malformado uma vez por operacao inflaria E sem exercitar nada a mais.
 */
public final class InventarioGraphql {

    /** Uma operacao servida: o tipo raiz e o nome do campo. */
    public record Operacao(String raiz, String nome) {
        @Override
        public String toString() {
            return raiz + "." + nome;
        }
    }

    /** Um ponto atacavel do schema: argumento, ou campo de objeto de entrada. */
    public record Entrada(Operacao operacao, String caminho, DimensaoGraphql dimensao,
                          boolean obrigatoria, boolean lista) {

        public String chave() {
            return operacao + " | " + caminho;
        }

        @Override
        public String toString() {
            return chave() + " (" + dimensao + (obrigatoria ? ", obrigatorio" : "") + ")";
        }
    }

    /** Uma tupla atacavel, de qualquer uma das tres naturezas. */
    public record Combinacao(Operacao operacao, String entrada, DimensaoGraphql dimensao,
                             String variante) {

        public String chaveDaEntrada() {
            return operacao + " | " + entrada;
        }

        @Override
        public String toString() {
            return chaveDaEntrada() + " | " + dimensao + " | " + variante;
        }
    }

    /** Alvo do documento de uma operacao. */
    public static final String DOCUMENTO = "<documento>";

    /** Alvo unico do transporte. */
    public static final String CORPO = "<corpo>";

    /** A fronteira de transporte nao pertence a operacao alguma. */
    public static final Operacao TRANSPORTE = new Operacao("<graphql-http>", CORPO);

    private InventarioGraphql() {}

    /** Operacoes de Query e Mutation, exceto as de introspeccao. */
    public static Map<Operacao, GraphQLFieldDefinition> operacoes(GraphQLSchema schema) {
        Map<Operacao, GraphQLFieldDefinition> mapa =
                new TreeMap<>(Comparator.comparing(Operacao::toString));
        acrescentar(mapa, schema.getQueryType(), "Query");
        acrescentar(mapa, schema.getMutationType(), "Mutation");
        acrescentar(mapa, schema.getSubscriptionType(), "Subscription");
        return mapa;
    }

    private static void acrescentar(Map<Operacao, GraphQLFieldDefinition> mapa,
                                    GraphQLObjectType raiz, String nome) {
        if (raiz == null) {
            return;
        }
        for (GraphQLFieldDefinition campo : raiz.getFieldDefinitions()) {
            if (!campo.getName().startsWith("__")) {
                mapa.put(new Operacao(nome, campo.getName()), campo);
            }
        }
    }

    /** Toda operacao servida precisa estar classificada, e toda classificacao precisa existir. */
    public static List<String> verificarClassificacao(Set<Operacao> descobertas,
                                                      Set<Operacao> incluidas,
                                                      Map<Operacao, String> excluidas) {
        List<String> erros = new ArrayList<>();
        for (Operacao operacao : descobertas) {
            if (!incluidas.contains(operacao) && !excluidas.containsKey(operacao)) {
                erros.add("operacao servida sem classificacao: " + operacao);
            }
        }
        for (Operacao operacao : incluidas) {
            if (!descobertas.contains(operacao)) {
                erros.add("operacao classificada e nao servida: " + operacao);
            }
            if (excluidas.containsKey(operacao)) {
                erros.add("operacao incluida e excluida ao mesmo tempo: " + operacao);
            }
        }
        for (Operacao operacao : excluidas.keySet()) {
            if (!descobertas.contains(operacao)) {
                erros.add("operacao excluida e nao servida: " + operacao);
            }
        }
        return erros;
    }

    /**
     * Entradas de uma operacao: cada argumento e, recursivamente, os campos dos objetos de
     * entrada. O documento e o transporte nao entram aqui — sao outras naturezas.
     */
    public static List<Entrada> entradasDe(Operacao operacao, GraphQLFieldDefinition campo) {
        List<Entrada> entradas = new ArrayList<>();
        campo.getArguments().forEach(argumento ->
                acrescentarEntrada(entradas, operacao, argumento.getName(), argumento.getType(),
                        new LinkedHashSet<>()));
        return entradas;
    }

    /** Nome de um argumento real da operacao, para gerar os documentos hostis dela. */
    public static String primeiroArgumento(GraphQLFieldDefinition campo) {
        return campo.getArguments().isEmpty() ? null : campo.getArguments().getFirst().getName();
    }

    private static void acrescentarEntrada(List<Entrada> entradas, Operacao operacao,
                                           String caminho, GraphQLType tipo,
                                           Set<String> visitados) {
        boolean obrigatoria = tipo instanceof GraphQLNonNull;
        GraphQLType nu = desembrulhar(tipo);
        boolean lista = ehLista(tipo);

        if (nu instanceof GraphQLInputObjectType objeto) {
            entradas.add(new Entrada(operacao, caminho, DimensaoGraphql.OBJETO, obrigatoria, lista));
            if (!visitados.add(objeto.getName())) {
                // Objeto de entrada recursivo: um ciclo nao acrescenta entrada nova, e
                // segui-lo nao terminaria.
                return;
            }
            for (GraphQLInputObjectField interno : objeto.getFieldDefinitions()) {
                acrescentarEntrada(entradas, operacao, caminho + "." + interno.getName(),
                        interno.getType(), visitados);
            }
            visitados.remove(objeto.getName());
            return;
        }
        entradas.add(new Entrada(operacao, caminho, dimensaoDe(nu), obrigatoria, lista));
    }

    /** Tipo para dimensao. Escalar sem dimensao normativa reprova. */
    public static DimensaoGraphql dimensaoDe(GraphQLType tipo) {
        GraphQLType nu = desembrulhar(tipo);
        if (nu instanceof GraphQLEnumType) {
            return DimensaoGraphql.ENUM;
        }
        if (nu instanceof GraphQLInputObjectType) {
            return DimensaoGraphql.OBJETO;
        }
        if (nu instanceof GraphQLScalarType escalar) {
            return switch (escalar.getName()) {
                case "ID" -> DimensaoGraphql.UUID;
                case "String" -> DimensaoGraphql.TEXTO;
                case "Int" -> DimensaoGraphql.INTEIRO;
                case "DateTime" -> DimensaoGraphql.DATA;
                default -> throw new IllegalStateException(
                        "escalar sem dimensao normativa: " + escalar.getName()
                                + " - classifique-o na spec antes de servi-lo");
            };
        }
        throw new IllegalStateException("tipo de entrada sem dimensao definida: " + nu);
    }

    private static GraphQLType desembrulhar(GraphQLType tipo) {
        GraphQLType atual = tipo;
        while (atual instanceof GraphQLNonNull nulavel) {
            atual = nulavel.getWrappedType();
        }
        while (atual instanceof GraphQLList lista) {
            atual = lista.getWrappedType();
            while (atual instanceof GraphQLNonNull interno) {
                atual = interno.getWrappedType();
            }
        }
        return atual;
    }

    private static boolean ehLista(GraphQLType tipo) {
        GraphQLType atual = tipo;
        while (atual instanceof GraphQLNonNull nulavel) {
            atual = nulavel.getWrappedType();
        }
        return atual instanceof GraphQLList;
    }

    /** Nome legivel de um tipo, para mensagem de erro. */
    public static String nomeDe(GraphQLType tipo) {
        GraphQLType nu = desembrulhar(tipo);
        return nu instanceof GraphQLNamedType nomeado ? nomeado.getName() : String.valueOf(nu);
    }

    /**
     * O conjunto exigido, somando as tres naturezas.
     *
     * <p>Entradas do schema multiplicam pelas variantes da sua dimensao; cada operacao
     * multiplica pelas variantes de documento; e o transporte entra uma unica vez.
     */
    public static Set<Combinacao> exigidas(List<Entrada> entradas, Set<Operacao> operacoes,
                                           CatalogoDeVariantesGraphql catalogo) {
        Set<Combinacao> combinacoes = new LinkedHashSet<>();
        for (Entrada entrada : entradas) {
            for (CatalogoDeVariantesGraphql.Variante variante
                    : catalogo.aplicaveis(entrada.dimensao())) {
                combinacoes.add(new Combinacao(entrada.operacao(), entrada.caminho(),
                        entrada.dimensao(), variante.nome()));
            }
        }
        for (Operacao operacao : operacoes) {
            for (CatalogoDeVariantesGraphql.Variante variante
                    : catalogo.aplicaveis(DimensaoGraphql.DOCUMENTO)) {
                combinacoes.add(new Combinacao(operacao, DOCUMENTO, DimensaoGraphql.DOCUMENTO,
                        variante.nome()));
            }
        }
        for (CatalogoDeVariantesGraphql.Variante variante
                : catalogo.aplicaveis(DimensaoGraphql.CORPO_HTTP)) {
            combinacoes.add(new Combinacao(TRANSPORTE, CORPO, DimensaoGraphql.CORPO_HTTP,
                    variante.nome()));
        }
        return combinacoes;
    }

    /**
     * Confronta o exigido com o executado, nos dois sentidos e nas tres naturezas.
     *
     * <p>O executado vem de um plano declarado a parte. Se viesse desta descoberta, a
     * comparacao seria verdadeira por construcao e nao provaria nada.
     */
    public static List<String> comparar(List<Entrada> entradas, Set<Operacao> operacoes,
                                        Set<Combinacao> exigidas, Set<Combinacao> executadas,
                                        Set<DimensaoGraphql> dimensoesObrigatorias) {
        List<String> erros = new ArrayList<>();
        if (entradas.isEmpty() || exigidas.isEmpty()) {
            erros.add("inventario vazio: nenhuma combinacao exigida");
            return erros;
        }

        Map<String, Set<Combinacao>> porAlvo = executadas.stream()
                .collect(Collectors.groupingBy(Combinacao::chaveDaEntrada, LinkedHashMap::new,
                        Collectors.toCollection(LinkedHashSet::new)));
        Set<String> semAtaque = new LinkedHashSet<>();

        for (Entrada entrada : entradas) {
            if (exigidas.stream().noneMatch(c -> c.chaveDaEntrada().equals(entrada.chave()))) {
                erros.add("entrada compativel sem variante no catalogo: " + entrada);
            }
            Set<Combinacao> atacadas = porAlvo.getOrDefault(entrada.chave(), Set.of());
            if (atacadas.isEmpty()) {
                semAtaque.add(entrada.chave());
                erros.add("entrada descoberta sem ataque: " + entrada);
                continue;
            }
            Set<DimensaoGraphql> como = atacadas.stream().map(Combinacao::dimensao)
                    .collect(Collectors.toCollection(() -> EnumSet.noneOf(DimensaoGraphql.class)));
            if (!como.equals(EnumSet.of(entrada.dimensao()))) {
                erros.add("dimensao divergente em " + entrada.chave() + ": descoberta "
                        + entrada.dimensao() + ", atacada como " + como);
            }
        }

        for (Operacao operacao : operacoes) {
            String alvo = operacao + " | " + DOCUMENTO;
            if (porAlvo.getOrDefault(alvo, Set.of()).isEmpty()) {
                semAtaque.add(alvo);
                erros.add("operacao sem ataque de documento: " + operacao);
            }
        }

        String alvoDoTransporte = TRANSPORTE + " | " + CORPO;
        if (porAlvo.getOrDefault(alvoDoTransporte, Set.of()).isEmpty()) {
            semAtaque.add(alvoDoTransporte);
            erros.add("fronteira de transporte sem ataque: " + TRANSPORTE);
        }
        for (Combinacao executada : executadas) {
            if (executada.dimensao() == DimensaoGraphql.CORPO_HTTP
                    && !executada.operacao().equals(TRANSPORTE)) {
                erros.add("ataque de transporte atribuido a uma operacao: " + executada
                        + " - o corpo HTTP e fronteira global e nao se multiplica por operacao");
            }
        }

        for (DimensaoGraphql dimensao : dimensoesObrigatorias) {
            if (exigidas.stream().noneMatch(c -> c.dimensao() == dimensao)) {
                erros.add("dimensao sem combinacao: " + dimensao);
            }
        }

        for (Combinacao exigida : exigidas) {
            if (!executadas.contains(exigida) && !semAtaque.contains(exigida.chaveDaEntrada())) {
                erros.add("combinacao exigida e nao executada: " + exigida);
            }
        }
        Set<String> alvosConhecidos = new LinkedHashSet<>();
        entradas.forEach(e -> alvosConhecidos.add(e.chave()));
        operacoes.forEach(o -> alvosConhecidos.add(o + " | " + DOCUMENTO));
        alvosConhecidos.add(alvoDoTransporte);
        for (Combinacao executada : executadas) {
            if (!exigidas.contains(executada)) {
                erros.add(alvosConhecidos.contains(executada.chaveDaEntrada())
                        ? "ataque sem combinacao exigida correspondente: " + executada
                        : "ataque orfao, sem alvo registrado: " + executada);
            }
        }
        return erros;
    }
}
