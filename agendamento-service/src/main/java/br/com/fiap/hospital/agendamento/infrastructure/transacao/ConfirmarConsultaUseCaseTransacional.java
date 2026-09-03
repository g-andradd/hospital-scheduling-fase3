package br.com.fiap.hospital.agendamento.infrastructure.transacao;

import br.com.fiap.hospital.agendamento.application.ConfirmarConsultaUseCase;
import br.com.fiap.hospital.agendamento.application.ConsultaResumo;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ConfirmarConsultaUseCaseTransacional {

    private final ConfirmarConsultaUseCase delegado;

    public ConfirmarConsultaUseCaseTransacional(ConfirmarConsultaUseCase delegado) {
        this.delegado = delegado;
    }

    @Transactional
    public ConsultaResumo executar(java.util.UUID consultaId) {
        return delegado.executar(consultaId);
    }
}
