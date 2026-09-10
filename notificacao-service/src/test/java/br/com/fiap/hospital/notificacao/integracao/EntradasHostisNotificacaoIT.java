package br.com.fiap.hospital.notificacao.integracao;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.fiap.hospital.contracts.ConsultaPayload;
import br.com.fiap.hospital.contracts.EventoEnvelope;
import br.com.fiap.hospital.contracts.EventoJson;
import br.com.fiap.hospital.contracts.MensageriaAutoConfiguration;
import br.com.fiap.hospital.contracts.TipoEvento;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Entradas que o servico nao compreende terminam na DLQ, sem deixar rastro.
 *
 * <p>A espera e sempre pela mensagem <b>daquele</b> evento na DLQ, por identidade. Esperar
 * a fila ficar nao-vazia confundiria o resto de outro caso com a mensagem do caso atual, e o
 * teste passaria observando a coisa errada.
 */
@DisplayName("Entradas hostis do notificacao-service")
class EntradasHostisNotificacaoIT extends NotificacaoITBase {

    private static final Instant FATO = Instant.parse("2026-09-11T09:00:00Z");

    @Autowired EventoJson json;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("Scenario: Mensagem malformada nao deixa efeito e chega a DLQ")
    void mensagemMalformadaNaoDeixaEfeitoEChegaADlq() {
        UUID eventId = UUID.randomUUID();
        var mensagem = mensagem("{ isto nao e json", eventId, TipoEvento.CONSULTA_CRIADA);

        publicar(mensagem, TipoEvento.CONSULTA_CRIADA.routingKey());

        var naDlq = aguardarNaDlq(eventId);
        assertThat(naDlq).as("a mensagem precisa alcancar a DLQ normativa").isNotNull();
        assertThat(naDlq.getMessageProperties().getHeaders())
                .as("x-death registra as tentativas e a origem da rejeicao")
                .containsKey("x-death");
        assertThat(new String(naDlq.getBody(), StandardCharsets.UTF_8))
                .as("os bytes originais chegam preservados")
                .contains("isto nao e json");
        exigirNenhumEfeito();
    }

    @Test
    @DisplayName("Scenario: Versao desconhecida e rejeitada")
    void versaoDesconhecidaERejeitada() throws Exception {
        var evento = evento(TipoEvento.CONSULTA_CRIADA, UUID.randomUUID(), FATO,
                ConsultaPayload.Status.AGENDADA);
        var arvore = (ObjectNode) mapper.readTree(json.escrever(evento));
        arvore.put("version", 2);

        publicar(mensagem(mapper.writeValueAsString(arvore), evento.eventId(),
                evento.eventType()), evento.eventType().routingKey());

        assertThat(aguardarNaDlq(evento.eventId()))
                .as("versao futura nao e adaptada por tolerancia: e recusada")
                .isNotNull();
        exigirNenhumEfeito();
    }

    @Test
    @DisplayName("Scenario: Campo obrigatorio ausente nao dispara busca no produtor")
    void campoObrigatorioAusenteERejeitado() throws Exception {
        var evento = evento(TipoEvento.CONSULTA_CRIADA, UUID.randomUUID(), FATO,
                ConsultaPayload.Status.AGENDADA);
        var arvore = mapper.readTree(json.escrever(evento));
        ((ObjectNode) arvore.path("payload")).remove("paciente");

        publicar(mensagem(mapper.writeValueAsString(arvore), evento.eventId(),
                evento.eventType()), evento.eventType().routingKey());

        assertThat(aguardarNaDlq(evento.eventId())).isNotNull();
        exigirNenhumEfeito();
        Mockito.verify(sender, Mockito.never()).enviar(Mockito.any());
    }

    /**
     * Scenario: Falha persistente reverte cada tentativa.
     *
     * <p>Depois da rejeicao, uma mensagem valida e publicada: o consumidor precisa continuar
     * ativo. Sem essa segunda metade, o teste provaria que a mensagem some, e nao que o
     * servico sobrevive a ela — que e o ponto do {@code default-requeue-rejected: false}.
     */
    @Test
    @DisplayName("Scenario: Falha persistente reverte cada tentativa")
    void falhaPersistenteReverteCadaTentativaEConsumidorSegueAtivo() {
        Mockito.doThrow(new IllegalStateException("canal indisponivel"))
                .when(sender).enviar(Mockito.any());
        var falho = evento(TipoEvento.CONSULTA_CRIADA, UUID.randomUUID(), FATO,
                ConsultaPayload.Status.AGENDADA);

        rabbit.convertAndSend(MensageriaAutoConfiguration.EXCHANGE,
                falho.eventType().routingKey(), falho);

        assertThat(aguardarNaDlq(falho.eventId()))
                .as("falha em todas as tentativas termina na DLQ, sem requeue infinito")
                .isNotNull();
        exigirNenhumEfeito();
        // Tres tentativas, conforme o contrato: o sender e alcancado uma vez por tentativa.
        Mockito.verify(sender, Mockito.times(3)).enviar(Mockito.any());

        // Scenario: Mensagem valida posterior continua sendo processada.
        Mockito.reset(sender);
        var valido = evento(TipoEvento.CONSULTA_CRIADA, UUID.randomUUID(), FATO,
                ConsultaPayload.Status.AGENDADA);
        publicarEAguardar(valido);

        assertThat(quantidade("evento_processado"))
                .as("o mesmo consumidor continua ativo depois da rejeicao")
                .isEqualTo(1);
        assertThat(quantidade("notificacao_enviada")).isEqualTo(1);
    }

    // --- apoio --------------------------------------------------------------------------

    private void exigirNenhumEfeito() {
        assertThat(quantidade("agenda_local")).isZero();
        assertThat(quantidade("notificacao_enviada")).isZero();
        assertThat(quantidade("evento_processado")).isZero();
    }

    private void publicar(Message mensagem, String routingKey) {
        rabbit.send(MensageriaAutoConfiguration.EXCHANGE, routingKey, mensagem);
    }

    private Message mensagem(String corpo, UUID eventId, TipoEvento tipo) {
        var propriedades = new MessageProperties();
        propriedades.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        propriedades.setMessageId(eventId.toString());
        propriedades.setHeader("x-event-type", tipo.name());
        propriedades.setHeader("x-event-id", eventId.toString());
        propriedades.setHeader("x-correlation-id", "correlacao-m06");
        return new Message(corpo.getBytes(StandardCharsets.UTF_8), propriedades);
    }
}
