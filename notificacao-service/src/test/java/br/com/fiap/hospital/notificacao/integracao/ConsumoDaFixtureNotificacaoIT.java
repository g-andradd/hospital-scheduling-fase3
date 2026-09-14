package br.com.fiap.hospital.notificacao.integracao;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.fiap.hospital.contracts.MensageriaAutoConfiguration;
import br.com.fiap.hospital.contracts.TipoEvento;
import br.com.fiap.hospital.notificacao.estrutura.FixtureDoContrato;
import br.com.fiap.hospital.notificacao.sender.NotificationSenderPort;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;

/**
 * O consumidor da notificacao processa os bytes exatos do exemplar canonico.
 *
 * <p>Os bytes vem do test-jar do shared-contracts, sem reserializacao: e o que garante que o
 * servico aceita a forma que o contrato publica, e nao a forma que um construtor de teste
 * monta. Os headers normativos sao extraidos do proprio exemplar.
 */
@DisplayName("Consumo do exemplar canonico pela notificacao")
class ConsumoDaFixtureNotificacaoIT extends NotificacaoITBase {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("Scenario: Notificacao processa os bytes do exemplar pelo broker real")
    void notificacaoProcessaOsBytesDoExemplarPeloBrokerReal() throws Exception {
        FixtureDoContrato.exigirOrigemNoTestJar();
        byte[] bytes = FixtureDoContrato.bytes();
        JsonNode exemplar = MAPPER.readTree(bytes);
        JsonNode payload = exemplar.path("payload");
        UUID eventId = UUID.fromString(exemplar.path("eventId").textValue());
        UUID consultaId = UUID.fromString(exemplar.path("aggregateId").textValue());

        publicarExemplar(bytes, exemplar);
        aguardarProcessamentoOuFalharPelaDlq(eventId);

        Map<String, Object> linha = agenda(consultaId);
        assertThat(linha).as("a agenda local da consulta do exemplar").isNotEmpty();
        assertThat(String.valueOf(linha.get("paciente_id")))
                .isEqualTo(payload.path("paciente").path("id").textValue());
        assertThat(linha.get("paciente_nome")).isEqualTo(payload.path("paciente").path("nome").textValue());
        assertThat(linha.get("paciente_email")).isEqualTo(payload.path("paciente").path("email").textValue());
        assertThat(linha.get("medico_nome")).isEqualTo(payload.path("medico").path("nome").textValue());
        assertThat(((Timestamp) linha.get("data_hora")).toInstant())
                .isEqualTo(OffsetDateTime.parse(payload.path("dataHora").textValue()).toInstant());
        assertThat(linha.get("status")).isEqualTo(payload.path("status").textValue());
        assertThat(((Timestamp) linha.get("ocorrido_em")).toInstant())
                .isEqualTo(Instant.parse(exemplar.path("occurredAt").textValue()));

        ArgumentCaptor<NotificationSenderPort.Mensagem> entregue =
                ArgumentCaptor.forClass(NotificationSenderPort.Mensagem.class);
        Mockito.verify(sender, Mockito.times(1)).enviar(entregue.capture());

        List<Map<String, Object>> registros = jdbc.queryForList(
                "SELECT tipo, destinatario, canal, conteudo FROM notificacao_enviada WHERE consulta_id = ?",
                consultaId);
        assertThat(registros).as("uma unica notificacao de agendamento").hasSize(1);
        Map<String, Object> registro = registros.getFirst();
        assertThat(registro.get("tipo")).isEqualTo(exemplar.path("eventType").textValue());
        assertThat(registro.get("destinatario")).isEqualTo(payload.path("paciente").path("email").textValue());
        assertThat(registro.get("canal")).isEqualTo("LOG");
        assertThat(registro.get("conteudo"))
                .as("o conteudo registrado e o mesmo entregue ao canal")
                .isEqualTo(entregue.getValue().corpo());
        assertThat(entregue.getValue().corpo())
                .contains(payload.path("paciente").path("nome").textValue())
                .contains(payload.path("medico").path("nome").textValue())
                .contains("10/09/2026 14:00");

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
     * <p>Sem isso, um exemplar recusado pelo consumidor apareceria como espera sem resultado,
     * e o motivo real — o servico nao aceita o contrato publicado — ficaria escondido.
     */
    private void aguardarProcessamentoOuFalharPelaDlq(UUID eventId) {
        String dlq = MensageriaAutoConfiguration.NOTIFICACAO + ".dlq";
        Awaitility.await().atMost(Duration.ofSeconds(30)).until(() -> {
            if (processados.existsById(eventId)) {
                return true;
            }
            Message rejeitada = rabbit.receive(dlq, 200);
            if (rejeitada != null) {
                throw new AssertionError("o exemplar canonico foi recusado pelo consumidor da "
                        + "notificacao e chegou a DLQ: "
                        + new String(rejeitada.getBody(), StandardCharsets.UTF_8));
            }
            return false;
        });
    }
}
