package br.com.fiap.hospital.notificacao.integracao;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * A superficie HTTP da notificacao e um endpoint so, e sem entrada de negocio.
 *
 * <p>O servico existe para consumir mensagens; o unico caminho HTTP e o disparo manual do
 * lembrete, que nao recebe parametro algum. Essa e a propriedade que este teste protege —
 * e ela nao se protege por convencao: um {@code @RequestParam} acrescentado ao handler, ou
 * um controlador novo, entraria no ar sem ataque hostil correspondente, porque nao existe
 * varredura de valores aqui. Aqui a varredura e da <b>forma</b> da superficie.
 *
 * <p>A descoberta vem dos handlers efetivamente registrados, e a classificacao e
 * bidirecional: caminho servido sem classificacao reprova, e classificacao sem caminho
 * servido tambem.
 */
@DisplayName("Superficie HTTP da notificacao")
class SuperficieHttpNotificacaoIT extends NotificacaoITBase {

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping mapeamentos;

    /** Metodo HTTP e rota, como o Spring MVC os registra. {@code *} e qualquer metodo. */
    record Endpoint(String metodo, String rota) {
        @Override
        public String toString() {
            return metodo + " " + rota;
        }
    }

    /** O unico endpoint de negocio da notificacao. */
    private static final Endpoint DISPARO =
            new Endpoint("POST", "/internal/lembretes/executar");

    /** Servidos e deliberadamente fora da varredura de valores, cada um com o motivo. */
    private static final Map<Endpoint, String> EXCLUIDOS = Map.of(
            new Endpoint("*", "/error"),
            "tratamento de erro do container, sem entrada de negocio");

    @Test
    @DisplayName("Scenario: a superfície HTTP é exatamente o disparo do lembrete")
    void superficieEExatamenteODisparoDoLembrete() {
        Map<Endpoint, Method> servidos = descobertos();

        assertThat(verificarClassificacao(servidos.keySet(), Set.of(DISPARO), EXCLUIDOS))
                .as("caminho servido sem classificacao, ou classificacao sem caminho servido")
                .isEmpty();
        assertThat(servidos).containsKey(DISPARO);
    }

    @Test
    @DisplayName("Scenario: o disparo não aceita entrada de negócio")
    void disparoNaoAceitaEntradaDeNegocio() {
        Method handler = descobertos().get(DISPARO);

        assertThat(entradasDeNegocio(handler))
                .as("um parametro novo aqui seria entrada nao atacada: nao existe varredura "
                        + "de valores neste servico")
                .isEmpty();
    }

    /**
     * O negativo: um handler sintetico com parametro precisa reprovar.
     *
     * <p>Sem ele, a assercao acima passaria mesmo que {@link #entradasDeNegocio(Method)}
     * tivesse deixado de enxergar parametros — o teste diria "nenhuma entrada" para
     * qualquer handler, inclusive um cheio delas.
     */
    @Test
    @DisplayName("handler sintético com parâmetro é recusado")
    void handlerSinteticoComParametroERecusado() {
        assertThat(entradasDeNegocio(metodoSintetico("comParametroDeConsulta")))
                .containsExactly("CONSULTA janela");
        assertThat(entradasDeNegocio(metodoSintetico("comCorpo")))
                .containsExactly("CORPO corpo");
        assertThat(entradasDeNegocio(metodoSintetico("comVariavelDeCaminho")))
                .containsExactly("CAMINHO id");
    }

    /** O outro negativo: um endpoint sintetico nao classificado precisa reprovar. */
    @Test
    @DisplayName("endpoint sintético não classificado é recusado")
    void endpointSinteticoNaoClassificadoERecusado() {
        Endpoint novo = new Endpoint("GET", "/internal/lembretes/relatorio");

        // Sem a tabela de exclusoes real: aqui o conjunto servido e sintetico, e cobrar
        // dele os caminhos de infraestrutura do servico misturaria duas coisas. O que este
        // negativo verifica e so a classificacao do caminho novo, nos dois sentidos.
        assertThat(verificarClassificacao(Set.of(DISPARO, novo), Set.of(DISPARO), Map.of()))
                .containsExactly(
                        "caminho servido sem classificacao: GET /internal/lembretes/relatorio");
        assertThat(verificarClassificacao(Set.of(DISPARO), Set.of(DISPARO, novo), Map.of()))
                .containsExactly(
                        "caminho classificado e nao servido: GET /internal/lembretes/relatorio");
    }

    // ------------------------------------------------------------------ descoberta

    private Map<Endpoint, Method> descobertos() {
        Map<Endpoint, Method> mapa = new TreeMap<>(java.util.Comparator.comparing(Endpoint::toString));
        for (Map.Entry<RequestMappingInfo, HandlerMethod> registro
                : mapeamentos.getHandlerMethods().entrySet()) {
            RequestMappingInfo info = registro.getKey();
            Set<String> metodos = info.getMethodsCondition().getMethods().isEmpty()
                    ? Set.of("*")
                    : info.getMethodsCondition().getMethods().stream()
                            .map(Enum::name).collect(Collectors.toSet());
            for (String metodo : metodos) {
                for (String rota : info.getPatternValues()) {
                    mapa.put(new Endpoint(metodo, rota), registro.getValue().getMethod());
                }
            }
        }
        return mapa;
    }

    private static List<String> verificarClassificacao(Set<Endpoint> servidos,
                                                       Set<Endpoint> incluidos,
                                                       Map<Endpoint, String> excluidos) {
        List<String> erros = new ArrayList<>();
        for (Endpoint endpoint : servidos) {
            if (!incluidos.contains(endpoint) && !excluidos.containsKey(endpoint)) {
                erros.add("caminho servido sem classificacao: " + endpoint);
            }
        }
        for (Endpoint endpoint : incluidos) {
            if (!servidos.contains(endpoint)) {
                erros.add("caminho classificado e nao servido: " + endpoint);
            }
        }
        for (Endpoint endpoint : excluidos.keySet()) {
            if (!servidos.contains(endpoint)) {
                erros.add("caminho excluido e nao servido: " + endpoint);
            }
        }
        return erros;
    }

    /** Parametros que o cliente controla. Injecoes do proprio Spring nao contam. */
    private static List<String> entradasDeNegocio(Method handler) {
        Map<String, String> encontradas = new LinkedHashMap<>();
        for (Parameter parametro : handler.getParameters()) {
            if (parametro.isAnnotationPresent(RequestParam.class)) {
                encontradas.put(parametro.getName(), "CONSULTA");
            } else if (parametro.isAnnotationPresent(PathVariable.class)) {
                encontradas.put(parametro.getName(), "CAMINHO");
            } else if (parametro.isAnnotationPresent(RequestBody.class)) {
                encontradas.put(parametro.getName(), "CORPO");
            }
        }
        return encontradas.entrySet().stream()
                .map(entrada -> entrada.getValue() + " " + entrada.getKey())
                .toList();
    }

    private static Method metodoSintetico(String nome) {
        for (Method metodo : ControladorSintetico.class.getDeclaredMethods()) {
            if (metodo.getName().equals(nome)) {
                return metodo;
            }
        }
        throw new IllegalArgumentException("metodo sintetico inexistente: " + nome);
    }

    /** Nao e registrado em lugar nenhum: existe so para alimentar os negativos. */
    @SuppressWarnings("unused")
    static class ControladorSintetico {
        @PostMapping("/internal/sintetico")
        public void comParametroDeConsulta(@RequestParam String janela) {}

        @PostMapping("/internal/sintetico")
        public void comCorpo(@RequestBody String corpo) {}

        @PostMapping("/internal/sintetico/{id}")
        public void comVariavelDeCaminho(@PathVariable String id) {}

        @PostMapping("/internal/sintetico")
        public void semEntrada() {}
    }
}
