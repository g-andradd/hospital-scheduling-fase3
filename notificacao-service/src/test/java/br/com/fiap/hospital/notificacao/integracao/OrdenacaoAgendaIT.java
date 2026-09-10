package br.com.fiap.hospital.notificacao.integracao;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.fiap.hospital.contracts.ConsultaPayload;
import br.com.fiap.hospital.contracts.EventoEnvelope;
import br.com.fiap.hospital.contracts.TipoEvento;
import br.com.fiap.hospital.notificacao.sender.NotificationSenderPort;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * A agenda nao regride, e a regra vive no comando enviado ao banco.
 *
 * <p>Uma agenda que retrocede e pior do que uma agenda ausente: ela faz o lembrete do M07
 * avisar o paciente sobre uma consulta que ja foi cancelada. Por isso o caso hostil —
 * cancelamento seguido de atualizacao antiga — e cenario de primeira classe aqui.
 */
@DisplayName("Ordenacao da agenda local")
class OrdenacaoAgendaIT extends NotificacaoITBase {

    private static final Instant CEDO = Instant.parse("2026-09-11T08:00:00Z");
    private static final Instant TARDE = Instant.parse("2026-09-11T10:00:00Z");
    private static final OffsetDateTime HORARIO_ORIGINAL =
            OffsetDateTime.ofInstant(Instant.parse("2026-09-20T14:30:00Z"), ZoneOffset.UTC);

    @Test
    @DisplayName("Scenario: Evento antigo e processado sem regredir a agenda")
    void eventoAntigoNaoRegrideAAgenda() {
        UUID consultaId = UUID.randomUUID();
        OffsetDateTime horarioNovo = HORARIO_ORIGINAL.plusDays(3);

        publicarEAguardar(evento(TipoEvento.CONSULTA_ATUALIZADA, consultaId, TARDE,
                ConsultaPayload.Status.AGENDADA, "Dra. Marta Reis", horarioNovo));
        publicarEAguardar(evento(TipoEvento.CONSULTA_CRIADA, consultaId, CEDO,
                ConsultaPayload.Status.AGENDADA, "Dr. Joao Lima", HORARIO_ORIGINAL));

        Map<String, Object> linha = agenda(consultaId);
        assertThat(linha.get("medico_nome"))
                .as("o fato antigo nao pode sobrescrever o recente")
                .isEqualTo("Dra. Marta Reis");
        assertThat(((java.sql.Timestamp) linha.get("data_hora")).toInstant())
                .isEqualTo(horarioNovo.toInstant());
        assertThat(((java.sql.Timestamp) linha.get("ocorrido_em")).toInstant()).isEqualTo(TARDE);
        assertThat(quantidade("evento_processado"))
                .as("o evento antigo tambem e processado: sem marca, a reentrega repetiria para sempre")
                .isEqualTo(2);
    }

    /**
     * Scenario: Atualizacao antiga nao ressuscita consulta cancelada.
     *
     * <p>O caso que mais importa dos tres. Sem a regra, um evento de remarcacao atrasado
     * devolveria a consulta ao estado agendado e o paciente receberia um aviso de
     * alteracao depois de ja ter sido avisado do cancelamento.
     */
    @Test
    @DisplayName("Scenario: Atualizacao antiga nao ressuscita consulta cancelada")
    void atualizacaoAntigaNaoRessuscitaConsultaCancelada() {
        UUID consultaId = UUID.randomUUID();

        publicarEAguardar(evento(TipoEvento.CONSULTA_CANCELADA, consultaId, TARDE,
                ConsultaPayload.Status.CANCELADA));
        Mockito.clearInvocations(sender);

        publicarEAguardar(evento(TipoEvento.CONSULTA_ATUALIZADA, consultaId, CEDO,
                ConsultaPayload.Status.AGENDADA, "Dra. Marta Reis", HORARIO_ORIGINAL));

        assertThat(agenda(consultaId).get("status"))
                .as("a consulta continua cancelada")
                .isEqualTo("CANCELADA");
        Mockito.verify(sender, Mockito.never()).enviar(Mockito.any());
        assertThat(quantidade("notificacao_enviada"))
                .as("o cancelamento gerou uma notificacao; a atualizacao antiga nao pode gerar outra")
                .isEqualTo(1);
        assertThat(quantidade("evento_processado")).isEqualTo(2);
    }

    @Test
    @DisplayName("Scenario: Fatos no mesmo instante seguem a ordem de chegada")
    void fatosNoMesmoInstanteSeguemAOrdemDeChegada() {
        UUID consultaId = UUID.randomUUID();

        publicarEAguardar(evento(TipoEvento.CONSULTA_CRIADA, consultaId, TARDE,
                ConsultaPayload.Status.AGENDADA, "Dr. Joao Lima", HORARIO_ORIGINAL));
        publicarEAguardar(evento(TipoEvento.CONSULTA_ATUALIZADA, consultaId, TARDE,
                ConsultaPayload.Status.AGENDADA, "Dra. Marta Reis", HORARIO_ORIGINAL));

        assertThat(agenda(consultaId).get("medico_nome"))
                .as("com occurredAt igual, o segundo recebido vence — o contrato nao declara sequencia")
                .isEqualTo("Dra. Marta Reis");
        assertThat(quantidade("evento_processado")).isEqualTo(2);
    }

    /**
     * A condicao precisa estar no comando que a aplicacao emite.
     *
     * <p>Nao adianta o teste escrever seu proprio {@code ON CONFLICT ... WHERE}: isso
     * provaria que o PostgreSQL honra a clausula, nao que o servico a usa. E as asercoes de
     * estado acima passariam igualmente com uma pre-consulta seguida de gravacao — que
     * reabriria o TOCTOU entre duas transacoes concorrentes.
     */
    @Test
    @DisplayName("a condicao de monotonicidade vive no comando emitido, num unico statement")
    void condicaoDeMonotonicidadeEstaNoComandoEmitido() {
        UUID consultaId = UUID.randomUUID();
        Mockito.clearInvocations(jdbc);

        publicarEAguardar(evento(TipoEvento.CONSULTA_CRIADA, consultaId, TARDE,
                ConsultaPayload.Status.AGENDADA));

        // As invocacoes reais, e nao matchers: update(String, Object...) e varargs, e o
        // matcher de vararg casa por posicao em vez de casar a chamada inteira.
        List<String> sqlDaAgenda = Mockito.mockingDetails(jdbc).getInvocations().stream()
                .filter(i -> i.getArguments().length > 0)
                .filter(i -> i.getArguments()[0] instanceof String)
                .filter(i -> ((String) i.getArguments()[0]).toLowerCase().contains("agenda_local"))
                .map(i -> i.getMethod().getName() + " :: "
                        + ((String) i.getArguments()[0]).replaceAll("\\s+", " ").trim())
                .toList();

        // Distintos, e nao contagem de invocacoes: JdbcTemplate.update(String, Object...)
        // delega para update(String, PreparedStatementSetter), e o spy registra as duas
        // chamadas do mesmo comando logico. Contar invocacoes mediria a implementacao do
        // Spring; o que interessa e quantos comandos SQL diferentes a agenda recebeu.
        List<String> escritas = sqlDaAgenda.stream()
                .filter(s -> s.startsWith("update :: "))
                .map(s -> s.substring("update :: ".length()))
                .distinct()
                .toList();
        List<String> leituras = sqlDaAgenda.stream().filter(s -> s.startsWith("query")).toList();

        assertThat(escritas)
                .as("a agenda tem de ser escrita por um comando so")
                .hasSize(1);
        assertThat(escritas.getFirst().toLowerCase())
                .contains("insert into agenda_local")
                .contains("on conflict (consulta_id) do update")
                .contains("where excluded.ocorrido_em >= agenda_local.ocorrido_em");
        assertThat(leituras)
                .as("consultar antes e decidir depois reabriria o TOCTOU que a condicao fecha")
                .isEmpty();

    }
}
