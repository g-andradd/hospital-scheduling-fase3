package br.com.fiap.hospital.agendamento.application;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Entrada de {@link AtualizarConsultaUseCase}. Campos nulos mantem o valor atual. */
public record AtualizarConsultaCommand(
        UUID consultaId,
        OffsetDateTime dataHora,
        Integer duracaoMinutos,
        UUID medicoId,
        String observacoes) {}
