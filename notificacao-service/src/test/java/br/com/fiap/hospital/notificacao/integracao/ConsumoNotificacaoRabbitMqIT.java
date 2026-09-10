package br.com.fiap.hospital.notificacao.integracao;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.fiap.hospital.contracts.ConsultaPayload;
import br.com.fiap.hospital.contracts.TipoEvento;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Os cinco tipos atravessando o RabbitMQ real, pela topologia normativa.
 *
 * <p>Publicacao no exchange com a routing key de cada tipo, converter e listener de
 * producao. E o que prova que a fila normativa esta ligada ao consumidor — as demais suites
 * assumem essa ligacao para verificar comportamento.
 */
@DisplayName("Consumo pela topologia real")
class ConsumoNotificacaoRabbitMqIT extends NotificacaoITBase {

    private static final Instant FATO = Instant.parse("2026-09-11T09:00:00Z");

    private static final Map<TipoEvento, ConsultaPayload.Status> STATUS_DE =
            new EnumMap<>(Map.of(
                    TipoEvento.CONSULTA_CRIADA, ConsultaPayload.Status.AGENDADA,
                    TipoEvento.CONSULTA_ATUALIZADA, ConsultaPayload.Status.AGENDADA,
                    TipoEvento.CONSULTA_CONFIRMADA, ConsultaPayload.Status.CONFIRMADA,
                    TipoEvento.CONSULTA_CANCELADA, ConsultaPayload.Status.CANCELADA,
                    TipoEvento.CONSULTA_REALIZADA, ConsultaPayload.Status.REALIZADA));

    @Test
    @DisplayName("os cinco tipos publicados no broker real chegam ao consumidor")
    void cincoTiposChegamAoConsumidor() {
        for (TipoEvento tipo : TipoEvento.values()) {
            UUID consultaId = UUID.randomUUID();

            publicarEAguardar(evento(tipo, consultaId, FATO, STATUS_DE.get(tipo)));

            assertThat(agenda(consultaId))
                    .as("%s publicado com routing key %s precisa chegar ao consumidor",
                            tipo, tipo.routingKey())
                    .isNotEmpty();
        }

        assertThat(quantidade("evento_processado"))
                .as("cinco eventos, cinco marcas")
                .isEqualTo(5);
        assertThat(quantidade("agenda_local")).isEqualTo(5);
        assertThat(quantidade("notificacao_enviada"))
                .as("criada, atualizada e cancelada notificam; confirmada e realizada nao")
                .isEqualTo(3);
    }
}
