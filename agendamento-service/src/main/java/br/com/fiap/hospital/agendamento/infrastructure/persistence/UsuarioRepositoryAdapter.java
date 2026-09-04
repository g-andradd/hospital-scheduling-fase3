package br.com.fiap.hospital.agendamento.infrastructure.persistence;

import br.com.fiap.hospital.agendamento.domain.Medico;
import br.com.fiap.hospital.agendamento.domain.Paciente;
import br.com.fiap.hospital.agendamento.domain.Usuario;
import br.com.fiap.hospital.agendamento.domain.port.UsuarioRepositoryPort;
import br.com.fiap.hospital.agendamento.infrastructure.persistence.mapper.UsuarioMapper;
import br.com.fiap.hospital.agendamento.infrastructure.persistence.repository.MedicoJpaRepository;
import br.com.fiap.hospital.agendamento.infrastructure.persistence.repository.PacienteJpaRepository;
import br.com.fiap.hospital.agendamento.infrastructure.persistence.repository.UsuarioJpaRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/** Leitura de usuarios, pacientes e medicos. Ausencia devolve vazio, nao excecao. */
@Repository
public class UsuarioRepositoryAdapter implements UsuarioRepositoryPort {

    private final UsuarioJpaRepository usuarios;
    private final PacienteJpaRepository pacientes;
    private final MedicoJpaRepository medicos;
    private final UsuarioMapper mapper;

    public UsuarioRepositoryAdapter(
            UsuarioJpaRepository usuarios,
            PacienteJpaRepository pacientes,
            MedicoJpaRepository medicos,
            UsuarioMapper mapper) {
        this.usuarios = usuarios;
        this.pacientes = pacientes;
        this.medicos = medicos;
        this.mapper = mapper;
    }

    @Override
    public Optional<Paciente> buscarPacientePorId(UUID id) {
        return pacientes.findById(id).map(mapper::paraDominio);
    }

    @Override
    public Optional<Medico> buscarMedicoPorId(UUID id) {
        return medicos.findById(id).map(mapper::paraDominio);
    }

    @Override
    public Optional<Usuario> buscarUsuarioPorId(UUID id) {
        return usuarios.findById(id).map(mapper::paraDominio);
    }

    @Override
    public Optional<Usuario> buscarUsuarioPorEmail(String email) {
        // O e-mail e normalizado para minusculas pelo value object; a busca acompanha.
        return email == null
                ? Optional.empty()
                : usuarios.findByEmail(email.trim().toLowerCase()).map(mapper::paraDominio);
    }

    @Override
    public Optional<Paciente> buscarPacientePorUsuario(UUID usuarioId) {
        return pacientes.findByUsuarioId(usuarioId).map(mapper::paraDominio);
    }

    @Override
    public Optional<Medico> buscarMedicoPorUsuario(UUID usuarioId) {
        return medicos.findByUsuarioId(usuarioId).map(mapper::paraDominio);
    }
}
