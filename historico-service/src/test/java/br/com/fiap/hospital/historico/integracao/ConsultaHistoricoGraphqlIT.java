package br.com.fiap.hospital.historico.integracao;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** As quatro leituras, com as dimensoes que o M09 promete a quem consulta. */
@DisplayName("Leitura do historico por GraphQL")
class ConsultaHistoricoGraphqlIT extends GraphqlITBase {

    private static final String POR_ID = """
            query($id: ID!) {
              consulta(id: $id) {
                id pacienteId pacienteNome medicoId medicoNome especialidade
                dataHora status observacoes criadoEm atualizadoEm
              }
            }
            """;

    private static final String DO_MEDICO = """
            query($medicoId: ID!) { consultasDoMedico(medicoId: $medicoId) { id } }
            """;

    private static final String MINHAS = "query { minhasConsultas { id } }";

    private static final OffsetDateTime DATA = instante("2026-09-18T14:30:00Z");

    @Test
    @DisplayName("Scenario: Identificador existente devolve o registro")
    void identificadorExistenteDevolveORegistro() {
        UUID id = inserirConsulta(pacienteA, medicoA, DATA, "CONFIRMADA", "trazer exames");

        var resposta = executar(tokenMedico(), POR_ID, Map.of("id", id.toString()));

        assertThat(resposta.temErro()).isFalse();
        var registro = resposta.dados("consulta");
        assertThat(registro.path("id").asText()).isEqualTo(id.toString());
        assertThat(registro.path("pacienteId").asText()).isEqualTo(pacienteA.toString());
        assertThat(registro.path("medicoId").asText()).isEqualTo(medicoA.toString());
        assertThat(registro.path("especialidade").asText()).isEqualTo("Cardiologia");
        assertThat(registro.path("status").asText()).isEqualTo("CONFIRMADA");
        assertThat(registro.path("observacoes").asText()).isEqualTo("trazer exames");
        assertThat(OffsetDateTime.parse(registro.path("dataHora").asText()))
                .isEqualTo(DATA);
        assertThat(registro.path("criadoEm").isNull()).isFalse();
        assertThat(registro.path("atualizadoEm").isNull()).isFalse();
    }

    /**
     * Inexistente e recusa, nao ausencia silenciosa.
     *
     * <p>Devolver {@code null} sem erro faria "nao existe" e "existe mas voce nao pode ver"
     * chegarem identicos ao cliente, e um bug de filtro passaria por registro inexistente.
     */
    @Test
    @DisplayName("Scenario: Identificador inexistente e recusado como nao encontrado")
    void identificadorInexistenteERecusado() {
        var resposta = executar(tokenMedico(), POR_ID, Map.of("id", UUID.randomUUID().toString()));

        assertThat(resposta.primeiroCodigo()).isEqualTo("NOT_FOUND");
        assertThat(resposta.corpo().path("errors").path(0).path("extensions").path("classification")
                .asText())
                .as("classificacao interna nao pode virar erro de servidor")
                .isNotEqualTo("INTERNAL_ERROR");
    }

    @Test
    @DisplayName("a consulta por medico recorta pelo medico informado")
    void consultaPorMedicoRecortaPeloMedico() {
        UUID doMedicoA = inserirConsulta(pacienteA, medicoA, DATA, "AGENDADA");
        inserirConsulta(pacienteB, medicoB, DATA, "AGENDADA");

        var resposta = executar(tokenMedico(), DO_MEDICO, Map.of("medicoId", medicoA.toString()));

        assertThat(resposta.ids("consultasDoMedico")).containsExactly(doMedicoA.toString());
    }

    /**
     * O paciente do token, e nada alem dele.
     *
     * <p>A operacao nao recebe identificador — se recebesse, este teste nao teria como
     * distinguir "resolveu do token" de "usou o argumento que passei".
     */
    @Test
    @DisplayName("minhasConsultas resolve o paciente do token")
    void minhasConsultasResolveOPacienteDoToken() {
        UUID minha = inserirConsulta(pacienteA, medicoA, DATA, "AGENDADA");
        inserirConsulta(pacienteB, medicoA, DATA, "AGENDADA");

        var resposta = executar(tokenPaciente(pacienteA), MINHAS);

        assertThat(resposta.temErro()).isFalse();
        assertThat(resposta.ids("minhasConsultas")).containsExactly(minha.toString());
    }
}
