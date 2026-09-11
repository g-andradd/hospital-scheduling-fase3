package br.com.fiap.hospital.notificacao.consumer;

import br.com.fiap.hospital.contracts.ConsultaPayload;
import br.com.fiap.hospital.contracts.EventoEnvelope;
import br.com.fiap.hospital.notificacao.domain.NotificacaoEnviada;
import br.com.fiap.hospital.notificacao.repository.NotificacaoEnviadaRepository;
import br.com.fiap.hospital.notificacao.sender.NotificationSenderPort;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Agenda local e notificacao reativa, sem anotacao de Rabbit e sem controlar transacao.
 *
 * <p>Quem delimita a transacao e a fronteira unica de consumo. Este componente roda dentro
 * dela; se algo aqui falhar, a excecao sobe e reverte tudo, inclusive a marca.
 */
@Component
public class ProcessadorDeEventos {

    private final JdbcTemplate jdbc;
    private final NotificacaoEnviadaRepository auditoria;
    private final NotificationSenderPort sender;
    private final TemplatesDeNotificacao templates;
    private final Clock clock;

    ProcessadorDeEventos(JdbcTemplate jdbc, NotificacaoEnviadaRepository auditoria,
                         NotificationSenderPort sender, TemplatesDeNotificacao templates,
                         Clock clock) {
        this.jdbc = jdbc;
        this.auditoria = auditoria;
        this.sender = sender;
        this.templates = templates;
        this.clock = clock;
    }

    /**
     * Aplica o fato e, se ele valer, notifica.
     *
     * <p>A notificacao depende de o fato ter sido <b>aplicado</b>, e nao de ele ter
     * chegado: avisar o paciente de uma remarcacao que o proprio servico descartou por ser
     * antiga seria pior do que nao avisar nada.
     */
    public void processar(EventoEnvelope<ConsultaPayload> evento) {
        boolean aplicado = aplicarNaAgenda(evento);
        if (aplicado && templates.notifica(evento.eventType())) {
            notificar(evento);
        }
    }

    /**
     * Upsert condicionado, num comando so.
     *
     * <p>A condicao de monotonicidade e avaliada pelo PostgreSQL sob o lock da linha.
     * Consultar antes e decidir depois reabriria exatamente a corrida que ela existe para
     * fechar. O retorno diz se o fato valeu: zero linhas significa evento antigo, que
     * ainda assim sera marcado como processado pela fronteira.
     */
    private boolean aplicarNaAgenda(EventoEnvelope<ConsultaPayload> evento) {
        ConsultaPayload p = evento.payload();
        int linhas = jdbc.update("""
                INSERT INTO agenda_local
                    (consulta_id, paciente_id, paciente_nome, paciente_email, medico_nome,
                     data_hora, status, ocorrido_em, atualizado_em)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (consulta_id) DO UPDATE SET
                    paciente_id = EXCLUDED.paciente_id,
                    paciente_nome = EXCLUDED.paciente_nome,
                    paciente_email = EXCLUDED.paciente_email,
                    medico_nome = EXCLUDED.medico_nome,
                    data_hora = EXCLUDED.data_hora,
                    status = EXCLUDED.status,
                    ocorrido_em = EXCLUDED.ocorrido_em,
                    atualizado_em = EXCLUDED.atualizado_em
                WHERE EXCLUDED.ocorrido_em >= agenda_local.ocorrido_em
                """,
                evento.aggregateId(), p.paciente().id(), p.paciente().nome(),
                p.paciente().email(), p.medico().nome(), p.dataHora(), p.status().name(),
                Timestamp.from(evento.occurredAt()), Timestamp.from(clock.instant()));
        return linhas > 0;
    }

    private void notificar(EventoEnvelope<ConsultaPayload> evento) {
        var mensagem = templates.montar(evento.eventType(), evento.payload());
        sender.enviar(mensagem);
        auditoria.saveAndFlush(new NotificacaoEnviada(
                UUID.randomUUID(), evento.aggregateId(), evento.eventType().name(),
                mensagem.destinatario(), sender.canal(), clock.instant(), mensagem.corpo()));
    }
}
