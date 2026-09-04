package br.com.fiap.hospital.agendamento.integracao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.EntityManager;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.exception.FlywayValidateException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** O schema vem de migration versionada, e nao da geracao automatica do Hibernate. */
@SpringBootTest
@Transactional
@DisplayName("Schema versionado por migration")
class SchemaVersionadoIT {

    @DynamicPropertySource
    static void propriedades(DynamicPropertyRegistry registro) {
        ContainerPostgres.registrarPropriedades(registro);
    }

    @Autowired private EntityManager em;
    @Autowired private Flyway flyway;
    @Autowired private JdbcTemplate jdbc;

    @Test
    @DisplayName("Scenario: Provisionamento a partir de banco vazio")
    void provisionamentoCriaTabelasEIndices() {
        @SuppressWarnings("unchecked")
        List<String> tabelas = em.createNativeQuery(
                        "SELECT tablename FROM pg_tables WHERE schemaname = 'public'")
                .getResultList();

        assertThat(tabelas).contains("usuario", "paciente", "medico", "consulta");

        Object versao = em.createNativeQuery(
                        "SELECT version FROM flyway_schema_history WHERE success = true "
                                + "ORDER BY installed_rank DESC LIMIT 1")
                .getSingleResult();
        assertThat(versao).isEqualTo("3");
    }

    @Test
    @DisplayName("Scenario: Reaplicacao nao repete migrations ja aplicadas")
    void reaplicacaoNaoExecutaNada() {
        assertThat(flyway.migrate().migrationsExecuted)
                .as("o banco ja esta na versao corrente")
                .isZero();
    }

    /**
     * Roda fora da transacao do teste de proposito: o Flyway valida por uma conexao
     * propria do DataSource, entao uma alteracao nao commitada seria invisivel para ele.
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("Scenario: Schema divergente do esperado interrompe a subida")
    void schemaDivergenteInterrompeASubida() {
        Integer original = jdbc.queryForObject(
                "SELECT checksum FROM flyway_schema_history WHERE version = '1'", Integer.class);
        try {
            // Simula um banco cujo estado nao corresponde as migrations conhecidas.
            // A validacao precisa recusar, nao acomodar a divergencia.
            jdbc.update("UPDATE flyway_schema_history SET checksum = checksum + 1 WHERE version = '1'");

            assertThatThrownBy(flyway::validate)
                    .isInstanceOf(FlywayValidateException.class)
                    .hasMessageContaining("checksum");
        } finally {
            jdbc.update("UPDATE flyway_schema_history SET checksum = ? WHERE version = '1'", original);
        }
    }

    @Test
    @DisplayName("o Hibernate esta em modo de validacao, nunca de geracao")
    void hibernateNaoGeraSchema() {
        assertThat(System.getProperty("spring.jpa.hibernate.ddl-auto", "validate"))
                .isNotEqualTo("update")
                .isNotEqualTo("create")
                .isNotEqualTo("create-drop");
    }
}
