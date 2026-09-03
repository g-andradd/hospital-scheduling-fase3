package br.com.fiap.hospital.agendamento.application;

import br.com.fiap.hospital.agendamento.domain.Consulta;
import br.com.fiap.hospital.agendamento.domain.EventoDeConsulta;
import br.com.fiap.hospital.agendamento.domain.TipoEventoConsulta;
import br.com.fiap.hospital.agendamento.domain.exception.ConsultaNaoEncontradaException;
import br.com.fiap.hospital.agendamento.domain.port.ConsultaRepositoryPort;
import br.com.fiap.hospital.agendamento.domain.port.EventPublisherPort;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Confirma uma consulta agendada. */
public class ConfirmarConsultaUseCase {

    private final ConsultaRepositoryPort consultas;
    private final EventPublisherPort eventos;
    private final Clock clock;

    public ConfirmarConsultaUseCase(
            ConsultaRepositoryPort consultas, EventPublisherPort eventos, Clock clock) {
        this.consultas = consultas;
        this.eventos = eventos;
        this.clock = clock;
    }

    public ConsultaResumo executar(UUID consultaId) {
        OffsetDateTime agora = OffsetDateTime.now(clock);

        Consulta consulta = consultas.buscarPorId(consultaId)
                .orElseThrow(() -> new ConsultaNaoEncontradaException(consultaId));

        consulta.confirmar(agora);

        Consulta salva = consultas.salvar(consulta);
        eventos.publicar(EventoDeConsulta.de(salva, TipoEventoConsulta.CONFIRMADA));
        return ConsultaResumo.de(salva);
    }
}
