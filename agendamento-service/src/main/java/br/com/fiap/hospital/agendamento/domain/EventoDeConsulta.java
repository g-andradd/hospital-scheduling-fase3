package br.com.fiap.hospital.agendamento.domain;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * Evento de dominio publicado a cada mudanca de estado de uma consulta.
 *
 * <p>Deliberadamente minimo. Nao e o envelope de docs/03-contrato-de-eventos.md — ele
 * carrega {@code eventId}, {@code correlationId}, {@code version} e o snapshot
 * completo serializado, que sao conceitos de transporte e exigiriam Jackson dentro do
 * dominio. Traduzir este evento para aquele envelope e trabalho do adaptador de
 * mensageria, no M05.
 */
public record EventoDeConsulta(
        UUID consultaId,
        TipoEventoConsulta tipo,
        OffsetDateTime ocorridoEm) {

    public EventoDeConsulta {
        Objects.requireNonNull(consultaId, "O id da consulta e obrigatorio no evento");
        Objects.requireNonNull(tipo, "O tipo do evento e obrigatorio");
        Objects.requireNonNull(ocorridoEm, "O instante do evento e obrigatorio");
    }

    /** Constroi o evento a partir do estado da consulta apos a mudanca. */
    public static EventoDeConsulta de(Consulta consulta, TipoEventoConsulta tipo) {
        Objects.requireNonNull(consulta, "A consulta e obrigatoria no evento");
        return new EventoDeConsulta(consulta.id(), tipo, consulta.atualizadoEm());
    }
}
