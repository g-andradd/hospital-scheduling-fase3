package br.com.fiap.hospital.historico.integracao;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.fiap.hospital.contracts.ConsultaPayload;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * O schema publicado, lido por introspeccao — nao o arquivo no disco.
 *
 * <p>Ler o {@code .graphqls} provaria que o texto esta escrito; ler a introspeccao prova
 * que o motor carregou aquilo e que e isso que o cliente enxerga.
 */
@DisplayName("Schema GraphQL do historico")
class SchemaGraphqlIT extends GraphqlITBase {

    private static final String INTROSPECCAO_ENUM = """
            query { __type(name: "StatusConsulta") { enumValues { name } } }
            """;

    private static final String INTROSPECCAO_INPUT = """
            query { __type(name: "CorrigirRegistroHistoricoInput") { inputFields { name } } }
            """;

    private static final String INTROSPECCAO_FILTRO = """
            query { __type(name: "FiltroConsulta") { inputFields { name } } }
            """;

    /**
     * As duas listas vem em requisicoes separadas de proposito.
     *
     * <p>O graphql-java recusa um documento que peca {@code __Type.fields} mais de uma vez
     * — a protecao contra introspeccao abusiva. Juntar queryType e mutationType numa
     * consulta so faria o teste falhar por uma razao que nao tem nada a ver com o schema.
     */
    @Test
    @DisplayName("o schema carrega e expoe as cinco operacoes")
    void schemaCarregaEExpoeAsOperacoes() {
        var queries = executar(tokenMedico(),
                "query { __schema { queryType { fields { name } } } }");
        var mutations = executar(tokenMedico(),
                "query { __schema { mutationType { fields { name } } } }");

        assertThat(queries.temErro())
                .as("erro na introspeccao: %s", queries.corpo().path("errors").toString())
                .isFalse();
        assertThat(queries.corpo().at("/data/__schema/queryType/fields").findValuesAsText("name"))
                .containsExactlyInAnyOrder(
                        "consultasDoPaciente", "minhasConsultas", "consultasDoMedico", "consulta");
        assertThat(mutations.corpo().at("/data/__schema/mutationType/fields")
                .findValuesAsText("name"))
                .containsExactly("corrigirRegistroHistorico");
    }

    /**
     * O enum do schema e o do contrato de eventos sao a mesma lista.
     *
     * <p>Se divergissem, um status projetado pelo M08 poderia nao ter representacao em
     * GraphQL — e a consulta falharia na serializacao, depois de o dado ja estar correto
     * no banco.
     */
    @Test
    @DisplayName("o enum de status tem exatamente os quatro valores do contrato")
    void enumDeStatusEspelhaOContrato() {
        var resposta = executar(tokenMedico(), INTROSPECCAO_ENUM);

        List<String> doContrato = Arrays.stream(ConsultaPayload.Status.values())
                .map(Enum::name).toList();
        assertThat(resposta.corpo().at("/data/__type/enumValues").findValuesAsText("name"))
                .containsExactlyInAnyOrderElementsOf(doContrato)
                .hasSize(4);
    }

    @Test
    @DisplayName("o filtro declara periodo, status e o intervalo")
    void filtroDeclaraOsCamposDoRecorte() {
        var resposta = executar(tokenMedico(), INTROSPECCAO_FILTRO);

        assertThat(resposta.corpo().at("/data/__type/inputFields").findValuesAsText("name"))
                .containsExactlyInAnyOrder("periodo", "status", "de", "ate");
    }

    /**
     * A ausencia e o contrato.
     *
     * <p>Nao existe campo de autor nem contraparte corrigivel dos identificadores. Isso nao
     * e verificado em tempo de execucao por codigo nenhum: e o schema que recusa, na
     * analise do documento. Este teste guarda essa ausencia — um campo acrescentado por
     * descuido no futuro faria a lista crescer e a asercao falhar.
     */
    @Test
    @DisplayName("o input da correcao nao expoe autor nem identificadores corrigiveis")
    void inputDaCorrecaoNaoExpoeIdentidade() {
        var resposta = executar(tokenMedico(), INTROSPECCAO_INPUT);

        List<String> campos = resposta.corpo().at("/data/__type/inputFields")
                .findValuesAsText("name");

        assertThat(campos).containsExactlyInAnyOrder(
                "consultaId", "justificativa", "pacienteNome", "medicoNome",
                "especialidade", "dataHora", "status", "observacoes");
        assertThat(campos)
                .as("autor vem do token; id de paciente e de medico nao sao corrigiveis")
                .doesNotContain("autorId", "medicoAutorId", "pacienteId", "medicoId", "novoConsultaId");
    }
}
