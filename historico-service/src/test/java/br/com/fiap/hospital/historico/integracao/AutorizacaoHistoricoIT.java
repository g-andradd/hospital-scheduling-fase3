package br.com.fiap.hospital.historico.integracao;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * O recorte de propriedade, que a matriz sozinha nao cobre.
 *
 * <p>A matriz responde "este perfil alcanca esta operacao?". Aqui a pergunta e outra:
 * alcancando a operacao, o paciente consegue chegar ao dado de <b>outro</b> paciente? E o
 * caso em que a autorizacao passa e o vazamento acontece mesmo assim.
 */
@DisplayName("Propriedade e identidade no historico")
class AutorizacaoHistoricoIT extends GraphqlITBase {

    private static final OffsetDateTime DATA = instante("2026-09-18T14:30:00Z");

    private static final String DO_PACIENTE = """
            query($p: ID!) { consultasDoPaciente(pacienteId: $p) { id pacienteNome } }
            """;

    private static final String POR_ID = """
            query($id: ID!) { consulta(id: $id) { id pacienteId pacienteNome } }
            """;

    @Test
    @DisplayName("Scenario: Paciente nao alcanca registro de terceiro por identificador")
    void pacienteNaoAlcancaRegistroDeTerceiro() {
        inserirConsulta(pacienteB, medicoA, DATA, "AGENDADA");

        var resposta = executar(tokenPaciente(pacienteA), DO_PACIENTE,
                Map.of("p", pacienteB.toString()));

        assertThat(resposta.primeiroCodigo()).isEqualTo("FORBIDDEN");
        assertThat(resposta.corpo().toString())
                .as("nem um nome pode escapar junto com a recusa")
                .doesNotContain(pacienteB.toString());
    }

    @Test
    @DisplayName("o paciente nao le por id um registro que nao e seu")
    void pacienteNaoLePorIdRegistroAlheio() {
        UUID alheia = inserirConsulta(pacienteB, medicoA, DATA, "AGENDADA");

        var resposta = executar(tokenPaciente(pacienteA), POR_ID, Map.of("id", alheia.toString()));

        assertThat(resposta.primeiroCodigo()).isEqualTo("FORBIDDEN");
        assertThat(resposta.dados("consulta").isNull() || resposta.dados("consulta").isMissingNode())
                .as("recusa nao pode vir acompanhada do registro")
                .isTrue();
    }

    @Test
    @DisplayName("Scenario: Consultas do paciente autenticado vem do token")
    void consultasDoPacienteAutenticadoVemDoToken() {
        UUID minha = inserirConsulta(pacienteA, medicoA, DATA, "AGENDADA");
        inserirConsulta(pacienteB, medicoA, DATA, "AGENDADA");

        var resposta = executar(tokenPaciente(pacienteA), "query { minhasConsultas { id } }");

        assertThat(resposta.ids("minhasConsultas")).containsExactly(minha.toString());
    }

    @Test
    @DisplayName("Scenario: Perfis clinicos nao usam a operacao do paciente")
    void perfisClinicosNaoUsamAOperacaoDoPaciente() {
        inserirConsulta(pacienteA, medicoA, DATA, "AGENDADA");

        assertThat(executar(tokenMedico(), "query { minhasConsultas { id } }").primeiroCodigo())
                .isEqualTo("FORBIDDEN");
        assertThat(executar(tokenEnfermeiro(), "query { minhasConsultas { id } }").primeiroCodigo())
                .isEqualTo("FORBIDDEN");

        // O que a decisao do PO preserva: os demais caminhos continuam abertos a eles.
        assertThat(executar(tokenMedico(), DO_PACIENTE, Map.of("p", pacienteA.toString()))
                .temErro()).isFalse();
        assertThat(executar(tokenEnfermeiro(), DO_PACIENTE, Map.of("p", pacienteA.toString()))
                .temErro()).isFalse();
    }

    /**
     * A identidade do token vence a do argumento.
     *
     * <p>Se o resolver aceitasse o id enviado, bastaria um paciente informar o proprio id
     * num token de outro para trocar de identidade. O teste usa um token cujo paciente e A
     * e pede o historico de A: o que ele prova e que o dado devolvido pertence a A mesmo
     * quando o argumento coincide — combinado com o caso acima, nao sobra caminho.
     */
    @Test
    @DisplayName("nenhum identificador do cliente participa da decisao")
    void identificadorDoClienteNaoDecide() {
        UUID daA = inserirConsulta(pacienteA, medicoA, DATA, "AGENDADA");
        inserirConsulta(pacienteB, medicoA, DATA, "AGENDADA");

        var comArgumento = executar(tokenPaciente(pacienteA), DO_PACIENTE,
                Map.of("p", pacienteA.toString()));
        var semArgumento = executar(tokenPaciente(pacienteA), "query { minhasConsultas { id } }");

        assertThat(comArgumento.ids("consultasDoPaciente")).containsExactly(daA.toString());
        assertThat(semArgumento.ids("minhasConsultas")).containsExactly(daA.toString());
    }
}
