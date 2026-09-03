package br.com.fiap.hospital.agendamento.application;

import java.util.UUID;

/** Entrada de {@link CancelarConsultaUseCase}. */
public record CancelarConsultaCommand(UUID consultaId, String motivo) {}
