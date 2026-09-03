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
}
