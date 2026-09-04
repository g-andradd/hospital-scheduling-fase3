package br.com.fiap.hospital.agendamento.domain.port;

import br.com.fiap.hospital.agendamento.domain.Medico;
import br.com.fiap.hospital.agendamento.domain.Paciente;
import br.com.fiap.hospital.agendamento.domain.Usuario;
import java.util.Optional;
import java.util.UUID;

/** Porta de saida para a leitura de usuarios, pacientes e medicos. */
public interface UsuarioRepositoryPort {

    Optional<Paciente> buscarPacientePorId(UUID id);

    Optional<Medico> buscarMedicoPorId(UUID id);

    Optional<Usuario> buscarUsuarioPorId(UUID id);

    /** Busca por e-mail, para a autenticacao. */
    Optional<Usuario> buscarUsuarioPorEmail(String email);

    /** Paciente associado ao usuario, quando houver. */
    Optional<Paciente> buscarPacientePorUsuario(UUID usuarioId);

    /** Medico associado ao usuario, quando houver. */
    Optional<Medico> buscarMedicoPorUsuario(UUID usuarioId);
}
