package br.com.fiap.hospital.historico.integracao;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.flywaydb.core.Flyway;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import javax.sql.DataSource;

@DisplayName("Schema versionado do histórico")
class SchemaHistoricoIT extends HistoricoITBase {
    @Autowired DataSource dataSource;
    @Autowired Flyway flyway;
    @Autowired Environment environment;
    @Autowired RabbitListenerEndpointRegistry listeners;

    @Test @DisplayName("Scenario: Provisionamento cria read model e trilha sem índices prematuros")
    void provisionamentoCriaReadModelSemIndicesPrematuros() {
        List<String> tabelas = jdbc.queryForList("SELECT tablename FROM pg_tables WHERE schemaname = 'public'", String.class);
        assertThat(tabelas).contains("consulta_historico", "consulta_evento", "evento_processado");
        assertThat(jdbc.queryForObject("""
                SELECT version FROM flyway_schema_history WHERE success = true
                ORDER BY installed_rank DESC LIMIT 1
                """, String.class)).isEqualTo("2");
        List<String> indices = jdbc.queryForList("""
                SELECT indexname FROM pg_indexes WHERE schemaname = 'public'
                AND tablename IN ('consulta_historico', 'consulta_evento', 'evento_processado')
                """, String.class);
        // O M08 exigia zero indice secundario porque nao havia consulta que o justificasse.
        // O M09 mediu os planos das consultas que passou a expor e criou dois, em V2. O que
        // esta asercao continua guardando e a ausencia de indice especulativo: nada de status,
        // que e filtro de baixa seletividade e nunca aparece sozinho nas consultas expostas.
        assertThat(indices).containsExactlyInAnyOrder(
                "consulta_historico_pkey", "consulta_evento_pkey", "evento_processado_pkey",
                "idx_consulta_historico_paciente_data_hora",
                "idx_consulta_historico_medico_data_hora");
        List<String> pks = jdbc.queryForList("""
                SELECT c.relname FROM pg_constraint p JOIN pg_class c ON c.oid = p.conrelid
                WHERE p.contype = 'p' AND c.relname IN ('consulta_historico', 'consulta_evento', 'evento_processado')
                """, String.class);
        assertThat(pks).containsExactlyInAnyOrder("consulta_historico", "consulta_evento", "evento_processado");
        assertThat(jdbc.queryForObject("""
                SELECT pg_get_constraintdef(f.oid) FROM pg_constraint f
                JOIN pg_class origem ON origem.oid = f.conrelid JOIN pg_class destino ON destino.oid = f.confrelid
                WHERE f.contype = 'f' AND origem.relname = 'consulta_evento' AND destino.relname = 'consulta_historico'
                """, String.class)).isEqualTo("FOREIGN KEY (consulta_id) REFERENCES consulta_historico(id)");
        var colunas = jdbc.queryForList("""
                SELECT table_name || '.' || column_name || ':' || data_type || ':' || is_nullable
                FROM information_schema.columns WHERE table_schema = 'public'
                AND (table_name, column_name) IN (('consulta_evento','payload'), ('consulta_evento','ocorrido_em'),
                ('consulta_historico','data_hora'), ('consulta_historico','criado_em'), ('consulta_historico','atualizado_em'))
                """, String.class);
        assertThat(colunas).contains("consulta_evento.payload:jsonb:NO", "consulta_evento.ocorrido_em:timestamp with time zone:NO",
                "consulta_historico.data_hora:timestamp with time zone:NO", "consulta_historico.criado_em:timestamp with time zone:NO",
                "consulta_historico.atualizado_em:timestamp with time zone:NO");
    }

    @Test @DisplayName("Scenario: Flyway provisiona banco vazio e reaplica sem alteração")
    void flywayProvisionaBancoVazioEReaplicaSemAlteracao() {
        String schema = "m08_" + UUID.randomUUID().toString().replace("-", "");
        try {
            var isolado = Flyway.configure().dataSource(dataSource).schemas(schema).createSchemas(true)
                    .locations("classpath:db/migration").load();
            assertThat(isolado.migrate().migrationsExecuted).isEqualTo(2);
            assertThat(isolado.migrate().migrationsExecuted).isZero();
        } finally {
            jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    @Test @DisplayName("Scenario: Flyway conclui antes de JPA validate e do listener")
    void flywayConcluiAntesDeJpaValidateEDoListener() {
        assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        assertThat(flyway.info().applied()).hasSize(2);
        assertThat(listeners.getListenerContainers()).hasSize(1);
        assertThat(listeners.getListenerContainers().iterator().next().isRunning()).isTrue();
        assertThat(snapshots.count()).isZero();
    }
}
