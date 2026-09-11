package br.com.fiap.hospital.notificacao.integracao;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.fiap.hospital.notificacao.lembrete.ServicoDeLembretes;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * O comando que a aplicacao realmente envia ao PostgreSQL, e quantos.
 *
 * <p>As duas garantias so podem ser observadas no SQL emitido. Um recorte aplicado em
 * memoria devolve o mesmo resultado de um recorte no comando, e um N+1 devolve o mesmo
 * resultado de uma leitura unica. Por isso a evidencia vem de {@link SqlEmitido}, na conexao,
 * e nao de um SQL escrito aqui.
 */
@DisplayName("Leitura dos candidatos ao lembrete")
class ConsultaDeCandidatosIT extends NotificacaoITBase {

    @Test
    @DisplayName("Scenario: Recortes aplicados no comando enviado ao armazenamento")
    void recortesAplicadosNoComandoEnviado() {
        agendar(AGORA.plus(Duration.ofHours(1)), "AGENDADA");

        List<String> selects = executarRegistrando().selects;

        assertThat(selects).as("uma leitura por execucao").hasSize(1);
        String sql = selects.getFirst().toLowerCase();
        assertThat(sql)
                .as("status, os dois limites como parametros e a exclusao dos ja lembrados")
                .contains("from agenda_local")
                .contains("status in ('agendada', 'confirmada')")
                .contains("data_hora > ?")
                .contains("data_hora <= ?")
                .contains("not exists")
                .contains("tipo = 'lembrete_d1'")
                .doesNotContain("now()", "current_timestamp", "localtimestamp",
                        "clock_timestamp", "statement_timestamp");
        assertThat(selects.getFirst())
                .as("o comando emitido e a constante de producao, nao um SQL remontado")
                .isEqualTo(ServicoDeLembretes.CANDIDATOS.replaceAll("\\s+", " ").trim());
    }

    /** Contagem positiva dos dois lados: zero contra zero seria igualdade sem significado. */
    @Test
    @DisplayName("Scenario: Mais candidatos nao multiplicam comandos de leitura")
    void maisCandidatosNaoMultiplicamLeituras() {
        agendar(AGORA.plus(Duration.ofHours(1)), "AGENDADA");
        Execucao comUm = executarRegistrando();

        for (int i = 0; i < 20; i++) {
            agendar(AGORA.plus(Duration.ofHours(2)).plusSeconds(60L * i), "CONFIRMADA");
        }
        Execucao comVinte = executarRegistrando();

        assertThat(comUm.confirmados).isEqualTo(1);
        assertThat(comVinte.confirmados).isEqualTo(20);
        assertThat(comUm.selects).as("com um candidato").hasSize(1);
        assertThat(comVinte.selects)
                .as("vinte candidatos nao podem custar mais leituras que um — isso seria N+1")
                .hasSize(1);
        assertThat(comUm.insercoes).hasSize(1);
        assertThat(comVinte.insercoes)
                .as("cada lembrete custa a propria reserva, e nada mais")
                .hasSize(20);
    }

    /**
     * O gravador registra a thread armada e so ela.
     *
     * <p>Sem isto, um comando do listener do M06 — que roda em outra thread e pode emitir a
     * qualquer momento — entraria na contagem, e a prova de "um comando por execucao" passaria
     * a depender de mensagem em voo.
     */
    @Test
    @DisplayName("o gravador de SQL captura a thread armada e ignora as demais")
    void gravadorCapturaAThreadArmadaEIgnoraAsDemais() throws Exception {
        SqlEmitido.armar();
        try {
            Thread outra = new Thread(() -> jdbc.queryForObject("SELECT 'de outra thread'", String.class));
            outra.start();
            outra.join(10_000);
            jdbc.queryForObject("SELECT 'da thread armada'", String.class);
        } finally {
            SqlEmitido.desarmar();
        }

        assertThat(SqlEmitido.comandos())
                .contains("SELECT 'da thread armada'")
                .doesNotContain("SELECT 'de outra thread'");
    }

    private record Execucao(int confirmados, List<String> selects, List<String> insercoes) { }

    private Execucao executarRegistrando() {
        SqlEmitido.armar();
        try {
            int confirmados = lembretes.executar();
            return new Execucao(confirmados, SqlEmitido.selects(), SqlEmitido.insercoes());
        } finally {
            SqlEmitido.desarmar();
        }
    }
}
