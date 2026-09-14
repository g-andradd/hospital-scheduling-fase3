package br.com.fiap.hospital.historico.integracao;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.fiap.hospital.contracts.MensageriaAutoConfiguration;
import br.com.fiap.hospital.contracts.TipoEvento;
import br.com.fiap.hospital.historico.estrutura.FixtureDoContrato;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;

/**
 * O consumidor do historico projeta os bytes exatos do exemplar canonico.
 *
 * <p>Os bytes vem do test-jar do shared-contracts, sem reserializacao, com os headers
 * normativos extraidos do proprio exemplar. A trilha e comparada com o payload do exemplar
 * como {@code jsonb}, no PostgreSQL: igualdade semantica, independente de espacos e ordem.
 */
@DisplayName("Consumo do exemplar canônico pelo histórico")
class ConsumoDaFixtureHistoricoIT extends HistoricoITBase {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("Scenario: Histórico processa os bytes do exemplar pelo broker real")
    void historicoProcessaOsBytesDoExemplarPeloBrokerReal() throws Exception {
        FixtureDoContrato.exigirOrigemNoTestJar();
        byte[] bytes = FixtureDoContrato.bytes();
        JsonNode exemplar = MAPPER.readTree(bytes);
        JsonNode payload = exemplar.path("payload");
        UUID eventId = UUID.fromString(exemplar.path("eventId").textValue());
        UUID consultaId = UUID.fromString(exemplar.path("aggregateId").textValue());
        Instant ocorridoEm = Instant.parse(exemplar.path("occurredAt").textValue());

        publicarExemplar(bytes, exemplar);
        aguardarProcessamentoOuFalharPelaDlq(eventId);

        Map<String, Object> snapshot = jdbc.queryForMap(
                "SELECT * FROM consulta_historico WHERE id = ?", consultaId);
        assertThat(String.valueOf(snapshot.get("paciente_id")))
                .isEqualTo(payload.path("paciente").path("id").textValue());
        assertThat(snapshot.get("paciente_nome")).isEqualTo(payload.path("paciente").path("nome").textValue());
        assertThat(String.valueOf(snapshot.get("medico_id")))
                .isEqualTo(payload.path("medico").path("id").textValue());
        assertThat(snapshot.get("medico_nome")).isEqualTo(payload.path("medico").path("nome").textValue());
        assertThat(snapshot.get("especialidade"))
                .isEqualTo(payload.path("medico").path("especialidade").textValue());
        assertThat(((Timestamp) snapshot.get("data_hora")).toInstant())
                .isEqualTo(OffsetDateTime.parse(payload.path("dataHora").textValue()).toInstant());
        assertThat(snapshot.get("status")).isEqualTo(payload.path("status").textValue());
        assertThat(snapshot.get("observacoes")).isEqualTo(payload.path("observacoes").textValue());
        assertThat(((Timestamp) snapshot.get("atualizado_em")).toInstant())
                .as("atualizado_em e o occurredAt do fato aplicado")
                .isEqualTo(ocorridoEm);

        Map<String, Object> fato = jdbc.queryForMap("""
                SELECT consulta_id, tipo_evento, ocorrido_em, payload = ?::jsonb AS payload_igual
                FROM consulta_evento WHERE id = ?
                """, MAPPER.writeValueAsString(payload), eventId);
        assertThat(String.valueOf(fato.get("consulta_id"))).isEqualTo(consultaId.toString());
        assertThat(fato.get("tipo_evento")).isEqualTo(exemplar.path("eventType").textValue());
        assertThat(((Timestamp) fato.get("ocorrido_em")).toInstant()).isEqualTo(ocorridoEm);
        assertThat(fato.get("payload_igual"))
                .as("a trilha guarda o payload do exemplar, semanticamente igual")
                .isEqualTo(Boolean.TRUE);
        assertThat(quantidade("consulta_evento")).isOne();

        assertThat(processados.existsById(eventId)).as("a marca do evento processado").isTrue();
    }

    private void publicarExemplar(byte[] bytes, JsonNode exemplar) {
        String eventType = exemplar.path("eventType").textValue();
        var propriedades = new MessageProperties();
        propriedades.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        propriedades.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        propriedades.setMessageId(exemplar.path("eventId").textValue());
        propriedades.setHeader("x-event-id", exemplar.path("eventId").textValue());
        propriedades.setHeader("x-event-type", eventType);
        propriedades.setHeader("x-correlation-id", exemplar.path("correlationId").textValue());
        rabbit.send(MensageriaAutoConfiguration.EXCHANGE, TipoEvento.valueOf(eventType).routingKey(),
                new Message(bytes, propriedades));
    }

    /**
     * Espera a marca do evento, mas falha de imediato se o exemplar chegar a DLQ.
     *
     * <p>Sem isso, um exemplar recusado apareceria como espera sem resultado, e o motivo real
     * — o servico nao aceita o contrato publicado — ficaria escondido.
     */
    private void aguardarProcessamentoOuFalharPelaDlq(UUID eventId) {
        String dlq = MensageriaAutoConfiguration.HISTORICO + ".dlq";
        Awaitility.await().atMost(Duration.ofSeconds(30)).until(() -> {
            if (processados.existsById(eventId)) {
                return true;
            }
            Message rejeitada = rabbit.receive(dlq, 200);
            if (rejeitada != null) {
                throw new AssertionError("o exemplar canonico foi recusado pelo consumidor do "
                        + "historico e chegou a DLQ: "
                        + new String(rejeitada.getBody(), StandardCharsets.UTF_8));
            }
            return false;
        });
    }
}
