package br.com.fiap.hospital.agendamento.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * Medico. Como no {@link Paciente}, identidade e acesso vivem no {@link Usuario}
 * associado.
 */
public record Medico(
        UUID id,
        Usuario usuario,
        Crm crm,
        String especialidade) {

    public Medico {
        Objects.requireNonNull(id, "O id do medico e obrigatorio");
        Objects.requireNonNull(usuario, "O usuario do medico e obrigatorio");
        Objects.requireNonNull(crm, "O CRM do medico e obrigatorio");
        if (especialidade == null || especialidade.isBlank()) {
            throw new IllegalArgumentException("A especialidade do medico e obrigatoria");
        }
        especialidade = especialidade.trim();
        if (!usuario.temPerfil(PerfilUsuario.MEDICO)) {
            throw new IllegalArgumentException(
                    "O usuario associado a um medico deve ter o perfil MEDICO, e nao "
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
