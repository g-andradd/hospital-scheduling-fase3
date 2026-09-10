package br.com.fiap.hospital.historico.infrastructure.messaging;

import br.com.fiap.hospital.contracts.ConsultaPayload;
import br.com.fiap.hospital.contracts.EventoEnvelope;
import br.com.fiap.hospital.contracts.EventoJson;
import br.com.fiap.hospital.historico.infrastructure.persistence.entity.ConsultaEventoEntity;
import br.com.fiap.hospital.historico.infrastructure.persistence.repository.ConsultaEventoJpaRepository;
import java.sql.Timestamp;
import java.time.Clock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
class ProjetorDoHistorico {
    private final JdbcTemplate jdbc;
    private final ConsultaEventoJpaRepository eventos;
    private final EventoJson json;
    private final Clock clock;

    ProjetorDoHistorico(JdbcTemplate jdbc, ConsultaEventoJpaRepository eventos, EventoJson json, Clock clock) {
        this.jdbc = jdbc;
        this.eventos = eventos;
        this.json = json;
        this.clock = clock;
    }

    void projetar(EventoEnvelope<ConsultaPayload> evento) {
        var payload = evento.payload();
        jdbc.update("""
                INSERT INTO consulta_historico
                    (id, paciente_id, paciente_nome, medico_id, medico_nome, especialidade,
                     data_hora, status, observacoes, criado_em, atualizado_em)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (id) DO UPDATE SET
                    paciente_id = EXCLUDED.paciente_id,
                    paciente_nome = EXCLUDED.paciente_nome,
                    medico_id = EXCLUDED.medico_id,
                    medico_nome = EXCLUDED.medico_nome,
                    especialidade = EXCLUDED.especialidade,
                    data_hora = EXCLUDED.data_hora,
                    status = EXCLUDED.status,
                    observacoes = EXCLUDED.observacoes,
                    atualizado_em = EXCLUDED.atualizado_em
                WHERE EXCLUDED.atualizado_em >= consulta_historico.atualizado_em
                """,
                evento.aggregateId(), payload.paciente().id(), payload.paciente().nome(),
                payload.medico().id(), payload.medico().nome(), payload.medico().especialidade(),
                payload.dataHora(), payload.status().name(), payload.observacoes(),
                Timestamp.from(clock.instant()), Timestamp.from(evento.occurredAt()));
        eventos.saveAndFlush(new ConsultaEventoEntity(evento, json.escreverPayload(evento)));
    }
}
