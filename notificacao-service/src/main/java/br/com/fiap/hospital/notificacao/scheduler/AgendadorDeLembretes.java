package br.com.fiap.hospital.notificacao.scheduler;

import br.com.fiap.hospital.notificacao.lembrete.ServicoDeLembretes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Dispara a varredura D-1 de hora em hora, e so isso: a regra mora no caso de uso.
 *
 * <p>O {@code @EnableScheduling} fica aqui, e nao numa configuracao global, pelo mesmo motivo
 * do agendador do outbox: com {@code notificacao.lembrete.agendador-habilitado=false} — o que
 * o profile {@code test} faz — o bean nao existe e nenhuma tarefa e registrada. A ausencia e
 * verificavel; "ainda nao disparou" nao seria.
 *
 * <p>A hora do disparo vem do agendador do Spring. A janela, que e a regra de negocio, vem
 * do {@code Clock} injetado no caso de uso.
 */
@Component
@EnableScheduling
@ConditionalOnProperty(name = "notificacao.lembrete.agendador-habilitado", havingValue = "true",
        matchIfMissing = true)
public class AgendadorDeLembretes {

    private static final Logger log = LoggerFactory.getLogger(AgendadorDeLembretes.class);

    private final ServicoDeLembretes lembretes;

    public AgendadorDeLembretes(ServicoDeLembretes lembretes) {
        this.lembretes = lembretes;
    }

    @Scheduled(cron = "${notificacao.lembrete.cron:0 0 * * * *}")
    public void executar() {
        log.info("Varredura D-1 concluida: {} lembrete(s) confirmado(s)", lembretes.executar());
    }
}
