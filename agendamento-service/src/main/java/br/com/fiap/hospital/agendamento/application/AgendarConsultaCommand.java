package br.com.fiap.hospital.agendamento.application;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Entrada de {@link AgendarConsultaUseCase}. Duracao nula assume o padrao do dominio. */
public record AgendarConsultaCommand(
        UUID pacienteId,
        UUID medicoId,
        UUID registradoPorId,
        OffsetDateTime dataHora,
        Integer duracaoMinutos,
        String observacoes) {}
