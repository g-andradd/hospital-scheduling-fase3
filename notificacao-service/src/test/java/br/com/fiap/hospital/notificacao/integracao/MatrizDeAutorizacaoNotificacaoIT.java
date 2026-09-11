package br.com.fiap.hospital.notificacao.integracao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import br.com.fiap.hospital.notificacao.integracao.MatrizDeAutorizacaoNotificacao.Celula;
import br.com.fiap.hospital.notificacao.integracao.MatrizDeAutorizacaoNotificacao.Expectativa;
import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;

/**
 * Cada celula da tabela do notificacao-service em docs/02-especificacao-funcional.md secao 3.
 *
 * <p>Um metodo por celula, cada um com o nome do seu Scenario — um metodo parametrizado unico
 * teria um so nome para tres Scenarios. Mas nenhum deles decide o resultado: cada um busca a
 * celula do seu perfil no documento e entrega a expectativa lida ao mesmo verificador. Mudar
 * a celula no documento muda o que o teste exige; remover a celula faz a busca falhar.
 */
@DisplayName("Matriz de autorizacao do notificacao-service")
class MatrizDeAutorizacaoNotificacaoIT extends NotificacaoITBase {

    private static final String ACESSO_NEGADO = "https://hospital.fiap.br/erros/acesso-negado";

    /**
     * Sem esta asercao as demais seriam decorativas: se o titulo da tabela mudasse, a leitura
     * voltaria vazia e as buscas de celula falhariam por outro motivo, ou nem rodariam.
     */
    @Test
    @DisplayName("a leitura encontrou 1 endpoint, 3 perfis e 3 celulas")
    void matrizTemUmEndpointTresPerfisETresCelulas() {
        assertThat(MatrizDeAutorizacaoNotificacao.quantidadeDeEndpoints()).isEqualTo(1);
        assertThat(MatrizDeAutorizacaoNotificacao.endpoints())
                .containsExactly("POST /internal/lembretes/executar");
        assertThat(MatrizDeAutorizacaoNotificacao.perfis())
                .containsExactly("MEDICO", "ENFERMEIRO", "PACIENTE");
        assertThat(MatrizDeAutorizacaoNotificacao.celulas()).hasSize(3);
    }

    @Test
    @DisplayName("Scenario: Medico dispara a execucao")
    void medicoDisparaAExecucao() throws Exception {
        verificar(MatrizDeAutorizacaoNotificacao.celula("MEDICO"));
    }

    @Test
    @DisplayName("Scenario: Enfermeiro dispara a execucao")
    void enfermeiroDisparaAExecucao() throws Exception {
        verificar(MatrizDeAutorizacaoNotificacao.celula("ENFERMEIRO"));
    }

    @Test
    @DisplayName("Scenario: Paciente e recusado sem executar a varredura")
    void pacienteERecusadoSemExecutarAVarredura() throws Exception {
        verificar(MatrizDeAutorizacaoNotificacao.celula("PACIENTE"));
    }

    /** O verificador comum: a expectativa vem da celula, e nao de quem chama. */
    private void verificar(Celula celula) throws Exception {
        UUID consulta = agendar(AGORA.plus(Duration.ofHours(6)), "AGENDADA");

        var resposta = mvc.perform(request(HttpMethod.valueOf(celula.metodo()), celula.endpoint())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenDe(celula.perfil())))
                .andReturn().getResponse();

        if (celula.expectativa() == Expectativa.PERMITIDO) {
            assertThat(resposta.getStatus()).as("%s: a celula permite", celula).isEqualTo(200);
            assertThat((Integer) JsonPath.read(resposta.getContentAsString(), "$.lembretesEnviados"))
                    .isEqualTo(1);
            assertThat(lembretesRegistrados(consulta)).isEqualTo(1);
        } else {
            assertThat(resposta.getStatus()).as("%s: a celula proibe", celula).isEqualTo(403);
            assertThat(resposta.getContentType()).startsWith("application/problem+json");
            assertThat((String) JsonPath.read(resposta.getContentAsString(), "$.type"))
                    .as("o 403 sai de RespostaDeSeguranca, e nao do tratador de erro interno")
                    .isEqualTo(ACESSO_NEGADO);
            Mockito.verify(lembretes, Mockito.never()).executar();
            Mockito.verify(sender, Mockito.never()).enviar(Mockito.any());
            assertThat(lembretesRegistrados(consulta)).isZero();
        }
    }
}
