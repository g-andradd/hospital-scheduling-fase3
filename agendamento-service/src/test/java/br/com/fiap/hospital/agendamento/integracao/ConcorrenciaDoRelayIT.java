package br.com.fiap.hospital.agendamento.integracao;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;

class ConcorrenciaDoRelayIT extends M05RabbitBase {
    @Test @DisplayName("Lote e concorrência do relay respeitam o limite")
    
    // Scenario: Lote e concorrência do relay respeitam o limite
    void doisRelaysNaoEnviamAMesmaLinhaSimultaneamente() throws Exception {
        criar();duplicar(100);
        var partida=new CyclicBarrier(2);
        try(var pool=Executors.newFixedThreadPool(2)) {
            Callable<Integer> executar=()->{partida.await(10,TimeUnit.SECONDS);return relay.executar();};
            var a=pool.submit(executar);var b=pool.submit(executar);
            assertThat(a.get(30,TimeUnit.SECONDS)).isEqualTo(50);
            assertThat(b.get(30,TimeUnit.SECONDS)).isEqualTo(50);
        }
        assertThat(relay.executar()).isEqualTo(1);
        assertThat(relay.executar()).isZero();
        for(String fila:List.of(N,H)) {
            Set<String> ids=new HashSet<>();
            for(int i=0;i<101;i++)assertThat(ids.add(receber(fila).getMessageProperties().getHeader("x-event-id"))).isTrue();
            assertThat(rabbit.receive(fila)).isNull();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_evento WHERE publicado_em IS NOT NULL",Integer.class)).isEqualTo(101);
    }
}
