package br.com.fiap.hospital.agendamento.domain;

/**
 * Perfis de acesso do sistema. A matriz de permissoes que os usa esta em
 * docs/02-especificacao-funcional.md secao 3 e e aplicada no M04.
 */
public enum PerfilUsuario {

    MEDICO,
    ENFERMEIRO,
    PACIENTE
}
