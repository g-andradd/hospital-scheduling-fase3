package br.com.fiap.hospital.agendamento.infrastructure.transacao;

import br.com.fiap.hospital.agendamento.application.ConsultaResumo;
import br.com.fiap.hospital.agendamento.application.ListarConsultasQuery;
import br.com.fiap.hospital.agendamento.application.ListarConsultasUseCase;
import br.com.fiap.hospital.agendamento.domain.Pagina;
import org.springframework.transaction.annotation.Transactional;

/*
 * Sem anotacao de estereotipo de proposito: este decorador e criado exclusivamente por
 * CasosDeUsoConfig, que constroi o caso de uso nu por dentro. Anota-lo faria o component
 * scan tentar cria-lo tambem, exigindo o caso de uso nu como bean — que e justamente o
 * que se quer que nao exista.
 */
public class ListarConsultasUseCaseTransacional {

    private final ListarConsultasUseCase delegado;

    public ListarConsultasUseCaseTransacional(ListarConsultasUseCase delegado) {
        this.delegado = delegado;
    }

    @Transactional(readOnly = true)
    public Pagina<ConsultaResumo> executar(ListarConsultasQuery query) {
        return delegado.executar(query);
    }
}
