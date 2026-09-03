package br.com.fiap.hospital.agendamento.application;

import br.com.fiap.hospital.agendamento.domain.Consulta;
import br.com.fiap.hospital.agendamento.domain.PeriodoConsulta;
import br.com.fiap.hospital.agendamento.domain.exception.ConflitoDeAgendaException;
import br.com.fiap.hospital.agendamento.domain.port.ConsultaRepositoryPort;
import java.util.List;
import java.util.UUID;

/**
 * Colaborador compartilhado por agendamento e alteracao. Nao e um caso de uso e nao e
 * publico: existe para que as duas operacoes apliquem exatamente a mesma regra de
 * conflito, que e como elas deixam de divergir.
 *
 * <p>A porta ja devolve apenas consultas ativas que se sobrepoem ao periodo, entao aqui
 * basta descartar a propria consulta e verificar se sobrou alguma.
 */
final class VerificadorDeAgenda {

    private final ConsultaRepositoryPort consultas;

    VerificadorDeAgenda(ConsultaRepositoryPort consultas) {
        this.consultas = consultas;
    }

    /**
     * @param consultaIgnorada consulta que nao deve contar como conflito consigo mesma
     *     numa remarcacao. Nulo num agendamento novo.
     */
    void exigirAgendaLivre(
            UUID medicoId, UUID pacienteId, PeriodoConsulta periodo, UUID consultaIgnorada) {

        if (haConflito(consultas.buscarAtivasDoMedicoNoPeriodo(medicoId, periodo), consultaIgnorada)) {
            throw ConflitoDeAgendaException.doMedico(descrever(periodo));
        }
        if (haConflito(
                consultas.buscarAtivasDoPacienteNoPeriodo(pacienteId, periodo), consultaIgnorada)) {
            throw ConflitoDeAgendaException.doPaciente(descrever(periodo));
        }
    }

    private boolean haConflito(List<Consulta> candidatas, UUID consultaIgnorada) {
        return candidatas.stream()
                .anyMatch(c -> consultaIgnorada == null || !c.id().equals(consultaIgnorada));
    }

    private String descrever(PeriodoConsulta periodo) {
        return periodo.inicio() + " a " + periodo.fim();
    }
}
