package br.com.fiap.hospital.historico.infrastructure.persistence.entity;

import br.com.fiap.hospital.contracts.EventoEnvelope;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "consulta_evento")
public class ConsultaEventoEntity implements Persistable<UUID> {
    @Id private UUID id;
    @Column(name = "consulta_id", nullable = false) private UUID consultaId;
    @Column(name = "tipo_evento", nullable = false) private String tipoEvento;
    @Column(name = "ocorrido_em", nullable = false) private Instant ocorridoEm;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb") private String payload;

    protected ConsultaEventoEntity() { }
    public ConsultaEventoEntity(EventoEnvelope<?> evento, String payload) {
        id = evento.eventId(); consultaId = evento.aggregateId(); tipoEvento = evento.eventType().name();
        ocorridoEm = evento.occurredAt(); this.payload = payload;
    }
    /**
     * Fato local da trilha, sem envelope AMQP por tras.
     *
     * <p>Existe para a correcao manual do M09, que e um fato do historico e nao um evento
     * do contrato: nao tem eventId de origem, nao tem routing key e nao vira publicacao.
     */
    public ConsultaEventoEntity(UUID id, UUID consultaId, String tipoEvento, Instant ocorridoEm,
                                String payload) {
        this.id = id; this.consultaId = consultaId; this.tipoEvento = tipoEvento;
        this.ocorridoEm = ocorridoEm; this.payload = payload;
    }

    @Override public UUID getId() { return id; }
    @Override public boolean isNew() { return true; }
    public UUID getConsultaId() { return consultaId; }
    public String getTipoEvento() { return tipoEvento; }
    public Instant getOcorridoEm() { return ocorridoEm; }
    public String getPayload() { return payload; }
}
