package br.com.fiap.hospital.notificacao.integracao;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.fiap.hospital.notificacao.scheduler.AgendadorDeLembretes;
import java.time.Duration;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.config.ScheduledTaskHolder;

/**
 * No profile de teste a varredura nao existe como tarefa, e nao apenas "ainda nao disparou".
 *
 * <p>Com cron horario, esperar alguns segundos e ver que nada aconteceu provaria pouco. A
 * evidencia principal e a ausencia do bean e de qualquer tarefa agendada; a espera so
 * confirma que a consulta elegivel fica sem lembrete ate o disparo explicito.
 */
@DisplayName("Agendador do lembrete no profile de teste")
class AgendadorDesligadoIT extends NotificacaoITBase {

    @Autowired ApplicationContext contexto;
    @Autowired Environment ambiente;

    @Test
    @DisplayName("Scenario: Profile de teste nao executa automaticamente")
    void profileDeTesteNaoExecutaAutomaticamente() {
        assertThat(ambiente.getActiveProfiles()).contains("test");
        assertThat(contexto.getBeansOfType(AgendadorDeLembretes.class))
                .as("sem o bean nao ha @EnableScheduling nem tarefa")
                .isEmpty();
        assertThat(contexto.getBeansOfType(ScheduledTaskHolder.class).values())
                .flatMap(ScheduledTaskHolder::getScheduledTasks)
                .noneMatch(tarefa -> tarefa.toString().contains("AgendadorDeLembretes"));

        UUID consulta = agendar(AGORA.plus(Duration.ofHours(1)), "AGENDADA");
        Awaitility.await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(5))
                .until(() -> lembretesRegistrados(consulta) == 0);
        Mockito.verify(lembretes, Mockito.never()).executar();

        assertThat(lembretes.executar())
                .as("o disparo explicito lembra a consulta")
                .isEqualTo(1);
        assertThat(lembretesRegistrados(consulta)).isEqualTo(1);
    }
}
