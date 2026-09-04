package br.com.fiap.hospital.agendamento.fake;

import br.com.fiap.hospital.agendamento.domain.Medico;
import br.com.fiap.hospital.agendamento.domain.Paciente;
import br.com.fiap.hospital.agendamento.domain.Usuario;
import br.com.fiap.hospital.agendamento.domain.port.UsuarioRepositoryPort;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Repositorio de usuarios, pacientes e medicos em memoria. */
public class UsuarioRepositoryFake implements UsuarioRepositoryPort {

    private final Map<UUID, Paciente> pacientes = new LinkedHashMap<>();
    private final Map<UUID, Medico> medicos = new LinkedHashMap<>();
    private final Map<UUID, Usuario> usuarios = new LinkedHashMap<>();

    @Override
    public Optional<Paciente> buscarPacientePorId(UUID id) {
        return Optional.ofNullable(pacientes.get(id));
    }

    @Override
    public Optional<Medico> buscarMedicoPorId(UUID id) {
        return Optional.ofNullable(medicos.get(id));
    }

    @Override
    public Optional<Usuario> buscarUsuarioPorId(UUID id) {
        return Optional.ofNullable(usuarios.get(id));
    }

    @Override
    public Optional<Usuario> buscarUsuarioPorEmail(String email) {
        return email == null ? Optional.empty() : usuarios.values().stream()
                .filter(u -> u.email().valor().equalsIgnoreCase(email.trim()))
                .findFirst();
    }

    @Override
    public Optional<Paciente> buscarPacientePorUsuario(UUID usuarioId) {
        return pacientes.values().stream()
                .filter(p -> p.usuario().id().equals(usuarioId))
                .findFirst();
    }

    @Override
    public Optional<Medico> buscarMedicoPorUsuario(UUID usuarioId) {
        return medicos.values().stream()
                .filter(m -> m.usuario().id().equals(usuarioId))
                .findFirst();
    }

    public UsuarioRepositoryFake com(Paciente paciente) {
        pacientes.put(paciente.id(), paciente);
        usuarios.put(paciente.usuario().id(), paciente.usuario());
        return this;
    }

    public UsuarioRepositoryFake com(Medico medico) {
        medicos.put(medico.id(), medico);
        usuarios.put(medico.usuario().id(), medico.usuario());
        return this;
    }

    public UsuarioRepositoryFake com(Usuario usuario) {
        usuarios.put(usuario.id(), usuario);
        return this;
    }
}
