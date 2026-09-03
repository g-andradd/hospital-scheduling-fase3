package br.com.fiap.hospital.agendamento.application;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Entrada de {@link AtualizarConsultaUseCase}.
 *
 * <p>Campo nulo mantem o valor atual. Para apagar as observacoes, envie string vazia ou
 * em branco — nulo significa "nao mexa", nao "limpe".
 */
public record AtualizarConsultaCommand(
        UUID consultaId,
        OffsetDateTime dataHora,
        Integer duracaoMinutos,
        UUID medicoId,
        String observacoes) {}
