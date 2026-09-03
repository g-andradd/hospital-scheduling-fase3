package br.com.fiap.hospital.agendamento.infrastructure.web;

import jakarta.validation.constraints.NotBlank;

/** Corpo de {@code PATCH /api/v1/consultas/{id}/cancelar}. */
public record CancelarConsultaRequest(
        @NotBlank(message = "O motivo do cancelamento e obrigatorio")
        String motivo) {}
