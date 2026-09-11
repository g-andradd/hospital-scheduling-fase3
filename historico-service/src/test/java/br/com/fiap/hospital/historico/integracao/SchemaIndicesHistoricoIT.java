package br.com.fiap.hospital.historico.integracao;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Os indices que o M08 adiou, agora medidos em vez de supostos.
 *
 * <p>O M08 nao criou indice secundario algum de proposito: sem consultas reais, qualquer
 * escolha seria palpite. O M09 tem as consultas, entao aqui se verifica o que foi criado e
 * se o plano de execucao real sustenta a decisao registrada em D7.
 */
@DisplayName("Indices e plano de consulta do historico")
class SchemaIndicesHistoricoIT extends GraphqlITBase {

    private static final Path V1 = Path.of("src", "main", "resources", "db", "migration",
            "V1__cria_read_model_do_historico.sql");

    /** Centro da massa e limite dos recortes: o mesmo instante do relogio fixo das suites. */
    private static final String LIMITE = "2026-09-10 12:00:00+00";

    @Test
    @DisplayName("V2 cria os dois indices das consultas expostas")
    void v2CriaOsDoisIndices() {
        List<String> indices = jdbc.queryForList(
                "SELECT indexname FROM pg_indexes WHERE tablename = 'consulta_historico'",
                String.class);

        assertThat(indices).contains(
                "idx_consulta_historico_paciente_data_hora",
                "idx_consulta_historico_medico_data_hora");
    }

    /**
     * A ausencia do indice de status tambem e decisao, e por isso e verificada.
     *
     * <p>Sem esta asercao, alguem poderia acrescenta-lo "por garantia" e nada acusaria o
     * desvio de D7 — que exige evidencia, e nao intuicao, para criar indice.
     */
    @Test
    @DisplayName("nao existe indice de status antecipado")
    void naoExisteIndiceDeStatus() {
        List<String> definicoes = jdbc.queryForList(
                "SELECT indexdef FROM pg_indexes WHERE tablename = 'consulta_historico'",
                String.class);

        assertThat(definicoes)
                .as("D7 fecha a decisao: status e filtro de baixa seletividade e nunca aparece sozinho")
                .noneMatch(definicao -> definicao.contains("(status")
                        || definicao.contains(", status"));
    }

    @Test
    @DisplayName("a migration original do read model permanece inalterada")
    void migrationOriginalPermaneceInalterada() throws Exception {
        Path caminho = Files.exists(V1) ? V1 : Path.of("historico-service").resolve(V1);
        String conteudo = Files.readString(caminho);

        assertThat(conteudo)
                .as("migration aplicada e imutavel: reescreve-la quebra o checksum de quem ja rodou")
                .doesNotContain("CREATE INDEX")
                .contains("CREATE TABLE consulta_historico");
    }

    @Test
    @DisplayName("o schema continua validado pelo Hibernate")
    void schemaContinuaValidado() {
        // ddl-auto=validate: se o mapeamento e o schema divergissem, o contexto nao teria
        // subido — este teste so existe para tornar a garantia visivel.
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_name IN "
                        + "('consulta_historico','consulta_evento','evento_processado')", Long.class))
                .isEqualTo(3L);
    }

    /**
     * O plano real, com o planner em condicoes normais.
     *
     * <p>D7 exige que a decisao dos indices venha de medicao, e medicao com
     * {@code enable_seqscan=off} nao mede nada: ela pergunta qual indice o PostgreSQL usaria
     * se fosse obrigado a usar um, e a resposta e sempre "o que existir". A pergunta certa e
     * se ele <b>escolhe</b> o indice quando pode escolher — e para isso a massa precisa ser
     * grande e o recorte precisa ser seletivo, como em producao.
     *
     * <p>Sao 6.000 linhas: cerca de 20 pertencem ao paciente alvo, cerca de 20 ao medico
     * alvo, e as demais recebem identificadores aleatorios. Cada recorte devolve entao algo
     * perto de 0,3% da tabela — a seletividade que faz o indice valer a pena. Se mesmo assim
     * o planner preferir varredura sequencial, a evidencia contradiz D7, e a regra de parada
     * manda interromper o apply e reabrir a decisao por /opsx:update, nunca forcar o planner
     * ate o teste ficar verde.
     */
    @Test
    @DisplayName("o plano das consultas expostas usa os indices de V2")
    void planoUsaOsIndicesDeV2() {
        massaRepresentativa();

        // O limite e fixo, e nao now(): com o relogio real do servidor, o mesmo teste
        // examinaria uma fatia diferente da massa a cada execucao e, passada a janela de
        // 200 dias, nao sobraria linha alguma no recorte — o plano mudaria sem ninguem
        // tocar em codigo. Um limite ancorado na propria massa mantem a medicao estavel.
        String planoPorPaciente = plano(
                "SELECT * FROM consulta_historico WHERE paciente_id = ? "
                        + "AND data_hora >= ?::timestamptz ORDER BY data_hora, id",
                pacienteA, LIMITE);
        String planoPorMedico = plano(
                "SELECT * FROM consulta_historico WHERE medico_id = ? "
                        + "AND data_hora >= ?::timestamptz ORDER BY data_hora, id",
                medicoA, LIMITE);

        assertThat(planoPorPaciente)
                .as("com massa representativa o planner escolhe o indice de paciente sozinho")
                .contains("idx_consulta_historico_paciente_data_hora");
        assertThat(planoPorMedico)
                .as("e o de medico sozinho")
                .contains("idx_consulta_historico_medico_data_hora");
    }

    /**
     * 6.000 linhas distribuidas em 400 dias em torno de {@link #LIMITE}, metade de cada lado.
     *
     * <p>Inserido em um comando so: 6.000 chamadas separadas levariam minutos e o custo nao
     * compraria nada, porque o que importa aqui e a distribuicao, nao o caminho de escrita.
     */
    private void massaRepresentativa() {
        jdbc.update("""
                INSERT INTO consulta_historico (id, paciente_id, paciente_nome, medico_id,
                        medico_nome, especialidade, data_hora, status, observacoes,
                        criado_em, atualizado_em)
                SELECT gen_random_uuid(),
                       CASE WHEN i % 300 = 0 THEN ?::uuid ELSE gen_random_uuid() END,
                       'Paciente ' || i,
                       CASE WHEN i % 300 = 1 THEN ?::uuid ELSE gen_random_uuid() END,
                       'Medico ' || i, 'Cardiologia',
                       ?::timestamptz + ((i % 400) - 200) * INTERVAL '1 day',
                       'AGENDADA', NULL, now(), now()
                FROM generate_series(1, 6000) AS i
                """, pacienteA, medicoA, LIMITE);
        jdbc.execute("ANALYZE consulta_historico");
    }

    private String plano(String sql, Object... parametros) {
        return String.join(System.lineSeparator(),
                jdbc.queryForList("EXPLAIN " + sql, String.class, parametros));
    }
}
