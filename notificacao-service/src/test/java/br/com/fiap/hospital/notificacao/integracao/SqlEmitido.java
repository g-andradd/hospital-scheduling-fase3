package br.com.fiap.hospital.notificacao.integracao;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/**
 * Registra o SQL que a aplicacao realmente envia ao PostgreSQL.
 *
 * <p>O lembrete emite por JDBC, e o {@code StatementInspector} usado no historico so ve o
 * Hibernate. Por isso o registro fica na propria conexao: todo comando preparado ou executado
 * passa por aqui, venha do {@code JdbcTemplate} ou do JPA.
 *
 * <p>Registra somente na thread armada pelo teste. O listener do M06 roda em outra thread e
 * pode emitir comandos a qualquer momento; conta-los faria a prova de "um comando por
 * execucao" depender de mensagem em voo.
 */
final class SqlEmitido {

    private static final List<String> COMANDOS = new CopyOnWriteArrayList<>();
    private static volatile Thread alvo;

    private static final Set<String> PREPARA = Set.of("prepareStatement", "prepareCall");
    private static final Set<String> EXECUTA =
            Set.of("execute", "executeQuery", "executeUpdate", "executeLargeUpdate", "addBatch");

    private SqlEmitido() { }

    /** Passa a registrar os comandos desta thread, descartando os anteriores. */
    static void armar() {
        COMANDOS.clear();
        alvo = Thread.currentThread();
    }

    static void desarmar() {
        alvo = null;
    }

    static List<String> comandos() {
        return List.copyOf(COMANDOS);
    }

    static List<String> selects() {
        return COMANDOS.stream().filter(c -> c.regionMatches(true, 0, "select", 0, 6)).toList();
    }

    static List<String> insercoes() {
        return COMANDOS.stream().filter(c -> c.regionMatches(true, 0, "insert", 0, 6)).toList();
    }

    private static void registrar(String sql) {
        if (sql != null && Thread.currentThread() == alvo) {
            COMANDOS.add(sql.replaceAll("\\s+", " ").trim());
        }
    }

    /**
     * Embrulha a {@code DataSource} sem trocar o pool.
     *
     * <p>{@link DelegatingDataSource} preserva {@code unwrap} e e reconhecida pelo Spring Boot
     * como delegacao, entao quem procura a Hikari por tras dela continua achando.
     */
    static DataSource embrulhar(DataSource original) {
        return new Gravador(original);
    }

    static final class Gravador extends DelegatingDataSource {
        Gravador(DataSource alvo) {
            super(alvo);
        }

        @Override
        public Connection getConnection() throws SQLException {
            return conexao(super.getConnection());
        }

        @Override
        public Connection getConnection(String usuario, String senha) throws SQLException {
            return conexao(super.getConnection(usuario, senha));
        }
    }

    private static Connection conexao(Connection real) {
        return proxy(Connection.class, real, (metodo, argumentos, resultado) -> {
            if (PREPARA.contains(metodo) && argumentos != null && argumentos.length > 0
                    && argumentos[0] instanceof String sql) {
                registrar(sql);
            }
            if (resultado instanceof Statement comando && metodo.equals("createStatement")) {
                return proxy(Statement.class, comando, (m, a, r) -> r);
            }
            return resultado;
        });
    }

    private interface AposInvocar {
        Object apos(String metodo, Object[] argumentos, Object resultado);
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> tipo, T real, AposInvocar apos) {
        InvocationHandler manipulador = (instancia, metodo, argumentos) -> {
            if (tipo == Statement.class && EXECUTA.contains(metodo.getName())
                    && argumentos != null && argumentos.length > 0
                    && argumentos[0] instanceof String sql) {
                registrar(sql);
            }
            try {
                return apos.apos(metodo.getName(), argumentos, metodo.invoke(real, argumentos));
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        };
        return (T) Proxy.newProxyInstance(SqlEmitido.class.getClassLoader(),
                new Class<?>[] {tipo}, manipulador);
    }
}
