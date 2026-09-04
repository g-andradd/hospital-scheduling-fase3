package br.com.fiap.hospital.agendamento.domain.exception;

import java.util.UUID;

public class PacienteNaoEncontradoException extends RecursoNaoEncontradoException {

    public PacienteNaoEncontradoException(UUID id) {
        super("Paciente nao encontrado: " + id);
    }
}
