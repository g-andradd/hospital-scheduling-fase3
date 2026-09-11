package br.com.fiap.hospital.contracts;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import com.fasterxml.jackson.databind.node.ObjectNode;

class ContratoDeEventosTest {
    @ParameterizedTest @EnumSource(TipoEvento.class)
    void cincoTipos(TipoEvento tipo) {
        var n=EntradasAmqp.arvore(); n.put("eventType",tipo.name());
        var p=(ObjectNode)n.get("payload");
        switch(tipo) {
            case CONSULTA_ATUALIZADA -> p.putObject("alteracoes");
            case CONSULTA_CONFIRMADA -> p.put("status","CONFIRMADA");
            case CONSULTA_CANCELADA -> {p.put("status","CANCELADA");p.put("motivoCancelamento","Solicitado");}
            case CONSULTA_REALIZADA -> p.put("status","REALIZADA");
            default -> {}
        }
        var codec=new EventoJson();
        var evento=codec.ler(n.toString());
        assertThat(codec.ler(codec.escrever(evento))).isEqualTo(evento);
        assertThat(tipo.routingKey()).isEqualTo("consulta."+tipo.name().substring("CONSULTA_".length()).toLowerCase(java.util.Locale.ROOT));
    }
}
