package br.com.fiap.hospital.notificacao.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@DisplayName("Filtro de correlação da notificação")
class CorrelationIdFilterTest {

    private final MockHttpServletRequest requisicao = new MockHttpServletRequest();
    private final MockHttpServletResponse resposta = new MockHttpServletResponse();

    @AfterEach
    void limparMdc() {
        MDC.clear();
    }

    @Test
    @DisplayName("header recebido é honrado no atributo, na resposta e no MDC durante a cadeia")
    void headerRecebidoEHonrado() throws Exception {
        requisicao.addHeader(CorrelationIdFilter.CABECALHO, "id-do-cliente");
        AtomicReference<String> duranteACadeia = new AtomicReference<>();

        new CorrelationIdFilter().doFilter(requisicao, resposta,
                (req, res) -> duranteACadeia.set(MDC.get(CorrelationIdFilter.ATRIBUTO)));

        assertThat(duranteACadeia).hasValue("id-do-cliente");
        assertThat(requisicao.getAttribute(CorrelationIdFilter.ATRIBUTO)).isEqualTo("id-do-cliente");
        assertThat(resposta.getHeader(CorrelationIdFilter.CABECALHO)).isEqualTo("id-do-cliente");
        assertThat(MDC.get(CorrelationIdFilter.ATRIBUTO)).isNull();
    }

    @ParameterizedTest(name = "header [{0}]")
    @ValueSource(strings = {"", "   "})
    @DisplayName("header vazio gera um id novo")
    void headerVazioGeraId(String vazio) throws Exception {
        requisicao.addHeader(CorrelationIdFilter.CABECALHO, vazio);
        AtomicReference<String> duranteACadeia = new AtomicReference<>();

        new CorrelationIdFilter().doFilter(requisicao, resposta,
                (req, res) -> duranteACadeia.set(MDC.get(CorrelationIdFilter.ATRIBUTO)));

        assertThat(duranteACadeia.get()).isNotBlank().isNotEqualTo(vazio);
        assertThat(resposta.getHeader(CorrelationIdFilter.CABECALHO)).isEqualTo(duranteACadeia.get());
        assertThat(requisicao.getAttribute(CorrelationIdFilter.ATRIBUTO)).isEqualTo(duranteACadeia.get());
    }

    @Test
    @DisplayName("header ausente gera um id novo, removido do MDC ao terminar")
    void headerAusenteGeraId() throws Exception {
        AtomicReference<String> duranteACadeia = new AtomicReference<>();

        new CorrelationIdFilter().doFilter(requisicao, resposta,
                (req, res) -> duranteACadeia.set(MDC.get(CorrelationIdFilter.ATRIBUTO)));

        assertThat(duranteACadeia.get()).isNotBlank();
        assertThat(resposta.getHeader(CorrelationIdFilter.CABECALHO)).isEqualTo(duranteACadeia.get());
        assertThat(MDC.get(CorrelationIdFilter.ATRIBUTO)).isNull();
    }

    @Test
    @DisplayName("contexto anterior é restaurado mesmo quando a cadeia lança exceção")
    void contextoAnteriorRestauradoNaFalha() {
        requisicao.addHeader(CorrelationIdFilter.CABECALHO, "request-atual");
        MDC.put(CorrelationIdFilter.ATRIBUTO, "anterior");

        assertThatThrownBy(() -> new CorrelationIdFilter().doFilter(requisicao, resposta, (req, res) -> {
            assertThat(MDC.get(CorrelationIdFilter.ATRIBUTO)).isEqualTo("request-atual");
            throw new IllegalStateException("falha da cadeia");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(MDC.get(CorrelationIdFilter.ATRIBUTO)).isEqualTo("anterior");
        assertThat(resposta.getHeader(CorrelationIdFilter.CABECALHO)).isEqualTo("request-atual");
    }

    @Test
    @DisplayName("roda com a maior precedência, antes da cadeia de segurança")
    void maiorPrecedencia() {
        assertThat(CorrelationIdFilter.class.getAnnotation(Order.class).value()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
    }
}
