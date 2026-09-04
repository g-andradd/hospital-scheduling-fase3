package br.com.fiap.hospital.contracts;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

class EventoEnvelopeConverterTest {
    private final EventoJson json=new EventoJson();
    private final EventoEnvelopeConverter converter=new EventoEnvelopeConverter(json);
    static List<EntradasAmqp.Caso> invalidas() { return EntradasAmqp.invalidas(); }

    @ParameterizedTest @MethodSource("invalidas")
    void recusaAntesDoEfeito(EntradasAmqp.Caso caso) {
        assertThatThrownBy(() -> converter.fromMessage(EntradasAmqp.mensagem(caso.corpo())))
                .as(caso.toString()).isInstanceOf(MensagemInvalidaException.class);
    }
    @Test void preservaInstanteOffsetENulos() {
        var evento=converter.fromMessage(EntradasAmqp.mensagem(EntradasAmqp.fixture()));
        assertThat(evento.payload().dataHora().getOffset().toString()).isEqualTo("-03:00");
        var message=converter.toMessage(evento,new org.springframework.amqp.core.MessageProperties());
        assertThat(converter.fromMessage(message)).isEqualTo(evento);
        assertThat(json.escrever(evento)).doesNotContain("senha","hash","cpf","alteracoes");
    }
    @ParameterizedTest @ValueSource(strings={"x-event-id","x-event-type","x-correlation-id"})
    void metadadoAusenteEDivergente(String header) {
        var message=EntradasAmqp.mensagem(EntradasAmqp.fixture());
        message.getMessageProperties().getHeaders().remove(header);
        assertThatThrownBy(() -> converter.fromMessage(message)).isInstanceOf(MensagemInvalidaException.class);
        message.getMessageProperties().setHeader(header,"outro");
        assertThatThrownBy(() -> converter.fromMessage(message)).isInstanceOf(MensagemInvalidaException.class);
    }
    @ParameterizedTest @ValueSource(strings={"__TypeId__","__ContentTypeId__","__KeyTypeId__"})
    void naoInstanciaTipoExterno(String header) {
        var message=EntradasAmqp.mensagem(EntradasAmqp.fixture());
        message.getMessageProperties().setHeader(header,"java.lang.Runtime");
        assertThatThrownBy(() -> converter.fromMessage(message)).isInstanceOf(MensagemInvalidaException.class);
    }
    @Test void rejeitaContentTypeERoutingKey() {
        var message=EntradasAmqp.mensagem(EntradasAmqp.fixture());
        message.getMessageProperties().setContentType("text/plain");
        assertThatThrownBy(() -> converter.fromMessage(message)).isInstanceOf(MensagemInvalidaException.class);
        message.getMessageProperties().setContentType("application/json");
        message.getMessageProperties().setReceivedRoutingKey("consulta.cancelada");
        assertThatThrownBy(() -> converter.fromMessage(message)).isInstanceOf(MensagemInvalidaException.class);
    }
    @Test void alteracoesMantemNuloAnteriorESaoImutaveis() {
        var n=EntradasAmqp.arvore(); n.put("eventType","CONSULTA_ATUALIZADA");
        var p=(com.fasterxml.jackson.databind.node.ObjectNode)n.get("payload");
        var a=p.putObject("alteracoes");
        a.putNull("observacoesAnterior"); a.put("dataHoraAnterior","2026-09-09T10:00:00-03:00");
        a.put("duracaoMinutosAnterior",45); a.put("medicoIdAnterior",UUID.randomUUID().toString());
        var e=json.ler(n.toString());
        assertThat(e.payload().alteracoes()).containsEntry("observacoesAnterior",null);
        assertThatThrownBy(() -> e.payload().alteracoes().put("x",1)).isInstanceOf(UnsupportedOperationException.class);
        assertThat(json.ler(json.escrever(e)).payload().alteracoes()).isEqualTo(e.payload().alteracoes());
        a.put("intruso","x");
        assertThatThrownBy(() -> json.ler(n.toString())).isInstanceOf(MensagemInvalidaException.class);
    }
}
