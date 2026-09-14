package br.com.fiap.hospital.agendamento.integracao.superficie;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.lang.reflect.Method;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * O inventario e o catalogo reprovam cada forma de a varredura ficar incompleta.
 *
 * <p>Os casos usam um controlador sintetico, e nao o do servico: assim cada negativo prova a
 * regra isolada, sem depender de o controlador real ter, hoje, o formato que o provoca.
 */
@DisplayName("Inventario da superficie REST")
class InventarioDaSuperficieTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final CatalogoDeVariantes CATALOGO = CatalogoDeVariantes.padrao();

    enum Situacao { ATIVA }

    record Interno(String z) {}

    record Corpo(@NotNull UUID a, @Size(max = 5) String t, OffsetDateTime d, Integer n,
                 Interno interno) {}

    @SuppressWarnings("unused")
    static class ControladorSintetico {
        @GetMapping("/x/{id}")
        public void porId(@PathVariable UUID id) {}

        @GetMapping("/x")
        public void lista(@RequestParam(required = false) Set<Situacao> situacao,
                          @RequestParam(required = false) OffsetDateTime de,
                          @RequestParam(defaultValue = "0") int pagina,
                          @RequestParam(required = false) String texto) {}

        @PostMapping("/x")
        public void criar(@RequestBody Corpo corpo) {}

        public void semAnotacao(String solto) {}

        public void tipoSemDimensao(@RequestParam Boolean ativo) {}

        public void comRequisicao(@RequestParam UUID id,
                                  jakarta.servlet.http.HttpServletRequest requisicao) {}
    }

    private static final Endpoint GET_ID = new Endpoint("GET", "/x/{id}");
    private static final Endpoint GET_LISTA = new Endpoint("GET", "/x");
    private static final Endpoint POST = new Endpoint("POST", "/x");

    // ------------------------------------------------------------ descoberta e classificacao

    @Test
    @DisplayName("as entradas do handler sao classificadas por localizacao e dimensao")
    void entradasSaoClassificadas() {
        assertThat(InventarioDaSuperficie.entradasDe(GET_ID, metodo("porId")))
                .containsExactly(new Entrada(GET_ID, Localizacao.CAMINHO, "id", Dimensao.UUID, true, null));

        assertThat(InventarioDaSuperficie.entradasDe(GET_LISTA, metodo("lista")))
                .extracting(Entrada::nome, Entrada::localizacao, Entrada::dimensao, Entrada::obrigatoria)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("situacao", Localizacao.CONSULTA, Dimensao.ENUM, false),
                        org.assertj.core.groups.Tuple.tuple("de", Localizacao.CONSULTA, Dimensao.DATA, false),
                        org.assertj.core.groups.Tuple.tuple("pagina", Localizacao.CONSULTA, Dimensao.INTEIRO, false),
                        org.assertj.core.groups.Tuple.tuple("texto", Localizacao.CONSULTA, Dimensao.TEXTO, false));

        assertThat(InventarioDaSuperficie.entradasDe(POST, metodo("criar")))
                .extracting(Entrada::nome, Entrada::localizacao, Entrada::dimensao, Entrada::obrigatoria,
                        Entrada::limiteDeTexto)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("corpo", Localizacao.CORPO, Dimensao.CORPO, true, null),
                        org.assertj.core.groups.Tuple.tuple("a", Localizacao.CAMPO, Dimensao.UUID, true, null),
                        org.assertj.core.groups.Tuple.tuple("t", Localizacao.CAMPO, Dimensao.TEXTO, false, 5),
                        org.assertj.core.groups.Tuple.tuple("d", Localizacao.CAMPO, Dimensao.DATA, false, null),
                        org.assertj.core.groups.Tuple.tuple("n", Localizacao.CAMPO, Dimensao.INTEIRO, false, null),
                        org.assertj.core.groups.Tuple.tuple("interno.z", Localizacao.CAMPO, Dimensao.TEXTO, false, null));
    }

    @Test
    @DisplayName("parametro sem classificacao reprova, em vez de ficar sem ataque")
    void parametroSemClassificacaoReprova() {
        assertThatThrownBy(() -> InventarioDaSuperficie.entradasDe(GET_LISTA, metodo("semAnotacao")))
                .hasMessageContaining("entrada sem classificacao");
    }

    @Test
    @DisplayName("tipo sem dimensao definida reprova")
    void tipoSemDimensaoReprova() {
        assertThatThrownBy(() -> InventarioDaSuperficie.entradasDe(GET_LISTA, metodo("tipoSemDimensao")))
                .hasMessageContaining("tipo sem dimensao definida");
    }

    @Test
    @DisplayName("parametro de infraestrutura da requisicao nao conta como entrada")
    void infraestruturaDaRequisicaoNaoEEntrada() {
        assertThat(InventarioDaSuperficie.entradasDe(GET_LISTA, metodo("comRequisicao")))
                .extracting(Entrada::nome).containsExactly("id");
    }

    @Test
    @DisplayName("handler sem condicao de metodo vira endpoint de qualquer metodo")
    void handlerSemMetodoViraCuringa() throws Exception {
        Method m = metodo("porId");
        var endpoints = InventarioDaSuperficie.endpoints(List.of(
                new InventarioDaSuperficie.Handler(Set.of(), Set.of("/erro"), m),
                new InventarioDaSuperficie.Handler(Set.of("GET", "HEAD"), Set.of("/x/{id}"), m)));
        assertThat(endpoints.keySet()).containsExactlyInAnyOrder(
                new Endpoint("*", "/erro"), new Endpoint("GET", "/x/{id}"), new Endpoint("HEAD", "/x/{id}"));
    }

    @Test
    @DisplayName("endpoint descoberto sem classificacao reprova")
    void endpointSemClassificacaoReprova() {
        assertThat(InventarioDaSuperficie.verificarClassificacao(
                Set.of(GET_ID, POST), Set.of(GET_ID), Map.of()))
                .containsExactly("endpoint descoberto sem classificacao: POST /x");
    }

    @Test
    @DisplayName("classificacao sem endpoint registrado reprova, incluida ou excluida")
    void classificacaoSemEndpointReprova() {
        assertThat(InventarioDaSuperficie.verificarClassificacao(
                Set.of(GET_ID), Set.of(GET_ID, POST), Map.of(GET_LISTA, "motivo")))
                .containsExactlyInAnyOrder(
                        "endpoint classificado e nao registrado: POST /x",
                        "endpoint excluido e nao registrado: GET /x");
    }

    // ------------------------------------------------------------ comparacao D x V contra E

    @Test
    @DisplayName("executado igual ao exigido nao tem divergencia")
    void executadoIgualAoExigido() {
        List<Entrada> entradas = entradas();
        Set<Combinacao> exigidas = InventarioDaSuperficie.exigidas(entradas, CATALOGO);
        assertThat(InventarioDaSuperficie.comparar(entradas, exigidas, exigidas, dimensoesDe(entradas)))
                .isEmpty();
    }

    @Test
    @DisplayName("combinacao exigida e nao executada reprova")
    void combinacaoNaoExecutadaReprova() {
        List<Entrada> entradas = entradas();
        Set<Combinacao> exigidas = InventarioDaSuperficie.exigidas(entradas, CATALOGO);
        Set<Combinacao> executadas = new LinkedHashSet<>(exigidas);
        Combinacao retirada = executadas.iterator().next();
        executadas.remove(retirada);

        assertThat(InventarioDaSuperficie.comparar(entradas, exigidas, executadas, dimensoesDe(entradas)))
                .containsExactly("combinacao exigida e nao executada: " + retirada);
    }

    @Test
    @DisplayName("entrada descoberta sem nenhum ataque reprova")
    void entradaSemAtaqueReprova() {
        List<Entrada> entradas = entradas();
        Set<Combinacao> exigidas = InventarioDaSuperficie.exigidas(entradas, CATALOGO);
        Set<Combinacao> executadas = exigidas.stream()
                .filter(c -> !c.entrada().equals("pagina"))
                .collect(Collectors.toCollection(LinkedHashSet::new));

        assertThat(InventarioDaSuperficie.comparar(entradas, exigidas, executadas, dimensoesDe(entradas)))
                .singleElement().asString().startsWith("entrada descoberta sem ataque: GET /x | CONSULTA pagina");
    }

    @Test
    @DisplayName("ataque sem entrada registrada reprova como orfao")
    void ataqueOrfaoReprova() {
        List<Entrada> entradas = entradas();
        Set<Combinacao> exigidas = InventarioDaSuperficie.exigidas(entradas, CATALOGO);
        Set<Combinacao> executadas = new LinkedHashSet<>(exigidas);
        Combinacao orfa = new Combinacao(GET_LISTA, Localizacao.CONSULTA, "inexistente", Dimensao.UUID, "nil");
        executadas.add(orfa);

        assertThat(InventarioDaSuperficie.comparar(entradas, exigidas, executadas, dimensoesDe(entradas)))
                .containsExactly("ataque orfao, sem entrada registrada: " + orfa);
    }

    @Test
    @DisplayName("entrada atacada com dimensao diferente da descoberta reprova")
    void dimensaoDivergenteReprova() {
        List<Entrada> entradas = entradas();
        Set<Combinacao> exigidas = InventarioDaSuperficie.exigidas(entradas, CATALOGO);
        Set<Combinacao> executadas = new LinkedHashSet<>();
        for (Combinacao c : exigidas) {
            executadas.add(c.entrada().equals("id")
                    ? new Combinacao(c.endpoint(), c.localizacao(), c.entrada(), Dimensao.TEXTO, "vazio")
                    : c);
        }

        assertThat(InventarioDaSuperficie.comparar(entradas, exigidas, executadas, dimensoesDe(entradas)))
                .anySatisfy(e -> assertThat(e).startsWith("dimensao divergente em GET /x/{id} | CAMINHO id"));
    }

    @Test
    @DisplayName("inventario vazio reprova, em vez de passar sem verificar nada")
    void inventarioVazioReprova() {
        assertThat(InventarioDaSuperficie.comparar(List.of(), Set.of(), Set.of(), EnumSet.allOf(Dimensao.class)))
                .containsExactly("inventario vazio: nenhuma combinacao exigida");
    }

    @Test
    @DisplayName("dimensao obrigatoria sem combinacao reprova")
    void dimensaoSemCombinacaoReprova() {
        List<Entrada> entradas = entradas();
        Set<Combinacao> exigidas = InventarioDaSuperficie.exigidas(entradas, CATALOGO);
        assertThat(InventarioDaSuperficie.comparar(entradas, exigidas, exigidas, EnumSet.allOf(Dimensao.class)))
                .isEmpty();
        List<Entrada> semCorpo = entradas.stream().filter(e -> e.dimensao() != Dimensao.CORPO).toList();
        Set<Combinacao> exigidasSemCorpo = InventarioDaSuperficie.exigidas(semCorpo, CATALOGO);
        assertThat(InventarioDaSuperficie.comparar(semCorpo, exigidasSemCorpo, exigidasSemCorpo,
                EnumSet.allOf(Dimensao.class)))
                .containsExactly("dimensao sem combinacao: CORPO");
    }

    // ------------------------------------------------------------ catalogo

    @Test
    @DisplayName("o catalogo padrao contem todas as variantes minimas de cada dimensao")
    void catalogoContemOsMinimos() {
        assertThat(CATALOGO.minimosAusentes()).isEmpty();
        assertThat(CATALOGO.locaisSemVariantes()).isEmpty();
    }

    @Test
    @DisplayName("retirar uma variante minima reprova")
    void retirarMinimoReprova() {
        assertThat(CATALOGO.sem(Dimensao.UUID, "nil").minimosAusentes()).containsExactly("UUID:nil");
        assertThat(CATALOGO.sem(Dimensao.CORPO, "chave-duplicada").minimosAusentes())
                .containsExactly("CORPO:chave-duplicada");
    }

    /**
     * Todo valor da tabela manual do M09 continua sendo atacado.
     *
     * <p>A linha de base foi registrada antes da reestruturacao. Cada item e uma tupla
     * dimensao × localizacao × valor renderizado; o catalogo precisa produzir cada uma.
     */
    @Test
    @DisplayName("todos os valores da linha de base estao contidos no catalogo")
    void linhaDeBaseContida() {
        Set<String> renderizados = new HashSet<>();
        ObjectNode corpoValido = MAPPER.createObjectNode().put("motivo", "controle");
        for (CatalogoDeVariantes.Variante v : CATALOGO.todas()) {
            Entrada amostra = new Entrada(POST, v.localizacao(), "x", v.dimensao(), false, 2000);
            renderizados.add(v.dimensao() + "|" + v.localizacao() + "|"
                    + v.renderizar(new CatalogoDeVariantes.Contexto(amostra, corpoValido)));
        }

        List<String> linhaDeBase = new java.util.ArrayList<>();
        for (String id : List.of("nao-e-um-uuid", "11111111-1111-1111-1111-111111111111", "0", "%20",
                "../../etc/passwd", "00000000-0000-0000-0000-000000000000")) {
            linhaDeBase.add("UUID|CAMINHO|" + id);
        }
        for (String corpo : List.of("", "{}", "   ", "[]", "{\"pacienteId\": ")) {
            linhaDeBase.add("CORPO|CORPO|" + corpo);
        }
        for (String uuid : List.of("\"nao-e-um-uuid\"", "\"11111111-1111-1111-1111-111111111111\"", "null")) {
            linhaDeBase.add("UUID|CAMPO|" + uuid);
        }
        for (String data : List.of("\"data-invalida\"", "\"nao-e-data\"")) {
            linhaDeBase.add("DATA|CAMPO|" + data);
        }
        for (String data : CatalogoDeVariantes.DATAS_HOSTIS) {
            linhaDeBase.add("DATA|CAMPO|" + com.fasterxml.jackson.databind.node.TextNode.valueOf(data));
            linhaDeBase.add("DATA|CONSULTA|" + data);
        }
        for (String inteiro : List.of("0", "-1", "2147483647", "9999999999999", "\"texto\"")) {
            linhaDeBase.add("INTEIRO|CAMPO|" + inteiro);
        }
        for (String texto : List.of("null", "\"\"", "\"   \"", "42")) {
            linhaDeBase.add("TEXTO|CAMPO|" + texto);
        }
        for (String status : List.of("NAO_EXISTE", "", "agendada")) {
            linhaDeBase.add("ENUM|CONSULTA|" + status);
        }
        for (String data : List.of("10/09/2026", "", "nao-e-data")) {
            linhaDeBase.add("DATA|CONSULTA|" + data);
        }
        linhaDeBase.add("UUID|CONSULTA|nao-e-um-uuid");
        for (String inteiro : List.of("-1", "2147483647", "9999999999", "0", "abc")) {
            linhaDeBase.add("INTEIRO|CONSULTA|" + inteiro);
        }

        assertThat(renderizados).containsAll(linhaDeBase);
    }

    // ------------------------------------------------------------ apoio

    private static List<Entrada> entradas() {
        List<Entrada> todas = new java.util.ArrayList<>();
        todas.addAll(InventarioDaSuperficie.entradasDe(GET_ID, metodo("porId")));
        todas.addAll(InventarioDaSuperficie.entradasDe(GET_LISTA, metodo("lista")));
        todas.addAll(InventarioDaSuperficie.entradasDe(POST, metodo("criar")));
        return todas;
    }

    private static Set<Dimensao> dimensoesDe(List<Entrada> entradas) {
        return entradas.stream().map(Entrada::dimensao)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(Dimensao.class)));
    }

    private static Method metodo(String nome) {
        for (Method m : ControladorSintetico.class.getDeclaredMethods()) {
            if (m.getName().equals(nome)) {
                return m;
            }
        }
        throw new IllegalArgumentException(nome);
    }
}
