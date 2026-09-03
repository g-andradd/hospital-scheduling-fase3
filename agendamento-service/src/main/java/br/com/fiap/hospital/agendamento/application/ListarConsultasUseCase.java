package br.com.fiap.hospital.agendamento.application;

import br.com.fiap.hospital.agendamento.domain.port.ConsultaRepositoryPort;
import java.util.List;

/** Lista consultas segundo os filtros informados. */
public class ListarConsultasUseCase {

    private final ConsultaRepositoryPort consultas;

    public ListarConsultasUseCase(ConsultaRepositoryPort consultas) {
        this.consultas = consultas;
    }

    public List<ConsultaResumo> executar(ListarConsultasQuery query) {
        ListarConsultasQuery efetiva = query == null ? ListarConsultasQuery.semFiltro() : query;
        return consultas.listar(efetiva.paraFiltro()).stream()
                .map(ConsultaResumo::de)
                .toList();
    }
}
