package br.com.fiap.hospital.agendamento.infrastructure.web;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.*;

class CorrelationIdFilterTest {
    @Test void contextoRestauraMesmoQuandoCadeiaFalha() {
        var request=new MockHttpServletRequest();request.addHeader("X-Correlation-Id","request-atual");
        var response=new MockHttpServletResponse();
        MDC.put("correlationId","anterior");
        try {
            assertThatThrownBy(()->new CorrelationIdFilter().doFilter(request,response,(req,res)->{
                assertThat(MDC.get("correlationId")).isEqualTo("request-atual");
                throw new IllegalStateException("teste");
            })).isInstanceOf(IllegalStateException.class);
            assertThat(MDC.get("correlationId")).isEqualTo("anterior");
            assertThat(response.getHeader("X-Correlation-Id")).isEqualTo("request-atual");
        } finally {MDC.clear();}
    }
    @Test void requestSemHeaderRecebeIdERemoveMdcAoTerminar() throws Exception {
        var request=new MockHttpServletRequest();var response=new MockHttpServletResponse();
        new CorrelationIdFilter().doFilter(request,response,(req,res)->{
            assertThat(MDC.get("correlationId")).isEqualTo(CorrelationIdFilter.de(request)).isNotBlank();
        });
        assertThat(MDC.get("correlationId")).isNull();
        assertThat(response.getHeader("X-Correlation-Id")).isNotBlank();
    }
}
