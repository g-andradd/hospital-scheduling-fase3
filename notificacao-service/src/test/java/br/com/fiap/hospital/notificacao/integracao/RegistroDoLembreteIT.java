package br.com.fiap.hospital.notificacao.integracao;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.fiap.hospital.contracts.MensageriaAutoConfiguration;
import br.com.fiap.hospital.contracts.TipoEvento;
import br.com.fiap.hospital.notificacao.sender.NotificationSenderPort.Mensagem;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/**
 * O que o lembrete entrega ao canal e o que fica registrado.
 *
 * <p>O registro e comparado com o argumento que o sender recebeu, e nao com um texto escrito
 * aqui: auditoria que guardasse outra coisa que o entregue nao serviria para auditar.
 */
@DisplayName("Registro do lembrete D-1")
class RegistroDoLembreteIT extends NotificacaoITBase {

    private static final List<String> FILAS_NORMATIVAS = List.of(
            MensageriaAutoConfiguration.NOTIFICACAO, MensageriaAutoConfiguration.NOTIFICACAO + ".dlq",
            MensageriaAutoConfiguration.HISTORICO, MensageriaAutoConfiguration.HISTORICO + ".dlq");

    @Test
    @DisplayName("Scenario: Lembrete confirmado fica registrado como foi entregue")
    void lembreteConfirmadoFicaRegistradoComoFoiEntregue() {
        UUID consulta = agendar(AGORA.plus(Duration.ofHours(3)), "CONFIRMADA", "joana@hospital.com");

        lembretes.executar();

        ArgumentCaptor<Mensagem> entregue = ArgumentCaptor.forClass(Mensagem.class);
        Mockito.verify(sender).enviar(entregue.capture());
        assertThat(lembretesRegistrados()).isEqualTo(1);

        Map<String, Object> registro = jdbc.queryForMap(
                "SELECT * FROM notificacao_enviada WHERE tipo = 'LEMBRETE_D1'");
        assertThat(registro.get("consulta_id")).isEqualTo(consulta);
        assertThat(registro.get("destinatario")).isEqualTo("joana@hospital.com");
        assertThat(registro.get("canal")).isEqualTo(sender.canal());
        assertThat(((Timestamp) registro.get("enviado_em")).toInstant())
                .as("enviado_em vem da fonte de tempo")
                .isEqualTo(AGORA);
        assertThat(registro.get("conteudo"))
                .as("o registro guarda o mesmo texto entregue ao canal")
                .isEqualTo(entregue.getValue().corpo());
        assertThat(entregue.getValue().destinatario()).isEqualTo("joana@hospital.com");
        assertThat(entregue.getValue().assunto()).isEqualTo("Lembrete de consulta");
    }

    /**
     * 14:30 UTC e 11:30 em Sao Paulo.
     *
     * <p>O relogio e reposicionado para colocar o horario exigido pela spec dentro da janela.
     * A agenda guarda o instante, sem o deslocamento, entao este e o unico lugar onde um
     * fuso errado apareceria — e ele apareceria como um horario tres horas adiantado.
     */
    @Test
    @DisplayName("Scenario: Conteudo informa medico e horario local da consulta")
    void conteudoInformaMedicoEHorarioLocal() {
        fixarRelogio(Instant.parse("2026-09-12T02:30:00Z"));
        agendar(Instant.parse("2026-09-12T14:30:00Z"), "AGENDADA");

        assertThat(lembretes.executar()).isEqualTo(1);

        ArgumentCaptor<Mensagem> entregue = ArgumentCaptor.forClass(Mensagem.class);
        Mockito.verify(sender).enviar(entregue.capture());
        assertThat(entregue.getValue().corpo())
                .contains("Maria Souza")
                .contains("Dr. Joao Lima")
                .contains("12/09/2026 11:30")
                .doesNotContain("14:30");
    }

    @Test
    @DisplayName("Scenario: Tipo do lembrete fica fora do contrato de eventos")
    void tipoDoLembreteFicaForaDoContratoDeEventos() {
        FILAS_NORMATIVAS.forEach(fila -> admin.purgeQueue(fila, false));
        agendar(AGORA.plus(Duration.ofHours(4)), "AGENDADA");

        assertThat(lembretes.executar()).isEqualTo(1);

        assertThat(Arrays.stream(TipoEvento.values()).map(Enum::name))
                .as("LEMBRETE_D1 e tipo local do registro, nao evento de integracao")
                .containsExactly("CONSULTA_CRIADA", "CONSULTA_ATUALIZADA", "CONSULTA_CONFIRMADA",
                        "CONSULTA_CANCELADA", "CONSULTA_REALIZADA");
        // A fila do historico recebe tudo que for publicado no exchange e nao tem consumidor
        // neste contexto: se o lembrete publicasse algo, apareceria ali.
        Awaitility.await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(3))
                .until(() -> FILAS_NORMATIVAS.stream().allMatch(this::filaVazia));
    }

    private boolean filaVazia(String fila) {
        var informacao = admin.getQueueInfo(fila);
        assertThat(informacao).as("a fila normativa %s precisa existir", fila).isNotNull();
        return informacao.getMessageCount() == 0;
    }
}
