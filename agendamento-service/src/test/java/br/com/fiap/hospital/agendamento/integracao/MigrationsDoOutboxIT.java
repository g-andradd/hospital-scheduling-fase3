package br.com.fiap.hospital.agendamento.integracao;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

class MigrationsDoOutboxIT extends M05JpaBase {
    @Autowired javax.sql.DataSource dataSource;
    static final UUID P=UUID.fromString("bbbbbbbb-0000-0000-0000-000000000001");
    static final UUID M=UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    static final UUID U=UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Test void bancoVazioEReaplicacao() throws Exception {
        emSchema((schema,j)-> {
            var flyway=config(schema).locations("classpath:db/migration").load();
            assertThat(flyway.migrate().migrationsExecuted).isEqualTo(3);
            assertThat(flyway.migrate().migrationsExecuted).isZero();
            assertThat(j.queryForObject("SELECT count(*) FROM consulta",Integer.class)).isZero();
        });
    }
    @Test void upgradeComSeedRealV900NaoCriaColisoes() throws Exception {
        emSchema((schema,j)->{
            v1ESeed(schema);
            assertThat(j.queryForObject("SELECT count(*) FROM consulta",Integer.class)).isZero();
            assertThat(j.queryForObject("SELECT count(*) FROM usuario",Integer.class)).isEqualTo(4);
            var f=config(schema).locations("classpath:db/migration","classpath:db/demo").outOfOrder(true).load();
            assertThat(f.migrate().migrationsExecuted).isEqualTo(2);
            assertThat(f.migrate().migrationsExecuted).isZero();
            assertThat(j.queryForObject("SELECT count(*) FROM usuario",Integer.class)).isEqualTo(4);
        });
    }
    @Test void upgradeComDadosFazBackfillSemAlterarInstantes() throws Exception {
        emSchema((schema,j)->{
            v1ESeed(schema);var id=inserir(j,INICIO,"AGENDADA");
            inserir(j,INICIO,"CANCELADA");inserir(j,INICIO.plusMinutes(30),"CONFIRMADA");
            config(schema).locations("classpath:db/migration","classpath:db/demo").outOfOrder(true).load().migrate();
            assertThat(j.queryForObject("SELECT count(*) FROM consulta",Integer.class)).isEqualTo(3);
            assertThat(j.queryForObject("SELECT lower(periodo_ocupado)=data_hora AND upper(periodo_ocupado)=data_hora+interval '30 minutes' FROM consulta WHERE id=?",Boolean.class,id)).isTrue();
        });
    }
    @Test void colisaoPreexistenteFalhaComIdsERecursoSemTolerancia() throws Exception {
        emSchema((schema,j)->{
            v1ESeed(schema);
            var a=inserir(j,INICIO,"AGENDADA");var b=inserir(j,INICIO.plusMinutes(10),"CONFIRMADA");
            assertThatThrownBy(()->config(schema).locations("classpath:db/migration","classpath:db/demo").outOfOrder(true).load().migrate())
                .hasStackTraceContaining(a.toString()).hasStackTraceContaining(b.toString()).hasStackTraceContaining("medico");
            assertThat(j.queryForObject("SELECT count(*) FROM consulta",Integer.class)).isEqualTo(2);
            assertThat(j.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_schema=? AND table_name='consulta' AND column_name='periodo_ocupado'",Integer.class,schema)).isZero();
        });
    }
    private void v1ESeed(String schema) {
        config(schema).locations("classpath:db/migration").target(MigrationVersion.fromVersion("1")).load().migrate();
        // Reproduz banco anterior ao M05: V1 + V900, ainda sem V2/V3 disponíveis.
        config(schema).locations("classpath:db/demo").validateOnMigrate(false).load().migrate();
    }
    private UUID inserir(JdbcTemplate j,java.time.OffsetDateTime data,String status) {
        var id=UUID.randomUUID();
        j.update("INSERT INTO consulta(id,paciente_id,medico_id,registrado_por_id,data_hora,duracao_minutos,status,criado_em,atualizado_em) VALUES(?,?,?,?,?,30,?,?,?)",
            id,P,M,U,data,status,AGORA,AGORA);return id;
    }
    private org.flywaydb.core.api.configuration.FluentConfiguration config(String schema) {
        return Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).createSchemas(true);
    }
    interface Cenario {void executar(String schema,JdbcTemplate jdbc) throws Exception;}
    private void emSchema(Cenario c) throws Exception {
        String schema="upgrade_"+UUID.randomUUID().toString().replace("-","");
        jdbc.execute("CREATE SCHEMA "+schema);
        try(var connection=dataSource.getConnection()) {
            connection.setSchema(schema);
            var local=new JdbcTemplate(new SingleConnectionDataSource(connection,true));
            try {c.executar(schema,local);} finally {connection.setSchema("m05_jpa");}
        } finally {jdbc.execute("DROP SCHEMA "+schema+" CASCADE");}
    }
}
