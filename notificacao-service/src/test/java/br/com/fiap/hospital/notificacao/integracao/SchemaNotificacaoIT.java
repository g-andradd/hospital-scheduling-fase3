package br.com.fiap.hospital.notificacao.integracao;

import static org.assertj.core.api.Assertions.assertThat;

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
 * O schema que a V1 realmente produziu.
 *
 * <p>A ausencia de indice secundario e verificada tanto quanto a presenca das tabelas: o
 * M07 e que define as consultas do lembrete e medira seus proprios planos. Criar indice
 * agora seria palpite, e um palpite que ninguem mais questionaria depois de commitado.
 */
@DisplayName("Schema do notificacao_db")
class SchemaNotificacaoIT extends NotificacaoITBase {

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
     * A ausencia tambem e decisao, e por isso e verificada.
     *
     * <p>Sem esta asercao alguem acrescentaria um indice "por garantia" e nada acusaria o
     * desvio de D2 — que adia a escolha para o M07, quando existirem consultas reais para
     * medir.
     */
    @Test
    @DisplayName("nenhum indice secundario do M07 foi antecipado")
    void nenhumIndiceSecundarioAntecipado() {
        List<String> indices = jdbc.queryForList("""
                SELECT indexname FROM pg_indexes WHERE schemaname = 'public'
                AND tablename IN ('agenda_local', 'notificacao_enviada', 'evento_processado')
                """, String.class);

        assertThat(indices).containsExactlyInAnyOrder(
                "agenda_local_pkey", "notificacao_enviada_pkey", "evento_processado_pkey");
    }

    @Test
    @DisplayName("Flyway provisiona banco vazio e reaplica sem alteracao")
    void flywayProvisionaEReaplicaSemAlteracao() {
        String schema = "m06_" + UUID.randomUUID().toString().replace("-", "");
        try {
            var isolado = Flyway.configure().dataSource(dataSource).schemas(schema)
                    .createSchemas(true).locations("classpath:db/migration").load();
            assertThat(isolado.migrate().migrationsExecuted).isEqualTo(1);
            assertThat(isolado.migrate().migrationsExecuted)
                    .as("reaplicar uma migration ja aplicada nao pode executar nada")
                    .isZero();
        } finally {
            jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    @Test
    @DisplayName("Flyway conclui antes de JPA validate e do listener")
    void flywayConcluiAntesDeJpaEDoListener() {
        assertThat(ambiente.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        assertThat(flyway.info().applied()).hasSize(1);
        // O contexto so sobe se o validate passou; o listener so existe se o contexto subiu.
        assertThat(listeners.getListenerContainers()).hasSize(1);
        assertThat(listeners.getListenerContainers().iterator().next().isRunning()).isTrue();
    }

    /**
     * Nenhum endpoint <b>do servico</b>.
     *
     * <p>O {@code basicErrorController} do proprio Boot fica de fora da contagem: ele vem
     * com o starter web, existe em todo servico da stack e nao e superficie que o M06
     * tenha criado. Exigir zero controllers no contexto inteiro seria uma asercao sobre o
     * framework, nao sobre esta change.
     */
    @Test
    @DisplayName("o servico nao expoe endpoint novo")
    void servicoNaoExpoeEndpointNovo() {
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
                .as("o M06 e um consumidor: endpoint HTTP so no M07")
                .isEmpty();
    }

    private List<String> colunasDe(String tabela) {
        return jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_name = ?",
                String.class, tabela);
    }
}
