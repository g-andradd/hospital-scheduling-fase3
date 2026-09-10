package br.com.fiap.hospital.historico.integracao;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.fiap.hospital.historico.integracao.MatrizDeAutorizacaoGraphql.Celula;
import br.com.fiap.hospital.historico.integracao.MatrizDeAutorizacaoGraphql.Expectativa;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Cada celula da matriz GraphQL de docs/02-especificacao-funcional.md secao 3 vira um caso.
 *
 * <p>Os casos vem da leitura da tabela, nao de uma lista escrita aqui. A consequencia e a
 * que importa: mudar a tabela muda os testes, e uma operacao nova aparece como tres casos
 * falhando ate ser implementada.
 */
@DisplayName("Matriz de autorizacao GraphQL")
class MatrizDeAutorizacaoGraphqlIT extends GraphqlITBase {

    private static final OffsetDateTime DATA = instante("2026-09-18T14:30:00Z");

    static Stream<Celula> celulas() {
        return MatrizDeAutorizacaoGraphql.celulas().stream();
    }

    /**
     * Sem esta asercao, a anterior seria decorativa.
     *
     * <p>Se o titulo da secao mudasse ou a tabela fosse reformatada, o parser devolveria
     * lista vazia, {@code celulas()} nao geraria caso algum e a suite passaria tendo
     * verificado exatamente nada — a falha que o M03 registrou.
     */
    @Test
    @DisplayName("a leitura encontrou 5 operacoes, 3 perfis e 15 celulas")
    void leituraEncontrouAMatrizCompleta() {
        assertThat(MatrizDeAutorizacaoGraphql.quantidadeDeOperacoes())
                .as("a tabela da secao 3 tem cinco operacoes")
                .isEqualTo(5);
        assertThat(MatrizDeAutorizacaoGraphql.perfis())
                .containsExactly("MEDICO", "ENFERMEIRO", "PACIENTE");
        assertThat(MatrizDeAutorizacaoGraphql.celulas()).hasSize(15);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("celulas")
    @DisplayName("Scenario: Cada celula da matriz e honrada")
    void celulaDaMatrizEHonrada(Celula celula) {
        UUID consultaDoPacienteA = inserirConsulta(pacienteA, medicoA, DATA, "AGENDADA");
        String token = tokenDe(celula.perfil());

        var resposta = invocar(celula, token, consultaDoPacienteA);

        if (celula.expectativa() == Expectativa.PROIBIDO) {
            assertThat(resposta.primeiroCodigo())
                    .as("%s deveria receber FORBIDDEN", celula)
                    .isEqualTo("FORBIDDEN");
        } else {
            assertThat(resposta.temErro())
                    .as("%s deveria ser permitido, mas veio %s", celula,
                            resposta.corpo().path("errors").toString())
                    .isFalse();
        }
    }

    private String tokenDe(String perfil) {
        return switch (perfil) {
            case "MEDICO" -> tokenMedico();
            case "ENFERMEIRO" -> tokenEnfermeiro();
            case "PACIENTE" -> tokenPaciente(pacienteA);
            default -> throw new IllegalArgumentException("Perfil desconhecido: " + perfil);
        };
    }

    /**
     * O alvo de cada operacao e sempre o proprio recurso do perfil sob teste.
     *
     * <p>A celula do paciente e "permitido com recorte": mandar o id de terceiro aqui
     * mediria a regra de propriedade, que tem cenarios proprios em
     * {@code AutorizacaoHistoricoIT}. Esta suite responde apenas se o perfil alcanca a
     * operacao.
     */
    private RespostaGraphql invocar(Celula celula, String token, UUID consultaId) {
        return switch (celula.operacao()) {
            case "consultasDoPaciente" -> executar(token,
                    "query($p: ID!) { consultasDoPaciente(pacienteId: $p) { id } }",
                    Map.of("p", pacienteA.toString()));
            case "minhasConsultas" -> executar(token, "query { minhasConsultas { id } }");
            case "consultasDoMedico" -> executar(token,
                    "query($m: ID!) { consultasDoMedico(medicoId: $m) { id } }",
                    Map.of("m", medicoA.toString()));
            case "consulta" -> executar(token,
                    "query($id: ID!) { consulta(id: $id) { id } }",
                    Map.of("id", consultaId.toString()));
            case "corrigirRegistroHistorico" -> executar(token,
                    """
                    mutation($in: CorrigirRegistroHistoricoInput!) {
                      corrigirRegistroHistorico(input: $in) { id }
                    }
                    """,
                    Map.of("in", Map.of(
                            "consultaId", consultaId.toString(),
                            "justificativa", "erro de digitacao no nome",
                            "pacienteNome", "Nome Corrigido")));
            default -> throw new IllegalArgumentException(
                    "Operacao sem caso de invocacao: " + celula.operacao());
        };
    }

    /** Guarda contra uma operacao nova entrar na tabela sem ganhar caso de invocacao. */
    @Test
    @DisplayName("toda operacao da tabela tem um caso de invocacao")
    void todaOperacaoDaTabelaTemInvocacao() {
        List<String> operacoes = MatrizDeAutorizacaoGraphql.celulas().stream()
                .map(Celula::operacao).distinct().toList();

        assertThat(operacoes).containsExactlyInAnyOrder(
                "consultasDoPaciente", "minhasConsultas", "consultasDoMedico",
                "consulta", "corrigirRegistroHistorico");
    }
}
