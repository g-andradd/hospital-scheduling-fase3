package br.com.fiap.hospital.notificacao.integracao;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;

/**
 * Os indices de V2, e o plano que os justifica — medido, nao suposto.
 *
 * <p>O plano e o do comando <b>capturado</b> da execucao real, com os parametros do relogio
 * fixo. Um {@code EXPLAIN} sobre SQL escrito aqui provaria o plano de um comando que a
 * aplicacao nao emite.
 */
@DisplayName("Indices e plano do lembrete D-1")
class IndicesDoLembreteIT extends NotificacaoITBase {

    @Autowired DataSource dataSource;
    @Autowired Environment ambiente;

    @Test
    @DisplayName("V2 cria a unicidade parcial do lembrete e o indice de status e horario")
    void v2CriaOsDoisIndices() {
        String unicidade = definicao("uq_notificacao_enviada_lembrete_d1");
        String composto = definicao("idx_agenda_local_status_data_hora");

        assertThat(unicidade)
                .contains("CREATE UNIQUE INDEX")
                .contains("notificacao_enviada")
                .contains("(consulta_id)")
                .as("parcial: so LEMBRETE_D1, para nao recusar notificacoes reativas repetidas")
                .contains("WHERE")
                .contains("'LEMBRETE_D1'");
        assertThat(composto)
                .contains("agenda_local")
                .as("status na frente, para restringir a faixa lida")
                .contains("(status, data_hora)");
        assertThat(ambiente.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
    }

    @Test
    @DisplayName("V2 reaplica num schema isolado sem executar nada de novo")
    void v2ReaplicaSemAlteracao() {
        String schema = "m07_" + UUID.randomUUID().toString().replace("-", "");
        try {
            var isolado = Flyway.configure().dataSource(dataSource).schemas(schema)
                    .createSchemas(true).locations("classpath:db/migration").load();
            assertThat(isolado.migrate().migrationsExecuted).isEqualTo(2);
            assertThat(isolado.migrate().migrationsExecuted).isZero();
            assertThat(jdbc.queryForList(
                    "SELECT indexname FROM pg_indexes WHERE schemaname = ?", String.class, schema))
                    .contains("uq_notificacao_enviada_lembrete_d1", "idx_agenda_local_status_data_hora");
        } finally {
            jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    /**
     * O planner em condicoes normais, sem {@code enable_seqscan=off}.
     *
     * <p>Obrigar o PostgreSQL a usar um indice so pergunta qual ele usaria se fosse forcado.
     * A pergunta de D2 e se ele <b>escolhe</b> o indice quando pode escolher — e para isso a
     * massa precisa ser grande e o recorte seletivo. Sao 6.000 consultas em 400 dias em torno
     * do relogio fixo, nos quatro status, com um lembrete previo em uma a cada cinco; a
     * janela de 24 horas devolve cerca de 0,1% da tabela. Se mesmo assim o planner preferir
     * varredura sequencial, a regra de parada manda reabrir D2, e nunca forcar o planner.
     */
    @Test
    @DisplayName("o plano do comando real usa o indice de status e horario")
    void planoUsaOIndiceDeStatusEHorario() {
        SqlEmitido.armar();
        try {
            lembretes.executar();
        } finally {
            SqlEmitido.desarmar();
        }
        List<String> selects = SqlEmitido.selects();
        assertThat(selects).as("a leitura precisa ter sido capturada").hasSize(1);

        massaRepresentativa();
        String plano = String.join(System.lineSeparator(), jdbc.queryForList(
                "EXPLAIN " + selects.getFirst(), String.class,
                Timestamp.from(AGORA), Timestamp.from(AGORA.plusSeconds(24 * 3600))));

        assertThat(plano)
                .as("com massa representativa o planner escolhe o indice composto sozinho")
                .contains("idx_agenda_local_status_data_hora");
    }

    /** Inserida em um comando so por tabela: o que importa e a distribuicao, nao a escrita. */
    private void massaRepresentativa() {
        jdbc.update("""
                INSERT INTO agenda_local (consulta_id, paciente_id, paciente_nome, paciente_email,
                        medico_nome, data_hora, status, ocorrido_em, atualizado_em)
                SELECT gen_random_uuid(), gen_random_uuid(), 'Paciente ' || i,
                       'paciente' || i || '@hospital.com', 'Medico ' || (i % 37),
                       ?::timestamptz + ((i % 400) - 200) * INTERVAL '1 day' + (i % 23) * INTERVAL '1 hour',
                       (ARRAY['AGENDADA', 'CONFIRMADA', 'CANCELADA', 'REALIZADA'])[1 + (i / 7) % 4],
                       ?::timestamptz, ?::timestamptz
                FROM generate_series(1, 6000) AS i
                """, Timestamp.from(AGORA), Timestamp.from(AGORA), Timestamp.from(AGORA));
        jdbc.update("""
                INSERT INTO notificacao_enviada (id, consulta_id, tipo, destinatario, canal,
                        enviado_em, conteudo)
                SELECT gen_random_uuid(), consulta_id,
                       CASE WHEN row_number() OVER () % 5 = 0 THEN 'LEMBRETE_D1' ELSE 'CONSULTA_CRIADA' END,
                       paciente_email, 'LOG', ?::timestamptz, 'texto'
                FROM agenda_local
                """, Timestamp.from(AGORA));
        jdbc.execute("ANALYZE agenda_local");
        jdbc.execute("ANALYZE notificacao_enviada");
    }

    private String definicao(String indice) {
        return jdbc.queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE schemaname = 'public' AND indexname = ?",
                String.class, indice);
    }
}
