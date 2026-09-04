package br.com.fiap.hospital.agendamento.application;

import br.com.fiap.hospital.agendamento.domain.Consulta;
import br.com.fiap.hospital.agendamento.domain.EventoDeConsulta;
import br.com.fiap.hospital.agendamento.domain.PeriodoConsulta;
import br.com.fiap.hospital.agendamento.domain.TipoEventoConsulta;
import br.com.fiap.hospital.agendamento.domain.exception.MedicoNaoEncontradoException;
import br.com.fiap.hospital.agendamento.domain.exception.PacienteNaoEncontradoException;
import br.com.fiap.hospital.agendamento.domain.exception.UsuarioNaoEncontradoException;
import br.com.fiap.hospital.agendamento.domain.port.ConsultaRepositoryPort;
import br.com.fiap.hospital.agendamento.domain.port.EventPublisherPort;
import br.com.fiap.hospital.agendamento.domain.port.UsuarioRepositoryPort;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Registra uma nova consulta. */
public class AgendarConsultaUseCase {

    private final ConsultaRepositoryPort consultas;
    private final UsuarioRepositoryPort usuarios;
    private final EventPublisherPort eventos;
    private final VerificadorDeAgenda verificador;
    private final Clock clock;

    public AgendarConsultaUseCase(
            ConsultaRepositoryPort consultas,
            UsuarioRepositoryPort usuarios,
            EventPublisherPort eventos,
            Clock clock) {
        this.consultas = consultas;
        this.usuarios = usuarios;
        this.eventos = eventos;
        this.verificador = new VerificadorDeAgenda(consultas);
        this.clock = clock;
    }

    public ConsultaResumo executar(AgendarConsultaCommand comando) {
        OffsetDateTime agora = OffsetDateTime.now(clock);

        usuarios.buscarPacientePorId(comando.pacienteId())
                .orElseThrow(() -> new PacienteNaoEncontradoException(comando.pacienteId()));
        usuarios.buscarMedicoPorId(comando.medicoId())
                .orElseThrow(() -> new MedicoNaoEncontradoException(comando.medicoId()));
        // O registrador tambem e chave estrangeira. Sem esta checagem, um id valido mas
        // inexistente so falha no flush, como violacao de constraint, e vira 500.
        usuarios.buscarUsuarioPorId(comando.registradoPorId())
                .orElseThrow(() -> new UsuarioNaoEncontradoException(comando.registradoPorId()));

        int duracao = comando.duracaoMinutos() == null
                ? Consulta.DURACAO_PADRAO_MINUTOS
                : comando.duracaoMinutos();
        PeriodoConsulta periodo = new PeriodoConsulta(comando.dataHora(), duracao);

        // A recusa por passado vem antes da checagem de agenda: e a regra mais barata
        // e a que produz a mensagem mais util quando as duas falhariam.
        Consulta consulta = Consulta.agendar(
                UUID.randomUUID(),
                comando.pacienteId(),
                comando.medicoId(),
                comando.registradoPorId(),
                periodo,
                comando.observacoes(),
                agora);

        verificador.exigirAgendaLivre(comando.medicoId(), comando.pacienteId(), periodo, null);

        Consulta salva = consultas.salvar(consulta);
        eventos.publicar(EventoDeConsulta.de(salva, TipoEventoConsulta.CRIADA));
        return ConsultaResumo.de(salva);
    }
}
