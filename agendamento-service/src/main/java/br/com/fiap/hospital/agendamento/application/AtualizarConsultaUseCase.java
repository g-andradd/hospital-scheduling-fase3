package br.com.fiap.hospital.agendamento.application;

import br.com.fiap.hospital.agendamento.domain.Consulta;
import br.com.fiap.hospital.agendamento.domain.EventoDeConsulta;
import br.com.fiap.hospital.agendamento.domain.PeriodoConsulta;
import br.com.fiap.hospital.agendamento.domain.TipoEventoConsulta;
import br.com.fiap.hospital.agendamento.domain.exception.ConsultaNaoEncontradaException;
import br.com.fiap.hospital.agendamento.domain.exception.MedicoNaoEncontradoException;
import br.com.fiap.hospital.agendamento.domain.port.ConsultaRepositoryPort;
import br.com.fiap.hospital.agendamento.domain.port.EventPublisherPort;
import br.com.fiap.hospital.agendamento.domain.port.UsuarioRepositoryPort;
import java.time.Clock;
import java.time.OffsetDateTime;

/** Altera periodo, medico e observacoes de uma consulta existente. */
public class AtualizarConsultaUseCase {

    private final ConsultaRepositoryPort consultas;
    private final UsuarioRepositoryPort usuarios;
    private final EventPublisherPort eventos;
    private final VerificadorDeAgenda verificador;
    private final Clock clock;

    public AtualizarConsultaUseCase(
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

    public ConsultaResumo executar(AtualizarConsultaCommand comando) {
        OffsetDateTime agora = OffsetDateTime.now(clock);

        Consulta consulta = consultas.buscarPorId(comando.consultaId())
                .orElseThrow(() -> new ConsultaNaoEncontradaException(comando.consultaId()));

        if (comando.medicoId() != null) {
            usuarios.buscarMedicoPorId(comando.medicoId())
                    .orElseThrow(() -> new MedicoNaoEncontradoException(comando.medicoId()));
        }

        PeriodoConsulta novoPeriodo = novoPeriodoDe(comando, consulta);

        // Toda validacao acontece ANTES de qualquer escrita no agregado.
        //
        // Validar depois de mutar deixaria a consulta alterada em memoria mesmo quando
        // a alteracao e recusada. Sob JPA isso nao seria apenas um estado sujo: a
        // entidade gerenciada sofreria flush no commit da transacao, e a alteracao
        // rejeitada seria persistida sem ninguem chamar salvar.
        //
        // A ordem tambem preserva a precedencia das recusas: status terminal e periodo
        // no passado sao verificados antes do conflito de agenda, entao uma consulta
        // cancelada recusa por transicao invalida, e nao por conflito.
        consulta.exigirAlteracaoValida(novoPeriodo, agora);

        // A propria consulta e ignorada na checagem: manter o mesmo horario nao pode
        // fazer a consulta conflitar consigo mesma.
        verificador.exigirAgendaLivre(
                comando.medicoId() != null ? comando.medicoId() : consulta.medicoId(),
                consulta.pacienteId(),
                novoPeriodo != null ? novoPeriodo : consulta.periodo(),
                consulta.id());

        consulta.atualizar(novoPeriodo, comando.medicoId(), comando.observacoes(), agora);

        Consulta salva = consultas.salvar(consulta);
        eventos.publicar(EventoDeConsulta.de(salva, TipoEventoConsulta.ATUALIZADA));
        return ConsultaResumo.de(salva);
    }

    /** Devolve nulo quando nem data nem duracao foram informadas, o que preserva o periodo. */
    private PeriodoConsulta novoPeriodoDe(AtualizarConsultaCommand comando, Consulta consulta) {
        if (comando.dataHora() == null && comando.duracaoMinutos() == null) {
            return null;
        }
        OffsetDateTime inicio =
                comando.dataHora() == null ? consulta.periodo().inicio() : comando.dataHora();
        int duracao = comando.duracaoMinutos() == null
                ? consulta.periodo().duracaoMinutos()
                : comando.duracaoMinutos();
        return new PeriodoConsulta(inicio, duracao);
    }
}
