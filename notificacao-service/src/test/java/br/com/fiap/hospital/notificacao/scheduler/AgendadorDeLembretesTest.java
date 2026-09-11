package br.com.fiap.hospital.notificacao.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.fiap.hospital.notificacao.lembrete.ServicoDeLembretes;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;

/**
 * O agendamento que o servico registraria, sem subir o servico.
 *
 * <p>O contexto aqui tem so o agendador e um caso de uso substituto: sem listener, sem fila,
 * sem banco. Por isso ele pode ter a configuracao que a prova pede — inclusive fora do
 * profile {@code test} — sem competir com a base das suites de integracao.
 *
 * <p>Os casos sem arquivo de configuracao nao herdam o profile {@code test} de
 * src/test/resources: provam o padrao da anotacao. Os casos com arquivo leem o
 * {@code application.yml} de producao e escolhem o profile explicitamente.
 */
@DisplayName("Agendador do lembrete D-1")
class AgendadorDeLembretesTest {

    private static final String TAREFA = "AgendadorDeLembretes.executar";

    private final ApplicationContextRunner semArquivos = new ApplicationContextRunner()
            .withUserConfiguration(AgendadorDeLembretes.class)
            .withBean(ServicoDeLembretes.class, () -> Mockito.mock(ServicoDeLembretes.class));

    private final ApplicationContextRunner comArquivos = semArquivos
            .withInitializer(new ConfigDataApplicationContextInitializer());

    @Test
    @DisplayName("Scenario: Sem configuracao, a varredura automatica e horaria")
    void semConfiguracaoAVarreduraEHoraria() {
        semArquivos.run(ctx -> {
            assertThat(ctx).hasSingleBean(AgendadorDeLembretes.class);
            assertThat(cronDe(ctx))
                    .as("sem propriedade alguma, o padrao da anotacao: inicio de cada hora")
                    .isEqualTo("0 0 * * * *");
        });
        comArquivos.withPropertyValues("spring.profiles.active=default").run(ctx -> {
            assertThat(ctx).hasSingleBean(AgendadorDeLembretes.class);
            assertThat(cronDe(ctx))
                    .as("o application.yml de producao, fora do profile test, tambem e horario")
                    .isEqualTo("0 0 * * * *");
        });
    }

    @Test
    @DisplayName("Scenario: Expressao configurada substitui a padrao")
    void expressaoConfiguradaSubstituiAPadrao() {
        semArquivos.withPropertyValues("notificacao.lembrete.cron=0 */15 * * * *")
                .run(ctx -> assertThat(cronDe(ctx)).isEqualTo("0 */15 * * * *"));
    }

    @Test
    @DisplayName("com o application.yml de producao e o profile test, o agendador nao existe")
    void profileTestRemoveOAgendador() {
        comArquivos.withPropertyValues("spring.profiles.active=test").run(ctx -> {
            assertThat(ctx).hasNotFailed().doesNotHaveBean(AgendadorDeLembretes.class);
            assertThat(tarefas(ctx))
                    .as("sem o bean nao ha @EnableScheduling, e nenhuma tarefa e registrada")
                    .isEmpty();
        });
    }

    @Test
    @DisplayName("desligar por propriedade tambem remove o agendador")
    void propriedadeFalsaRemoveOAgendador() {
        semArquivos.withPropertyValues("notificacao.lembrete.agendador-habilitado=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(AgendadorDeLembretes.class));
    }

    private static String cronDe(AssertableApplicationContext ctx) {
        return tarefas(ctx).stream()
                .map(ScheduledTask::getTask)
                .filter(CronTask.class::isInstance)
                .filter(tarefa -> tarefa.toString().contains(TAREFA))
                .map(tarefa -> ((CronTask) tarefa).getExpression())
                .findFirst()
                .orElseThrow(() -> new AssertionError("nenhuma tarefa cron de " + TAREFA));
    }

    private static List<ScheduledTask> tarefas(AssertableApplicationContext ctx) {
        return ctx.getBeansOfType(ScheduledTaskHolder.class).values().stream()
                .flatMap(holder -> holder.getScheduledTasks().stream())
                .toList();
    }
}
