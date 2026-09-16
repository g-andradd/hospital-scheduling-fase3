package br.com.fiap.hospital.agendamento.integracao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Evidencia runtime do RNF-06 (D6): o contexto das suites do agendamento usa o PostgreSQL 16 e o
 * RabbitMQ 3.13 dos containers da propria suite, com uma operacao real em cada um.
 *
 * <p>Nao declara configuracao propria: herda a da base e reaproveita o mesmo contexto das suites.
 */
class InfraestruturaRealAgendamentoIT extends M05RabbitBase {

    @Autowired DataSource dataSource;

    /** Host, porta e banco; o banco e nulo para o RabbitMQ. */
    record Endereco(String host, int porta, String banco) {

        static Endereco daUrlJdbc(String url) {
            URI uri = URI.create(url.substring("jdbc:".length()));
            return new Endereco(uri.getHost(), uri.getPort(), uri.getPath().substring(1));
        }
    }

    /** Comparador da evidencia: o endereco recebido pelo contexto e o do container da suite. */
    static void confere(String recurso, Endereco recebido, Endereco doContainer) {
        assertThat(recebido).as(recurso + " aponta para o container da suite").isEqualTo(doContainer);
    }

    private static Endereco postgres() {
        PostgreSQLContainer<?> container = ContainerPostgres.instancia();
        return new Endereco(container.getHost(), container.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT), container.getDatabaseName());
    }

    private static Endereco rabbitmq() {
        return new Endereco(BROKER.getHost(), BROKER.getAmqpPort(), null);
    }

    private String urlRecebida() throws SQLException {
        try (Connection conexao = dataSource.getConnection()) {
            return conexao.getMetaData().getURL();
        }
    }

    private CachingConnectionFactory fabrica() {
        assertThat(rabbit.getConnectionFactory()).isInstanceOf(CachingConnectionFactory.class);
        return (CachingConnectionFactory) rabbit.getConnectionFactory();
    }

    @Test
    @DisplayName("PostgreSQL 16 real: a fonte de dados aponta para o container da suíte e consulta server_version_num")
    void provaPostgreSql() throws SQLException {
        try (Connection conexao = dataSource.getConnection();
                Statement comando = conexao.createStatement();
                ResultSet resultado = comando.executeQuery("SELECT current_setting('server_version_num')::int")) {
            confere("fonte de dados", Endereco.daUrlJdbc(conexao.getMetaData().getURL()), postgres());
            assertThat(resultado.next()).isTrue();
            assertThat(resultado.getInt(1)).isGreaterThanOrEqualTo(160000).isLessThan(170000);
            System.out.println("Evidencia RNF-06 (agendamento-service): PostgreSQL " + conexao.getMetaData().getURL()
                    + " server_version_num=" + resultado.getInt(1));
        }
    }

    @Test
    @DisplayName("RabbitMQ 3.13 real: a fábrica aponta para o container da suíte, a conexão informa a versão e a fila normativa existe")
    void provaRabbitMq() {
        CachingConnectionFactory fabrica = fabrica();
        confere("fabrica de conexoes", new Endereco(fabrica.getHost(), fabrica.getPort(), null), rabbitmq());
        String versao = rabbit.execute(canal -> String.valueOf(canal.getConnection().getServerProperties().get("version")));
        assertThat(versao).startsWith("3.13");
        int portaDaConexao = rabbit.execute(canal -> canal.getConnection().getPort());
        assertThat(portaDaConexao).isEqualTo(BROKER.getAmqpPort());
        String filaDeclarada = rabbit.execute(canal -> canal.queueDeclarePassive(N).getQueue());
        assertThat(filaDeclarada).isEqualTo(N);
        System.out.println("Evidencia RNF-06 (agendamento-service): RabbitMQ " + fabrica.getHost() + ":" + fabrica.getPort() + " version=" + versao
                + " porta da conexao=" + portaDaConexao + " fila " + filaDeclarada + " declarada passivamente");
    }

    @Test
    @DisplayName("o comparador de evidência reprova endereço runtime divergente do container")
    void enderecoDivergenteReprova() throws SQLException {
        Endereco recebido = Endereco.daUrlJdbc(urlRecebida());
        Endereco postgres = postgres();
        assertThatThrownBy(() -> confere("fonte de dados", recebido, new Endereco(postgres.host(), postgres.porta() + 1, postgres.banco())))
                .isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> confere("fonte de dados", recebido, new Endereco(postgres.host(), postgres.porta(), "outro_db")))
                .isInstanceOf(AssertionError.class);
        CachingConnectionFactory fabrica = fabrica();
        Endereco rabbitmq = rabbitmq();
        assertThatThrownBy(() -> confere("fabrica de conexoes", new Endereco(fabrica.getHost(), fabrica.getPort(), null),
                new Endereco(rabbitmq.host(), rabbitmq.porta() + 1, null))).isInstanceOf(AssertionError.class);
    }
}
