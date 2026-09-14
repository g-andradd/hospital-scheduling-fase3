package br.com.fiap.hospital.historico.integracao;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Regressoes das recusas que a varredura GraphQL obrigou a criar.
 *
 * <p>Cada uma nasceu de um erro interno reproduzivel: identificador que nao e UUID, instante
 * fora da faixa do armazenamento, texto com NUL, texto maior que a coluna e corpo HTTP que o
 * endpoint nem interpreta. A varredura prova que nenhuma resposta e 5xx nem erro interno;
 * estes testes provam <b>qual</b> e a resposta, que a recusa acontece <b>antes de qualquer
 * efeito</b> — sem SQL do repositorio, snapshot e trilha intactos — e que o que e
 * persistivel continua sendo aceito.
 *
 * <p>A prova de "antes do repositorio" usa o {@link SqlCapturado}, que ja participa do
 * contexto desta base: nenhum espiao novo, e portanto nenhum segundo contexto. A captura e
 * limpa imediatamente antes da requisicao invalida e conferida antes de qualquer consulta
 * auxiliar da propria asercao; o controle valido mostra que o mesmo mecanismo enxerga o SQL
 * quando ele de fato acontece.
 */
@DisplayName("Recusa de entrada extrema no GraphQL")
class RecusaDeEntradaExtremaGraphqlIT extends GraphqlITBase {

    private static final OffsetDateTime DATA = instante("2026-09-18T14:30:00Z");

    private static final String CONSULTA = "query($id: ID!) { consulta(id: $id) { id } }";
    private static final String POR_PACIENTE =
            "query($p: ID!) { consultasDoPaciente(pacienteId: $p) { id } }";
    private static final String POR_MEDICO =
            "query($m: ID!) { consultasDoMedico(medicoId: $m) { id } }";
    private static final String COM_FILTRO =
            "query($f: FiltroConsulta) { minhasConsultas(filtro: $f) { id } }";
    private static final String CORRIGIR =
            "mutation($in: CorrigirRegistroHistoricoInput!) {"
                    + " corrigirRegistroHistorico(input: $in) { id } }";

    private static final List<Object> IDS_INVALIDOS =
            List.of("nao-e-um-uuid", "", 42, List.of("a", "b"));

    // ---------------------------------------------------------------- identificador

    @Test
    @DisplayName("Scenario: Identificador inválido é recusado antes do repositório")
    void identificadorInvalidoNasQueriesNaoAlcancaORepositorio() {
        UUID existente = inserirConsulta(pacienteA, medicoA, DATA, "AGENDADA");

        for (Object id : IDS_INVALIDOS) {
            exigirRecusaSemSql("consulta(id: " + id + ")",
                    () -> executar(tokenMedico(), CONSULTA, Map.of("id", id)));
            exigirRecusaSemSql("consultasDoPaciente(pacienteId: " + id + ")",
                    () -> executar(tokenMedico(), POR_PACIENTE, Map.of("p", id)));
            exigirRecusaSemSql("consultasDoMedico(medicoId: " + id + ")",
                    () -> executar(tokenMedico(), POR_MEDICO, Map.of("m", id)));
        }
        exigirRecusaSemSql("consulta com literal 42 no documento",
                () -> executar(tokenMedico(), "query { consulta(id: 42) { id } }"));

        SqlCapturado.limpar();
        var controle = executar(tokenMedico(), CONSULTA, Map.of("id", existente.toString()));
        assertThat(SqlCapturado.selectsSobre("consulta_historico"))
                .as("o mecanismo enxerga o SQL quando o repositorio e de fato consultado")
                .isNotEmpty();
        assertThat(controle.temErro()).isFalse();
        assertThat(controle.dados("consulta").path("id").asText()).isEqualTo(existente.toString());
    }

    @Test
    @DisplayName("consultaId inválido na correção é recusado antes do repositório, sem efeito")
    void consultaIdInvalidoNaMutacaoNaoAlcancaORepositorio() {
        UUID alvo = inserirConsulta(pacienteA, medicoA, DATA, "AGENDADA", "observacao inicial");
        String antes = snapshot(alvo);

        for (Object id : IDS_INVALIDOS) {
            Map<String, Object> input = new HashMap<>();
            input.put("consultaId", id);
            input.put("justificativa", "correcao com identificador invalido");
            input.put("pacienteNome", "Nome Que Nao Pode Gravar");
            exigirRecusaSemSql("corrigirRegistroHistorico(input.consultaId: " + id + ")",
                    () -> executar(tokenMedico(), CORRIGIR, Map.of("in", input)));

            assertThat(snapshot(alvo)).as("snapshot intacto com consultaId %s", id).isEqualTo(antes);
            assertThat(trilha.count()).as("trilha intacta com consultaId %s", id).isZero();
        }

        exigirRecusaSemSql("corrigirRegistroHistorico com consultaId literal 42", () -> executar(
                tokenMedico(),
                "mutation { corrigirRegistroHistorico(input: {consultaId: 42,"
                        + " justificativa: \"literal\", pacienteNome: \"Nome Literal\"}) { id } }"));
        assertThat(snapshot(alvo)).isEqualTo(antes);
        assertThat(trilha.count()).isZero();

        SqlCapturado.limpar();
        var controle = corrigir(alvo, "pacienteNome", "Nome Corrigido Valido");
        assertThat(SqlCapturado.selectsSobre("consulta_historico"))
                .as("a correcao valida passa pelo repositorio").isNotEmpty();
        assertThat(controle.temErro())
                .as("controle da mutation: %s", controle.corpo().path("errors")).isFalse();
        assertThat(trilha.count()).isEqualTo(1);
    }

    /**
     * Executa a requisicao com a captura limpa e confere, antes de qualquer outra consulta,
     * que nenhum SELECT — nem o de bloqueio da correcao — foi emitido.
     */
    private void exigirRecusaSemSql(String rotulo, java.util.function.Supplier<RespostaGraphql> chamada) {
        SqlCapturado.limpar();
        RespostaGraphql resposta = chamada.get();
        List<String> sqlEmitido = SqlCapturado.selects();

        assertThat(sqlEmitido).as("%s alcancou o repositorio", rotulo).isEmpty();
        assertThat(resposta.primeiroCodigo()).as(rotulo).isEqualTo("BAD_REQUEST");
        assertThat(resposta.corpo().toString())
                .doesNotContain("org.springframework", "java.lang", "Exception");
    }

    // ---------------------------------------------------------------- faixa temporal

    @Test
    @DisplayName("Scenario: Limite temporal fora da faixa persistível é recusado antes da consulta")
    void limiteTemporalForaDaFaixaERecusado() {
        inserirConsulta(pacienteA, medicoA, DATA, "AGENDADA");
        String maximo = "+999999999-12-31T23:59:59.999999999-18:00";
        String minimo = "-999999999-01-01T00:00:00+18:00";

        for (Map<String, Object> filtro : List.<Map<String, Object>>of(
                Map.of("de", maximo), Map.of("de", minimo),
                Map.of("ate", maximo), Map.of("ate", minimo),
                Map.of("de", minimo, "ate", maximo))) {

            var resposta = executar(tokenPaciente(pacienteA), COM_FILTRO, Map.of("f", filtro));

            assertThat(resposta.primeiroCodigo()).as("filtro %s", filtro).isEqualTo("BAD_REQUEST");
            assertThat(resposta.corpo().toString())
                    .doesNotContain("PSQLException", "timestamp out of range", "org.postgresql");
        }

        var controle = executar(tokenPaciente(pacienteA), COM_FILTRO,
                Map.of("f", Map.of("de", "0001-01-01T00:00:00Z", "ate", "9999-12-31T23:59:59Z")));
        assertThat(controle.temErro())
                .as("limite dentro da faixa continua listando: %s", controle.corpo().path("errors"))
                .isFalse();
    }

    @Test
    @DisplayName("Scenario: Data corrigida fora da faixa persistível é recusada antes da escrita")
    void dataCorrigidaForaDaFaixaERecusada() {
        UUID alvo = inserirConsulta(pacienteA, medicoA, DATA, "AGENDADA");
        String antes = snapshot(alvo);

        var resposta = corrigir(alvo, "dataHora", "+999999999-12-31T23:59:59.999999999-18:00");

        assertThat(resposta.primeiroCodigo()).isEqualTo("BAD_REQUEST");
        assertThat(snapshot(alvo)).isEqualTo(antes);
        assertThat(trilha.count()).isZero();

        var controle = corrigir(alvo, "dataHora", "2026-09-19T14:30:00Z");
        assertThat(controle.temErro())
                .as("data dentro da faixa continua corrigivel: %s", controle.corpo().path("errors"))
                .isFalse();
    }

    // ---------------------------------------------------------------- textos da correcao

    private static final String NUL = String.valueOf((char) 0);
    private static final List<String> CAMPOS_DE_TEXTO =
            List.of("justificativa", "pacienteNome", "medicoNome", "especialidade", "observacoes");

    @Test
    @DisplayName("Scenario: Texto da correção com caractere não representável é recusado")
    void textoDaCorrecaoComNulERecusado() {
        UUID alvo = inserirConsulta(pacienteA, medicoA, DATA, "AGENDADA", "observacao inicial");
        String antes = snapshot(alvo);

        for (String campo : CAMPOS_DE_TEXTO) {
            for (String valor : List.of(NUL, NUL + "borda", "no me" + NUL + "io", "fim" + NUL)) {
                var resposta = corrigir(alvo, campo, valor);

                assertThat(resposta.primeiroCodigo()).as("%s com NUL", campo).isEqualTo("BAD_REQUEST");
                assertThat(resposta.primeiraMensagem())
                        .as("a recusa e pelo NUL do proprio campo, e nao por outra regra")
                        .contains(campo);
                assertThat(snapshot(alvo)).as("%s alterou o snapshot", campo).isEqualTo(antes);
                assertThat(trilha.count()).as("%s escreveu na trilha", campo).isZero();
            }
        }
    }

    /**
     * Caracterizacao: controles diferentes de NUL sao persistiveis de ponta a ponta.
     *
     * <p>Os achados da varredura continham NUL nos dois valores ({@code \0\1\37} e
     * {@code an\0tes}), entao nao provavam nada sobre U+0001 ou U+001F isolados. Aqui eles
     * atravessam a correcao real, sao lidos de volta do snapshot e da trilha {@code jsonb}, e
     * so por isso a politica pode recusar exatamente o NUL.
     */
    @Test
    @DisplayName("controles isolados diferentes de NUL atravessam correção, snapshot e auditoria")
    void controlesIsoladosAtravessamCorrecaoSnapshotEAuditoria() {
        for (char controle : new char[] {(char) 0x01, (char) 0x1F}) {
            for (String campo : CAMPOS_DE_TEXTO) {
                UUID alvo = inserirConsulta(pacienteA, medicoA, DATA, "AGENDADA", "observacao inicial");
                String valor = "an" + controle + "tes " + campo;

                var resposta = corrigir(alvo, campo, valor);

                assertThat(resposta.temErro())
                        .as("U+%04X em %s: %s", (int) controle, campo, resposta.corpo().path("errors"))
                        .isFalse();
                exigirPersistido(alvo, campo, valor);
            }
        }

        UUID alvo = inserirConsulta(pacienteA, medicoA, DATA, "AGENDADA", "observacao inicial");
        String legitimo = "Mário Antônio\nSegunda linha\tcom tabulação";
        assertThat(corrigir(alvo, "pacienteNome", legitimo).temErro()).isFalse();
        exigirPersistido(alvo, "pacienteNome", legitimo);
    }

    @Test
    @DisplayName("Scenario: Texto da correção acima do limite da coluna é recusado")
    void textoDaCorrecaoAcimaDoLimiteDaColunaERecusado() {
        String emoji = new String(Character.toChars(0x1F600));

        for (String campo : List.of("pacienteNome", "medicoNome", "especialidade")) {
            UUID alvo = inserirConsulta(pacienteA, medicoA, DATA, "AGENDADA", "observacao inicial");
            String antes = snapshot(alvo);
            long trilhaAntes = trilha.count();

            for (String excesso : List.of("x".repeat(256), emoji.repeat(256))) {
                var resposta = corrigir(alvo, campo, excesso);
                assertThat(resposta.primeiroCodigo())
                        .as("%s com 256 caracteres (%d unidades UTF-16)", campo, excesso.length())
                        .isEqualTo("BAD_REQUEST");
                assertThat(snapshot(alvo)).isEqualTo(antes);
                assertThat(trilha.count()).as("%s escreveu na trilha", campo).isEqualTo(trilhaAntes);
            }

            for (String noLimite : List.of("y".repeat(255), emoji.repeat(255))) {
                UUID aceito = inserirConsulta(pacienteA, medicoA, DATA, "AGENDADA", "observacao inicial");
                var resposta = corrigir(aceito, campo, noLimite);
                assertThat(resposta.temErro())
                        .as("%s com 255 caracteres e %d unidades UTF-16: %s", campo,
                                noLimite.length(), resposta.corpo().path("errors"))
                        .isFalse();
                exigirPersistido(aceito, campo, noLimite);
            }
        }

        UUID livre = inserirConsulta(pacienteA, medicoA, DATA, "AGENDADA", "observacao inicial");
        assertThat(corrigir(livre, "observacoes", "o".repeat(5000)).temErro())
                .as("campo de texto livre nao ganha teto arbitrario").isFalse();
    }

    // ---------------------------------------------------------------- fronteira do corpo

    @Test
    @DisplayName("Scenario: Corpo HTTP inválido é recusado na fronteira")
    void corpoHttpInvalidoERecusadoNaFronteira() {
        UUID alvo = inserirConsulta(pacienteA, medicoA, DATA, "AGENDADA");
        String antes = snapshot(alvo);

        for (String corpo : new String[] {"null", "[]", "42", "\"texto\"", "", "   "}) {
            ResponseEntity<String> resposta = postarCorpoCru(tokenMedico(), corpo);

            assertThat(resposta.getStatusCode().value()).as("corpo [%s]", corpo).isEqualTo(400);
            assertThat(resposta.getBody())
                    .contains("BAD_REQUEST")
                    .doesNotContain("NullPointerException", "org.springframework", "java.lang",
                            "consulta_historico", "stackTrace");
        }

        // JSON malformado segue o caminho normal, e esse caminho tambem precisa recusar em
        // 4xx sem resolver operacao alguma.
        SqlCapturado.limpar();
        ResponseEntity<String> malformado = postarCorpoCru(tokenMedico(),
                "{\"query\": \"query { consulta(id: \\\"" + alvo + "\\\") { id } }\"");
        assertThat(SqlCapturado.selects()).as("o JSON malformado nao executou nada").isEmpty();
        assertThat(malformado.getStatusCode().is4xxClientError())
                .as("malformado respondeu %s: %s", malformado.getStatusCode(), malformado.getBody())
                .isTrue();
        assertThat(malformado.getBody() == null ? "" : malformado.getBody())
                .doesNotContain(alvo.toString());

        assertThat(snapshot(alvo)).as("nenhuma operacao executou").isEqualTo(antes);
        assertThat(trilha.count()).isZero();

        var controle = executar(tokenMedico(), CONSULTA, Map.of("id", alvo.toString()));
        assertThat(controle.temErro()).as("o corpo bem formado passa pela mesma fronteira").isFalse();
    }

    // ---------------------------------------------------------------- apoio

    /**
     * Correcao de um campo. Quando o campo e a justificativa, que nao e corrigivel, um nome
     * valido acompanha a requisicao: sem ele a recusa viria da regra "a correcao precisa
     * alterar algo", e nao do valor sob teste.
     */
    private RespostaGraphql corrigir(UUID alvo, String campo, Object valor) {
        Map<String, Object> input = new HashMap<>();
        input.put("consultaId", alvo.toString());
        input.put("justificativa", "regressao de entrada extrema");
        if ("justificativa".equals(campo)) {
            input.put("pacienteNome", "Nome Base " + alvo.toString().substring(0, 8));
        }
        input.put(campo, valor);
        return executar(tokenMedico(), CORRIGIR, Map.of("in", input));
    }

    /** Le de volta do snapshot e da trilha jsonb o valor que a correcao gravou. */
    private void exigirPersistido(UUID alvo, String campo, String valor) {
        String auditado = "justificativa".equals(campo)
                ? jdbc.queryForObject("SELECT payload->>'justificativa' FROM consulta_evento"
                        + " WHERE consulta_id = ? AND tipo_evento = 'CORRECAO_MANUAL'",
                        String.class, alvo)
                : jdbc.queryForObject("SELECT jsonb_extract_path_text(payload, 'alteracoes', ?,"
                        + " 'depois') FROM consulta_evento"
                        + " WHERE consulta_id = ? AND tipo_evento = 'CORRECAO_MANUAL'",
                        String.class, campo, alvo);
        assertThat(auditado).as("trilha de %s", campo).isEqualTo(valor);

        String coluna = switch (campo) {
            case "pacienteNome" -> "paciente_nome";
            case "medicoNome" -> "medico_nome";
            case "especialidade" -> "especialidade";
            case "observacoes" -> "observacoes";
            default -> null;
        };
        if (coluna != null) {
            String gravado = jdbc.queryForObject(
                    "SELECT " + coluna + " FROM consulta_historico WHERE id = ?", String.class, alvo);
            assertThat(gravado).as("snapshot de %s", campo).isEqualTo(valor);
            assertThat(gravado.codePointCount(0, gravado.length()))
                    .isEqualTo(valor.codePointCount(0, valor.length()));
        }
    }

    private String snapshot(UUID id) {
        List<Map<String, Object>> linhas = jdbc.queryForList(
                "SELECT paciente_nome, medico_nome, especialidade, data_hora, status, observacoes,"
                        + " atualizado_em FROM consulta_historico WHERE id = ?", id);
        return linhas.isEmpty() ? "<ausente>" : linhas.getFirst().toString();
    }

    private ResponseEntity<String> postarCorpoCru(String token, String corpo) {
        HttpHeaders cabecalhos = new HttpHeaders();
        cabecalhos.setContentType(MediaType.APPLICATION_JSON);
        cabecalhos.setBearerAuth(token);
        return rest.exchange("/graphql", HttpMethod.POST,
                new HttpEntity<>(corpo, cabecalhos), String.class);
    }
}
