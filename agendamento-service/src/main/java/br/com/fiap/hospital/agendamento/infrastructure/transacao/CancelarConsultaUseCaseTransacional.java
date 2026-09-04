package br.com.fiap.hospital.agendamento.infrastructure.transacao;

import br.com.fiap.hospital.agendamento.application.CancelarConsultaUseCase;
import br.com.fiap.hospital.agendamento.application.ConsultaResumo;
import br.com.fiap.hospital.agendamento.application.CancelarConsultaCommand;
import org.springframework.transaction.annotation.Transactional;

/*
 * Sem anotacao de estereotipo de proposito: este decorador e criado exclusivamente por
 * CasosDeUsoConfig, que constroi o caso de uso nu por dentro. Anota-lo faria o component
 * scan tentar cria-lo tambem, exigindo o caso de uso nu como bean — que e justamente o
 * que se quer que nao exista.
 */
public class CancelarConsultaUseCaseTransacional {

    private final CancelarConsultaUseCase delegado;

    public CancelarConsultaUseCaseTransacional(CancelarConsultaUseCase delegado) {
        this.delegado = delegado;
    }

    @Transactional
    public ConsultaResumo executar(CancelarConsultaCommand comando) {
        return delegado.executar(comando);
    }
}
