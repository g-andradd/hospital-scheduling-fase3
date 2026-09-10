package br.com.fiap.hospital.historico.integracao;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * O recorte da leitura, contra PostgreSQL real e com relogio fixo.
 *
 * <p>O relogio fixo nao e conveniencia: sem ele, "exatamente agora" muda entre a montagem
 * dos dados e a asercao, e a fronteira de FUTURAS/PASSADAS — que e justamente o ponto
 * onde a regra pode estar errada — deixaria de ser verificavel.
 */
@DisplayName("Filtro do historico")
class FiltroHistoricoIT extends GraphqlITBase {

    private static final String CONSULTA = """
            query($pacienteId: ID!, $filtro: FiltroConsulta) {
              consultasDoPaciente(pacienteId: $pacienteId, filtro: $filtro) {
                id dataHora status
              }
            }
            """;

    private static final OffsetDateTime PASSADO = instante("2026-09-01T09:00:00Z");
    private static final OffsetDateTime AGORA_EXATO = instante("2026-09-10T12:00:00Z");
    private static final OffsetDateTime FUTURO = instante("2026-09-20T15:00:00Z");

    private List<String> consultar(Map<String, Object> filtro) {
        var resposta = executar(tokenMedico(), CONSULTA,
                filtro == null
                        ? Map.of("pacienteId", pacienteA.toString())
                        : Map.of("pacienteId", pacienteA.toString(), "filtro", filtro));
        assertThat(resposta.temErro())
                .as("erro inesperado: %s", resposta.corpo().path("errors").toString())
                .isFalse();
        return resposta.ids("consultasDoPaciente");
    }

    @Test
    @DisplayName("Scenario: Periodo TODAS nao recorta pelo relogio")
    void periodoTodasNaoRecortaPeloRelogio() {
        UUID passada = inserirConsulta(pacienteA, medicoA, PASSADO, "REALIZADA");
        UUID presente = inserirConsulta(pacienteA, medicoA, AGORA_EXATO, "CONFIRMADA");
        UUID futura = inserirConsulta(pacienteA, medicoA, FUTURO, "AGENDADA");

        assertThat(consultar(Map.of("periodo", "TODAS")))
                .containsExactlyInAnyOrder(passada.toString(), presente.toString(), futura.toString());
    }

    @Test
    @DisplayName("Scenario: Periodo FUTURAS devolve apenas o que ainda nao passou")
    void periodoFuturasDevolveApenasOQueNaoPassou() {
        inserirConsulta(pacienteA, medicoA, PASSADO, "REALIZADA");
        UUID futura = inserirConsulta(pacienteA, medicoA, FUTURO, "AGENDADA");

        assertThat(consultar(Map.of("periodo", "FUTURAS"))).containsExactly(futura.toString());
    }

    @Test
    @DisplayName("Scenario: Periodo PASSADAS devolve apenas o que ja ocorreu")
    void periodoPassadasDevolveApenasOQueOcorreu() {
        UUID passada = inserirConsulta(pacienteA, medicoA, PASSADO, "REALIZADA");
        inserirConsulta(pacienteA, medicoA, FUTURO, "AGENDADA");

        assertThat(consultar(Map.of("periodo", "PASSADAS"))).containsExactly(passada.toString());
    }

    /**
     * A fronteira exata e onde a regra costuma estar errada por um caractere.
     *
     * <p>Trocar {@code >=} por {@code >} faria o registro do instante atual sumir das duas
     * consultas — e nenhum outro teste notaria, porque todos os demais dados estao longe
     * da fronteira.
     */
    @Test
    @DisplayName("Scenario: Registro exatamente no instante atual pertence ao futuro")
    void registroNoInstanteAtualPertenceAoFuturo() {
        UUID noInstante = inserirConsulta(pacienteA, medicoA, AGORA_EXATO, "CONFIRMADA");

        assertThat(consultar(Map.of("periodo", "FUTURAS"))).contains(noInstante.toString());
        assertThat(consultar(Map.of("periodo", "PASSADAS"))).doesNotContain(noInstante.toString());
    }

    @Test
    @DisplayName("Scenario: Intervalo explicito inclui o inicio e exclui o fim")
    void intervaloIncluiInicioEExcluiFim() {
        OffsetDateTime de = instante("2026-09-05T10:00:00Z");
        OffsetDateTime ate = instante("2026-09-15T10:00:00Z");
        UUID noInicio = inserirConsulta(pacienteA, medicoA, de, "AGENDADA");
        UUID noFim = inserirConsulta(pacienteA, medicoA, ate, "AGENDADA");

        List<String> ids = consultar(Map.of("de", de.toString(), "ate", ate.toString()));

        assertThat(ids).contains(noInicio.toString());
        assertThat(ids)
                .as("o limite superior e exclusivo: [de, ate)")
                .doesNotContain(noFim.toString());
    }

    @Test
    @DisplayName("Scenario: Filtro por status seleciona os valores informados")
    void filtroPorStatusSelecionaOsValoresInformados() {
        UUID agendada = inserirConsulta(pacienteA, medicoA, FUTURO, "AGENDADA");
        UUID cancelada = inserirConsulta(pacienteA, medicoA, FUTURO, "CANCELADA");
        inserirConsulta(pacienteA, medicoA, FUTURO, "REALIZADA");

        assertThat(consultar(Map.of("status", List.of("AGENDADA"))))
                .containsExactly(agendada.toString());
        assertThat(consultar(Map.of("status", List.of("AGENDADA", "CANCELADA"))))
                .containsExactlyInAnyOrder(agendada.toString(), cancelada.toString());
    }

    @Test
    @DisplayName("Scenario: Lista de status vazia nao restringe o resultado")
    void listaDeStatusVaziaNaoRestringe() {
        inserirConsulta(pacienteA, medicoA, PASSADO, "REALIZADA");
        inserirConsulta(pacienteA, medicoA, FUTURO, "AGENDADA");

        assertThat(consultar(Map.of("status", List.of())))
                .as("lista vazia e ausencia de filtro, nao 'nenhum status casa'")
                .hasSize(2)
                .containsExactlyInAnyOrderElementsOf(consultar(null));
    }

    @Test
    @DisplayName("Scenario: Periodo, intervalo e status sao combinados por conjuncao")
    void periodoIntervaloEStatusSaoCombinadosPorConjuncao() {
        UUID alvo = inserirConsulta(pacienteA, medicoA, instante("2026-09-18T10:00:00Z"), "AGENDADA");
        inserirConsulta(pacienteA, medicoA, instante("2026-09-18T10:00:00Z"), "CANCELADA");
        inserirConsulta(pacienteA, medicoA, PASSADO, "AGENDADA");
        inserirConsulta(pacienteA, medicoA, instante("2026-09-25T10:00:00Z"), "AGENDADA");

        List<String> ids = consultar(Map.of(
                "periodo", "FUTURAS",
                "status", List.of("AGENDADA"),
                "de", instante("2026-09-15T00:00:00Z").toString(),
                "ate", instante("2026-09-20T00:00:00Z").toString()));

        assertThat(ids)
                .as("cada condicao sozinha deixaria passar um dos outros tres registros")
                .containsExactly(alvo.toString());
    }

    @Test
    @DisplayName("Scenario: Ausencia de registros devolve colecao vazia")
    void ausenciaDeRegistrosDevolveColecaoVazia() {
        inserirConsulta(pacienteA, medicoA, PASSADO, "REALIZADA");

        var resposta = executar(tokenMedico(), CONSULTA, Map.of(
                "pacienteId", pacienteA.toString(),
                "filtro", Map.of("status", List.of("CANCELADA"))));

        assertThat(resposta.temErro())
                .as("resultado vazio e resposta valida, nao erro")
                .isFalse();
        assertThat(resposta.ids("consultasDoPaciente")).isEmpty();
    }

    /**
     * Ordem total, e nao apenas ordenada por data.
     *
     * <p>Com tres registros no mesmo instante, ordenar so por {@code data_hora} deixa o
     * desempate para o plano do PostgreSQL: a mesma consulta pode devolver ordens
     * diferentes entre execucoes, e o cliente nao teria como paginar de forma estavel.
     */
    @Test
    @DisplayName("Scenario: Ordenacao e total e deterministica")
    void ordenacaoEDeterministica() {
        OffsetDateTime empate = instante("2026-09-12T08:00:00Z");
        inserirConsulta(pacienteA, medicoA, empate, "AGENDADA");
        inserirConsulta(pacienteA, medicoA, empate, "CONFIRMADA");
        inserirConsulta(pacienteA, medicoA, empate, "CANCELADA");
        inserirConsulta(pacienteA, medicoA, PASSADO, "REALIZADA");

        List<String> primeira = consultar(null);
        List<String> segunda = consultar(null);
        List<String> terceira = consultar(null);

        assertThat(primeira).isEqualTo(segunda).isEqualTo(terceira).hasSize(4);
        assertThat(primeira.subList(1, 4))
                .as("no empate de dataHora, o id decide — e decide sempre igual")
                .isSorted();
    }
}
