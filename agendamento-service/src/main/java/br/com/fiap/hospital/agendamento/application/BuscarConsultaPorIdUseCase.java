package br.com.fiap.hospital.agendamento.application;

import br.com.fiap.hospital.agendamento.domain.exception.ConsultaNaoEncontradaException;
import br.com.fiap.hospital.agendamento.domain.port.ConsultaRepositoryPort;
import java.util.UUID;

/** Recupera uma consulta pelo identificador. */
public class BuscarConsultaPorIdUseCase {

    private final ConsultaRepositoryPort consultas;

    public BuscarConsultaPorIdUseCase(ConsultaRepositoryPort consultas) {
        this.consultas = consultas;
    }

    public ConsultaResumo executar(UUID consultaId) {
        return consultas.buscarPorId(consultaId)
                .map(ConsultaResumo::de)
                .orElseThrow(() -> new ConsultaNaoEncontradaException(consultaId));
    }
}
