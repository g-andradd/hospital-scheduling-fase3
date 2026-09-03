package br.com.fiap.hospital.agendamento.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * Usuario do sistema, com o perfil que determina o que ele pode fazer.
 *
 * <p>O {@code senhaHash} existe porque e coluna do modelo de dados, mas nada neste
 * change o le ou compara — autenticacao e o M04. O {@code toString} o omite
 * deliberadamente: RNF-01 exige que a senha nunca apareca em log nem em resposta, e
 * o {@code toString} gerado por padrao em um record a exporia no primeiro
 * {@code log.debug} descuidado.
 */
public record Usuario(
        UUID id,
        String nome,
        Email email,
        String senhaHash,
        PerfilUsuario perfil,
        boolean ativo) {

    public Usuario {
        Objects.requireNonNull(id, "O id do usuario e obrigatorio");
        Objects.requireNonNull(email, "O e-mail do usuario e obrigatorio");
        Objects.requireNonNull(perfil, "O perfil do usuario e obrigatorio");
        if (nome == null || nome.isBlank()) {
            throw new IllegalArgumentException("O nome do usuario e obrigatorio");
        }
        nome = nome.trim();
    }

    public boolean temPerfil(PerfilUsuario outro) {
        return perfil == outro;
    }

    @Override
    public String toString() {
        return "Usuario[id=%s, nome=%s, email=%s, perfil=%s, ativo=%s]"
                .formatted(id, nome, email.valor(), perfil, ativo);
    }
}
