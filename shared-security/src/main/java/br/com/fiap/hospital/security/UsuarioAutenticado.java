package br.com.fiap.hospital.security;

import java.util.UUID;

/**
 * Identidade extraida do token, disponivel para os servicos.
 *
 * <p>{@code pacienteId} e {@code medicoId} sao nulos quando nao se aplicam ao perfil.
 */
public record UsuarioAutenticado(
        UUID usuarioId, String email, String perfil, UUID pacienteId, UUID medicoId) {}
