package br.com.fiap.hospital.agendamento.infrastructure.web;

import br.com.fiap.hospital.agendamento.domain.PerfilUsuario;

/**
 * Resposta de POST /auth/login.
 *
 * <p>Nao carrega a senha nem o hash. Nao carrega tampouco o identificador de paciente ou
 * de medico: eles viajam dentro do token, e repeti-los aqui daria ao cliente um dado que
 * ele nao precisa manipular.
 */
public record LoginResponse(String accessToken, long expiresIn, PerfilUsuario perfil) {}
