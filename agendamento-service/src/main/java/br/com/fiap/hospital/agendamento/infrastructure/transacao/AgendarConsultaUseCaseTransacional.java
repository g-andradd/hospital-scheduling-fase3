package br.com.fiap.hospital.agendamento.infrastructure.transacao;

import br.com.fiap.hospital.agendamento.application.AgendarConsultaUseCase;
import br.com.fiap.hospital.agendamento.application.ConsultaResumo;
import br.com.fiap.hospital.agendamento.application.AgendarConsultaCommand;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
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
