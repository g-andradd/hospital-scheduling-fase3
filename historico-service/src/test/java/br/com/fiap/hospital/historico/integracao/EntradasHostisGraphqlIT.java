package br.com.fiap.hospital.historico.integracao;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.fiap.hospital.historico.integracao.superficie.CatalogoDeVariantesGraphql;
import br.com.fiap.hospital.historico.integracao.superficie.DimensaoGraphql;
import br.com.fiap.hospital.historico.integracao.superficie.DocumentosHostis;
import br.com.fiap.hospital.historico.integracao.superficie.InventarioGraphql;
import br.com.fiap.hospital.historico.integracao.superficie.InventarioGraphql.Combinacao;
import br.com.fiap.hospital.historico.integracao.superficie.InventarioGraphql.Entrada;
import br.com.fiap.hospital.historico.integracao.superficie.InventarioGraphql.Operacao;
import graphql.schema.GraphQLFieldDefinition;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.graphql.execution.GraphQlSource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Ataca toda a superficie GraphQL do historico e exige que nenhuma resposta seja 5xx, erro
 * interno ou vazamento.
 *
 * <p>O conjunto exigido vem do schema servido, em tres naturezas que nao se misturam:
 * entradas do schema (argumentos e campos de objeto de entrada), o documento de cada
 * operacao — gerado a partir dela, e nao um texto fixo — e a fronteira de transporte, que e
 * global e entra uma vez so. O executado vem de um plano declarado a parte, e a comparacao
 * no ultimo metodo e o que acusa operacao nova, argumento novo, ataque orfao e combinacao
 * esquecida.
 *
 * <p>Sao cinco metodos {@code @Test} comuns e ordenados — e nao uma fabrica dinamica: uma
 * fabrica que nao emitisse teste algum passaria sem deixar caso no {@code TEST-*.xml}.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Entradas hostis GraphQL")
class EntradasHostisGraphqlIT extends GraphqlITBase {

    @Autowired GraphQlSource graphQlSource;

    private static final Operacao CONSULTAS_DO_PACIENTE =
            new Operacao("Query", "consultasDoPaciente");
    private static final Operacao MINHAS_CONSULTAS = new Operacao("Query", "minhasConsultas");
    private static final Operacao CONSULTAS_DO_MEDICO = new Operacao("Query", "consultasDoMedico");
    private static final Operacao CONSULTA = new Operacao("Query", "consulta");
    private static final Operacao CORRIGIR = new Operacao("Mutation", "corrigirRegistroHistorico");

    private static final Set<Operacao> INCLUIDAS = Set.of(CONSULTAS_DO_PACIENTE, MINHAS_CONSULTAS,
            CONSULTAS_DO_MEDICO, CONSULTA, CORRIGIR);

    /** Nenhuma operacao servida fica de fora: a introspeccao ja nao entra no inventario. */
    private static final Map<Operacao, String> EXCLUIDAS = Map.of();

    private static final CatalogoDeVariantesGraphql CATALOGO = CatalogoDeVariantesGraphql.padrao();

    private static final OffsetDateTime DATA = instante("2026-09-18T14:30:00Z");

    /** Codigos estaveis: o cliente programa reacao em cima deles. */
    private static final Set<String> CODIGOS_ACEITOS =
            Set.of("BAD_REQUEST", "NOT_FOUND", "FORBIDDEN");

    private static final List<String> MARCADORES_DE_VAZAMENTO = List.of(
            "PSQLException", "org.postgresql", "org.springframework", "org.hibernate",
            "java.lang.", "consulta_historico", "consulta_evento", "select ", "stackTrace");

    private final Set<Combinacao> executadasNaPassagem1 = new LinkedHashSet<>();
    private final Set<Combinacao> executadasNaPassagem2 = new LinkedHashSet<>();

    private UUID consultaBase;

    // ================================================================ plano

    /** Uma entrada do schema como o plano a declara — independente da descoberta. */
    record EntradaDeclarada(String caminho, DimensaoGraphql dimensao) {}

    /**
     * Um alvo do plano.
     *
     * @param argumentoReal argumento da propria operacao, usado pelos documentos hostis
     */
    record Alvo(Operacao operacao, String argumentoReal, String documento,
                Supplier<Map<String, Object>> variaveis, Supplier<String> token,
                List<EntradaDeclarada> entradas) {}

    private List<Alvo> plano() {
        return List.of(
                new Alvo(CONSULTAS_DO_PACIENTE, "pacienteId",
                        "query($pacienteId: ID!, $filtro: FiltroConsulta) {"
                                + " consultasDoPaciente(pacienteId: $pacienteId, filtro: $filtro)"
                                + " { id } }",
                        () -> variaveis("pacienteId", pacienteA.toString(), "filtro", filtroValido()),
                        this::tokenMedico,
                        List.of(new EntradaDeclarada("pacienteId", DimensaoGraphql.UUID),
                                new EntradaDeclarada("filtro", DimensaoGraphql.OBJETO),
                                new EntradaDeclarada("filtro.periodo", DimensaoGraphql.ENUM),
                                new EntradaDeclarada("filtro.status", DimensaoGraphql.ENUM),
                                new EntradaDeclarada("filtro.de", DimensaoGraphql.DATA),
                                new EntradaDeclarada("filtro.ate", DimensaoGraphql.DATA))),

                // O paciente so alcanca a propria lista: e a unica operacao em que esse
                // perfil e o controle correto.
                new Alvo(MINHAS_CONSULTAS, "filtro",
                        "query($filtro: FiltroConsulta) { minhasConsultas(filtro: $filtro) { id } }",
                        () -> variaveis("filtro", filtroValido()),
                        () -> tokenPaciente(pacienteA),
                        List.of(new EntradaDeclarada("filtro", DimensaoGraphql.OBJETO),
                                new EntradaDeclarada("filtro.periodo", DimensaoGraphql.ENUM),
                                new EntradaDeclarada("filtro.status", DimensaoGraphql.ENUM),
                                new EntradaDeclarada("filtro.de", DimensaoGraphql.DATA),
                                new EntradaDeclarada("filtro.ate", DimensaoGraphql.DATA))),

                new Alvo(CONSULTAS_DO_MEDICO, "medicoId",
                        "query($medicoId: ID!, $filtro: FiltroConsulta) {"
                                + " consultasDoMedico(medicoId: $medicoId, filtro: $filtro)"
                                + " { id } }",
                        () -> variaveis("medicoId", medicoA.toString(), "filtro", filtroValido()),
                        this::tokenMedico,
                        List.of(new EntradaDeclarada("medicoId", DimensaoGraphql.UUID),
                                new EntradaDeclarada("filtro", DimensaoGraphql.OBJETO),
                                new EntradaDeclarada("filtro.periodo", DimensaoGraphql.ENUM),
                                new EntradaDeclarada("filtro.status", DimensaoGraphql.ENUM),
                                new EntradaDeclarada("filtro.de", DimensaoGraphql.DATA),
                                new EntradaDeclarada("filtro.ate", DimensaoGraphql.DATA))),

                new Alvo(CONSULTA, "id",
                        "query($id: ID!) { consulta(id: $id) { id } }",
                        () -> variaveis("id", consultaBase.toString()),
                        this::tokenMedico,
                        List.of(new EntradaDeclarada("id", DimensaoGraphql.UUID))),

                new Alvo(CORRIGIR, "input",
                        "mutation($input: CorrigirRegistroHistoricoInput!) {"
                                + " corrigirRegistroHistorico(input: $input) { id } }",
                        () -> variaveis("input", inputValido(novaConsulta())),
                        this::tokenMedico,
                        List.of(new EntradaDeclarada("input", DimensaoGraphql.OBJETO),
                                new EntradaDeclarada("input.consultaId", DimensaoGraphql.UUID),
                                new EntradaDeclarada("input.justificativa", DimensaoGraphql.TEXTO),
                                new EntradaDeclarada("input.pacienteNome", DimensaoGraphql.TEXTO),
                                new EntradaDeclarada("input.medicoNome", DimensaoGraphql.TEXTO),
                                new EntradaDeclarada("input.especialidade", DimensaoGraphql.TEXTO),
                                new EntradaDeclarada("input.dataHora", DimensaoGraphql.DATA),
                                new EntradaDeclarada("input.status", DimensaoGraphql.ENUM),
                                new EntradaDeclarada("input.observacoes", DimensaoGraphql.TEXTO))));
    }

    private static Map<String, Object> filtroValido() {
        Map<String, Object> filtro = new LinkedHashMap<>();
        filtro.put("periodo", "TODAS");
        filtro.put("status", List.of("AGENDADA"));
        filtro.put("de", "2026-01-01T00:00:00Z");
        filtro.put("ate", "2031-01-01T00:00:00Z");
        return filtro;
    }

    private Map<String, Object> inputValido(UUID alvo) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("consultaId", alvo.toString());
        input.put("justificativa", "correcao de controle da varredura");
        input.put("pacienteNome", "Nome Controle " + alvo.toString().substring(0, 8));
        input.put("medicoNome", "Dr Controle");
        input.put("especialidade", "Neurologia");
        input.put("dataHora", "2026-09-19T14:30:00Z");
        input.put("status", "CONFIRMADA");
        input.put("observacoes", "observacao de controle");
        return input;
    }

    private static Map<String, Object> variaveis(Object... pares) {
        Map<String, Object> mapa = new LinkedHashMap<>();
        for (int i = 0; i < pares.length; i += 2) {
            mapa.put((String) pares[i], pares[i + 1]);
        }
        return mapa;
    }

    /** Alvo novo por combinacao que muda estado: uma correcao aceita nao contamina a proxima. */
    private UUID novaConsulta() {
        return inserirConsulta(pacienteA, medicoA, DATA, "AGENDADA", "observacao inicial");
    }

    private void prepararDados() {
        consultaBase = inserirConsulta(pacienteA, medicoA, DATA, "AGENDADA");
        inserirConsulta(pacienteB, medicoB, DATA, "CONFIRMADA");
    }

    // ================================================================ metodos ordenados

    @Test
    @Order(1)
    @DisplayName("Scenario: Controle válido alcança o resolver")
    void controles() {
        prepararDados();
        assertThat(executarControles()).as("controles que nao alcancaram o resolver").isEmpty();
    }

    @Test
    @Order(2)
    @DisplayName("Scenario: Nenhuma entrada hostil GraphQL produz 5xx, erro interno ou vazamento (passagem 1)")
    void passagem1() {
        prepararDados();
        assertThat(executarPassagem(1, executadasNaPassagem1))
                .as("respostas a entradas hostis na passagem 1").isEmpty();
    }

    @Test
    @Order(3)
    @DisplayName("Scenario: Repetição da varredura GraphQL não degrada o serviço (passagem 2)")
    void passagem2() {
        prepararDados();
        assertThat(executarPassagem(2, executadasNaPassagem2))
                .as("respostas a entradas hostis na passagem 2").isEmpty();
    }

    @Test
    @Order(4)
    @DisplayName("controles continuam alcançando o resolver depois das duas passagens")
    void controlesFinais() {
        prepararDados();
        assertThat(executarControles()).as("controles depois da varredura").isEmpty();
    }

    @Test
    @Order(5)
    @DisplayName("Scenario: Cada argumento e campo de entrada é atacado em todas as variantes da dimensão")
    void comparacao() {
        Map<Operacao, GraphQLFieldDefinition> servidas =
                InventarioGraphql.operacoes(graphQlSource.schema());

        List<String> erros = new ArrayList<>(
                InventarioGraphql.verificarClassificacao(servidas.keySet(), INCLUIDAS, EXCLUIDAS));

        Set<Operacao> planejadas = plano().stream().map(Alvo::operacao).collect(Collectors.toSet());
        if (!planejadas.equals(INCLUIDAS)) {
            erros.add("plano de ataques divergente das operacoes incluidas: " + planejadas);
        }
        CATALOGO.minimosAusentes()
                .forEach(m -> erros.add("variante minima ausente do catalogo: " + m));

        // Cada documento hostil precisa atingir a operacao que o rotula, e nenhuma outra.
        Set<String> nomesServidos = servidas.keySet().stream()
                .map(Operacao::nome).collect(Collectors.toSet());
        for (Alvo alvo : plano()) {
            GraphQLFieldDefinition campo = servidas.get(alvo.operacao());
            if (campo != null && campo.getArguments().stream()
                    .noneMatch(a -> a.getName().equals(alvo.argumentoReal()))) {
                erros.add("argumento do plano nao existe na operacao: " + alvo.operacao()
                        + " | " + alvo.argumentoReal());
            }
            erros.addAll(DocumentosHostis.verificar(alvo.operacao(),
                    DocumentosHostis.paraOperacao(
                            CatalogoDeVariantesGraphql.raizDe(alvo.operacao()),
                            alvo.operacao().nome(), alvo.argumentoReal()),
                    nomesServidos));
        }

        List<Entrada> entradas = new ArrayList<>();
        for (Operacao operacao : INCLUIDAS) {
            GraphQLFieldDefinition campo = servidas.get(operacao);
            if (campo != null) {
                entradas.addAll(InventarioGraphql.entradasDe(operacao, campo));
            }
        }
        Set<Combinacao> exigidas = InventarioGraphql.exigidas(entradas, INCLUIDAS, CATALOGO);
        Set<DimensaoGraphql> presentes = entradas.stream().map(Entrada::dimensao)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(DimensaoGraphql.class)));
        presentes.add(DimensaoGraphql.DOCUMENTO);
        presentes.add(DimensaoGraphql.CORPO_HTTP);

        InventarioGraphql.comparar(entradas, INCLUIDAS, exigidas, executadasNaPassagem1, presentes)
                .forEach(e -> erros.add("passagem 1: " + e));
        InventarioGraphql.comparar(entradas, INCLUIDAS, exigidas, executadasNaPassagem2, presentes)
                .forEach(e -> erros.add("passagem 2: " + e));

        long vinculadas = exigidas.stream()
                .filter(c -> c.dimensao() != DimensaoGraphql.DOCUMENTO
                        && c.dimensao() != DimensaoGraphql.CORPO_HTTP).count();
        long documentos = exigidas.stream()
                .filter(c -> c.dimensao() == DimensaoGraphql.DOCUMENTO).count();
        long transporte = exigidas.stream()
                .filter(c -> c.dimensao() == DimensaoGraphql.CORPO_HTTP).count();

        System.out.printf("[varredura graphql] operacoes servidas=%d incluidas=%d entradas=%d "
                        + "exigidas=%d (vinculadas=%d documentos=%d transporte=%d) "
                        + "executadas(p1)=%d executadas(p2)=%d%n",
                servidas.size(), INCLUIDAS.size(), entradas.size(), exigidas.size(),
                vinculadas, documentos, transporte,
                executadasNaPassagem1.size(), executadasNaPassagem2.size());
        exigidas.stream().collect(Collectors.groupingBy(Combinacao::dimensao, TreeMap::new,
                        Collectors.counting()))
                .forEach((d, n) -> System.out.printf(
                        "[varredura graphql] dimensao %s: %d combinacoes%n", d, n));

        assertThat(erros).as("inventario do schema contra a varredura executada").isEmpty();
    }

    // ================================================================ execucao

    private List<String> executarControles() {
        List<String> falhas = new ArrayList<>();
        for (Alvo alvo : plano()) {
            RespostaGraphql resposta =
                    executar(alvo.token().get(), alvo.documento(), alvo.variaveis().get());
            if (resposta.statusHttp() != 200 || resposta.temErro()) {
                falhas.add(alvo.operacao() + " | controle respondeu " + resposta.statusHttp()
                        + " com erros " + resposta.corpo().path("errors"));
            }
        }
        return falhas;
    }

    private List<String> executarPassagem(int numero, Set<Combinacao> executadas) {
        List<String> falhas = new ArrayList<>();
        Map<String, Map<String, Integer>> contagens = new TreeMap<>();

        for (Alvo alvo : plano()) {
            for (EntradaDeclarada entrada : alvo.entradas()) {
                for (CatalogoDeVariantesGraphql.Variante variante
                        : CATALOGO.aplicaveis(entrada.dimensao())) {
                    Combinacao combinacao = new Combinacao(alvo.operacao(), entrada.caminho(),
                            entrada.dimensao(), variante.nome());
                    falhas.addAll(atacarEntrada(alvo, entrada, variante, combinacao, contagens));
                    executadas.add(combinacao);
                }
            }
            for (CatalogoDeVariantesGraphql.Variante variante
                    : CATALOGO.aplicaveis(DimensaoGraphql.DOCUMENTO)) {
                Combinacao combinacao = new Combinacao(alvo.operacao(),
                        InventarioGraphql.DOCUMENTO, DimensaoGraphql.DOCUMENTO, variante.nome());
                falhas.addAll(atacarDocumento(alvo, variante, combinacao, contagens));
                executadas.add(combinacao);
            }
        }

        // Fronteira de transporte: fora do laco das operacoes, uma vez so.
        for (CatalogoDeVariantesGraphql.Variante variante
                : CATALOGO.aplicaveis(DimensaoGraphql.CORPO_HTTP)) {
            Combinacao combinacao = new Combinacao(InventarioGraphql.TRANSPORTE,
                    InventarioGraphql.CORPO, DimensaoGraphql.CORPO_HTTP, variante.nome());
            falhas.addAll(atacarTransporte(variante, combinacao, contagens));
            executadas.add(combinacao);
        }

        contagens.forEach((alvo, porCodigo) -> System.out.printf(
                "[varredura graphql] passagem %d | %s -> %s%n", numero, alvo, porCodigo));
        return falhas;
    }

    private List<String> atacarEntrada(Alvo alvo, EntradaDeclarada entrada,
                                       CatalogoDeVariantesGraphql.Variante variante,
                                       Combinacao combinacao,
                                       Map<String, Map<String, Integer>> contagens) {
        Map<String, Object> variaveis = alvo.variaveis().get();
        UUID alvoDaMutacao = alvoDaMutacao(alvo, variaveis);
        String snapshotAntes = alvoDaMutacao == null ? null : snapshot(alvoDaMutacao);
        long trilhaAntes = trilha.count();

        var contexto = new CatalogoDeVariantesGraphql.Contexto(alvo.operacao(),
                alvo.argumentoReal(),
                new Entrada(alvo.operacao(), entrada.caminho(), entrada.dimensao(), false, false),
                variaveis);
        substituir(variaveis, entrada.caminho(), variante.renderizar(contexto));

        ResponseEntity<String> resposta = postar(alvo.token().get(), alvo.documento(), variaveis);

        List<String> falhas = verificar(combinacao, resposta, contagens);
        if (alvoDaMutacao != null) {
            falhas.addAll(exigirSemEfeito(combinacao, resposta, alvoDaMutacao, snapshotAntes,
                    trilhaAntes));
        }
        return falhas;
    }

    private List<String> atacarDocumento(Alvo alvo, CatalogoDeVariantesGraphql.Variante variante,
                                         Combinacao combinacao,
                                         Map<String, Map<String, Integer>> contagens) {
        Map<String, Object> variaveis = alvo.variaveis().get();
        UUID alvoDaMutacao = alvoDaMutacao(alvo, variaveis);
        String snapshotAntes = alvoDaMutacao == null ? null : snapshot(alvoDaMutacao);
        long trilhaAntes = trilha.count();

        var contexto = new CatalogoDeVariantesGraphql.Contexto(alvo.operacao(),
                alvo.argumentoReal(), null, variaveis);
        String documento = (String) variante.renderizar(contexto);

        ResponseEntity<String> resposta = postar(alvo.token().get(), documento, null);

        List<String> falhas = verificar(combinacao, resposta, contagens);
        if (alvoDaMutacao != null) {
            falhas.addAll(exigirSemEfeito(combinacao, resposta, alvoDaMutacao, snapshotAntes,
                    trilhaAntes));
        }
        return falhas;
    }

    private List<String> atacarTransporte(CatalogoDeVariantesGraphql.Variante variante,
                                          Combinacao combinacao,
                                          Map<String, Map<String, Integer>> contagens) {
        var contexto = new CatalogoDeVariantesGraphql.Contexto(
                InventarioGraphql.TRANSPORTE, null, null, Map.of());
        String corpo = (String) variante.renderizar(contexto);

        return verificar(combinacao, postarCorpoCru(tokenMedico(), corpo, variante.contentType()),
                contagens);
    }

    /** O alvo da correcao, quando a variante nao destruiu o proprio input. */
    private UUID alvoDaMutacao(Alvo alvo, Map<String, Object> variaveis) {
        if (!alvo.operacao().equals(CORRIGIR)) {
            return null;
        }
        Object input = variaveis.get("input");
        return input instanceof Map<?, ?> mapa && mapa.get("consultaId") instanceof String id
                ? UUID.fromString(id)
                : null;
    }

    // ================================================================ asseracoes

    private List<String> verificar(Combinacao combinacao, ResponseEntity<String> resposta,
                                   Map<String, Map<String, Integer>> contagens) {
        List<String> falhas = new ArrayList<>();
        String rotulo = combinacao.toString();
        int status = resposta.getStatusCode().value();
        String corpo = resposta.getBody() == null ? "" : resposta.getBody();

        if (status >= 500) {
            falhas.add(rotulo + " respondeu HTTP " + status + ": " + resumo(corpo));
            registrar(contagens, combinacao, "HTTP " + status);
            return falhas;
        }
        for (String marcador : MARCADORES_DE_VAZAMENTO) {
            if (corpo.contains(marcador)) {
                falhas.add(rotulo + " vazou '" + marcador + "': " + resumo(corpo));
            }
        }

        String codigo;
        try {
            var arvore = mapper.readTree(corpo.isBlank() ? "{}" : corpo);
            boolean temErros = arvore.has("errors") && !arvore.path("errors").isEmpty();
            if (temErros) {
                codigo = arvore.path("errors").path(0).path("extensions").path("code")
                        .asText("SEM_CODIGO");
                String classificacao = arvore.path("errors").path(0).path("extensions")
                        .path("classification").asText("");
                if ("INTERNAL_ERROR".equals(codigo) || "INTERNAL_ERROR".equals(classificacao)) {
                    falhas.add(rotulo + " produziu erro interno: " + resumo(corpo));
                } else if (!CODIGOS_ACEITOS.contains(codigo)) {
                    falhas.add(rotulo + " respondeu com codigo instavel '" + codigo + "': "
                            + resumo(corpo));
                }
            } else if (arvore.has("data")) {
                codigo = "ACEITO";
            } else {
                // Sem corpo GraphQL: a recusa veio da fronteira HTTP, e precisa ser 4xx.
                codigo = "HTTP " + status;
                if (status < 400) {
                    falhas.add(rotulo + " respondeu " + status + " sem corpo GraphQL: "
                            + resumo(corpo));
                }
            }
        } catch (Exception e) {
            codigo = "CORPO_ILEGIVEL";
            if (status < 400) {
                falhas.add(rotulo + " respondeu " + status + " com corpo ilegivel: " + resumo(corpo));
            }
        }
        registrar(contagens, combinacao, codigo);
        return falhas;
    }

    /** Entrada recusada na mutation nao altera snapshot nem trilha. */
    private List<String> exigirSemEfeito(Combinacao combinacao, ResponseEntity<String> resposta,
                                         UUID alvo, String snapshotAntes, long trilhaAntes) {
        String corpo = resposta.getBody() == null ? "" : resposta.getBody();
        boolean recusada = resposta.getStatusCode().value() != 200 || corpo.contains("\"errors\"");
        if (!recusada) {
            return List.of();
        }
        List<String> falhas = new ArrayList<>();
        if (!Objects.equals(snapshot(alvo), snapshotAntes)) {
            falhas.add(combinacao + " alterou o snapshot apesar de recusada");
        }
        if (trilha.count() != trilhaAntes) {
            falhas.add(combinacao + " escreveu na trilha apesar de recusada");
        }
        return falhas;
    }

    private String snapshot(UUID id) {
        List<Map<String, Object>> linhas = jdbc.queryForList(
                "SELECT paciente_nome, medico_nome, especialidade, data_hora, status, observacoes,"
                        + " atualizado_em FROM consulta_historico WHERE id = ?", id);
        return linhas.isEmpty() ? "<ausente>" : linhas.getFirst().toString();
    }

    // ================================================================ transporte

    /** POST com o corpo exatamente como escrito — inclusive sem corpo algum. */
    private ResponseEntity<String> postarCorpoCru(String token, String corpo, String contentType) {
        HttpHeaders cabecalhos = new HttpHeaders();
        cabecalhos.setContentType(MediaType.parseMediaType(
                contentType == null ? MediaType.APPLICATION_JSON_VALUE : contentType));
        cabecalhos.setBearerAuth(token);
        return rest.exchange("/graphql", HttpMethod.POST,
                new HttpEntity<>(corpo, cabecalhos), String.class);
    }

    /** Substitui o valor de um caminho de variavel, inclusive dentro de objeto de entrada. */
    @SuppressWarnings("unchecked")
    private static void substituir(Map<String, Object> variaveis, String caminho, Object valor) {
        int ponto = caminho.indexOf('.');
        if (ponto < 0) {
            variaveis.put(caminho, valor);
            return;
        }
        String raiz = caminho.substring(0, ponto);
        String resto = caminho.substring(ponto + 1);
        Object interno = variaveis.get(raiz);
        Map<String, Object> objeto = interno instanceof Map<?, ?> mapa
                ? new LinkedHashMap<>((Map<String, Object>) mapa)
                : new LinkedHashMap<>();
        substituir(objeto, resto, valor);
        variaveis.put(raiz, objeto);
    }

    private static void registrar(Map<String, Map<String, Integer>> contagens,
                                  Combinacao combinacao, String codigo) {
        contagens.computeIfAbsent(combinacao.chaveDaEntrada() + " | " + combinacao.dimensao(),
                k -> new TreeMap<>()).merge(codigo, 1, Integer::sum);
    }

    private static String resumo(String corpo) {
        String limpo = corpo.replace("\n", " ");
        return limpo.length() <= 300 ? limpo : limpo.substring(0, 297) + "...";
    }
}
