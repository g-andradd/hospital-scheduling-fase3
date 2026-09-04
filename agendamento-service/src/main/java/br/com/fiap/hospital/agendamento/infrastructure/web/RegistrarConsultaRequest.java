package br.com.fiap.hospital.agendamento.infrastructure.web;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Corpo de {@code POST /api/v1/consultas}. Duracao ausente assume o padrao do dominio. */
public record RegistrarConsultaRequest(
        @NotNull(message = "O paciente e obrigatorio")
        UUID pacienteId,

        @NotNull(message = "O medico e obrigatorio")
        UUID medicoId,

        @NotNull(message = "O usuario que registra a consulta e obrigatorio")
        UUID registradoPorId,

        @NotNull(message = "A data e hora da consulta sao obrigatorias")
        OffsetDateTime dataHora,

        @Min(value = 1, message = "A duracao deve ser de pelo menos 1 minuto")
        Integer duracaoMinutos,

        @Size(max = 2000, message = "As observacoes devem ter no maximo 2000 caracteres")
        String observacoes) {}
