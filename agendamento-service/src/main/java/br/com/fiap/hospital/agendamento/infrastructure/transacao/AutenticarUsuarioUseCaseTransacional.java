package br.com.fiap.hospital.agendamento.infrastructure.transacao;

import br.com.fiap.hospital.agendamento.application.AutenticarUsuarioCommand;
import br.com.fiap.hospital.agendamento.application.AutenticarUsuarioUseCase;
import br.com.fiap.hospital.agendamento.application.IdentidadeAutenticada;
import org.springframework.transaction.annotation.Transactional;

/*
 * Sem anotacao de estereotipo: criado exclusivamente por CasosDeUsoConfig, que constroi
 * o caso de uso nu por dentro.
 */
public class AutenticarUsuarioUseCaseTransacional {

    private final AutenticarUsuarioUseCase delegado;

    public AutenticarUsuarioUseCaseTransacional(AutenticarUsuarioUseCase delegado) {
        this.delegado = delegado;
    }

    @Transactional(readOnly = true)
    public IdentidadeAutenticada executar(AutenticarUsuarioCommand comando) {
        return delegado.executar(comando);
    }
}
