package br.com.fiap.hospital.agendamento.domain;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * Paciente. Os dados de identidade e acesso ficam no {@link Usuario} associado; aqui
 * ficam apenas os atributos proprios do papel.
 */
public record Paciente(
        UUID id,
        Usuario usuario,
        Cpf cpf,
        LocalDate dataNascimento,
        String telefone) {

    public Paciente {
        Objects.requireNonNull(id, "O id do paciente e obrigatorio");
        Objects.requireNonNull(usuario, "O usuario do paciente e obrigatorio");
        Objects.requireNonNull(cpf, "O CPF do paciente e obrigatorio");
        Objects.requireNonNull(dataNascimento, "A data de nascimento do paciente e obrigatoria");
        if (!usuario.temPerfil(PerfilUsuario.PACIENTE)) {
            throw new IllegalArgumentException(
                    "O usuario associado a um paciente deve ter o perfil PACIENTE, e nao "
                            + usuario.perfil());
        }
    }

    public String nome() {
        return usuario.nome();
    }

    public Email email() {
        return usuario.email();
    }
}
