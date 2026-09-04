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
        credenciaisEPool(registro);
    }

    /**
     * Mesmo container, esquema proprio — para quando o teste precisa de um banco limpo
     * e nao apenas de tabelas limpas.
     *
     * <p>O par de testes do seed de demonstracao e o caso: um sobe com o perfil demo e
     * carrega o seed, o outro sobe sem o perfil e exige que os usuarios nao existam. No
     * esquema compartilhado, o Flyway do primeiro deixaria as linhas no banco, e o
     * segundo passaria ou falharia conforme a ordem de execucao — que ninguem controla.
     */
    public static void registrarPropriedadesEmEsquema(
            DynamicPropertyRegistry registro, String esquema) {
        registro.add("spring.datasource.url", () -> comEsquema(INSTANCIA.getJdbcUrl(), esquema));
        credenciaisEPool(registro);
        registro.add("spring.flyway.schemas", () -> esquema);
        registro.add("spring.flyway.create-schemas", () -> true);
    }

    /**
     * Pool pequeno, porque o custo aqui nao e desempenho — e o limite de conexoes.
     *
     * <p>O Spring mantem em cache um contexto por configuracao distinta, e cada contexto
     * segura o proprio pool ate o fim da suite. Com o padrao de 10 conexoes, algumas
     * classes de teste a mais esgotam o {@code max_connections} do Postgres, e o sintoma
     * nao aponta para a causa: a classe que falha e a que subir por ultimo, nao a que
     * abriu conexoes demais. Testes de integracao usam uma conexao de cada vez.
     */
    private static void credenciaisEPool(DynamicPropertyRegistry registro) {
        registro.add("spring.datasource.username", INSTANCIA::getUsername);
        registro.add("spring.datasource.password", INSTANCIA::getPassword);
        registro.add("spring.datasource.hikari.maximum-pool-size", () -> 3);
        registro.add("spring.datasource.hikari.minimum-idle", () -> 0);
    }

    private static String comEsquema(String url, String esquema) {
        return url + (url.contains("?") ? "&" : "?") + "currentSchema=" + esquema;
    }
}
