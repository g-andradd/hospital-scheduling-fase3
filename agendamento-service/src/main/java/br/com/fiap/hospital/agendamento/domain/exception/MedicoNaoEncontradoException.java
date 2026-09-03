package br.com.fiap.hospital.agendamento.domain.exception;

import java.util.UUID;

public class MedicoNaoEncontradoException extends RecursoNaoEncontradoException {

    public MedicoNaoEncontradoException(UUID id) {
        super("Medico nao encontrado: " + id);
    }
}
