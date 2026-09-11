package br.com.fiap.hospital.historico.integracao;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A correcao manual e sua auditoria, contra PostgreSQL real.
 *
 * <p>O que estes testes protegem nao e o caminho feliz: e a atomicidade entre alterar o
 * registro e registrar quem alterou e por que. Um snapshot corrigido sem trilha e
 * indistinguivel de dado original — e e nesse estado que uma auditoria mente.
 */
@DisplayName("Correcao manual do registro")
class CorrecaoHistoricoIT extends GraphqlITBase {

    private static final OffsetDateTime DATA = instante("2026-09-18T14:30:00Z");

    private static final String CORRIGIR = """
            mutation($in: CorrigirRegistroHistoricoInput!) {
              corrigirRegistroHistorico(input: $in) {
                id pacienteId medicoId pacienteNome medicoNome especialidade
                dataHora status observacoes atualizadoEm
              }
            }
            """;

    private RespostaGraphql corrigir(Map<String, Object> input) {
        return executar(tokenMedico(), CORRIGIR, Map.of("in", input));
    }

    private static Map<String, Object> entrada(UUID consultaId, String justificativa) {
        Map<String, Object> input = new HashMap<>();
        input.put("consultaId", consultaId.toString());
        input.put("justificativa", justificativa);
        return input;
    }

    private JsonNode auditoriaDe(UUID consultaId) {
        List<String> payloads = jdbc.queryForList(
                "SELECT payload::text FROM consulta_evento WHERE consulta_id = ? "
                        + "AND tipo_evento = 'CORRECAO_MANUAL'", String.class, consultaId);
        assertThat(payloads).as("a correcao precisa ter deixado exatamente uma trilha").hasSize(1);
        try {
            return mapper.readTree(payloads.getFirst());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // --- correcao valida -----------------------------------------------------------------

    @Test
    @DisplayName("Scenario: Correcao valida altera o snapshot e registra a trilha")
    void correcaoValidaAlteraSnapshotERegistraTrilha() {
        UUID id = inserirConsulta(pacienteA, medicoA, DATA, "AGENDADA", "observacao inicial");
        Map<String, Object> input = entrada(id, "erro de digitacao no nome do paciente");
        input.put("pacienteNome", "Maria Corrigida");

        var resposta = corrigir(input);

        assertThat(resposta.temErro())
                .as("erro: %s", resposta.corpo().path("errors").toString()).isFalse();
        var registro = snapshots.findById(id).orElseThrow();
        assertThat(registro.getPacienteNome()).isEqualTo("Maria Corrigida");
        assertThat(registro.getAtualizadoEm())
                .as("atualizado_em passa a marcar o instante da correcao")
                .isEqualTo(AGORA);

        JsonNode auditoria = auditoriaDe(id);
        assertThat(auditoria.path("justificativa").asText())
                .isEqualTo("erro de digitacao no nome do paciente");
        assertThat(auditoria.path("alteracoes").path("pacienteNome").path("antes").asText())
                .isEqualTo("Paciente " + pacienteA.toString().substring(0, 4));
        assertThat(auditoria.path("alteracoes").path("pacienteNome").path("depois").asText())
                .isEqualTo("Maria Corrigida");
    }

    /**
     * Scenario: Autor da correcao vem do token.
     *
     * <p>A prova e dupla e precisa das duas metades: o schema nao expor campo de autor
     * (verificado por introspeccao em {@code SchemaGraphqlIT}) e a trilha registrar o
     * medico do token. Uma sem a outra deixaria a duvida de onde o valor saiu.
     */
    @Test
    @DisplayName("Scenario: Autor da correcao vem do token")
    void autorDaCorrecaoVemDoToken() {
        UUID id = inserirConsulta(pacienteA, medicoA, DATA, "AGENDADA");
        Map<String, Object> input = entrada(id, "ajuste de especialidade");
        input.put("especialidade", "Neurologia");

        assertThat(corrigir(input).temErro()).isFalse();

        assertThat(auditoriaDe(id).path("medicoAutorId").asText())
                .as("o autor e o medico do token, e nao um valor recebido do cliente")
                .isEqualTo(medicoA.toString());
    }

    @Test
    @DisplayName("Scenario: Identificadores nao existem como campos corrigiveis")
    void identificadoresNaoSaoCorrigiveis() {
        UUID id = inserirConsulta(pacienteA, medicoA, DATA, "AGENDADA");
        Map<String, Object> input = entrada(id, "tentativa de trocar o paciente");
        input.put("pacienteId", pacienteB.toString());

        var resposta = corrigir(input);

        assertThat(resposta.primeiroCodigo())
                .as("o campo nao existe no schema: recusa vem antes do resolver")
                .isEqualTo("BAD_REQUEST");
        var registro = snapshots.findById(id).orElseThrow();
        assertThat(registro.getPacienteId()).isEqualTo(pacienteA);
        assertThat(registro.getMedicoId()).isEqualTo(medicoA);
        assertThat(registro.getId()).isEqualTo(id);
    }

    // --- recusas -------------------------------------------------------------------------

    @Test
    @DisplayName("Scenario: Correcao sem alteracao efetiva e recusada")
    void correcaoSemAlteracaoEfetivaERecusada() {
        UUID id = inserirConsulta(pacienteA, medicoA, DATA, "AGENDADA", "observacao inicial");
        Map<String, Object> input = entrada(id, "reenvio sem mudanca");
        input.put("observacoes", "observacao inicial");

        var resposta = corrigir(input);

        assertThat(resposta.primeiroCodigo()).isEqualTo("BAD_REQUEST");
        assertThat(trilha.count())
                .as("auditoria de nao-mudanca seria ruido indistinguivel de correcao real")
                .isZero();
    }

    @Test
    @DisplayName("Scenario: Justificativa ausente ou vazia e recusada")
    void justificativaAusenteOuVaziaERecusada() {
        UUID id = inserirConsulta(pacienteA, medicoA, DATA, "AGENDADA");

        Map<String, Object> emBranco = entrada(id, "   ");
        emBranco.put("pacienteNome", "Nome Novo");
        assertThat(corrigir(emBranco).primeiroCodigo()).isEqualTo("BAD_REQUEST");

        Map<String, Object> semCampo = new HashMap<>();
        semCampo.put("consultaId", id.toString());
        semCampo.put("pacienteNome", "Nome Novo");
        assertThat(corrigir(semCampo).primeiroCodigo())
                .as("justificativa e obrigatoria no schema")
                .isEqualTo("BAD_REQUEST");

        assertThat(snapshots.findById(id).orElseThrow().getPacienteNome())
                .isNotEqualTo("Nome Novo");
    }

    @Test
    @DisplayName("Scenario: Status corrigido precisa pertencer ao dominio valido")
    void statusForaDoDominioERecusado() {
        UUID id = inserirConsulta(pacienteA, medicoA, DATA, "AGENDADA");
        Map<String, Object> input = entrada(id, "status errado");
        input.put("status", "ARQUIVADA");

        assertThat(corrigir(input).primeiroCodigo()).isEqualTo("BAD_REQUEST");
        assertThat(snapshots.findById(id).orElseThrow().getStatus()).isEqualTo("AGENDADA");
    }

    /**
     * A maquina de transicoes do agendamento nao se aplica aqui.
     *
     * <p>Corrigir um registro que ficou com status errado e exatamente o caso de uso. Se a
     * mutation recusasse REALIZADA -> AGENDADA por transicao invalida, ela seria inutil
     * justamente onde precisa existir.
     */
    @Test
    @DisplayName("corrigir status nao aplica a maquina de transicoes")
    void correcaoDeStatusNaoAplicaMaquinaDeTransicoes() {
        UUID id = inserirConsulta(pacienteA, medicoA, DATA, "REALIZADA");
        Map<String, Object> input = entrada(id, "consulta marcada como realizada por engano");
        input.put("status", "AGENDADA");

        assertThat(corrigir(input).temErro()).isFalse();
        assertThat(snapshots.findById(id).orElseThrow().getStatus()).isEqualTo("AGENDADA");
    }

    // --- semantica de ausente e nulo -----------------------------------------------------

    @Test
    @DisplayName("campo ausente nao corrige e nulo em observacoes limpa")
    void ausenteNaoCorrigeENuloLimpaObservacoes() {
        UUID id = inserirConsulta(pacienteA, medicoA, DATA, "AGENDADA", "observacao inicial");
        Map<String, Object> limpar = entrada(id, "observacao registrada na consulta errada");
        limpar.put("observacoes", null);

        assertThat(corrigir(limpar).temErro()).isFalse();

        var registro = snapshots.findById(id).orElseThrow();
        assertThat(registro.getObservacoes())
                .as("nulo explicito em observacoes limpa o registro clinico")
                .isNull();
        assertThat(registro.getEspecialidade())
                .as("campo ausente do documento nao foi tocado")
                .isEqualTo("Cardiologia");
    }

    @Test
    @DisplayName("nulo nos demais campos e recusado")
    void nuloNosDemaisCamposERecusado() {
        UUID id = inserirConsulta(pacienteA, medicoA, DATA, "AGENDADA");
        Map<String, Object> input = entrada(id, "tentativa de apagar a especialidade");
        input.put("especialidade", null);

        assertThat(corrigir(input).primeiroCodigo())
                .as("apagar uma dimensao obrigatoria nao e correcao")
                .isEqualTo("BAD_REQUEST");
        assertThat(snapshots.findById(id).orElseThrow().getEspecialidade())
                .isEqualTo("Cardiologia");
    }

    // --- atomicidade e concorrencia ------------------------------------------------------

    /**
     * Scenario: Falha na auditoria desfaz a correcao inteira.
     *
     * <p>A falha e injetada no proprio PostgreSQL, por trigger, e nao por mock: o que
     * precisa ser provado e que a transacao reverte, e transacao e do banco.
     */
    @Test
    @DisplayName("Scenario: Falha na auditoria desfaz a correcao inteira")
    void falhaNaAuditoriaDesfazACorrecaoInteira() {
        UUID id = inserirConsulta(pacienteA, medicoA, DATA, "AGENDADA", "observacao inicial");
        jdbc.execute("""
                CREATE FUNCTION rejeitar_auditoria_m09() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'falha na auditoria'; END $$
                """);
        jdbc.execute("""
                CREATE TRIGGER rejeitar_auditoria_m09 BEFORE INSERT ON consulta_evento
                FOR EACH ROW EXECUTE FUNCTION rejeitar_auditoria_m09()
                """);
        try {
            Map<String, Object> input = entrada(id, "correcao que nao vai sobreviver");
            input.put("pacienteNome", "Nao Deve Persistir");

            assertThat(corrigir(input).temErro()).isTrue();

            var registro = snapshots.findById(id).orElseThrow();
            assertThat(registro.getPacienteNome())
                    .as("snapshot alterado sem trilha e o estado que a auditoria nao pode ter")
                    .isEqualTo("Paciente " + pacienteA.toString().substring(0, 4));
            assertThat(registro.getAtualizadoEm()).isEqualTo(AGORA);
            assertThat(trilha.count()).isZero();
        } finally {
            jdbc.execute("DROP TRIGGER rejeitar_auditoria_m09 ON consulta_evento");
            jdbc.execute("DROP FUNCTION rejeitar_auditoria_m09()");
        }
    }

    /**
     * Scenario: Correcoes concorrentes nao perdem alteracao.
     *
     * <p>Sem o bloqueio pessimista, as duas transacoes leriam o mesmo estado anterior e a
     * auditoria da segunda registraria um "antes" que ja nao era verdade — perda
     * silenciosa dentro do proprio registro de auditoria.
     */
    @Test
    @DisplayName("Scenario: Correcoes concorrentes nao perdem alteracao")
    void correcoesConcorrentesNaoPerdemAlteracao() throws Exception {
        UUID id = inserirConsulta(pacienteA, medicoA, DATA, "AGENDADA");
        CountDownLatch largada = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            var primeira = executor.submit(() -> correrCorrecao(largada, id, "Nome Um", "primeira"));
            var segunda = executor.submit(() -> correrCorrecao(largada, id, "Nome Dois", "segunda"));
            largada.countDown();

            assertThat(primeira.get(30, TimeUnit.SECONDS)).isTrue();
            assertThat(segunda.get(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            executor.shutdownNow();
        }

        // A ordenacao NAO pode ser por ocorrido_em: o Clock e fixo, as duas auditorias
        // carimbam o mesmo instante e o empate deixaria a ordem indefinida. O que ordena os
        // fatos aqui e a propria cadeia de valores — e provar essa cadeia e provar a
        // serializacao, que e o ponto do @Lock(PESSIMISTIC_WRITE).
        List<Map<String, Object>> auditorias = jdbc.queryForList(
                "SELECT payload->'alteracoes'->'pacienteNome'->>'antes' AS antes, "
                        + "payload->'alteracoes'->'pacienteNome'->>'depois' AS depois "
                        + "FROM consulta_evento WHERE consulta_id = ? AND tipo_evento = 'CORRECAO_MANUAL'",
                id);
        assertThat(auditorias)
                .as("as duas correcoes confirmaram, entao as duas tem de estar na trilha")
                .hasSize(2);

        String original = "Paciente " + pacienteA.toString().substring(0, 4);
        Map<String, Object> primeiraAplicada = auditorias.stream()
                .filter(a -> original.equals(a.get("antes")))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "nenhuma auditoria partiu do valor original: " + auditorias));
        Map<String, Object> segundaAplicada = auditorias.stream()
                .filter(a -> a != primeiraAplicada)
                .findFirst()
                .orElseThrow();

        assertThat(segundaAplicada.get("antes"))
                .as("sem serializacao as duas leriam o original e uma sobrescreveria a outra; "
                        + "a segunda tem de ter enxergado o resultado da primeira")
                .isEqualTo(primeiraAplicada.get("depois"));
        assertThat(snapshots.findById(id).orElseThrow().getPacienteNome())
                .as("e o snapshot final e o depois terminal da cadeia")
                .isEqualTo(segundaAplicada.get("depois"));
    }

    private boolean correrCorrecao(CountDownLatch largada, UUID id, String nome, String motivo) {
        try {
            largada.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        Map<String, Object> input = entrada(id, motivo);
        input.put("pacienteNome", nome);
        return !corrigir(input).temErro();
    }
}
