package br.com.fiap.hospital.contracts;

import java.time.Instant;
import java.util.UUID;

public record EventoEnvelope<T>(
        UUID eventId, TipoEvento eventType, UUID aggregateId, Instant occurredAt,
        int version, String correlationId, T payload) {}

