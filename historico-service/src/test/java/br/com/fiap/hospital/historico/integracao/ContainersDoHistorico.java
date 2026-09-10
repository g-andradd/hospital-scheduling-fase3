package br.com.fiap.hospital.historico.integracao;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;

/**
 * Um PostgreSQL e um RabbitMQ para toda a suite do historico.
 *
 * <p>As duas bases de teste — a do consumo AMQP do M08 e a do GraphQL do M09 — apontam
 * para os mesmos containers. Subir um par por base custaria minutos por execucao sem
 * provar nada a mais: o que difere entre elas e o ambiente web, nao a infraestrutura.
 */
final class ContainersDoHistorico {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("historico_db").withUsername("hospital").withPassword("hospital");

    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3.13-management");

    static {
        POSTGRES.start();
        RABBIT.start();
    }

    private ContainersDoHistorico() { }

    static void registrar(DynamicPropertyRegistry registro) {
        registro.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registro.add("spring.datasource.username", POSTGRES::getUsername);
        registro.add("spring.datasource.password", POSTGRES::getPassword);
        registro.add("spring.datasource.hikari.maximum-pool-size", () -> 3);
        registro.add("spring.rabbitmq.host", RABBIT::getHost);
        registro.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
        registro.add("spring.rabbitmq.username", RABBIT::getAdminUsername);
        registro.add("spring.rabbitmq.password", RABBIT::getAdminPassword);
    }
}
