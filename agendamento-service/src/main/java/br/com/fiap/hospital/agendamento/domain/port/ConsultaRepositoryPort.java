package br.com.fiap.hospital.agendamento.domain.port;

import br.com.fiap.hospital.agendamento.domain.Consulta;
import br.com.fiap.hospital.agendamento.domain.FiltroDeConsultas;
import br.com.fiap.hospital.agendamento.domain.Pagina;
import br.com.fiap.hospital.agendamento.domain.PeriodoConsulta;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Porta de saida para a persistencia de consultas.
 *
 * <p>As buscas de conflito ja chegam recortadas por periodo E por status ativo. Isso e
 * deliberado e e uma decisao tomada para o adaptador: a assinatura obriga o M02 a
 * filtrar <b>no banco</b>, com a query de sobreposicao de intervalo e o indice
 * correspondente. Uma assinatura mais permissiva — "traga todas as consultas do
 * medico" — deixaria o filtro subir para o caso de uso e carregaria a agenda inteira
 * em memoria a cada agendamento.
 */
public interface ConsultaRepositoryPort {

    Consulta salvar(Consulta consulta);

    Optional<Consulta> buscarPorId(UUID id);

    /** Consultas ativas do medico que se sobrepoem ao periodo informado. */
    List<Consulta> buscarAtivasDoMedicoNoPeriodo(UUID medicoId, PeriodoConsulta periodo);

    /** Consultas ativas do paciente que se sobrepoem ao periodo informado. */
    List<Consulta> buscarAtivasDoPacienteNoPeriodo(UUID pacienteId, PeriodoConsulta periodo);

    /**
     * Pagina de consultas que satisfazem o filtro.
     *
     * <p>A paginacao e resolvida pelo armazenamento: so os elementos da pagina pedida
     * atravessam a fronteira. Trazer tudo e fatiar depois anularia o teto de tamanho.
     */
    Pagina<Consulta> listar(FiltroDeConsultas filtro);
}
