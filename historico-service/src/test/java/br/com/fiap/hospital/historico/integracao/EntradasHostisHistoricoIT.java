package br.com.fiap.hospital.historico.integracao;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.fiap.hospital.contracts.ConsultaPayload;
import br.com.fiap.hospital.contracts.EventoEnvelope;
import br.com.fiap.hospital.contracts.MensageriaAutoConfiguration;
import br.com.fiap.hospital.contracts.TipoEvento;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

@DisplayName("Entradas hostis do histórico")
class EntradasHostisHistoricoIT extends HistoricoITBase {
    private static final Duration PAUSAS_DE_RETRY = Duration.ofMillis(2_500);

    @Test @DisplayName("Scenario: Mensagem malformada não deixa efeito e chega à DLQ")
    void mensagemMalformadaNaoDeixaEfeitoEChegaADlq() {
        verificarHostil(mensagem("{", UUID.randomUUID(), TipoEvento.CONSULTA_CRIADA, "hostil"), TipoEvento.CONSULTA_CRIADA.routingKey());
    }

    @Test @DisplayName("Scenario: Campo obrigatório ausente é rejeitado pelo consumidor real")
    void campoObrigatorioAusenteERejeitadoPeloConsumidorReal() throws Exception {
        var evento = evento(TipoEvento.CONSULTA_CRIADA, UUID.randomUUID(), Instant.parse("2026-09-10T13:00:00Z"), ConsultaPayload.Status.AGENDADA, "ausente");
        var arvore = new ObjectMapper().readTree(json.escrever(evento));
        ((com.fasterxml.jackson.databind.node.ObjectNode) arvore.path("payload")).remove("paciente");
        verificarHostil(mensagem(new ObjectMapper().writeValueAsString(arvore), evento), evento.eventType().routingKey());
    }

    @Test @DisplayName("Scenario: Header incompatível é rejeitado pelo consumidor real")
    void headerIncompativelERejeitadoPeloConsumidorReal() {
        var evento = evento(TipoEvento.CONSULTA_CRIADA, UUID.randomUUID(), Instant.parse("2026-09-10T13:00:00Z"), ConsultaPayload.Status.AGENDADA, "header");
        var mensagem = mensagem(json.escrever(evento), evento);
        mensagem.getMessageProperties().setHeader("x-event-type", TipoEvento.CONSULTA_CANCELADA.name());
        verificarHostil(mensagem, evento.eventType().routingKey());
    }

    @Test @DisplayName("Scenario: Routing key incompatível é rejeitada pelo consumidor real")
    void routingKeyIncompativelERejeitadaPeloConsumidorReal() {
        var evento = evento(TipoEvento.CONSULTA_CRIADA, UUID.randomUUID(), Instant.parse("2026-09-10T13:00:00Z"), ConsultaPayload.Status.AGENDADA, "routing");
        verificarHostil(mensagem(json.escrever(evento), evento), TipoEvento.CONSULTA_ATUALIZADA.routingKey());
    }

    @Test @DisplayName("Scenario: Content type incompatível é rejeitado pelo consumidor real")
    void contentTypeIncompativelERejeitadoPeloConsumidorReal() {
        var evento = evento(TipoEvento.CONSULTA_CRIADA, UUID.randomUUID(), Instant.parse("2026-09-10T13:00:00Z"), ConsultaPayload.Status.AGENDADA, "content-type");
        var mensagem = mensagem(json.escrever(evento), evento);
        mensagem.getMessageProperties().setContentType("text/plain");
        verificarHostil(mensagem, evento.eventType().routingKey());
    }

    @Test @DisplayName("Scenario: Versão desconhecida é rejeitada pelo consumidor real")
    void versaoDesconhecidaERejeitadaPeloConsumidorReal() {
        var evento = evento(TipoEvento.CONSULTA_CRIADA, UUID.randomUUID(), Instant.parse("2026-09-10T13:00:00Z"), ConsultaPayload.Status.AGENDADA, "versão");
        verificarHostil(mensagem(json.escrever(evento).replace("\"version\":1", "\"version\":2"), evento), evento.eventType().routingKey());
    }

    @Test @DisplayName("Scenario: Falha persistente da projeção reverte cada tentativa")
    void falhaPersistenteReverteCadaTentativa() {
        var evento = evento(TipoEvento.CONSULTA_CRIADA, UUID.randomUUID(), Instant.parse("2026-09-10T13:00:00Z"), ConsultaPayload.Status.AGENDADA, "falha");
        jdbc.execute("CREATE SEQUENCE tentativas_m08");
        jdbc.execute("""
                CREATE FUNCTION rejeitar_projecao_m08() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN PERFORM nextval('tentativas_m08'); RAISE EXCEPTION 'falha de projeção'; END $$
                """);
        jdbc.execute("""
                CREATE TRIGGER rejeitar_projecao_m08 BEFORE INSERT ON consulta_evento
                FOR EACH ROW EXECUTE FUNCTION rejeitar_projecao_m08()
                """);
        try {
            verificarHostil(mensagem(json.escrever(evento), evento), evento.eventType().routingKey());
            assertThat(jdbc.queryForObject("SELECT last_value FROM tentativas_m08", Long.class)).isEqualTo(3L);
        } finally {
            jdbc.execute("DROP TRIGGER rejeitar_projecao_m08 ON consulta_evento");
            jdbc.execute("DROP FUNCTION rejeitar_projecao_m08()");
            jdbc.execute("DROP SEQUENCE tentativas_m08");
        }
    }

    @Test @DisplayName("Scenario: Mensagem válida posterior continua sendo processada")
    void mensagemValidaPosteriorContinuaSendoProcessada() {
        verificarHostil(mensagem("{", UUID.randomUUID(), TipoEvento.CONSULTA_CRIADA, "hostil"), TipoEvento.CONSULTA_CRIADA.routingKey());
        publicar(evento(TipoEvento.CONSULTA_CRIADA, UUID.randomUUID(), Instant.parse("2026-09-10T13:00:00Z"), ConsultaPayload.Status.AGENDADA, "válida"));
        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(quantidade("consulta_evento")).isOne());
        assertThat(quantidade("consulta_historico")).isOne();
        assertThat(quantidade("evento_processado")).isOne();
    }

    private void publicar(EventoEnvelope<ConsultaPayload> evento) {
        rabbit.convertAndSend(MensageriaAutoConfiguration.EXCHANGE, evento.eventType().routingKey(), evento);
    }

    private Message mensagem(String corpo, EventoEnvelope<ConsultaPayload> evento) {
        return mensagem(corpo, evento.eventId(), evento.eventType(), evento.correlationId());
    }

    private Message mensagem(String corpo, UUID eventId, TipoEvento tipo, String correlacao) {
        var propriedades = new MessageProperties();
        propriedades.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        propriedades.setHeader("x-event-id", eventId.toString());
        propriedades.setHeader("x-event-type", tipo.name());
        propriedades.setHeader("x-correlation-id", correlacao);
        return new Message(corpo.getBytes(StandardCharsets.UTF_8), propriedades);
    }

    private void verificarHostil(Message original, String routingKey) {
        long inicio = System.nanoTime();
        rabbit.send(MensageriaAutoConfiguration.EXCHANGE, routingKey, original);
        var dlq = receberDaDlq();
        assertThat(Duration.ofNanos(System.nanoTime() - inicio)).isGreaterThanOrEqualTo(PAUSAS_DE_RETRY);
        assertThat(tentativasDoConverter.get()).isEqualTo(3);
        assertThat(dlq.getBody()).isEqualTo(original.getBody());
        assertThat(dlq.getMessageProperties().getXDeathHeader()).isNotEmpty();
        assertThat(quantidade("consulta_historico")).isZero();
        assertThat(quantidade("consulta_evento")).isZero();
        assertThat(quantidade("evento_processado")).isZero();
        assertThat(rabbit.receive(MensageriaAutoConfiguration.HISTORICO, 500)).isNull();
    }

    private Message receberDaDlq() {
        return Awaitility.await().atMost(Duration.ofSeconds(20)).until(
                () -> rabbit.receive(MensageriaAutoConfiguration.HISTORICO + ".dlq", 1_000),
                java.util.Objects::nonNull);
    }
}
