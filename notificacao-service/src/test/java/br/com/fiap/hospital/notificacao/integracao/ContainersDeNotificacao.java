package br.com.fiap.hospital.notificacao.integracao;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;

/**
 * Um PostgreSQL e um RabbitMQ para toda a suite do M06.
 *
 * <p>Compartilhar os containers entre as bases custa nada e economiza minutos por
 * execucao: o que difere entre as suites e a configuracao do contexto, nao a
 * infraestrutura.
 */
final class ContainersDeNotificacao {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("notificacao_db").withUsername("hospital").withPassword("hospital");

    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3.13-management");

    static {
        POSTGRES.start();
        RABBIT.start();
    }

    private ContainersDeNotificacao() { }

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
