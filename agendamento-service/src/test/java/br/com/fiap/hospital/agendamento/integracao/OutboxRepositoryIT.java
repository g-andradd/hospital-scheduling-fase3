package br.com.fiap.hospital.agendamento.integracao;

import static org.assertj.core.api.Assertions.*;
import java.math.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class OutboxRepositoryIT extends M05JpaBase {
    @Test void schemaIndiceParcialEContadorSemOverflow() {
        criar();var id=eventos().getFirst().eventId();
        assertThat(jdbc.queryForObject("SELECT indexdef FROM pg_indexes WHERE schemaname=current_schema() AND indexname='ix_outbox_pendente'",String.class))
            .contains("WHERE (publicado_em IS NULL)");
        jdbc.update("UPDATE outbox_evento SET tentativas=2147483647 WHERE id=?",id);
        tx.executeWithoutResult(s->outbox.falhou(id));
        assertThat(tx.<BigInteger>execute(s->outbox.bloquearLote().getFirst().tentativas())).isEqualTo(new BigInteger("2147483648"));
        for(BigDecimal valor:List.of(new BigDecimal("-1"),new BigDecimal("1.5")))
            assertThatThrownBy(()->jdbc.update("UPDATE outbox_evento SET tentativas=? WHERE id=?",valor,id)).isInstanceOf(DataIntegrityViolationException.class);
        tx.executeWithoutResult(s->outbox.publicado(id,AGORA));
        assertThat(tx.<List<br.com.fiap.hospital.agendamento.infrastructure.messaging.OutboxRepository.Pendente>>execute(s->outbox.bloquearLote())).isEmpty();
    }
    @Test void cinquentaLocksNaoBloqueiamOutraConexao() throws Exception {
        criar();
        jdbc.update("""
            INSERT INTO outbox_evento(id,agregado_id,tipo_evento,payload,routing_key,criado_em)
            SELECT gen_random_uuid(),agregado_id,tipo_evento,payload,routing_key,criado_em
            FROM outbox_evento CROSS JOIN generate_series(1,59)
            """);
        var selecionados=new CountDownLatch(1);var liberar=new CountDownLatch(1);
        try(var pool=Executors.newSingleThreadExecutor()) {
            var primeira=pool.submit(()->tx.execute(s->{
                var lote=outbox.bloquearLote();selecionados.countDown();
                try {assertThat(liberar.await(10,TimeUnit.SECONDS)).isTrue();}
                catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
                return lote;
            }));
            assertThat(selecionados.await(10,TimeUnit.SECONDS)).isTrue();
            List<UUID> segunda;
            try {segunda=tx.execute(s->outbox.bloquearLote().stream().map(p->p.id()).toList());}
            finally {liberar.countDown();}
            var uma=primeira.get(10,TimeUnit.SECONDS);
            assertThat(uma).hasSize(50);assertThat(segunda).hasSize(10);
            assertThat(uma).extracting(p->p.id()).doesNotContainAnyElementsOf(segunda);
        } finally {liberar.countDown();}
    }
    @Test void prioridadePorTentativasImpedeMonopolio() {
        criar();var antigo=eventos().getFirst().eventId();
        jdbc.update("UPDATE outbox_evento SET tentativas=999999 WHERE id=?",antigo);
        agendar.executar(comando(paciente,medico,INICIO.plusDays(1)));
        assertThat(tx.<UUID>execute(s->outbox.bloquearLote().getFirst().id())).isNotEqualTo(antigo);
    }
}
