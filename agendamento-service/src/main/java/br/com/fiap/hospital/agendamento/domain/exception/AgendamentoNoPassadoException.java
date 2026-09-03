package br.com.fiap.hospital.agendamento.domain.exception;

import java.time.OffsetDateTime;

/** Consulta nao pode ser marcada no passado nem no instante corrente. Mapeada para 422. */
public class AgendamentoNoPassadoException extends RuntimeException {

    public AgendamentoNoPassadoException(OffsetDateTime solicitado, OffsetDateTime agora) {
        super("Nao e possivel agendar consulta no passado. Solicitado: " + solicitado
                + ", momento atual: " + agora);
    }
}
