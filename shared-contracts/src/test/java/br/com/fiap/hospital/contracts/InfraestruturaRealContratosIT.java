package br.com.fiap.hospital.contracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;

/**
 * Evidencia runtime do RNF-06 (D6): o contexto das suites de contratos usa o RabbitMQ 3.13 do container
 * da propria suite, com uma operacao real. O modulo nao usa banco, e por isso nao prova PostgreSQL.
 *
 * <p>Nao declara configuracao propria: herda a da base e reaproveita o mesmo contexto das suites.
 */
class InfraestruturaRealContratosIT extends RabbitITBase {

    /** Host e porta do broker. */
    record Endereco(String host, int porta) {}

    /** Comparador da evidencia: o endereco recebido pelo contexto e o do container da suite. */
    static void confere(String recurso, Endereco recebido, Endereco doContainer) {
        assertThat(recebido).as(recurso + " aponta para o container da suite").isEqualTo(doContainer);
    }

    private static Endereco rabbitmq() {
        return new Endereco(BROKER.getHost(), BROKER.getAmqpPort());
    }

    private CachingConnectionFactory fabrica() {
        assertThat(rabbit.getConnectionFactory()).isInstanceOf(CachingConnectionFactory.class);
        return (CachingConnectionFactory) rabbit.getConnectionFactory();
    }

    @Test
    @DisplayName("RabbitMQ 3.13 real: a fábrica aponta para o container da suíte, a conexão informa a versão e a fila normativa existe")
    void provaRabbitMq() {
        CachingConnectionFactory fabrica = fabrica();
        confere("fabrica de conexoes", new Endereco(fabrica.getHost(), fabrica.getPort()), rabbitmq());
        String versao = rabbit.execute(canal -> String.valueOf(canal.getConnection().getServerProperties().get("version")));
        assertThat(versao).startsWith("3.13");
        int portaDaConexao = rabbit.execute(canal -> canal.getConnection().getPort());
        assertThat(portaDaConexao).isEqualTo(BROKER.getAmqpPort());
        String filaDeclarada = rabbit.execute(canal -> canal.queueDeclarePassive(N).getQueue());
        assertThat(filaDeclarada).isEqualTo(N);
        System.out.println("Evidencia RNF-06 (shared-contracts): RabbitMQ " + fabrica.getHost() + ":" + fabrica.getPort() + " version=" + versao
                + " porta da conexao=" + portaDaConexao + " fila " + filaDeclarada + " declarada passivamente");
    }

    @Test
    @DisplayName("o comparador de evidência reprova endereço runtime divergente do container")
    void enderecoDivergenteReprova() {
        CachingConnectionFactory fabrica = fabrica();
        Endereco recebido = new Endereco(fabrica.getHost(), fabrica.getPort());
        Endereco rabbitmq = rabbitmq();
        assertThatThrownBy(() -> confere("fabrica de conexoes", recebido, new Endereco(rabbitmq.host(), rabbitmq.porta() + 1)))
                .isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> confere("fabrica de conexoes", recebido, new Endereco("outro-host", rabbitmq.porta())))
                .isInstanceOf(AssertionError.class);
    }
}
