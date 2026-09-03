package br.com.fiap.hospital.agendamento.infrastructure.transacao;

import br.com.fiap.hospital.agendamento.application.CancelarConsultaUseCase;
import br.com.fiap.hospital.agendamento.application.ConsultaResumo;
import br.com.fiap.hospital.agendamento.application.CancelarConsultaCommand;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
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
