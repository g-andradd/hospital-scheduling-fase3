package br.com.fiap.hospital.agendamento.infrastructure.messaging;

import br.com.fiap.hospital.contracts.*;
import java.math.BigInteger;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class OutboxRepository {
    private final JdbcTemplate jdbc;
    public OutboxRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public void inserir(EventoEnvelope<ConsultaPayload> e,String json) {
        jdbc.update("""
                INSERT INTO outbox_evento(id,agregado_id,tipo_evento,payload,routing_key,criado_em)
                VALUES (?,?,?,?::jsonb,?,?)
                """, e.eventId(),e.aggregateId(),e.eventType().name(),json,e.eventType().routingKey(),
                e.occurredAt().atOffset(ZoneOffset.UTC));
    }
    public List<Pendente> bloquearLote() {
        return jdbc.query("""
                SELECT id,payload::text,routing_key,tentativas FROM outbox_evento
                WHERE publicado_em IS NULL
                ORDER BY tentativas,criado_em,id LIMIT 50 FOR UPDATE SKIP LOCKED
                """, (rs,row) -> new Pendente(rs.getObject("id",UUID.class),rs.getString("payload"),
                rs.getString("routing_key"),rs.getBigDecimal("tentativas").toBigIntegerExact()));
    }
    public void publicado(UUID id, OffsetDateTime agora) {
        jdbc.update("UPDATE outbox_evento SET publicado_em=? WHERE id=?",agora,id);
    }
    public void falhou(UUID id) {
        jdbc.update("UPDATE outbox_evento SET tentativas=tentativas+1 WHERE id=?",id);
    }
    public record Pendente(UUID id,String json,String routingKey,BigInteger tentativas) {}
}

