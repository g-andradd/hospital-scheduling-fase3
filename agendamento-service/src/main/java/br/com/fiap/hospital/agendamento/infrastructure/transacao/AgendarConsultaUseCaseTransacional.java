package br.com.fiap.hospital.agendamento.infrastructure.transacao;

import br.com.fiap.hospital.agendamento.application.AgendarConsultaUseCase;
import br.com.fiap.hospital.agendamento.application.ConsultaResumo;
import br.com.fiap.hospital.agendamento.application.AgendarConsultaCommand;
import org.springframework.transaction.annotation.Transactional;

/*
 * Sem anotacao de estereotipo de proposito: este decorador e criado exclusivamente por
 * CasosDeUsoConfig, que constroi o caso de uso nu por dentro. Anota-lo faria o component
 * scan tentar cria-lo tambem, exigindo o caso de uso nu como bean — que e justamente o
 * que se quer que nao exista.
 */
public class AgendarConsultaUseCaseTransacional {

    private final AgendarConsultaUseCase delegado;

    public AgendarConsultaUseCaseTransacional(AgendarConsultaUseCase delegado) {
        this.delegado = delegado;
    }

    @Transactional
    public ConsultaResumo executar(AgendarConsultaCommand comando) {
        return delegado.executar(comando);
    }
}
