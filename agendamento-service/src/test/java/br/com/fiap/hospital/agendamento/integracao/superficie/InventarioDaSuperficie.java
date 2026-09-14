package br.com.fiap.hospital.agendamento.integracao.superficie;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.time.temporal.Temporal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ValueConstants;

/**
 * Descobre a superficie HTTP a partir dos handlers efetivamente registrados, e confronta o que
 * foi descoberto com o que foi atacado.
 *
 * <p>A lista de entradas nao e escrita a mao em lugar nenhum. E o inverso da tabela fixa que o
 * {@code EntradasHostisIT} tinha ate o M09: la, uma entrada nova so era atacada se alguem
 * lembrasse de acrescenta-la; aqui, ela aparece por reflexao no dia em que e escrita, e a
 * comparacao com o plano de ataques falha ate ser coberta.
 */
public final class InventarioDaSuperficie {

    /** Um handler registrado: metodos HTTP (vazio = qualquer), padroes de rota e metodo Java. */
    public record Handler(Set<String> metodos, Set<String> rotas, Method metodo) {}

    /** Parametros que o Spring injeta e que nao sao entrada do cliente. */
    private static final Set<Class<?>> INFRAESTRUTURA_DA_REQUISICAO = Set.of(
            jakarta.servlet.http.HttpServletRequest.class,
            jakarta.servlet.http.HttpServletResponse.class,
            java.security.Principal.class,
            java.util.Locale.class);

    private static final Set<Class<?>> INTEIROS = Set.of(
            int.class, Integer.class, long.class, Long.class, short.class, Short.class);

    private InventarioDaSuperficie() {}

    /** Um endpoint por par metodo × rota. Handler sem condicao de metodo vira {@code *}. */
    public static Map<Endpoint, Method> endpoints(Collection<Handler> handlers) {
        Map<Endpoint, Method> mapa = new TreeMap<>(Comparator.comparing(Endpoint::toString));
        for (Handler handler : handlers) {
            Set<String> metodos = handler.metodos().isEmpty() ? Set.of("*") : handler.metodos();
            for (String metodo : metodos) {
                for (String rota : handler.rotas()) {
                    mapa.put(new Endpoint(metodo, rota), handler.metodo());
                }
            }
        }
        return mapa;
    }

    /** Toda rota registrada precisa estar classificada, e toda classificacao precisa existir. */
    public static List<String> verificarClassificacao(Set<Endpoint> descobertos,
                                                      Set<Endpoint> incluidos,
                                                      Map<Endpoint, String> excluidos) {
        List<String> erros = new ArrayList<>();
        for (Endpoint endpoint : descobertos) {
            if (!incluidos.contains(endpoint) && !excluidos.containsKey(endpoint)) {
                erros.add("endpoint descoberto sem classificacao: " + endpoint);
            }
        }
        for (Endpoint endpoint : incluidos) {
            if (!descobertos.contains(endpoint)) {
                erros.add("endpoint classificado e nao registrado: " + endpoint);
            }
            if (excluidos.containsKey(endpoint)) {
                erros.add("endpoint incluido e excluido ao mesmo tempo: " + endpoint);
            }
        }
        for (Endpoint endpoint : excluidos.keySet()) {
            if (!descobertos.contains(endpoint)) {
                erros.add("endpoint excluido e nao registrado: " + endpoint);
            }
        }
        return erros;
    }

    /** As entradas de um handler. Parametro que nao se consegue classificar reprova. */
    public static List<Entrada> entradasDe(Endpoint endpoint, Method metodo) {
        List<Entrada> entradas = new ArrayList<>();
        for (Parameter parametro : metodo.getParameters()) {
            PathVariable caminho = parametro.getAnnotation(PathVariable.class);
            RequestParam consulta = parametro.getAnnotation(RequestParam.class);
            RequestBody corpo = parametro.getAnnotation(RequestBody.class);
            if (caminho != null) {
                entradas.add(new Entrada(endpoint, Localizacao.CAMINHO,
                        nome(caminho.value(), caminho.name(), parametro),
                        dimensaoDe(parametro.getType(), parametro.getParameterizedType()),
                        caminho.required(), null));
            } else if (consulta != null) {
                boolean obrigatoria = consulta.required()
                        && ValueConstants.DEFAULT_NONE.equals(consulta.defaultValue());
                entradas.add(new Entrada(endpoint, Localizacao.CONSULTA,
                        nome(consulta.value(), consulta.name(), parametro),
                        dimensaoDe(parametro.getType(), parametro.getParameterizedType()),
                        obrigatoria, null));
            } else if (corpo != null) {
                entradas.add(new Entrada(endpoint, Localizacao.CORPO, "corpo", Dimensao.CORPO,
                        corpo.required(), null));
                if (!parametro.getType().isRecord()) {
                    throw new IllegalStateException("corpo sem estrutura inspecionavel em "
                            + endpoint + ": " + parametro.getType().getName());
                }
                camposDe(endpoint, parametro.getType(), "", entradas);
            } else if (!INFRAESTRUTURA_DA_REQUISICAO.contains(parametro.getType())) {
                throw new IllegalStateException("entrada sem classificacao em " + endpoint
                        + ": parametro '" + parametro.getName() + "' do tipo "
                        + parametro.getType().getName());
            }
        }
        return entradas;
    }

    private static void camposDe(Endpoint endpoint, Class<?> registro, String prefixo,
                                 List<Entrada> entradas) {
        for (RecordComponent componente : registro.getRecordComponents()) {
            String nome = prefixo + componente.getName();
            if (componente.getType().isRecord()) {
                camposDe(endpoint, componente.getType(), nome + ".", entradas);
                continue;
            }
            Method acessor = componente.getAccessor();
            Size tamanho = acessor.getAnnotation(Size.class);
            boolean obrigatoria = anotado(acessor, NotNull.class)
                    || anotado(acessor, NotBlank.class) || anotado(acessor, NotEmpty.class);
            entradas.add(new Entrada(endpoint, Localizacao.CAMPO, nome,
                    dimensaoDe(componente.getType(), componente.getGenericType()), obrigatoria,
                    tamanho == null ? null : tamanho.max()));
        }
    }

    /** Tipo → dimensao. Tipo sem dimensao definida reprova, em vez de ficar sem ataque. */
    public static Dimensao dimensaoDe(Class<?> tipo, Type generico) {
        if (tipo == java.util.UUID.class) {
            return Dimensao.UUID;
        }
        if (tipo.isEnum()) {
            return Dimensao.ENUM;
        }
        if (Collection.class.isAssignableFrom(tipo)
                && generico instanceof ParameterizedType parametrizado
                && parametrizado.getActualTypeArguments()[0] instanceof Class<?> elemento) {
            return dimensaoDe(elemento, elemento);
        }
        if (Temporal.class.isAssignableFrom(tipo)) {
            return Dimensao.DATA;
        }
        if (INTEIROS.contains(tipo)) {
            return Dimensao.INTEIRO;
        }
        if (tipo == String.class) {
            return Dimensao.TEXTO;
        }
        throw new IllegalStateException("tipo sem dimensao definida: " + generico.getTypeName());
    }

    /** O conjunto exigido: cada entrada descoberta × cada variante aplicavel do catalogo. */
    public static Set<Combinacao> exigidas(List<Entrada> entradas, CatalogoDeVariantes catalogo) {
        Set<Combinacao> combinacoes = new LinkedHashSet<>();
        for (Entrada entrada : entradas) {
            for (CatalogoDeVariantes.Variante variante
                    : catalogo.aplicaveis(entrada.dimensao(), entrada.localizacao())) {
                combinacoes.add(new Combinacao(entrada.endpoint(), entrada.localizacao(),
                        entrada.nome(), entrada.dimensao(), variante.nome()));
            }
        }
        return combinacoes;
    }

    /**
     * Confronta o exigido com o executado, nos dois sentidos.
     *
     * <p>O executado vem de um plano declarado a parte, e nao desta descoberta: se viesse
     * daqui, a comparacao seria verdadeira por construcao e nao provaria nada.
     */
    public static List<String> comparar(List<Entrada> entradas, Set<Combinacao> exigidas,
                                        Set<Combinacao> executadas,
                                        Set<Dimensao> dimensoesObrigatorias) {
        List<String> erros = new ArrayList<>();
        if (entradas.isEmpty() || exigidas.isEmpty()) {
            erros.add("inventario vazio: nenhuma combinacao exigida");
            return erros;
        }

        Map<String, Set<Combinacao>> executadasPorEntrada = executadas.stream()
                .collect(Collectors.groupingBy(Combinacao::chaveDaEntrada, LinkedHashMap::new,
                        Collectors.toCollection(LinkedHashSet::new)));
        Set<String> entradasSemAtaque = new LinkedHashSet<>();
        for (Entrada entrada : entradas) {
            boolean temVariante = exigidas.stream()
                    .anyMatch(c -> c.chaveDaEntrada().equals(entrada.chave()));
            if (!temVariante) {
                erros.add("entrada compativel sem variante no catalogo: " + entrada);
            }
            Set<Combinacao> atacadas = executadasPorEntrada.getOrDefault(entrada.chave(), Set.of());
            if (atacadas.isEmpty()) {
                entradasSemAtaque.add(entrada.chave());
                erros.add("entrada descoberta sem ataque: " + entrada);
                continue;
            }
            Set<Dimensao> atacadaComo = atacadas.stream().map(Combinacao::dimensao)
                    .collect(Collectors.toCollection(() -> EnumSet.noneOf(Dimensao.class)));
            if (!atacadaComo.equals(EnumSet.of(entrada.dimensao()))) {
                erros.add("dimensao divergente em " + entrada.chave() + ": descoberta "
                        + entrada.dimensao() + ", atacada como " + atacadaComo);
            }
        }

        for (Dimensao dimensao : dimensoesObrigatorias) {
            if (exigidas.stream().noneMatch(c -> c.dimensao() == dimensao)) {
                erros.add("dimensao sem combinacao: " + dimensao);
            }
        }

        for (Combinacao exigida : exigidas) {
            if (!executadas.contains(exigida) && !entradasSemAtaque.contains(exigida.chaveDaEntrada())) {
                erros.add("combinacao exigida e nao executada: " + exigida);
            }
        }
        Set<String> chavesDescobertas = entradas.stream().map(Entrada::chave)
                .collect(Collectors.toSet());
        for (Combinacao executada : executadas) {
            if (!exigidas.contains(executada)) {
                erros.add(chavesDescobertas.contains(executada.chaveDaEntrada())
                        ? "ataque sem combinacao exigida correspondente: " + executada
                        : "ataque orfao, sem entrada registrada: " + executada);
            }
        }
        return erros;
    }

    private static String nome(String valor, String nome, Parameter parametro) {
        if (!valor.isEmpty()) {
            return valor;
        }
        return nome.isEmpty() ? parametro.getName() : nome;
    }

    private static boolean anotado(Method metodo, Class<? extends Annotation> anotacao) {
        return metodo.isAnnotationPresent(anotacao);
    }
}
