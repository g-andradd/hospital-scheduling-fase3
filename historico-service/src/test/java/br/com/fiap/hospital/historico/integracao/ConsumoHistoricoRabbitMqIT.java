package br.com.fiap.hospital.historico.integracao;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.fiap.hospital.contracts.ConsultaPayload;
import br.com.fiap.hospital.contracts.MensageriaAutoConfiguration;
import br.com.fiap.hospital.contracts.TipoEvento;
import java.time.Instant;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Consumo RabbitMQ do histórico")
class ConsumoHistoricoRabbitMqIT extends HistoricoITBase {
    @Test @DisplayName("os cinco tipos normativos publicados no broker são projetados")
    void cincoTiposNormativosPublicadosNoBrokerSaoProjetados() {
        Instant agora = Instant.parse("2026-09-10T13:00:00Z");
        for (TipoEvento tipo : TipoEvento.values()) {
            var status = switch (tipo) {
                case CONSULTA_CRIADA, CONSULTA_ATUALIZADA -> ConsultaPayload.Status.AGENDADA;
                case CONSULTA_CONFIRMADA -> ConsultaPayload.Status.CONFIRMADA;
                case CONSULTA_CANCELADA -> ConsultaPayload.Status.CANCELADA;
                case CONSULTA_REALIZADA -> ConsultaPayload.Status.REALIZADA;
            };
            var evento = evento(tipo, UUID.randomUUID(), agora, status, tipo.name());
            rabbit.convertAndSend(MensageriaAutoConfiguration.EXCHANGE, tipo.routingKey(), evento);
        }
        Awaitility.await().untilAsserted(() -> assertThat(quantidade("consulta_evento")).isEqualTo(5));
        assertThat(quantidade("evento_processado")).isEqualTo(5);
    }
}
