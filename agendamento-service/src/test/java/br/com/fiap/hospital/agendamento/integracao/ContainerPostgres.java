package br.com.fiap.hospital.agendamento.integracao;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Postgres unico para toda a suite de integracao.
 *
 * <p>Container estatico iniciado uma vez e reaproveitado por todas as classes. Subir
 * um Postgres por classe custaria mais que os proprios testes, e o isolamento entre
 * metodos vem da limpeza das tabelas, nao de containers separados.
 */
public final class ContainerPostgres {

    private static final PostgreSQLContainer<?> INSTANCIA =
            new PostgreSQLContainer<>("postgres:16")
                    .withDatabaseName("agendamento_db")
                    .withUsername("hospital")
                    .withPassword("hospital");

    static {
        INSTANCIA.start();
    }

    private ContainerPostgres() {}

    public static void registrarPropriedades(DynamicPropertyRegistry registro) {
        registro.add("spring.datasource.url", INSTANCIA::getJdbcUrl);
        registro.add("spring.datasource.username", INSTANCIA::getUsername);
        registro.add("spring.datasource.password", INSTANCIA::getPassword);
    }
}
