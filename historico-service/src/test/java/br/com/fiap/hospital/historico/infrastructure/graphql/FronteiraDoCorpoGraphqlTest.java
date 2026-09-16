package br.com.fiap.hospital.historico.infrastructure.graphql;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.util.StreamUtils;

/**
 * A fronteira do corpo recusa sem chamar a cadeia, e deixa passar sem alterar nada.
 *
 * <p>O teste de integracao so enxerga o status: um 400 vindo daqui e um 400 vindo da
 * execucao GraphQL sao indistinguiveis de fora. Aqui a cadeia e controlada, entao da para
 * provar as duas coisas que importam — que o corpo recusado <b>nao chega</b> a execucao, e
 * que o corpo aceito chega com os mesmos bytes que o cliente enviou.
 */
@DisplayName("Fronteira do corpo GraphQL")
class FronteiraDoCorpoGraphqlTest {

    private final FronteiraDoCorpoGraphql fronteira = new FronteiraDoCorpoGraphql();
    private final AtomicInteger chamadas = new AtomicInteger();
    private final AtomicReference<byte[]> recebidos = new AtomicReference<>();

    private final FilterChain cadeia = (requisicao, resposta) -> {
        chamadas.incrementAndGet();
        recebidos.set(StreamUtils.copyToByteArray(requisicao.getInputStream()));
    };

    @ParameterizedTest(name = "corpo [{0}]")
    @ValueSource(strings = {"", "   ", "null", "[]", "42", "\"texto\"", "true"})
    @DisplayName("corpo vazio, nulo, array ou escalar é recusado com 400 sem chamar a cadeia")
    void corpoNaoObjetoERecusadoSemChamarACadeia(String corpo) throws Exception {
        MockHttpServletResponse resposta = executar(corpo.getBytes(StandardCharsets.UTF_8));

        assertThat(resposta.getStatus()).isEqualTo(400);
        assertThat(chamadas).as("a execucao GraphQL nao pode ter sido alcancada").hasValue(0);
        exigirRecusaSanitizada(resposta);
    }

    @Test
    @DisplayName("corpo ausente é recusado com 400 sem chamar a cadeia")
    void corpoAusenteERecusado() throws Exception {
        MockHttpServletResponse resposta = executar(null);

        assertThat(resposta.getStatus()).isEqualTo(400);
        assertThat(chamadas).hasValue(0);
        exigirRecusaSanitizada(resposta);
    }

    @Test
    @DisplayName("corpo objeto válido chama a cadeia uma vez, com os mesmos bytes")
    void corpoObjetoChamaACadeiaComOsMesmosBytes() throws Exception {
        byte[] original = "{\"query\":\"query { minhasConsultas { id } }\",\"variables\":{\"x\":\"á\"}}"
                .getBytes(StandardCharsets.UTF_8);

        MockHttpServletResponse resposta = executar(original);

        assertThat(chamadas).hasValue(1);
        assertThat(recebidos.get()).as("nada pode ter sido consumido ou alterado").isEqualTo(original);
        assertThat(resposta.getContentAsString()).as("a fronteira nao escreve na resposta").isEmpty();
    }

    @Test
    @DisplayName("JSON malformado segue deliberadamente o caminho normal")
    void jsonMalformadoSegueOCaminhoNormal() throws Exception {
        byte[] malformado = "{\"query\": ".getBytes(StandardCharsets.UTF_8);

        executar(malformado);

        assertThat(chamadas)
                .as("JSON invalido ja e recusado adiante com codigo estavel; nao e deste filtro")
                .hasValue(1);
        assertThat(recebidos.get()).isEqualTo(malformado);
    }

    @Test
    @DisplayName("fora de POST /graphql a fronteira não interfere")
    void foraDoEndpointNaoInterfere() throws Exception {
        MockHttpServletRequest requisicao = new MockHttpServletRequest("POST", "/outro");
        requisicao.setContent("null".getBytes(StandardCharsets.UTF_8));

        fronteira.doFilter(requisicao, new MockHttpServletResponse(), cadeia);

        assertThat(chamadas).hasValue(1);
    }

    private MockHttpServletResponse executar(byte[] corpo) throws Exception {
        MockHttpServletRequest requisicao = new MockHttpServletRequest("POST", "/graphql");
        requisicao.setContentType("application/json");
        if (corpo != null) {
            requisicao.setContent(corpo);
        }
        MockHttpServletResponse resposta = new MockHttpServletResponse();
        fronteira.doFilter(requisicao, resposta, cadeia);
        return resposta;
    }

    private static void exigirRecusaSanitizada(MockHttpServletResponse resposta) throws Exception {
        String corpo = resposta.getContentAsString();
        JsonNode arvore = new ObjectMapper().readTree(corpo);

        assertThat(arvore.path("errors").path(0).path("extensions").path("code").asText())
                .isEqualTo("BAD_REQUEST");
        assertThat(corpo).doesNotContain("Exception", "java.", "org.springframework", "stack");
    }
}
