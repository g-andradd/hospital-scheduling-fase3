package br.com.fiap.hospital.agendamento.infrastructure.transacao;

import br.com.fiap.hospital.agendamento.application.AtualizarConsultaUseCase;
import br.com.fiap.hospital.agendamento.application.ConsultaResumo;
import br.com.fiap.hospital.agendamento.application.AtualizarConsultaCommand;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AtualizarConsultaUseCaseTransacional {

    private final AtualizarConsultaUseCase delegado;

    public AtualizarConsultaUseCaseTransacional(AtualizarConsultaUseCase delegado) {
        this.delegado = delegado;
    }

    @Transactional
    public ConsultaResumo executar(AtualizarConsultaCommand comando) {
        return delegado.executar(comando);
    }
}
