package br.com.fiap.hospital.agendamento.infrastructure.transacao;

import br.com.fiap.hospital.agendamento.application.BuscarConsultaPorIdUseCase;
import br.com.fiap.hospital.agendamento.application.ConsultaResumo;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BuscarConsultaPorIdUseCaseTransacional {

    private final BuscarConsultaPorIdUseCase delegado;

    public BuscarConsultaPorIdUseCaseTransacional(BuscarConsultaPorIdUseCase delegado) {
        this.delegado = delegado;
    }

    @Transactional
    public ConsultaResumo executar(java.util.UUID consultaId) {
        return delegado.executar(consultaId);
    }
}
