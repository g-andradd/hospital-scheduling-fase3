package br.com.fiap.hospital.agendamento.application;

import br.com.fiap.hospital.agendamento.domain.PerfilUsuario;
import java.util.UUID;

/**
 * Quem se autenticou. Nao contem token nem senha.
 *
 * <p>{@code pacienteId} e {@code medicoId} sao nulos quando nao se aplicam ao perfil.
 */
public record IdentidadeAutenticada(
        UUID usuarioId, String email, PerfilUsuario perfil, UUID pacienteId, UUID medicoId) {}
