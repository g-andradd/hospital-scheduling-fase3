package br.com.fiap.hospital.agendamento.infrastructure.transacao;

import br.com.fiap.hospital.agendamento.application.ListarConsultasUseCase;
import br.com.fiap.hospital.agendamento.application.ConsultaResumo;
import br.com.fiap.hospital.agendamento.application.ListarConsultasQuery;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ListarConsultasUseCaseTransacional {

    private final ListarConsultasUseCase delegado;

    public ListarConsultasUseCaseTransacional(ListarConsultasUseCase delegado) {
        this.delegado = delegado;
    }

    @Transactional
    public java.util.List<ConsultaResumo> executar(ListarConsultasQuery query) {
        return delegado.executar(query);
    }
}
