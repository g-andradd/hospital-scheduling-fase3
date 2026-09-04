package br.com.fiap.hospital.agendamento.integracao;

import static org.assertj.core.api.Assertions.*;
import br.com.fiap.hospital.agendamento.domain.*;
import br.com.fiap.hospital.contracts.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.MDC;

class CorrelacaoEOffsetDoEventoIT extends M05JpaBase {
    @Test 
    // Scenario: Fato sem request recebe correlação estável
    void duasCorrelacoesEOrigemSemRequestSaoEstaveis() {
        try {
            MDC.put("correlationId","request-a");criar();
            MDC.put("correlationId","request-b");agendar.executar(comando(paciente,medico,INICIO.plusDays(1)));
            MDC.clear();agendar.executar(comando(paciente,medico,INICIO.plusDays(2)));
            var e=eventos();
            assertThat(e).extracting(EventoEnvelope::correlationId).contains("request-a","request-b").doesNotHaveDuplicates();
            assertThat(e).allSatisfy(v->{
                assertThat(v.occurredAt()).isEqualTo(RELOGIO.instant());
                assertThat(v.correlationId()).isNotBlank();
            });
            assertThat(eventos()).isEqualTo(e); // MDC da requisição não participa da releitura
        } finally {MDC.clear();}
    }
    @ParameterizedTest(name="Offset deriva da zona no instante do agendamento: {0}")
    @CsvSource({"2018-01-15T14:00:00+05:00,-02:00","2018-07-15T14:00:00+05:00,-03:00","2026-10-05T14:00:00+05:00,-03:00"})
    
    // Scenario: Offset deriva da zona no instante do agendamento
    void regrasHistoricasIndependemDaSessaoEDaJvm(String entrada,String offset) {
        var anterior=TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"));
            var data=OffsetDateTime.parse(entrada);
            tx.executeWithoutResult(s-> {
                jdbc.execute("SET LOCAL TIME ZONE 'Pacific/Auckland'");
                var c=Consulta.reconstituir(UUID.randomUUID(),paciente,medico,registrante,new PeriodoConsulta(data,30),
                    StatusConsulta.REALIZADA,null,null,AGORA,AGORA);
                consultas.salvar(c);
                publisher.publicar(EventoDeConsulta.de(c,TipoEventoConsulta.REALIZADA));
            });
            var e=eventos().getFirst();
            assertThat(e.payload().dataHora().getOffset()).isEqualTo(ZoneOffset.of(offset));
            assertThat(e.payload().dataHora().toInstant()).isEqualTo(data.toInstant());
            assertThat(e.occurredAt()).isEqualTo(RELOGIO.instant());
            assertThat(json.escrever(e)).contains("\"occurredAt\":\"2026-09-04T12:00:00Z\"");
        } finally {TimeZone.setDefault(anterior);MDC.clear();}
    }
}
