package br.com.fiap.hospital.agendamento.infrastructure.web;

import jakarta.validation.constraints.NotBlank;

/** Corpo de POST /auth/login. */
public record LoginRequest(
        @NotBlank(message = "O e-mail e obrigatorio") String email,
        @NotBlank(message = "A senha e obrigatoria") String senha) {}
