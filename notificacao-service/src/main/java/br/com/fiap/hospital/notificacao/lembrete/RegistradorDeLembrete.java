package br.com.fiap.hospital.notificacao.lembrete;

import br.com.fiap.hospital.notificacao.consumer.TemplatesDeNotificacao;
import br.com.fiap.hospital.notificacao.sender.NotificationSenderPort;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Um candidato, uma transacao: reserva, depois envio.
 *
 * <p>A reserva e a propria linha de {@code notificacao_enviada}, gravada <b>antes</b> do
 * envio. Duas execucoes concorrentes que selecionaram a mesma consulta disputam a mesma
 * chave da unicidade parcial: a segunda espera a primeira terminar e, se ela confirmou,
 * encontra o conflito e nao envia. Enviar antes de reservar deixaria as duas acionarem o
 * canal antes de a chave decidir.
 *
 * <p>Se o envio falha, a excecao reverte a reserva e nada fica registrado. Se o envio
 * conclui e a confirmacao falha, a mensagem saiu sem registro e a execucao seguinte
 * reenvia: e a janela ao-menos-uma-vez que o design declara, e nao um descuido.
 */
@Component
public class RegistradorDeLembrete {

    /**
     * Grava a reserva, ou nada. Zero linhas significa que outra execucao ja tem a consulta.
     *
     * <p>O predicado do {@code ON CONFLICT} precisa repetir o do indice parcial para o
     * PostgreSQL inferi-lo. Se o indice sumir, o comando falha em vez de duplicar.
     */
    static final String RESERVA = """
            INSERT INTO notificacao_enviada
                (id, consulta_id, tipo, destinatario, canal, enviado_em, conteudo)
            VALUES (?, ?, 'LEMBRETE_D1', ?, ?, ?, ?)
            ON CONFLICT (consulta_id) WHERE tipo = 'LEMBRETE_D1' DO NOTHING
            """;

    private final JdbcTemplate jdbc;
    private final NotificationSenderPort sender;
    private final TemplatesDeNotificacao templates;
    private final Clock clock;

    RegistradorDeLembrete(JdbcTemplate jdbc, NotificationSenderPort sender,
                          TemplatesDeNotificacao templates, Clock clock) {
        this.jdbc = jdbc;
        this.sender = sender;
        this.templates = templates;
        this.clock = clock;
    }

    /** Devolve {@code true} quando este lembrete foi reservado, enviado e sera confirmado. */
    @Transactional(propagation = Propagation.REQUIRED)
    public boolean lembrar(CandidatoAoLembrete candidato) {
        var mensagem = templates.lembrete(candidato.pacienteNome(), candidato.pacienteEmail(),
                candidato.medicoNome(), candidato.dataHora());
        int reservadas = jdbc.update(RESERVA, UUID.randomUUID(), candidato.consultaId(),
                mensagem.destinatario(), sender.canal(), Timestamp.from(clock.instant()),
                mensagem.corpo());
        if (reservadas == 0) return false;
        sender.enviar(mensagem);
        return true;
    }
}
