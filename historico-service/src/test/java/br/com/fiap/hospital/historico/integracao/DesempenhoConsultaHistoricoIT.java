package br.com.fiap.hospital.historico.integracao;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * O que a aplicacao realmente envia ao banco, e quantas vezes.
 *
 * <p>As duas garantias desta suite so podem ser observadas no SQL emitido. Um filtro
 * aplicado em memoria devolve o mesmo resultado de um filtro aplicado no comando, e um N+1
 * devolve o mesmo resultado de uma consulta unica — nenhuma asercao sobre os dados
 * distingue os casos. Por isso a evidencia vem de {@link SqlCapturado}, que registra o que
 * o Hibernate mandou durante a requisicao GraphQL, e nao de um {@code EXPLAIN} que o teste
 * escrevesse por conta propria: esse provaria o plano de um SQL inventado aqui.
 */
@DisplayName("Desempenho da leitura do historico")
class DesempenhoConsultaHistoricoIT extends GraphqlITBase {

    private static final String CONSULTA = """
            query($p: ID!, $f: FiltroConsulta) {
              consultasDoPaciente(pacienteId: $p, filtro: $f) {
                id pacienteNome medicoNome especialidade dataHora status observacoes
              }
            }
            """;

    private static final String POR_MEDICO = """
            query($m: ID!, $f: FiltroConsulta) {
              consultasDoMedico(medicoId: $m, filtro: $f) { id dataHora status }
            }
            """;

    private static final OffsetDateTime DATA = instante("2026-09-18T14:30:00Z");

    @Test
    @DisplayName("Scenario: Filtro e aplicado no armazenamento")
    void filtroEAplicadoNoArmazenamento() {
        inserirConsulta(pacienteA, medicoA, DATA, "AGENDADA");
        inserirConsulta(pacienteA, medicoA, DATA, "CANCELADA");
        inserirConsulta(pacienteA, medicoA, DATA.minusYears(1), "AGENDADA");

        SqlCapturado.limpar();
        var resposta = executar(tokenMedico(), CONSULTA, Map.of(
                "p", pacienteA.toString(),
                "f", Map.of("periodo", "FUTURAS", "status", List.of("AGENDADA"))));

        assertThat(resposta.ids("consultasDoPaciente"))
                .as("o recorte precisa valer para o resultado")
                .hasSize(1);

        assertThat(primeiroSelect())
                .as("os tres recortes precisam estar no comando enviado, e nao numa selecao posterior")
                .contains("paciente_id=?")
                .contains("data_hora>=?")
                .contains("status in (?)");
    }

    @Test
    @DisplayName("Scenario: Filtro e aplicado no armazenamento (por medico)")
    void recorteDeMedicoEAplicadoNoArmazenamento() {
        inserirConsulta(pacienteA, medicoA, DATA, "AGENDADA");
        inserirConsulta(pacienteB, medicoB, DATA, "AGENDADA");

        SqlCapturado.limpar();
        assertThat(executar(tokenMedico(), POR_MEDICO, Map.of("m", medicoA.toString()))
                .ids("consultasDoMedico"))
                .hasSize(1);

        assertThat(primeiroSelect())
                .as("o recorte por medico tambem tem de estar no comando enviado")
                .contains("medico_id=?")
                .doesNotContain("paciente_id=?");
    }

    /**
     * O intervalo explicito precisa aparecer como duas comparacoes, e nao como uma.
     *
     * <p>Um filtro que emitisse so {@code data_hora>=?} devolveria o mesmo resultado sempre
     * que nenhum registro caisse depois de {@code ate} — o defeito ficaria latente ate o dia
     * em que caisse. Ver os dois operadores no SQL e o que fecha a semantica semiaberta.
     */
    @Test
    @DisplayName("Scenario: Intervalo explicito e aplicado no armazenamento")
    void intervaloExplicitoEAplicadoNoArmazenamento() {
        inserirConsulta(pacienteA, medicoA, DATA, "AGENDADA");

        SqlCapturado.limpar();
        executar(tokenMedico(), CONSULTA, Map.of(
                "p", pacienteA.toString(),
                "f", Map.of("de", DATA.toString(), "ate", DATA.plusDays(7).toString())));

        String sql = primeiroSelect();
        assertThat(sql)
                .as("de e inclusivo e ate e exclusivo: os dois operadores no comando")
                .contains("data_hora>=?")
                .contains("data_hora<?");
    }

    private String primeiroSelect() {
        var selects = SqlCapturado.selectsSobre("consulta_historico");
        assertThat(selects)
                .as("a requisicao tem de ter emitido pelo menos um SELECT sobre o snapshot")
                .isNotEmpty();
        return selects.getFirst().toLowerCase();
    }

    /**
     * Contagem positiva dos dois lados: zero contra zero seria igualdade sem significado.
     */
    @Test
    @DisplayName("Scenario: Resultado maior nao multiplica comandos de leitura")
    void resultadoMaiorNaoMultiplicaComandos() {
        inserirConsulta(pacienteA, medicoA, DATA, "AGENDADA");
        Map<String, Object> variaveis = Map.of("p", pacienteA.toString());

        SqlCapturado.limpar();
        assertThat(executar(tokenMedico(), CONSULTA, variaveis).ids("consultasDoPaciente"))
                .hasSize(1);
        int comUm = SqlCapturado.selectsSobre("consulta_historico").size();

        for (int i = 0; i < 40; i++) {
            inserirConsulta(pacienteA, medicoA, DATA.plusDays(i + 1), "AGENDADA");
        }

        SqlCapturado.limpar();
        assertThat(executar(tokenMedico(), CONSULTA, variaveis).ids("consultasDoPaciente"))
                .hasSize(41);
        int comMuitos = SqlCapturado.selectsSobre("consulta_historico").size();

        assertThat(comUm)
                .as("sem comando nenhum a comparacao seria vazia")
                .isPositive();
        assertThat(comMuitos)
                .as("41 registros nao podem custar mais comandos que 1 — isso seria N+1")
                .isEqualTo(comUm);
    }
}
