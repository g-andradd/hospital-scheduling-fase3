package br.com.fiap.hospital.notificacao.integracao;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.fiap.hospital.contracts.ConsultaPayload;
import br.com.fiap.hospital.contracts.TipoEvento;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Os tres instantes que o servico registra vem da fonte de tempo injetada.
 *
 * <p>Com relogio fixo eles sao valores exatos, e nao "algo perto de agora" — o que torna a
 * asercao capaz de falhar. O instante do <b>fato</b> e outra coisa e nao vem daqui: e o
 * {@code occurredAt} do envelope, e a distincao e verificada explicitamente porque confundir
 * os dois faria a ordenacao depender da hora do consumo.
 */
@DisplayName("Fonte de tempo do notificacao-service")
class RelogioNotificacaoIT extends NotificacaoITBase {

    private static final Instant FATO = Instant.parse("2026-09-11T09:00:00Z");

    @Test
    @DisplayName("Scenario: Instantes registrados sao governados pela fonte de tempo")
    void instantesRegistradosVemDoClock() {
        UUID consultaId = UUID.randomUUID();
        var evento = evento(TipoEvento.CONSULTA_CRIADA, consultaId, FATO,
                ConsultaPayload.Status.AGENDADA);

        publicarEAguardar(evento);

        Map<String, Object> linha = agenda(consultaId);
        assertThat(((Timestamp) linha.get("atualizado_em")).toInstant())
                .as("atualizado_em: quando o servico agiu")
                .isEqualTo(AGORA);

        assertThat(auditoria.findAll().getFirst().getEnviadoEm())
                .as("enviado_em: quando o servico enviou")
                .isEqualTo(AGORA);

        assertThat(processados.findById(evento.eventId()).orElseThrow().getProcessadoEm())
                .as("processado_em: quando o servico marcou")
                .isEqualTo(AGORA);
    }

    /**
     * O instante do fato nao e o do relogio.
     *
     * <p>Se {@code ocorrido_em} viesse do {@code Clock}, todos os eventos teriam o mesmo
     * instante e a regra de monotonicidade viraria letra morta — qualquer evento passaria no
     * {@code >=}. Esta asercao e o que impede essa troca de passar despercebida.
     */
    @Test
    @DisplayName("o instante do fato vem do envelope, nao do relogio do servico")
    void instanteDoFatoVemDoEnvelope() {
        UUID consultaId = UUID.randomUUID();

        publicarEAguardar(evento(TipoEvento.CONSULTA_CRIADA, consultaId, FATO,
                ConsultaPayload.Status.AGENDADA));

        Map<String, Object> linha = agenda(consultaId);
        assertThat(((Timestamp) linha.get("ocorrido_em")).toInstant())
                .as("ocorrido_em e o occurredAt do envelope")
                .isEqualTo(FATO)
                .isNotEqualTo(AGORA);
    }
}
