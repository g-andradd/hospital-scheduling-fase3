package br.com.fiap.hospital.agendamento.integracao.superficie;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * O que cada dimensao significa: os valores hostis aplicados a toda entrada daquela dimensao.
 *
 * <p>O catalogo pode crescer. Ele nao pode encolher abaixo dos {@link #MINIMOS}: uma variante
 * retirada daqui sairia ao mesmo tempo do conjunto exigido e do executado, e a comparacao nao
 * perceberia. A asserção dos minimos e o que fecha essa brecha.
 */
public final class CatalogoDeVariantes {

    /** O que uma variante precisa para se renderizar: a entrada e o corpo valido do controle. */
    public record Contexto(Entrada entrada, ObjectNode corpoValido) {}

    /**
     * Um valor hostil para uma dimensao numa localizacao.
     *
     * <p>A renderizacao depende da localizacao: segmento de caminho ja codificado, valor de
     * consulta cru (quem envia codifica), literal JSON de campo, ou o corpo inteiro — nulo
     * significa requisicao sem corpo.
     *
     * @param recusavelPeloServidor a recusa pode vir do conector ou do firewall, antes da
     *     aplicacao, sem Problem Detail
     */
    public record Variante(String nome, Dimensao dimensao, Localizacao localizacao,
                           Function<Contexto, String> valor, String contentType,
                           boolean recusavelPeloServidor) {

        public String renderizar(Contexto contexto) {
            return valor.apply(contexto);
        }

        @Override
        public String toString() {
            return dimensao + ":" + localizacao + ":" + nome;
        }
    }

    /** As bordas de data e hora que a tabela do M03 ja atacava, mantidas integralmente. */
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
    public static final Map<Dimensao, Set<String>> MINIMOS = minimos();

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final List<Variante> variantes;

    public CatalogoDeVariantes(List<Variante> variantes) {
        this.variantes = List.copyOf(variantes);
    }

    public List<Variante> todas() {
        return variantes;
    }

    public List<Variante> aplicaveis(Dimensao dimensao, Localizacao localizacao) {
        return variantes.stream()
                .filter(v -> v.dimensao() == dimensao && v.localizacao() == localizacao)
                .toList();
    }

    public Variante variante(Dimensao dimensao, Localizacao localizacao, String nome) {
        return aplicaveis(dimensao, localizacao).stream()
                .filter(v -> v.nome().equals(nome))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "variante inexistente: " + dimensao + ":" + localizacao + ":" + nome));
    }

    /** Uma copia sem as variantes daquele nome naquela dimensao, para provar a sensibilidade. */
    public CatalogoDeVariantes sem(Dimensao dimensao, String nome) {
        return new CatalogoDeVariantes(variantes.stream()
                .filter(v -> !(v.dimensao() == dimensao && v.nome().equals(nome)))
                .toList());
    }

    /** Nomes minimos ausentes, no formato {@code DIMENSAO:nome}. */
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

    /** Pares dimensao × localizacao sem variante — uma entrada ali ficaria sem ataque. */
    public List<String> locaisSemVariantes() {
        List<String> vazios = new ArrayList<>();
        for (Dimensao dimensao : Dimensao.values()) {
            List<Localizacao> locais = dimensao == Dimensao.CORPO
                    ? List.of(Localizacao.CORPO)
                    : List.of(Localizacao.CAMINHO, Localizacao.CONSULTA, Localizacao.CAMPO);
            for (Localizacao local : locais) {
                if (aplicaveis(dimensao, local).isEmpty()) {
                    vazios.add(dimensao + ":" + local);
                }
            }
        }
        return vazios;
    }

    // ------------------------------------------------------------------ catalogo padrao

    public static CatalogoDeVariantes padrao() {
        List<Variante> v = new ArrayList<>();

        // UUID
        escalar(v, Dimensao.UUID, "malformado", "nao-e-um-uuid");
        escalar(v, Dimensao.UUID, "inexistente", "11111111-1111-1111-1111-111111111111");
        escalar(v, Dimensao.UUID, "nil", "00000000-0000-0000-0000-000000000000");
        escalar(v, Dimensao.UUID, "vazio", "");
        bruto(v, Dimensao.UUID, "caminho-estranho-zero", "0", false);
        bruto(v, Dimensao.UUID, "caminho-estranho-espaco", "%20", false);
        bruto(v, Dimensao.UUID, "caminho-estranho-travessia", "../../etc/passwd", true);
        bruto(v, Dimensao.UUID, "caminho-estranho-travessia-codificada", "..%2F..%2Fetc%2Fpasswd", true);
        campo(v, Dimensao.UUID, "nulo", "null");
        campo(v, Dimensao.UUID, "tipo-incompativel", "42");

        // ENUM
        escalar(v, Dimensao.ENUM, "inexistente", "NAO_EXISTE");
        escalar(v, Dimensao.ENUM, "vazio", "");
        escalar(v, Dimensao.ENUM, "caixa-divergente", "agendada");
        escalar(v, Dimensao.ENUM, "ordinal", "0");
        campo(v, Dimensao.ENUM, "nulo", "null");
        campo(v, Dimensao.ENUM, "tipo-incompativel", "[]");

        // DATA
        for (int i = 0; i < DATAS_HOSTIS.size(); i++) {
            escalar(v, Dimensao.DATA, NOMES_DAS_DATAS.get(i), DATAS_HOSTIS.get(i));
        }
        escalar(v, Dimensao.DATA, "sem-offset", "2026-09-10T10:00:00");
        escalar(v, Dimensao.DATA, "formato-local", "10/09/2026");
        escalar(v, Dimensao.DATA, "texto-invalido", "data-invalida");
        escalar(v, Dimensao.DATA, "texto-nao-data", "nao-e-data");
        campo(v, Dimensao.DATA, "timestamp-numerico", "1788000000000");
        campo(v, Dimensao.DATA, "nulo", "null");

        // INTEIRO: literal numerico no JSON, texto no caminho e na consulta
        numero(v, "zero", "0");
        numero(v, "negativo", "-1");
        numero(v, "int-max", "2147483647");
        numero(v, "int-max-mais-um", "2147483648");
        numero(v, "muito-grande", "9999999999999");
        numero(v, "dez-digitos", "9999999999");
        numero(v, "fracao", "1.5");
        escalar(v, Dimensao.INTEIRO, "texto", "abc");
        escalar(v, Dimensao.INTEIRO, "vazio", "");
        campo(v, Dimensao.INTEIRO, "tipo-incompativel", json("texto"));
        campo(v, Dimensao.INTEIRO, "nulo", "null");

        // TEXTO
        escalar(v, Dimensao.TEXTO, "vazio", "");
        escalar(v, Dimensao.TEXTO, "espacos", "   ");
        escalar(v, Dimensao.TEXTO, "controle-e-nul", "\0\1\37");
        // Nas bordas o trim esconde o caractere; no meio, ele chega ao banco e ao evento.
        escalar(v, Dimensao.TEXTO, "controle-no-meio", "an\0tes");
        escalar(v, Dimensao.TEXTO, "unicode-4-bytes", "😀");
        escalar(v, Dimensao.TEXTO, "sql", "' OR '1'='1' --");
        escalar(v, Dimensao.TEXTO, "html", "<script>alert(1)</script>");
        for (Localizacao local : List.of(Localizacao.CAMINHO, Localizacao.CONSULTA, Localizacao.CAMPO)) {
            v.add(new Variante("acima-do-limite", Dimensao.TEXTO, local,
                    c -> renderizar(local, "x".repeat(limiteDe(c) + 1)), null, false));
        }
        campo(v, Dimensao.TEXTO, "nulo", "null");
        campo(v, Dimensao.TEXTO, "tipo-incompativel", "42");

        // CORPO
        corpo(v, "json-malformado", c -> "{\"pacienteId\": ", null);
        corpo(v, "ausente", c -> null, null);
        corpo(v, "vazio", c -> "", null);
        corpo(v, "somente-espacos", c -> "   ", null);
        corpo(v, "null", c -> "null", null);
        corpo(v, "array", c -> "[]", null);
        corpo(v, "escalar", c -> "42", null);
        corpo(v, "objeto-vazio", c -> "{}", null);
        corpo(v, "campos-tipo-incompativel", CatalogoDeVariantes::camposComTipoIncompativel, null);
        corpo(v, "text-plain", c -> c.corpoValido().toString(), "text/plain");
        corpo(v, "application-xml", c -> c.corpoValido().toString(), "application/xml");
        corpo(v, "campo-desconhecido",
                c -> c.corpoValido().deepCopy().put("campoDesconhecido", "x").toString(), null);
        corpo(v, "chave-duplicada", CatalogoDeVariantes::chaveDuplicada, null);
        corpo(v, "conteudo-apos-json", c -> c.corpoValido().toString() + " {}", null);

        return new CatalogoDeVariantes(v);
    }

    private static Map<Dimensao, Set<String>> minimos() {
        Map<Dimensao, Set<String>> minimos = new EnumMap<>(Dimensao.class);
        minimos.put(Dimensao.UUID, Set.of("malformado", "inexistente", "nil", "vazio",
                "caminho-estranho-zero", "caminho-estranho-espaco", "caminho-estranho-travessia",
                "nulo", "tipo-incompativel"));
        minimos.put(Dimensao.ENUM, Set.of("inexistente", "vazio", "caixa-divergente", "ordinal",
                "nulo", "tipo-incompativel"));
        Set<String> datas = new LinkedHashSet<>(NOMES_DAS_DATAS);
        datas.addAll(List.of("sem-offset", "formato-local", "timestamp-numerico", "nulo"));
        minimos.put(Dimensao.DATA, Set.copyOf(datas));
        minimos.put(Dimensao.INTEIRO, Set.of("zero", "negativo", "int-max", "int-max-mais-um",
                "muito-grande", "fracao", "texto", "vazio", "nulo", "tipo-incompativel"));
        minimos.put(Dimensao.TEXTO, Set.of("vazio", "espacos", "acima-do-limite",
                "controle-e-nul", "controle-no-meio", "unicode-4-bytes", "sql", "html", "nulo",
                "tipo-incompativel"));
        minimos.put(Dimensao.CORPO, Set.of("json-malformado", "ausente", "null", "array",
                "escalar", "campos-tipo-incompativel", "text-plain", "application-xml",
                "campo-desconhecido", "chave-duplicada", "conteudo-apos-json"));
        return Map.copyOf(minimos);
    }

    // ------------------------------------------------------------------ construtores

    /** O mesmo valor nas tres localizacoes escalares, renderizado para cada uma. */
    private static void escalar(List<Variante> v, Dimensao dimensao, String nome, String valor) {
        boolean caminhoVazio = valor.isEmpty();
        v.add(new Variante(nome, dimensao, Localizacao.CAMINHO,
                c -> renderizar(Localizacao.CAMINHO, valor), null, caminhoVazio));
        v.add(new Variante(nome, dimensao, Localizacao.CONSULTA, c -> valor, null, false));
        v.add(new Variante(nome, dimensao, Localizacao.CAMPO, c -> json(valor), null, false));
    }

    /** Literal numerico: sem aspas no JSON. */
    private static void numero(List<Variante> v, String nome, String literal) {
        v.add(new Variante(nome, Dimensao.INTEIRO, Localizacao.CAMINHO, c -> literal, null, false));
        v.add(new Variante(nome, Dimensao.INTEIRO, Localizacao.CONSULTA, c -> literal, null, false));
        v.add(new Variante(nome, Dimensao.INTEIRO, Localizacao.CAMPO, c -> literal, null, false));
    }

    /** Segmento de caminho enviado exatamente como escrito, sem codificacao adicional. */
    private static void bruto(List<Variante> v, Dimensao dimensao, String nome, String segmento,
                              boolean recusavelPeloServidor) {
        v.add(new Variante(nome, dimensao, Localizacao.CAMINHO, c -> segmento, null,
                recusavelPeloServidor));
    }

    /** Literal JSON, so aplicavel a campo do corpo. */
    private static void campo(List<Variante> v, Dimensao dimensao, String nome, String literal) {
        v.add(new Variante(nome, dimensao, Localizacao.CAMPO, c -> literal, null, false));
    }

    private static void corpo(List<Variante> v, String nome, Function<Contexto, String> corpo,
                              String contentType) {
        v.add(new Variante(nome, Dimensao.CORPO, Localizacao.CORPO, corpo, contentType, false));
    }

    private static String renderizar(Localizacao local, String valor) {
        return switch (local) {
            case CAMINHO -> URLEncoder.encode(valor, StandardCharsets.UTF_8).replace("+", "%20");
            case CAMPO -> json(valor);
            default -> valor;
        };
    }

    private static int limiteDe(Contexto contexto) {
        Integer limite = contexto.entrada() == null ? null : contexto.entrada().limiteDeTexto();
        return limite == null ? 10_000 : limite;
    }

    private static String json(String texto) {
        return TextNode.valueOf(texto).toString();
    }

    private static String camposComTipoIncompativel(Contexto contexto) {
        ObjectNode corpo = contexto.corpoValido().deepCopy();
        List<String> nomes = new ArrayList<>();
        corpo.fieldNames().forEachRemaining(nomes::add);
        nomes.forEach(nome -> corpo.set(nome, MAPPER.createArrayNode()));
        return corpo.toString();
    }

    private static String chaveDuplicada(Contexto contexto) {
        ObjectNode corpo = contexto.corpoValido();
        Iterator<String> nomes = corpo.fieldNames();
        if (!nomes.hasNext()) {
            return "{\"a\":1,\"a\":2}";
        }
        String primeiro = nomes.next();
        String texto = corpo.toString();
        return "{" + json(primeiro) + ":\"duplicado\"," + texto.substring(1);
    }
}
