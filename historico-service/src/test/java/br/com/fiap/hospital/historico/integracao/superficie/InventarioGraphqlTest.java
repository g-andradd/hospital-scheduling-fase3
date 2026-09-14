package br.com.fiap.hospital.historico.integracao.superficie;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.fiap.hospital.historico.integracao.superficie.InventarioGraphql.Combinacao;
import br.com.fiap.hospital.historico.integracao.superficie.InventarioGraphql.Entrada;
import br.com.fiap.hospital.historico.integracao.superficie.InventarioGraphql.Operacao;
import graphql.Scalars;
import graphql.schema.Coercing;
import graphql.schema.GraphQLArgument;
import graphql.schema.GraphQLEnumType;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLInputObjectField;
import graphql.schema.GraphQLInputObjectType;
import graphql.schema.GraphQLInputType;
import graphql.schema.GraphQLList;
import graphql.schema.GraphQLNonNull;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLScalarType;
import graphql.schema.GraphQLSchema;
import graphql.schema.GraphQLType;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * O inventario GraphQL reprova cada forma de a varredura ficar incompleta.
 *
 * <p>Os schemas sao sinteticos, montados aqui: cada negativo prova a regra isolada, sem
 * depender de o schema real ter hoje o formato que a provoca. E sao schemas de verdade, do
 * mesmo tipo que {@code GraphQlSource.schema()} devolve — nao uma estrutura paralela que
 * poderia divergir do que o servico serve.
 *
 * <p>Alem das regras de cobertura, aqui se prova a <b>honestidade do rotulo</b>: um ataque
 * de documento rotulado para uma operacao tem de executar aquela operacao, e um ataque de
 * transporte nao pode ser contado uma vez por operacao. Sem essas duas guardas, o total de
 * combinacoes cresce sem que nada a mais seja exercitado.
 */
@DisplayName("Inventario da superficie GraphQL")
class InventarioGraphqlTest {

    private static final CatalogoDeVariantesGraphql CATALOGO = CatalogoDeVariantesGraphql.padrao();

    /** Coercing inerte: o inventario le nomes de tipo e nunca executa a operacao. */
    private static final Coercing<Object, Object> INERTE = new Coercing<>() {};

    private static final GraphQLScalarType DATE_TIME =
            GraphQLScalarType.newScalar().name("DateTime").coercing(INERTE).build();
    private static final GraphQLScalarType MOEDA =
            GraphQLScalarType.newScalar().name("Moeda").coercing(INERTE).build();

    private static final GraphQLEnumType STATUS = GraphQLEnumType.newEnum()
            .name("StatusConsulta").value("AGENDADA").value("CANCELADA").build();

    private static final GraphQLInputObjectType FILTRO = GraphQLInputObjectType.newInputObject()
            .name("FiltroConsulta")
            .field(campoDeEntrada("periodo", STATUS))
            .field(campoDeEntrada("status", GraphQLList.list(GraphQLNonNull.nonNull(STATUS))))
            .field(campoDeEntrada("de", DATE_TIME))
            .build();

    private static final Operacao CONSULTAS = new Operacao("Query", "consultas");

    // ------------------------------------------------------------ dimensoes e descoberta

    @Test
    @DisplayName("cada tipo do schema vira a dimensão normativa correspondente")
    void tiposViramDimensoes() {
        assertThat(InventarioGraphql.dimensaoDe(Scalars.GraphQLID)).isEqualTo(DimensaoGraphql.UUID);
        assertThat(InventarioGraphql.dimensaoDe(Scalars.GraphQLString))
                .isEqualTo(DimensaoGraphql.TEXTO);
        assertThat(InventarioGraphql.dimensaoDe(Scalars.GraphQLInt))
                .isEqualTo(DimensaoGraphql.INTEIRO);
        assertThat(InventarioGraphql.dimensaoDe(DATE_TIME)).isEqualTo(DimensaoGraphql.DATA);
        assertThat(InventarioGraphql.dimensaoDe(STATUS)).isEqualTo(DimensaoGraphql.ENUM);
        assertThat(InventarioGraphql.dimensaoDe(GraphQLNonNull.nonNull(
                GraphQLList.list(GraphQLNonNull.nonNull(STATUS)))))
                .as("lista e obrigatoriedade nao mudam a dimensao do elemento")
                .isEqualTo(DimensaoGraphql.ENUM);
        assertThat(InventarioGraphql.dimensaoDe(FILTRO)).isEqualTo(DimensaoGraphql.OBJETO);
    }

    @Test
    @DisplayName("argumento Float reprova por falta de dimensão normativa")
    void argumentoFloatReprova() {
        assertThatThrownBy(() -> entradas(operacao("consultas",
                argumento("preco", Scalars.GraphQLFloat))))
                .hasMessageContaining("escalar sem dimensao normativa: Float");
    }

    @Test
    @DisplayName("argumento Boolean reprova pelo mesmo motivo")
    void argumentoBooleanReprova() {
        assertThatThrownBy(() -> entradas(operacao("consultas",
                argumento("ativo", Scalars.GraphQLBoolean))))
                .hasMessageContaining("escalar sem dimensao normativa: Boolean");
    }

    @Test
    @DisplayName("escalar customizado sem dimensão reprova")
    void escalarCustomizadoSemDimensaoReprova() {
        assertThatThrownBy(() -> entradas(operacao("consultas", argumento("valor", MOEDA))))
                .hasMessageContaining("escalar sem dimensao normativa: Moeda");
    }

    @Test
    @DisplayName("as entradas são só as do schema: documento e transporte não entram nelas")
    void entradasSaoSoAsDoSchema() {
        List<Entrada> entradas = entradas(operacao("consultas",
                argumento("id", GraphQLNonNull.nonNull(Scalars.GraphQLID)),
                argumento("filtro", FILTRO)));

        assertThat(entradas).extracting(Entrada::caminho, Entrada::dimensao, Entrada::obrigatoria)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("id", DimensaoGraphql.UUID, true),
                        org.assertj.core.groups.Tuple.tuple("filtro", DimensaoGraphql.OBJETO, false),
                        org.assertj.core.groups.Tuple.tuple("filtro.periodo", DimensaoGraphql.ENUM, false),
                        org.assertj.core.groups.Tuple.tuple("filtro.status", DimensaoGraphql.ENUM, false),
                        org.assertj.core.groups.Tuple.tuple("filtro.de", DimensaoGraphql.DATA, false));
        assertThat(entradas).extracting(Entrada::caminho)
                .doesNotContain(InventarioGraphql.DOCUMENTO, InventarioGraphql.CORPO);
    }

    @Test
    @DisplayName("um argumento Int novo passa a exigir os ataques de INTEIRO")
    void argumentoIntNovoExigeAtaquesDeInteiro() {
        List<Entrada> antes = entradas(operacao("consultas",
                argumento("id", GraphQLNonNull.nonNull(Scalars.GraphQLID))));
        assertThat(InventarioGraphql.exigidas(antes, Set.of(CONSULTAS), CATALOGO))
                .as("sem Int no schema, nenhuma combinacao de INTEIRO e exigida")
                .noneMatch(c -> c.dimensao() == DimensaoGraphql.INTEIRO);

        List<Entrada> depois = entradas(operacao("consultas",
                argumento("id", GraphQLNonNull.nonNull(Scalars.GraphQLID)),
                argumento("pagina", Scalars.GraphQLInt)));

        Set<String> exigidasDoInteiro =
                InventarioGraphql.exigidas(depois, Set.of(CONSULTAS), CATALOGO).stream()
                        .filter(c -> c.dimensao() == DimensaoGraphql.INTEIRO)
                        .map(Combinacao::variante)
                        .collect(Collectors.toSet());

        assertThat(exigidasDoInteiro)
                .containsAll(CatalogoDeVariantesGraphql.MINIMOS.get(DimensaoGraphql.INTEIRO));
    }

    // ------------------------------------------------------------ classificacao

    @Test
    @DisplayName("operação servida sem classificação reprova")
    void operacaoServidaSemClassificacaoReprova() {
        assertThat(InventarioGraphql.verificarClassificacao(
                Set.of(CONSULTAS, new Operacao("Mutation", "corrigir")), Set.of(CONSULTAS),
                Map.of()))
                .containsExactly("operacao servida sem classificacao: Mutation.corrigir");
    }

    @Test
    @DisplayName("classificação sem operação servida reprova, incluída ou excluída")
    void classificacaoSemOperacaoReprova() {
        assertThat(InventarioGraphql.verificarClassificacao(
                Set.of(CONSULTAS), Set.of(CONSULTAS, new Operacao("Query", "fantasma")),
                Map.of(new Operacao("Query", "sumida"), "motivo")))
                .containsExactlyInAnyOrder(
                        "operacao classificada e nao servida: Query.fantasma",
                        "operacao excluida e nao servida: Query.sumida");
    }

    @Test
    @DisplayName("a introspecção não entra no inventário")
    void introspeccaoNaoEntraNoInventario() {
        GraphQLSchema schema = schema(operacao("consultas",
                argumento("id", GraphQLNonNull.nonNull(Scalars.GraphQLID))));

        assertThat(InventarioGraphql.operacoes(schema).keySet())
                .extracting(Operacao::nome).containsExactly("consultas");
    }

    // ------------------------------------------------------------ honestidade do rotulo

    private static final Operacao MINHAS = new Operacao("Query", "minhasConsultas");
    private static final Set<String> SERVIDAS = Set.of("minhasConsultas", "consulta",
            "consultasDoPaciente", "consultasDoMedico", "corrigirRegistroHistorico");

    @Test
    @DisplayName("documentos gerados executam, pelo AST, a operação que os rotula")
    void documentosGeradosExecutamAOperacaoAlvo() {
        assertThat(DocumentosHostis.verificar(MINHAS,
                DocumentosHostis.paraOperacao("query", "minhasConsultas", "filtro"), SERVIDAS))
                .isEmpty();
        assertThat(DocumentosHostis.verificar(new Operacao("Query", "consulta"),
                DocumentosHostis.paraOperacao("query", "consulta", "id"), SERVIDAS))
                .as("'consulta' e prefixo de 'consultasDoPaciente': o AST compara nomes exatos")
                .isEmpty();
        assertThat(DocumentosHostis.verificar(new Operacao("Query", "consultasDoPaciente"),
                DocumentosHostis.paraOperacao("query", "consultasDoPaciente", "pacienteId"),
                SERVIDAS))
                .isEmpty();
        assertThat(DocumentosHostis.verificar(new Operacao("Mutation", "corrigirRegistroHistorico"),
                DocumentosHostis.paraOperacao("mutation", "corrigirRegistroHistorico", "input"),
                SERVIDAS))
                .isEmpty();
    }

    @Test
    @DisplayName("root field real sendo outra operação é detectado")
    void rootFieldDeOutraOperacaoEDetectado() {
        assertThat(verificarUm("query { consulta(id: 42) { id } }"))
                .anySatisfy(e -> assertThat(e).contains("executa consulta"))
                .anySatisfy(e -> assertThat(e).contains("nao chama essa operacao"));
    }

    @Test
    @DisplayName("nome-alvo apenas em comentário não conta como chamada")
    void nomeAlvoEmComentarioNaoConta() {
        assertThat(verificarUm("query { consulta(id: 1) { id } } # minhasConsultas"))
                .anySatisfy(e -> assertThat(e).contains("nao chama essa operacao"));
    }

    @Test
    @DisplayName("nome-alvo apenas em alias não conta como chamada")
    void nomeAlvoEmAliasNaoConta() {
        assertThat(verificarUm("query { minhasConsultas: consulta(id: 1) { id } }"))
                .anySatisfy(e -> assertThat(e).contains("executa consulta"))
                .anySatisfy(e -> assertThat(e).contains("nao chama essa operacao"));
    }

    @Test
    @DisplayName("nome-alvo apenas em literal String não conta como chamada")
    void nomeAlvoEmLiteralStringNaoConta() {
        assertThat(verificarUm("query { consulta(id: \"minhasConsultas\") { id } }"))
                .anySatisfy(e -> assertThat(e).contains("nao chama essa operacao"));
    }

    @Test
    @DisplayName("tipo de operação divergente da raiz é detectado")
    void tipoDeOperacaoDivergenteEDetectado() {
        assertThat(DocumentosHostis.verificar(new Operacao("Mutation", "corrigirRegistroHistorico"),
                Map.of("literal-incompativel",
                        "query { corrigirRegistroHistorico(input: 42) { id } }"), SERVIDAS))
                .anySatisfy(e -> assertThat(e).contains("executa como QUERY"));
    }

    @Test
    @DisplayName("vazio precisa estar vazio e sintaxe inválida precisa falhar na análise")
    void vazioESintaxeInvalidaSaoConferidosAParte() {
        assertThat(DocumentosHostis.verificar(MINHAS, Map.of(
                DocumentosHostis.VAZIO, "query { minhasConsultas { id } }",
                DocumentosHostis.SINTAXE_INVALIDA, "query { minhasConsultas { id } }"), SERVIDAS))
                .hasSize(2)
                .anySatisfy(e -> assertThat(e).contains("deveria estar vazio"))
                .anySatisfy(e -> assertThat(e).contains("e sintaticamente valido"));
    }

    @Test
    @DisplayName("o argumento incompatível é o da própria operação")
    void argumentoIncompativelEDaPropriaOperacao() {
        assertThat(DocumentosHostis.paraOperacao("query", "minhasConsultas", "filtro")
                .get("literal-incompativel"))
                .isEqualTo("query { minhasConsultas(filtro: 42) { id } }");
        assertThat(DocumentosHostis.paraOperacao("mutation", "corrigirRegistroHistorico", "input")
                .get("literal-incompativel"))
                .isEqualTo("mutation { corrigirRegistroHistorico(input: 42) { id } }");
    }

    private static List<String> verificarUm(String documento) {
        return DocumentosHostis.verificar(MINHAS, Map.of("literal-incompativel", documento),
                SERVIDAS);
    }

    // ------------------------------------------------------------ comparacao E = D x V

    @Test
    @DisplayName("executado igual ao exigido não tem divergência")
    void executadoIgualAoExigido() {
        List<Entrada> entradas = entradasDoSchemaSintetico();
        Set<Combinacao> exigidas =
                InventarioGraphql.exigidas(entradas, Set.of(CONSULTAS), CATALOGO);

        assertThat(InventarioGraphql.comparar(entradas, Set.of(CONSULTAS), exigidas, exigidas,
                dimensoes(entradas))).isEmpty();
    }

    @Test
    @DisplayName("as três naturezas entram no exigido, e o transporte entra uma vez só")
    void tresNaturezasComTransporteUnico() {
        List<Entrada> entradas = entradasDoSchemaSintetico();
        Set<Operacao> operacoes = Set.of(CONSULTAS, new Operacao("Query", "outra"));
        Set<Combinacao> exigidas = InventarioGraphql.exigidas(entradas, operacoes, CATALOGO);

        long documentos = exigidas.stream()
                .filter(c -> c.dimensao() == DimensaoGraphql.DOCUMENTO).count();
        long transporte = exigidas.stream()
                .filter(c -> c.dimensao() == DimensaoGraphql.CORPO_HTTP).count();

        assertThat(documentos)
                .as("documento e por operacao")
                .isEqualTo(2L * CATALOGO.aplicaveis(DimensaoGraphql.DOCUMENTO).size());
        assertThat(transporte)
                .as("transporte e fronteira global: nao multiplica por operacao")
                .isEqualTo(CATALOGO.aplicaveis(DimensaoGraphql.CORPO_HTTP).size());
        assertThat(exigidas).filteredOn(c -> c.dimensao() == DimensaoGraphql.CORPO_HTTP)
                .allSatisfy(c -> assertThat(c.operacao()).isEqualTo(InventarioGraphql.TRANSPORTE));
    }

    @Test
    @DisplayName("ataque de transporte contabilizado por operação reprova")
    void transporteContabilizadoPorOperacaoReprova() {
        List<Entrada> entradas = entradasDoSchemaSintetico();
        Set<Combinacao> exigidas =
                InventarioGraphql.exigidas(entradas, Set.of(CONSULTAS), CATALOGO);

        Set<Combinacao> executadas = exigidas.stream()
                .filter(c -> c.dimensao() != DimensaoGraphql.CORPO_HTTP)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        for (String operacao : List.of("consultas", "outra", "terceira", "quarta", "quinta")) {
            executadas.add(new Combinacao(new Operacao("Query", operacao),
                    InventarioGraphql.CORPO, DimensaoGraphql.CORPO_HTTP, "null"));
        }

        List<String> erros = InventarioGraphql.comparar(entradas, Set.of(CONSULTAS), exigidas,
                executadas, dimensoes(entradas));

        assertThat(erros).filteredOn(e -> e.startsWith("ataque de transporte atribuido"))
                .as("cada atribuicao indevida e acusada")
                .hasSize(5);
        assertThat(erros).anySatisfy(e -> assertThat(e)
                .startsWith("fronteira de transporte sem ataque"));
    }

    @Test
    @DisplayName("combinação exigida e não executada reprova")
    void combinacaoNaoExecutadaReprova() {
        List<Entrada> entradas = entradasDoSchemaSintetico();
        Set<Combinacao> exigidas =
                InventarioGraphql.exigidas(entradas, Set.of(CONSULTAS), CATALOGO);
        Set<Combinacao> executadas = new LinkedHashSet<>(exigidas);
        Combinacao retirada = executadas.iterator().next();
        executadas.remove(retirada);

        assertThat(InventarioGraphql.comparar(entradas, Set.of(CONSULTAS), exigidas, executadas,
                dimensoes(entradas)))
                .containsExactly("combinacao exigida e nao executada: " + retirada);
    }

    @Test
    @DisplayName("operação, argumento ou campo sem ataque reprova")
    void entradaSemAtaqueReprova() {
        List<Entrada> entradas = entradasDoSchemaSintetico();
        Set<Combinacao> exigidas =
                InventarioGraphql.exigidas(entradas, Set.of(CONSULTAS), CATALOGO);
        Set<Combinacao> executadas = exigidas.stream()
                .filter(c -> !c.entrada().equals("filtro.de"))
                .collect(Collectors.toCollection(LinkedHashSet::new));

        assertThat(InventarioGraphql.comparar(entradas, Set.of(CONSULTAS), exigidas, executadas,
                dimensoes(entradas)))
                .singleElement().asString()
                .startsWith("entrada descoberta sem ataque: Query.consultas | filtro.de");
    }

    @Test
    @DisplayName("operação sem ataque de documento reprova")
    void operacaoSemAtaqueDeDocumentoReprova() {
        List<Entrada> entradas = entradasDoSchemaSintetico();
        Set<Combinacao> exigidas =
                InventarioGraphql.exigidas(entradas, Set.of(CONSULTAS), CATALOGO);
        Set<Combinacao> executadas = exigidas.stream()
                .filter(c -> c.dimensao() != DimensaoGraphql.DOCUMENTO)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        assertThat(InventarioGraphql.comparar(entradas, Set.of(CONSULTAS), exigidas, executadas,
                dimensoes(entradas)))
                .containsExactly("operacao sem ataque de documento: Query.consultas");
    }

    @Test
    @DisplayName("ataque órfão reprova")
    void ataqueOrfaoReprova() {
        List<Entrada> entradas = entradasDoSchemaSintetico();
        Set<Combinacao> exigidas =
                InventarioGraphql.exigidas(entradas, Set.of(CONSULTAS), CATALOGO);
        Set<Combinacao> executadas = new LinkedHashSet<>(exigidas);
        Combinacao orfa = new Combinacao(CONSULTAS, "inexistente", DimensaoGraphql.UUID, "nil");
        executadas.add(orfa);

        assertThat(InventarioGraphql.comparar(entradas, Set.of(CONSULTAS), exigidas, executadas,
                dimensoes(entradas)))
                .containsExactly("ataque orfao, sem alvo registrado: " + orfa);
    }

    @Test
    @DisplayName("dimensão presente sem combinação reprova")
    void dimensaoPresenteSemCombinacaoReprova() {
        List<Entrada> entradas = entradasDoSchemaSintetico();
        CatalogoDeVariantesGraphql semData = new CatalogoDeVariantesGraphql(
                CATALOGO.todas().stream()
                        .filter(v -> v.dimensao() != DimensaoGraphql.DATA).toList());
        Set<Combinacao> exigidas =
                InventarioGraphql.exigidas(entradas, Set.of(CONSULTAS), semData);

        assertThat(InventarioGraphql.comparar(entradas, Set.of(CONSULTAS), exigidas, exigidas,
                dimensoes(entradas)))
                .anySatisfy(e -> assertThat(e)
                        .startsWith("entrada compativel sem variante no catalogo"))
                .anySatisfy(e -> assertThat(e).isEqualTo("dimensao sem combinacao: DATA"));
    }

    @Test
    @DisplayName("inventário vazio reprova, em vez de passar sem verificar nada")
    void inventarioVazioReprova() {
        assertThat(InventarioGraphql.comparar(List.of(), Set.of(), Set.of(), Set.of(),
                EnumSet.allOf(DimensaoGraphql.class)))
                .containsExactly("inventario vazio: nenhuma combinacao exigida");
    }

    @Test
    @DisplayName("entrada atacada com dimensão diferente da descoberta reprova")
    void dimensaoDivergenteReprova() {
        List<Entrada> entradas = entradasDoSchemaSintetico();
        Set<Combinacao> exigidas =
                InventarioGraphql.exigidas(entradas, Set.of(CONSULTAS), CATALOGO);
        Set<Combinacao> executadas = new LinkedHashSet<>();
        for (Combinacao c : exigidas) {
            executadas.add(c.entrada().equals("id")
                    ? new Combinacao(c.operacao(), c.entrada(), DimensaoGraphql.TEXTO, "vazio")
                    : c);
        }

        assertThat(InventarioGraphql.comparar(entradas, Set.of(CONSULTAS), exigidas, executadas,
                dimensoes(entradas)))
                .anySatisfy(e -> assertThat(e)
                        .startsWith("dimensao divergente em Query.consultas | id"));
    }

    // ------------------------------------------------------------ catalogo

    @Test
    @DisplayName("o catálogo padrão contém todas as variantes mínimas de cada dimensão")
    void catalogoContemOsMinimos() {
        assertThat(CATALOGO.minimosAusentes()).isEmpty();
        assertThat(CATALOGO.dimensoesSemVariantes()).isEmpty();
    }

    @Test
    @DisplayName("retirar uma variante mínima reprova")
    void retirarMinimoReprova() {
        assertThat(CATALOGO.sem(DimensaoGraphql.UUID, "nil").minimosAusentes())
                .containsExactly("UUID:nil");
        assertThat(CATALOGO.sem(DimensaoGraphql.DOCUMENTO, "sintaxe-invalida").minimosAusentes())
                .containsExactly("DOCUMENTO:sintaxe-invalida");
    }

    // ------------------------------------------------------------ apoio

    private static List<Entrada> entradasDoSchemaSintetico() {
        return entradas(operacao("consultas",
                argumento("id", GraphQLNonNull.nonNull(Scalars.GraphQLID)),
                argumento("filtro", FILTRO)));
    }

    private static List<Entrada> entradas(GraphQLFieldDefinition campo) {
        GraphQLSchema schema = schema(campo);
        Operacao operacao = new Operacao("Query", campo.getName());
        return InventarioGraphql.entradasDe(operacao,
                InventarioGraphql.operacoes(schema).get(operacao));
    }

    private static Set<DimensaoGraphql> dimensoes(List<Entrada> entradas) {
        Set<DimensaoGraphql> presentes = entradas.stream().map(Entrada::dimensao)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(DimensaoGraphql.class)));
        presentes.add(DimensaoGraphql.DOCUMENTO);
        presentes.add(DimensaoGraphql.CORPO_HTTP);
        return presentes;
    }

    private static GraphQLSchema schema(GraphQLFieldDefinition campo) {
        return GraphQLSchema.newSchema()
                .query(GraphQLObjectType.newObject().name("Query").field(campo).build())
                .build();
    }

    private static GraphQLFieldDefinition operacao(String nome, GraphQLArgument... argumentos) {
        GraphQLFieldDefinition.Builder campo = GraphQLFieldDefinition.newFieldDefinition()
                .name(nome).type(Scalars.GraphQLString);
        new ArrayList<>(List.of(argumentos)).forEach(campo::argument);
        return campo.build();
    }

    private static GraphQLArgument argumento(String nome, GraphQLType tipo) {
        return GraphQLArgument.newArgument().name(nome).type((GraphQLInputType) tipo).build();
    }

    private static GraphQLInputObjectField campoDeEntrada(String nome, GraphQLType tipo) {
        return GraphQLInputObjectField.newInputObjectField().name(nome)
                .type((GraphQLInputType) tipo).build();
    }
}
