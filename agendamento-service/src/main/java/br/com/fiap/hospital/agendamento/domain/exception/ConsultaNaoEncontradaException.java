package br.com.fiap.hospital.agendamento.domain.exception;

import java.util.UUID;

public class ConsultaNaoEncontradaException extends RecursoNaoEncontradoException {

    public ConsultaNaoEncontradaException(UUID id) {
        super("Consulta nao encontrada: " + id);
    }
}
