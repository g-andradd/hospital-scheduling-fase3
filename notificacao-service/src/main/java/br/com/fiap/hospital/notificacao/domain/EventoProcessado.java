package br.com.fiap.hospital.notificacao.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.springframework.data.domain.Persistable;
import java.time.Instant;
import java.util.UUID;

/**
 * Marca de idempotencia. Gravada por ultimo, na mesma transacao do efeito.
 *
 * <p>{@code Persistable} com {@code isNew() == true} nao e detalhe: sem isso o Spring Data
 * ve um id atribuido, sem {@code @Version}, e trata a entidade como <b>existente</b> — grava
 * por {@code merge()}, que faz SELECT e depois UPDATE. Sob entregas concorrentes do mesmo
 * eventId, a segunda transacao encontraria a linha ja commitada pela primeira, atualizaria em
 * vez de colidir, e <b>as duas confirmariam o efeito</b>: duas notificacoes para o mesmo
 * evento, sem erro nenhum. Forcando {@code persist()}, a chave primaria faz o seu papel e
 * serializa a confirmacao.
 */
@Entity
@Table(name = "evento_processado")
public class EventoProcessado implements Persistable<UUID> {

    @Id @Column(name = "event_id") private UUID eventId;
    @Column(name = "processado_em", nullable = false) private Instant processadoEm;

    protected EventoProcessado() { }

    public EventoProcessado(UUID eventId, Instant processadoEm) {
        this.eventId = eventId;
        this.processadoEm = processadoEm;
    }

    @Override public UUID getId() { return eventId; }

    @Override public boolean isNew() { return true; }

    public UUID getEventId() { return eventId; }
    public Instant getProcessadoEm() { return processadoEm; }
}
