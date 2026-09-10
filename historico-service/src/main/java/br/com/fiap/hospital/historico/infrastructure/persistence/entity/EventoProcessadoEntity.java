package br.com.fiap.hospital.historico.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "evento_processado")
public class EventoProcessadoEntity {
    @Id @Column(name = "event_id") private UUID eventId;
    @Column(name = "processado_em", nullable = false) private Instant processadoEm;
    protected EventoProcessadoEntity() { }
    public EventoProcessadoEntity(UUID eventId, Instant processadoEm) { this.eventId = eventId; this.processadoEm = processadoEm; }
    public UUID getEventId() { return eventId; }
    public Instant getProcessadoEm() { return processadoEm; }
}
