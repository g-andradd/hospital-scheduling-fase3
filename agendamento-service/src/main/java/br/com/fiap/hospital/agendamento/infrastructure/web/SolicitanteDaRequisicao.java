package br.com.fiap.hospital.agendamento.infrastructure.web;

import br.com.fiap.hospital.agendamento.domain.PerfilUsuario;
import br.com.fiap.hospital.agendamento.domain.SolicitanteAutenticado;
import br.com.fiap.hospital.security.UsuarioAutenticado;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Traduz o contexto de seguranca do Spring para o tipo de dominio.
 *
 * <p>E a unica ponte entre os dois, e fica na borda web de proposito: assim
 * {@code application} recebe o solicitante como dado, sem conhecer o Spring Security.
 */
final class SolicitanteDaRequisicao {

    private SolicitanteDaRequisicao() {}

    static SolicitanteAutenticado atual() {
        Authentication autenticacao = SecurityContextHolder.getContext().getAuthentication();
        if (autenticacao == null || !(autenticacao.getPrincipal() instanceof UsuarioAutenticado u)) {
            // A cadeia de filtros ja recusou requisicao sem autenticacao antes de chegar
            // aqui. Se isto acontecer, e defeito de configuracao, nao entrada do cliente.
            throw new IllegalStateException("Requisicao autenticada sem identidade no contexto");
        }
        return new SolicitanteAutenticado(
                u.usuarioId(), PerfilUsuario.valueOf(u.perfil()), u.pacienteId());
    }
}
