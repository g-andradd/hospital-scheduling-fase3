package br.com.fiap.hospital.historico.integracao.superficie;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * O que cada dimensao do schema significa: os valores hostis aplicados a toda entrada dela.
 *
 * <p>O catalogo pode crescer. Ele nao pode encolher abaixo dos {@link #MINIMOS}: uma
 * variante retirada daqui sairia ao mesmo tempo do conjunto exigido e do executado, e a
 * comparacao nao perceberia. A assercao dos minimos e o que fecha essa brecha.
 *
 * <p>As variantes de DOCUMENTO nao guardam texto fixo: elas pedem o documento ao
 * {@link DocumentosHostis}, que o gera a partir da operacao-alvo e de um argumento real
 * dela. As de CORPO_HTTP nao dependem de operacao alguma — sao a fronteira de transporte.
 */
public final class CatalogoDeVariantesGraphql {

    /** O que a variante precisa saber para se renderizar. */
    public record Contexto(InventarioGraphql.Operacao operacao, String argumentoReal,
                           InventarioGraphql.Entrada entrada,
                           Map<String, Object> variaveisValidas) {}

    /**
     * Um valor hostil.
     *
     * <p>Nas dimensoes vinculadas, o valor e o que entra na variavel. Em DOCUMENTO e
     * CORPO_HTTP, e o texto do documento ou o corpo HTTP cru — nulo significa "sem corpo".
     */
    public record Variante(String nome, DimensaoGraphql dimensao,
                           Function<Contexto, Object> valor, String contentType) {

        public Object renderizar(Contexto contexto) {
            return valor.apply(contexto);
        }

        @Override
        public String toString() {
            return dimensao + ":" + nome;
        }
    }

    /** As mesmas bordas de data e hora da varredura REST, pelo mesmo motivo. */
    public static final List<String> DATAS_HOSTIS = List.of(
            "+999999999-12-31T23:59:59.999999999-18:00",
            "-999999999-01-01T00:00:00+18:00",
            "0000-01-01T00:00:00Z",
            "999999999-12-31T23:59:59Z",
            "9999-12-31T23:59:59Z",
            "2020-01-01T10:00:00-03:00",
            "2029-12-31T10:00:00-03:00",
            "2026-02-30T10:00:00-03:00",
            "2026-13-01T10:00:00-03:00",
            "2026-09-10T25:00:00-03:00",
            "2026-09-10T10:00:00+99:00",
            "");

    private static final List<String> NOMES_DAS_DATAS = List.of(
            "borda-maxima", "borda-minima", "ano-zero", "ano-maximo", "ano-9999", "passado",
            "alem-do-horizonte", "dia-inexistente", "mes-inexistente", "hora-inexistente",
            "offset-impossivel", "vazio");

    /** O minimo que cada dimensao precisa conter. Retirar um nome daqui e decisao de spec. */
    public static final Map<DimensaoGraphql, Set<String>> MINIMOS = minimos();

    private final List<Variante> variantes;

    public CatalogoDeVariantesGraphql(List<Variante> variantes) {
        this.variantes = List.copyOf(variantes);
    }

    public List<Variante> todas() {
        return variantes;
    }

    public List<Variante> aplicaveis(DimensaoGraphql dimensao) {
        return variantes.stream().filter(v -> v.dimensao() == dimensao).toList();
    }

    /** Uma copia sem aquela variante, para provar a sensibilidade da assercao dos minimos. */
    public CatalogoDeVariantesGraphql sem(DimensaoGraphql dimensao, String nome) {
        return new CatalogoDeVariantesGraphql(variantes.stream()
                .filter(v -> !(v.dimensao() == dimensao && v.nome().equals(nome)))
                .toList());
    }

    /** Nomes minimos ausentes, no formato DIMENSAO:nome. */
    public List<String> minimosAusentes() {
        List<String> ausentes = new ArrayList<>();
        MINIMOS.forEach((dimensao, nomes) -> {
            Set<String> presentes = variantes.stream()
                    .filter(v -> v.dimensao() == dimensao)
                    .map(Variante::nome)
                    .collect(Collectors.toSet());
            nomes.stream().filter(n -> !presentes.contains(n)).sorted()
                    .forEach(n -> ausentes.add(dimensao + ":" + n));
        });
        return ausentes;
    }

    /** Dimensoes sem variante alguma: uma entrada dali ficaria sem ataque. */
    public List<String> dimensoesSemVariantes() {
        return Arrays.stream(DimensaoGraphql.values())
                .filter(d -> aplicaveis(d).isEmpty())
                .map(Enum::name)
                .toList();
    }

    public static CatalogoDeVariantesGraphql padrao() {
        List<Variante> v = new ArrayList<>();

        escalar(v, DimensaoGraphql.UUID, "malformado", "nao-e-um-uuid");
        escalar(v, DimensaoGraphql.UUID, "inexistente", "11111111-1111-1111-1111-111111111111");
        escalar(v, DimensaoGraphql.UUID, "nil", "00000000-0000-0000-0000-000000000000");
        escalar(v, DimensaoGraphql.UUID, "vazio", "");
        escalar(v, DimensaoGraphql.UUID, "numero-json", 42);
        escalar(v, DimensaoGraphql.UUID, "lista-no-lugar-de-escalar", List.of("a", "b"));
        escalar(v, DimensaoGraphql.UUID, "nulo-em-obrigatorio", null);

        escalar(v, DimensaoGraphql.ENUM, "inexistente", "NAO_EXISTE");
        escalar(v, DimensaoGraphql.ENUM, "caixa-divergente", "agendada");
        escalar(v, DimensaoGraphql.ENUM, "vazio", "");
        escalar(v, DimensaoGraphql.ENUM, "numero", 0);
        escalar(v, DimensaoGraphql.ENUM, "elemento-nulo-em-lista", nulosEmLista());

        for (int i = 0; i < DATAS_HOSTIS.size(); i++) {
            escalar(v, DimensaoGraphql.DATA, NOMES_DAS_DATAS.get(i), DATAS_HOSTIS.get(i));
        }
        escalar(v, DimensaoGraphql.DATA, "sem-offset", "2026-09-10T10:00:00");
        escalar(v, DimensaoGraphql.DATA, "formato-local", "10/09/2026");
        escalar(v, DimensaoGraphql.DATA, "numero-json", 1788000000000L);

        escalar(v, DimensaoGraphql.TEXTO, "vazio", "");
        escalar(v, DimensaoGraphql.TEXTO, "espacos", "   ");
        escalar(v, DimensaoGraphql.TEXTO, "dez-mil-caracteres", "x".repeat(10000));
        escalar(v, DimensaoGraphql.TEXTO, "controle-e-nul", "\0\1\37");
        escalar(v, DimensaoGraphql.TEXTO, "controle-no-meio", "an\0tes");
        escalar(v, DimensaoGraphql.TEXTO, "unicode-4-bytes", "\uD83D\uDE00");
        escalar(v, DimensaoGraphql.TEXTO, "sql", "' OR '1'='1' --");
        escalar(v, DimensaoGraphql.TEXTO, "html", "<script>alert(1)</script>");
        escalar(v, DimensaoGraphql.TEXTO, "numero-no-lugar", 42);
        escalar(v, DimensaoGraphql.TEXTO, "objeto-no-lugar", Map.of("a", 1));

        escalar(v, DimensaoGraphql.INTEIRO, "zero", 0);
        escalar(v, DimensaoGraphql.INTEIRO, "negativo", -1);
        escalar(v, DimensaoGraphql.INTEIRO, "int-max", Integer.MAX_VALUE);
        escalar(v, DimensaoGraphql.INTEIRO, "int-max-mais-um", 2147483648L);
        escalar(v, DimensaoGraphql.INTEIRO, "muito-grande", 9999999999999L);
        escalar(v, DimensaoGraphql.INTEIRO, "fracao", 1.5);
        escalar(v, DimensaoGraphql.INTEIRO, "texto", "abc");
        escalar(v, DimensaoGraphql.INTEIRO, "nulo", null);
        escalar(v, DimensaoGraphql.INTEIRO, "tipo-incompativel", List.of(1));

        escalar(v, DimensaoGraphql.OBJETO, "escalar", "isto-nao-e-objeto");
        escalar(v, DimensaoGraphql.OBJETO, "lista", List.of(Map.of()));
        escalar(v, DimensaoGraphql.OBJETO, "objeto-vazio", Map.of());
        escalar(v, DimensaoGraphql.OBJETO, "nulo", null);
        v.add(new Variante("campo-desconhecido", DimensaoGraphql.OBJETO,
                CatalogoDeVariantesGraphql::comCampoDesconhecido, null));
        v.add(new Variante("obrigatorio-ausente", DimensaoGraphql.OBJETO, c -> Map.of(), null));

        // DOCUMENTO: o texto vem do gerador, a partir da operacao-alvo e de um argumento
        // real dela. Nenhum nome de operacao fixo entra aqui.
        DocumentosHostis.paraOperacao("query", "exemplo", "arg").keySet()
                .forEach(nome -> v.add(new Variante(nome, DimensaoGraphql.DOCUMENTO,
                        c -> DocumentosHostis.paraOperacao(raizDe(c.operacao()),
                                c.operacao().nome(), c.argumentoReal()).get(nome), null)));

        // CORPO_HTTP: fronteira de transporte, uma vez so, sem relacao com operacao.
        corpo(v, "json-malformado", c -> "{\"query\": ", null);
        corpo(v, "ausente", c -> null, null);
        corpo(v, "null", c -> "null", null);
        corpo(v, "array", c -> "[]", null);
        corpo(v, "escalar", c -> "42", null);
        corpo(v, "text-plain", c -> "{\"query\":\"query { __typename }\"}", "text/plain");
        corpo(v, "query-ausente", c -> "{}", null);
        corpo(v, "query-nao-string", c -> "{\"query\": 42}", null);
        corpo(v, "variables-nao-objeto",
                c -> "{\"query\":\"query { __typename }\",\"variables\": 42}", null);
        corpo(v, "operationName-inexistente",
                c -> "{\"query\":\"query A { __typename }\",\"operationName\":\"NaoExiste\"}", null);

        return new CatalogoDeVariantesGraphql(v);
    }

    private static Map<DimensaoGraphql, Set<String>> minimos() {
        Map<DimensaoGraphql, Set<String>> minimos = new EnumMap<>(DimensaoGraphql.class);
        minimos.put(DimensaoGraphql.UUID, Set.of("malformado", "inexistente", "nil", "vazio",
                "numero-json", "lista-no-lugar-de-escalar", "nulo-em-obrigatorio"));
        minimos.put(DimensaoGraphql.ENUM, Set.of("inexistente", "caixa-divergente", "vazio",
                "numero", "elemento-nulo-em-lista"));
        Set<String> datas = new LinkedHashSet<>(NOMES_DAS_DATAS);
        datas.addAll(List.of("sem-offset", "numero-json"));
        minimos.put(DimensaoGraphql.DATA, Set.copyOf(datas));
        minimos.put(DimensaoGraphql.TEXTO, Set.of("vazio", "espacos", "dez-mil-caracteres",
                "controle-e-nul", "controle-no-meio", "unicode-4-bytes", "sql", "html",
                "numero-no-lugar", "objeto-no-lugar"));
        minimos.put(DimensaoGraphql.INTEIRO, Set.of("zero", "negativo", "int-max",
                "int-max-mais-um", "muito-grande", "fracao", "texto", "nulo",
                "tipo-incompativel"));
        minimos.put(DimensaoGraphql.OBJETO, Set.of("escalar", "lista", "campo-desconhecido",
                "obrigatorio-ausente", "objeto-vazio", "nulo"));
        minimos.put(DimensaoGraphql.DOCUMENTO, Set.of("sintaxe-invalida", "vazio",
                "campo-inexistente", "argumento-desconhecido", "literal-incompativel",
                "variavel-nao-declarada", "variavel-com-tipo-divergente",
                "duas-operacoes-sem-operationName"));
        minimos.put(DimensaoGraphql.CORPO_HTTP, Set.of("json-malformado", "ausente", "null",
                "array", "escalar", "text-plain", "query-ausente", "query-nao-string",
                "variables-nao-objeto", "operationName-inexistente"));
        return Map.copyOf(minimos);
    }

    /** Query e Mutation viram as palavras-chave do documento. */
    public static String raizDe(InventarioGraphql.Operacao operacao) {
        return operacao.raiz().toLowerCase();
    }

    private static void escalar(List<Variante> v, DimensaoGraphql dimensao, String nome,
                                Object valor) {
        v.add(new Variante(nome, dimensao, c -> valor, null));
    }

    private static void corpo(List<Variante> v, String nome, Function<Contexto, Object> corpo,
                              String contentType) {
        v.add(new Variante(nome, DimensaoGraphql.CORPO_HTTP, corpo, contentType));
    }

    /** Lista com elemento nulo: o schema declara [StatusConsulta!]. */
    private static List<Object> nulosEmLista() {
        List<Object> lista = new ArrayList<>();
        lista.add(null);
        return lista;
    }

    @SuppressWarnings("unchecked")
    private static Object comCampoDesconhecido(Contexto contexto) {
        Object valor = contexto.variaveisValidas() == null
                ? null
                : contexto.variaveisValidas().get(raizDoCaminho(contexto));
        Map<String, Object> objeto = valor instanceof Map<?, ?> mapa
                ? new LinkedHashMap<>((Map<String, Object>) mapa)
                : new LinkedHashMap<>();
        objeto.put("campoDesconhecido", "x");
        return objeto;
    }

    private static String raizDoCaminho(Contexto contexto) {
        String caminho = contexto.entrada().caminho();
        int ponto = caminho.indexOf('.');
        return ponto < 0 ? caminho : caminho.substring(0, ponto);
    }
}
