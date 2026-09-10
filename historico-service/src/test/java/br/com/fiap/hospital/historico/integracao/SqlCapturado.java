package br.com.fiap.hospital.historico.integracao;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.hibernate.resource.jdbc.spi.StatementInspector;

/**
 * Registra o SQL que o Hibernate realmente envia ao banco.
 *
 * <p>Duas perguntas do M09 so tem resposta aqui. A primeira e se o filtro foi para o
 * comando ou ficou numa selecao em memoria depois — um {@code EXPLAIN} escrito pelo
 * proprio teste nao responde isso, porque prova o plano de um SQL que o teste inventou,
 * nao o que a aplicacao emitiu. A segunda e quantos comandos uma consulta raiz custa, que
 * e a unica forma de detectar N+1: o resultado devolvido e identico com ou sem ele.
 *
 * <p>O Hibernate instancia esta classe por nome, entao o acumulador e estatico. As suites
 * de integracao rodam em serie no failsafe; ainda assim a lista e concorrente, porque a
 * requisicao HTTP e atendida em outra thread que nao a do teste.
 */
public class SqlCapturado implements StatementInspector {

    public static final String PROPRIEDADE =
            "spring.jpa.properties.hibernate.session_factory.statement_inspector";

    private static final List<String> COMANDOS = new CopyOnWriteArrayList<>();

    @Override
    public String inspect(String sql) {
        COMANDOS.add(sql);
        return sql;
    }

    static void limpar() {
        COMANDOS.clear();
    }

    /** Somente os SELECT: INSERT de massa e TRUNCATE de limpeza nao interessam aqui. */
    static List<String> selects() {
        return COMANDOS.stream()
                .map(sql -> sql.replaceAll("\\s+", " ").trim())
                .filter(sql -> sql.regionMatches(true, 0, "select", 0, 6))
                .toList();
    }

    static List<String> selectsSobre(String tabela) {
        return selects().stream().filter(sql -> sql.contains(tabela)).toList();
    }
}
