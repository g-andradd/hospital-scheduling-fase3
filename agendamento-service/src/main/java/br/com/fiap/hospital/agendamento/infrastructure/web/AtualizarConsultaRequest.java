package br.com.fiap.hospital.agendamento.infrastructure.web;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Corpo de {@code PUT /api/v1/consultas/{id}}.
 *
 * <p><b>Campo ausente preserva o valor atual.</b> E semantica de PATCH sob o verbo PUT,
 * e a divergencia e consciente: exigir o corpo completo faria uma remarcacao que nao
 * reenvia observacoes apagar registro clinico. Para apagar observacoes, envie string
 * vazia; nulo significa "nao mexa".
 */
public record AtualizarConsultaRequest(
        OffsetDateTime dataHora,

        @Min(value = 1, message = "A duracao deve ser de pelo menos 1 minuto")
        Integer duracaoMinutos,

        UUID medicoId,

        @Size(max = 2000, message = "As observacoes devem ter no maximo 2000 caracteres")
        String observacoes) {}
