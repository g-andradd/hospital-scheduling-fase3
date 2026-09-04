package br.com.fiap.hospital.agendamento.application;

import br.com.fiap.hospital.agendamento.domain.Pagina;
import br.com.fiap.hospital.agendamento.domain.port.ConsultaRepositoryPort;

/** Lista consultas paginadas, segundo os filtros informados. */
public class ListarConsultasUseCase {

    private final ConsultaRepositoryPort consultas;

    public ListarConsultasUseCase(ConsultaRepositoryPort consultas) {
        this.consultas = consultas;
    }

    public Pagina<ConsultaResumo> executar(ListarConsultasQuery query) {
        ListarConsultasQuery efetiva = query == null ? ListarConsultasQuery.semFiltro() : query;
        return consultas.listar(efetiva.paraFiltro()).mapear(ConsultaResumo::de);
    }
}
