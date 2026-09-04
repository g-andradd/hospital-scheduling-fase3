package br.com.fiap.hospital.agendamento.application;

import br.com.fiap.hospital.agendamento.domain.Pagina;
import br.com.fiap.hospital.agendamento.domain.SolicitanteAutenticado;
import br.com.fiap.hospital.agendamento.domain.port.ConsultaRepositoryPort;

/** Lista consultas paginadas, recortadas pela identidade quando o perfil exige. */
public class ListarConsultasUseCase {

    private final ConsultaRepositoryPort consultas;

    public ListarConsultasUseCase(ConsultaRepositoryPort consultas) {
        this.consultas = consultas;
    }

    /**
     * Para o perfil paciente, o filtro de paciente e <b>sobrescrito</b> pelo proprio
     * identificador, informado ou nao.
     *
     * <p>Sobrescrever em silencio, em vez de recusar, e deliberado: um 403 confirmaria
     * ao solicitante que o identificador que ele tentou existe. Ele recebe as proprias
     * consultas e nada aprende sobre as dos outros.
     */
    public Pagina<ConsultaResumo> executar(
            ListarConsultasQuery query, SolicitanteAutenticado solicitante) {

        ListarConsultasQuery efetiva = query == null ? ListarConsultasQuery.semFiltro() : query;
        if (solicitante.ePaciente()) {
            efetiva = efetiva.recortadaPara(solicitante.pacienteId());
        }
        return consultas.listar(efetiva.paraFiltro()).mapear(ConsultaResumo::de);
    }
}
