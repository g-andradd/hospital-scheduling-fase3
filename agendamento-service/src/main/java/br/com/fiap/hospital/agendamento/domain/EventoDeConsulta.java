package br.com.fiap.hospital.agendamento.domain;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/** Valores imutaveis do fato. O envelope de transporte pertence ao adaptador. */
public record EventoDeConsulta(UUID consultaId, TipoEventoConsulta tipo,
        OffsetDateTime ocorridoEm, Snapshot posterior, Snapshot anterior) {
    public EventoDeConsulta {
        Objects.requireNonNull(consultaId, "O id da consulta e obrigatorio no evento");
        Objects.requireNonNull(tipo, "O tipo do evento e obrigatorio");
        Objects.requireNonNull(ocorridoEm, "O instante do evento e obrigatorio");
        Objects.requireNonNull(posterior, "O estado posterior e obrigatorio");
    }
    public static EventoDeConsulta de(Consulta consulta, TipoEventoConsulta tipo) {
        return de(consulta,tipo,null);
    }
    public static EventoDeConsulta de(Consulta consulta, TipoEventoConsulta tipo, Snapshot anterior) {
        Objects.requireNonNull(consulta, "A consulta e obrigatoria no evento");
        return new EventoDeConsulta(consulta.id(),tipo,consulta.atualizadoEm(),Snapshot.de(consulta),anterior);
    }
    public record Snapshot(UUID pacienteId, UUID medicoId, UUID registradoPorId,
            PeriodoConsulta periodo, StatusConsulta status, String observacoes, String motivoCancelamento) {
        public static Snapshot de(Consulta c) {
            return new Snapshot(c.pacienteId(),c.medicoId(),c.registradoPorId(),c.periodo(),c.status(),
                    c.observacoes(),c.motivoCancelamento());
        }
    }
}
