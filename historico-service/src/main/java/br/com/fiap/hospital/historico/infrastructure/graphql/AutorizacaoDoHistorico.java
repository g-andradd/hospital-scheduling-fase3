package br.com.fiap.hospital.historico.infrastructure.graphql;

import br.com.fiap.hospital.security.UsuarioAutenticado;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * O recorte de propriedade, fora do corpo do resolver.
 *
 * <p>O {@code @PreAuthorize} decide perfil; ele nao sabe se <i>este</i> paciente pode ver
 * <i>aquele</i> registro. Manter a regra aqui, e nao espalhada nos resolvers, e a mesma
 * separacao que a ADR-004 impos ao agendamento: autorizacao espalhada em controller e o
 * furo mais comum.
 *
 * <p>A identidade vem sempre do token. Nenhum identificador recebido do cliente participa
 * da decisao — se participasse, bastaria enviar o id de outro paciente para ler o
 * historico dele.
 */
@Component
public class AutorizacaoDoHistorico {

    public static final String PERFIL_PACIENTE = "PACIENTE";

    public UsuarioAutenticado autenticado() {
        Authentication autenticacao = SecurityContextHolder.getContext().getAuthentication();
        if (autenticacao == null || !(autenticacao.getPrincipal() instanceof UsuarioAutenticado usuario)) {
            throw new AccessDeniedException("Identidade ausente no contexto.");
        }
        return usuario;
    }

    /** O paciente do token. Usado por minhasConsultas, que nao aceita argumento de identidade. */
    public UUID pacienteDoToken() {
        UsuarioAutenticado usuario = autenticado();
        if (usuario.pacienteId() == null) {
            throw new AccessDeniedException("Token sem paciente associado.");
        }
        return usuario.pacienteId();
    }

    /** O medico do token. Autor da correcao; nunca vem do input. */
    public UUID medicoDoToken() {
        UsuarioAutenticado usuario = autenticado();
        if (usuario.medicoId() == null) {
            throw new AccessDeniedException("Token sem medico associado.");
        }
        return usuario.medicoId();
    }

    /** MEDICO e ENFERMEIRO alcancam qualquer paciente; PACIENTE, apenas o proprio. */
    public void exigirAcessoAoPaciente(UUID pacienteAlvo) {
        UsuarioAutenticado usuario = autenticado();
        if (!PERFIL_PACIENTE.equals(usuario.perfil())) {
            return;
        }
        if (!pacienteAlvo.equals(usuario.pacienteId())) {
            throw new AccessDeniedException("Paciente so acessa o proprio historico.");
        }
    }
}
