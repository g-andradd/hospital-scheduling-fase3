package br.com.fiap.hospital.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

class EventoJsonPayloadTest {
    @Test void escreveSomenteOPayloadPreservandoArvoreDoContrato() throws Exception {
        ObjectNode envelope = EntradasAmqp.arvore();
        var evento = new EventoJson().ler(envelope.toString());
        var esperado = new ObjectMapper().readTree(envelope.get("payload").toString());
        assertThat(new ObjectMapper().readTree(new EventoJson().escreverPayload(evento))).isEqualTo(esperado);
    }
}
