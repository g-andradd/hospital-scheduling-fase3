package br.com.fiap.hospital.agendamento.contrato;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * O comparador aceita o envelope compativel e acusa cada divergencia, uma de cada vez.
 *
 * <p>Sem esta suite, um comparador permissivo — que ignorasse um campo a mais ou aceitasse
 * qualquer texto num campo gerado — faria a compatibilidade do produtor passar sem provar nada.
 */
@DisplayName("Comparacao com o exemplar canonico")
class ComparacaoComFixtureTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final UUID CONSULTA = UUID.fromString("7a1b2c3d-0000-4000-8000-00000000000a");
    private static final String EVENTO = "5b0c1d2e-0000-4000-8000-00000000000b";
    private static final String CORRELACAO = "correlacao-gerada-na-gravacao";
    private static final Instant FATO = Instant.parse("2026-09-04T12:00:00Z");

    private static final ComparacaoComFixture.Contexto CONTEXTO =
            new ComparacaoComFixture.Contexto(CONSULTA, FATO, EVENTO, CORRELACAO);

    @Test
    @DisplayName("um envelope compativel, com os campos gerados coerentes, nao tem divergencias")
    void envelopeCompativelNaoTemDivergencias() throws Exception {
        assertThat(ComparacaoComFixture.divergencias(exemplar(), produzidoCompativel(), CONTEXTO))
                .isEmpty();
    }

    static Stream<Arguments> divergencias() {
        return Stream.of(
                caso("chave a mais na raiz", p -> p.put("extra", 1),
                        "campo a mais no produzido: extra"),
                caso("chave a menos no payload", p -> payload(p).remove("observacoes"),
                        "campo ausente no produzido: payload.observacoes"),
                caso("chave a menos aninhada", p -> ((ObjectNode) payload(p).get("medico")).remove("crm"),
                        "campo ausente no produzido: payload.medico.crm"),
                caso("tipo divergente", p -> payload(p).put("duracaoMinutos", "30"),
                        "tipo divergente em payload.duracaoMinutos"),
                caso("valor divergente", p -> ((ObjectNode) payload(p).get("medico")).put("nome", "Outro"),
                        "valor divergente em payload.medico.nome"),
                caso("texto de dataHora divergente para o mesmo instante",
                        p -> payload(p).put("dataHora", "2026-09-10T17:00:00Z"),
                        "valor divergente em payload.dataHora"),
                caso("aggregateId diferente de consultaId",
                        p -> p.put("aggregateId", "00000000-0000-4000-8000-0000000000ff"),
                        "aggregateId diverge de payload.consultaId"),
                caso("consulta do payload diferente da consulta criada", p -> {
                    p.put("aggregateId", "00000000-0000-4000-8000-0000000000ee");
                    payload(p).put("consultaId", "00000000-0000-4000-8000-0000000000ee");
                }, "payload.consultaId diverge da consulta criada"),
                caso("occurredAt sem Z", p -> p.put("occurredAt", "2026-09-04T09:00:00-03:00"),
                        "occurredAt nao esta em UTC com Z"),
                caso("occurredAt diferente do instante do fato",
                        p -> p.put("occurredAt", "2026-09-04T12:00:01Z"),
                        "occurredAt diverge do instante do fato"),
                caso("UUID nao canonico", p -> p.put("eventId", EVENTO.toUpperCase()),
                        "eventId nao e UUID canonico"),
                caso("campo gerado com tipo nao textual", p -> p.put("eventId", 42),
                        "tipo divergente em eventId"),
                caso("correlacao vazia", p -> p.put("correlationId", ""),
                        "correlationId vazio"),
                caso("alteracoes presente", p -> payload(p).putObject("alteracoes"),
                        "campo a mais no produzido: payload.alteracoes"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("divergencias")
    @DisplayName("Scenario: Campos gerados sao validados, e nao ignorados")
    void cadaDivergenciaEAcusada(Consumer<ObjectNode> mutacao, String esperado) throws Exception {
        ObjectNode produzido = produzidoCompativel();
        mutacao.accept(produzido);

        assertThat(ComparacaoComFixture.divergencias(exemplar(), produzido, CONTEXTO))
                .as("a mutacao precisa ser acusada pelo comparador")
                .anySatisfy(divergencia -> assertThat(divergencia).contains(esperado));
    }

    @Test
    @DisplayName("header divergente do envelope e acusado")
    void headerDivergenteEAcusado() throws Exception {
        var contexto = new ComparacaoComFixture.Contexto(
                CONSULTA, FATO, "00000000-0000-4000-8000-0000000000dd", "outra-correlacao");

        assertThat(ComparacaoComFixture.divergencias(exemplar(), produzidoCompativel(), contexto))
                .anySatisfy(d -> assertThat(d).contains("eventId diverge do header x-event-id"))
                .anySatisfy(d -> assertThat(d).contains("correlationId diverge do header x-correlation-id"));
    }

    private static Arguments caso(String nome, Consumer<ObjectNode> mutacao, String esperado) {
        return Arguments.of(Named.of(nome, mutacao), esperado);
    }

    private static ObjectNode payload(ObjectNode envelope) {
        return (ObjectNode) envelope.get("payload");
    }

    private static JsonNode exemplar() throws Exception {
        return MAPPER.readTree(FixtureDoContrato.texto());
    }

    /** O exemplar com os campos gerados preenchidos como o produtor os preencheria. */
    private static ObjectNode produzidoCompativel() throws Exception {
        ObjectNode produzido = (ObjectNode) exemplar();
        produzido.put("eventId", EVENTO);
        produzido.put("aggregateId", CONSULTA.toString());
        produzido.put("occurredAt", FATO.toString());
        produzido.put("correlationId", CORRELACAO);
        payload(produzido).put("consultaId", CONSULTA.toString());
        return produzido;
    }
}
