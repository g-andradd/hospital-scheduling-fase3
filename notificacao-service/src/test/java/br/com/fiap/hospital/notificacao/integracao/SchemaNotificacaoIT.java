package br.com.fiap.hospital.notificacao.integracao;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import javax.sql.DataSource;

/**
 * O schema que as migrations realmente produziram.
 *
 * <p>A lista de indices e verificada por igualdade, e nao por inclusao: o M06 nao criou
 * indice secundario algum, e o M07 criou exatamente os dois que as consultas do lembrete
 * exigem, com o plano medido. Um indice "por garantia" acrescentado depois faria esta suite
 * falhar — que e o ponto.
 */
@DisplayName("Schema do notificacao_db")
class SchemaNotificacaoIT extends NotificacaoITBase {

    private static final Path V1 = Path.of("src", "main", "resources", "db", "migration",
            "V1__cria_modelo_de_notificacao.sql");

    @Autowired Environment ambiente;
    @Autowired Flyway flyway;
    @Autowired DataSource dataSource;
    @Autowired RabbitListenerEndpointRegistry listeners;
    @Autowired ApplicationContext contexto;

    @Test
    @DisplayName("V1 cria as tres tabelas do modelo normativo")
    void v1CriaAsTresTabelas() {
        List<String> tabelas = jdbc.queryForList(
                "SELECT tablename FROM pg_tables WHERE schemaname = 'public'", String.class);

        assertThat(tabelas).contains("agenda_local", "notificacao_enviada", "evento_processado");
    }

    @Test
    @DisplayName("agenda_local tem as colunas do modelo e o instante do fato aplicado")
    void agendaLocalTemAsColunasEsperadas() {
        List<String> colunas = jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_name = 'agenda_local'",
                String.class);

        assertThat(colunas).containsExactlyInAnyOrder(
                "consulta_id", "paciente_id", "paciente_nome", "paciente_email", "medico_nome",
                "data_hora", "status", "ocorrido_em", "atualizado_em");
    }

    @Test
    @DisplayName("notificacao_enviada e evento_processado tem as colunas do modelo")
    void demaisTabelasTemAsColunasEsperadas() {
        assertThat(colunasDe("notificacao_enviada")).containsExactlyInAnyOrder(
                "id", "consulta_id", "tipo", "destinatario", "canal", "enviado_em", "conteudo");
        assertThat(colunasDe("evento_processado")).containsExactlyInAnyOrder(
                "event_id", "processado_em");
    }

    /**
     * A lista inteira, e nao "contem".
     *
     * <p>As PKs vem de V1 e os dois indices vem de V2, do lembrete. Qualquer outro indice e
     * desvio de decisao — o M06 adiou a escolha para quando houvesse consultas reais para
     * medir, e o M07 mediu as suas.
     */
    @Test
    @DisplayName("os indices sao exatamente as PKs de V1 e os dois do lembrete em V2")
    void indicesSaoAsPksDeV1EOsDoLembrete() {
        List<String> indices = jdbc.queryForList("""
                SELECT indexname FROM pg_indexes WHERE schemaname = 'public'
                AND tablename IN ('agenda_local', 'notificacao_enviada', 'evento_processado')
                """, String.class);

        assertThat(indices).containsExactlyInAnyOrder(
                "agenda_local_pkey", "notificacao_enviada_pkey", "evento_processado_pkey",
                "uq_notificacao_enviada_lembrete_d1", "idx_agenda_local_status_data_hora");
    }

    @Test
    @DisplayName("V1 continua sem indice: migration aplicada e imutavel")
    void v1ContinuaSemIndice() throws Exception {
        Path caminho = Files.exists(V1) ? V1 : Path.of("notificacao-service").resolve(V1);

        assertThat(Files.readString(caminho))
                .as("reescrever V1 quebraria o checksum de quem ja a aplicou")
                .doesNotContain("CREATE INDEX")
                .doesNotContain("CREATE UNIQUE INDEX");
    }

    @Test
    @DisplayName("Flyway provisiona banco vazio e reaplica sem alteracao")
    void flywayProvisionaEReaplicaSemAlteracao() {
        String schema = "m06_" + UUID.randomUUID().toString().replace("-", "");
        try {
            var isolado = Flyway.configure().dataSource(dataSource).schemas(schema)
                    .createSchemas(true).locations("classpath:db/migration").load();
            assertThat(isolado.migrate().migrationsExecuted)
                    .as("V1 e V2, do zero")
                    .isEqualTo(2);
            assertThat(isolado.migrate().migrationsExecuted)
                    .as("reaplicar migrations ja aplicadas nao pode executar nada")
                    .isZero();
        } finally {
            jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    @Test
    @DisplayName("Flyway conclui antes de JPA validate e do listener")
    void flywayConcluiAntesDeJpaEDoListener() {
        assertThat(ambiente.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        assertThat(flyway.info().applied()).hasSize(2);
        // O contexto so sobe se o validate passou; o listener so existe se o contexto subiu.
        assertThat(listeners.getListenerContainers()).hasSize(1);
        assertThat(listeners.getListenerContainers().iterator().next().isRunning()).isTrue();
    }

    /**
     * Exatamente um endpoint <b>do servico</b>: o disparo manual do lembrete.
     *
     * <p>O {@code basicErrorController} do proprio Boot fica de fora da contagem: ele vem
     * com o starter web, existe em todo servico da stack e nao e superficie que o servico
     * tenha criado. Qualquer controller alem do lembrete precisa de decisao propria.
     */
    @Test
    @DisplayName("o servico expoe exatamente o controller do lembrete")
    void servicoExpoeSoOControllerDoLembrete() {
        var controllers = java.util.stream.Stream.concat(
                        java.util.Arrays.stream(contexto.getBeanNamesForAnnotation(
                                org.springframework.web.bind.annotation.RestController.class)),
                        java.util.Arrays.stream(contexto.getBeanNamesForAnnotation(
                                org.springframework.stereotype.Controller.class)))
                .distinct()
                .filter(nome -> contexto.getType(nome) != null)
                .filter(nome -> contexto.getType(nome).getPackageName()
                        .startsWith("br.com.fiap.hospital"))
                .toList();

        assertThat(controllers)
                .as("o M07 acrescenta so o disparo manual do lembrete")
                .containsExactly("lembreteController");
    }

    private List<String> colunasDe(String tabela) {
        return jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_name = ?",
                String.class, tabela);
    }
}
