package br.com.fiap.hospital.notificacao.lembrete;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * O caso de uso do lembrete D-1: o job horario e o disparo manual chamam este metodo, e
 * nenhum dos dois tem regra propria.
 *
 * <p>Este metodo <b>nao</b> e transacional, de proposito. Cada candidato e lembrado na
 * transacao de {@link RegistradorDeLembrete}; uma transacao unica faria a falha do decimo
 * candidato reverter os nove anteriores, que ja foram entregues, e a execucao seguinte os
 * reenviaria.
 */
@Component
public class ServicoDeLembretes {

    private static final Logger log = LoggerFactory.getLogger(ServicoDeLembretes.class);

    /** A janela e (agora, agora + 24h]: futuro estrito, e ate 24 horas inclusive. */
    static final Duration JANELA = Duration.ofHours(24);

    /**
     * Todos os candidatos de uma execucao, num comando so.
     *
     * <p>Janela, status e ausencia de lembrete anterior sao avaliados pelo PostgreSQL, e os
     * dois limites chegam como parametros calculados do {@code Clock} — o relogio do banco
     * mediria outra janela que a dos testes. O {@code NOT EXISTS} e filtro de eficiencia, e
     * nao a garantia: quem impede lembrete repetido e a unicidade parcial, na reserva.
     */
    public static final String CANDIDATOS = """
            SELECT a.consulta_id, a.paciente_nome, a.paciente_email, a.medico_nome, a.data_hora
            FROM agenda_local a
            WHERE a.status IN ('AGENDADA', 'CONFIRMADA')
              AND a.data_hora > ? AND a.data_hora <= ?
              AND NOT EXISTS (SELECT 1 FROM notificacao_enviada n
                              WHERE n.consulta_id = a.consulta_id AND n.tipo = 'LEMBRETE_D1')
            ORDER BY a.data_hora, a.consulta_id
            """;

    private final JdbcTemplate jdbc;
    private final RegistradorDeLembrete registrador;
    private final Clock clock;

    ServicoDeLembretes(JdbcTemplate jdbc, RegistradorDeLembrete registrador, Clock clock) {
        this.jdbc = jdbc;
        this.registrador = registrador;
        this.clock = clock;
    }

    /**
     * Lembra os candidatos da janela e devolve quantos lembretes foram confirmados.
     *
     * <p>Falha na leitura dos candidatos propaga: nada foi reservado nem enviado. Falha num
     * candidato chega aqui com a transacao dele ja revertida; ela e registrada e o laco
     * segue, e a consulta continua elegivel na execucao seguinte.
     */
    public int executar() {
        Instant agora = clock.instant();
        List<CandidatoAoLembrete> candidatos = jdbc.query(CANDIDATOS, ServicoDeLembretes::candidato,
                Timestamp.from(agora), Timestamp.from(agora.plus(JANELA)));

        int confirmados = 0;
        for (CandidatoAoLembrete candidato : candidatos) {
            try {
                if (registrador.lembrar(candidato)) confirmados++;
            } catch (RuntimeException e) {
                // So o identificador da consulta: a mensagem da excecao pode trazer o
                // endereco do paciente, e o log nao e lugar de dado pessoal.
                log.warn("Lembrete D-1 nao confirmado para a consulta {}: {}",
                        candidato.consultaId(), e.getClass().getSimpleName());
            }
        }
        return confirmados;
    }

    private static CandidatoAoLembrete candidato(ResultSet linha, int indice) throws SQLException {
        return new CandidatoAoLembrete(
                linha.getObject("consulta_id", UUID.class),
                linha.getString("paciente_nome"),
                linha.getString("paciente_email"),
                linha.getString("medico_nome"),
                linha.getObject("data_hora", OffsetDateTime.class).toInstant());
    }
}
