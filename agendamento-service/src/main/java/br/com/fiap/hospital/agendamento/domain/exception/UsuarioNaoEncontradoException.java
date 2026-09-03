package br.com.fiap.hospital.agendamento.domain.exception;

import java.util.UUID;

public class UsuarioNaoEncontradoException extends RecursoNaoEncontradoException {

    public UsuarioNaoEncontradoException(UUID id) {
        super("Usuario nao encontrado: " + id);
    }
}
