package br.com.fiap.hospital.agendamento.application;

import br.com.fiap.hospital.agendamento.domain.Consulta;
import br.com.fiap.hospital.agendamento.domain.SolicitanteAutenticado;
import br.com.fiap.hospital.agendamento.domain.exception.AcessoNegadoException;
import br.com.fiap.hospital.agendamento.domain.exception.ConsultaNaoEncontradaException;
import br.com.fiap.hospital.agendamento.domain.port.ConsultaRepositoryPort;
import java.util.UUID;

/** Recupera uma consulta pelo identificador, respeitando a regra de propriedade. */
public class BuscarConsultaPorIdUseCase {

    private final ConsultaRepositoryPort consultas;

    public BuscarConsultaPorIdUseCase(ConsultaRepositoryPort consultas) {
        this.consultas = consultas;
    }

    /**
     * O solicitante e obrigatorio, e essa e a garantia da regra de propriedade: nao ha
     * como chamar este caso de uso sem dizer quem esta pedindo.
     */
    public ConsultaResumo executar(UUID consultaId, SolicitanteAutenticado solicitante) {
        Consulta consulta = consultas.buscarPorId(consultaId)
                .orElseThrow(() -> new ConsultaNaoEncontradaException(consultaId));

        if (solicitante.ePaciente() && !solicitante.eTitularDe(consulta)) {
            throw new AcessoNegadoException("Esta consulta pertence a outro paciente");
        }
        return ConsultaResumo.de(consulta);
    }
}
