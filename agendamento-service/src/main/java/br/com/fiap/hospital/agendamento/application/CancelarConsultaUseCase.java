package br.com.fiap.hospital.agendamento.application;

import br.com.fiap.hospital.agendamento.domain.Consulta;
import br.com.fiap.hospital.agendamento.domain.EventoDeConsulta;
import br.com.fiap.hospital.agendamento.domain.TipoEventoConsulta;
import br.com.fiap.hospital.agendamento.domain.exception.ConsultaNaoEncontradaException;
import br.com.fiap.hospital.agendamento.domain.port.ConsultaRepositoryPort;
import br.com.fiap.hospital.agendamento.domain.port.EventPublisherPort;
import java.time.Clock;
import java.time.OffsetDateTime;

/** Cancela uma consulta, exigindo motivo. */
public class CancelarConsultaUseCase {

    private final ConsultaRepositoryPort consultas;
    private final EventPublisherPort eventos;
    private final Clock clock;

    public CancelarConsultaUseCase(
            ConsultaRepositoryPort consultas, EventPublisherPort eventos, Clock clock) {
        this.consultas = consultas;
        this.eventos = eventos;
        this.clock = clock;
    }

    public ConsultaResumo executar(CancelarConsultaCommand comando) {
        OffsetDateTime agora = OffsetDateTime.now(clock);

        Consulta consulta = consultas.buscarPorId(comando.consultaId())
                .orElseThrow(() -> new ConsultaNaoEncontradaException(comando.consultaId()));

        consulta.cancelar(comando.motivo(), agora);

        Consulta salva = consultas.salvar(consulta);
        eventos.publicar(EventoDeConsulta.de(salva, TipoEventoConsulta.CANCELADA));
        return ConsultaResumo.de(salva);
    }
}
