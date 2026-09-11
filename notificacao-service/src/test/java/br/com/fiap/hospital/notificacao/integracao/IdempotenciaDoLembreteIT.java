package br.com.fiap.hospital.notificacao.integracao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.fiap.hospital.contracts.ConsultaPayload;
import br.com.fiap.hospital.contracts.TipoEvento;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * No maximo um lembrete D-1 por consulta, durante toda a vida dela.
 *
 * <p>A garantia verificada e sobre o estado persistido e, sob concorrencia, sobre o numero de
 * chamadas ao canal: a reserva vem antes do envio, e a segunda execucao espera a primeira na
 * chave da unicidade parcial. O que fica fora e declarado no design — envio concluido com a
 * confirmacao falhando —, e nada aqui afirma o contrario.
 */
@DisplayName("Unicidade do lembrete D-1")
class IdempotenciaDoLembreteIT extends NotificacaoITBase {

    @Test
    @DisplayName("Scenario: Execucao repetida nao lembra de novo")
    void execucaoRepetidaNaoLembraDeNovo() {
        UUID consulta = agendar(AGORA.plus(Duration.ofHours(5)), "AGENDADA");

        assertThat(lembretes.executar()).isEqualTo(1);
        assertThat(lembretes.executar())
                .as("a segunda execucao encontra a consulta ja lembrada")
                .isZero();

        assertThat(lembretesRegistrados(consulta)).isEqualTo(1);
        Mockito.verify(sender, Mockito.times(1)).enviar(Mockito.any());
    }

    /**
     * Scenario: Execucoes concorrentes confirmam e entregam um unico lembrete.
     *
     * <p>A barreira libera as duas execucoes na entrada da operacao por candidato: as duas ja
     * leram a consulta como ainda nao lembrada. A que reserva primeiro envia e confirma; a
     * outra espera na chave, encontra o conflito e nao envia. Enviar antes de reservar faria
     * este teste ver duas chamadas ao canal.
     */
    @Test
    @DisplayName("Scenario: Execucoes concorrentes confirmam e entregam um unico lembrete")
    void execucoesConcorrentesConfirmamEEntregamUmUnicoLembrete() throws Exception {
        UUID consulta = agendar(AGORA.plus(Duration.ofHours(5)), "AGENDADA");
        barreira.armar(2);
        ligarBarreiraAntesDaReserva();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> primeira = executor.submit(() -> lembretes.executar());
            Future<Integer> segunda = executor.submit(() -> lembretes.executar());

            assertThat(primeira.get(60, TimeUnit.SECONDS) + segunda.get(60, TimeUnit.SECONDS))
                    .as("somente uma das duas execucoes confirma o lembrete")
                    .isEqualTo(1);
        } finally {
            executor.shutdownNow();
            barreira.desarmar();
        }

        Mockito.verify(registrador, Mockito.times(2)).lembrar(Mockito.any());
        Mockito.verify(sender, Mockito.times(1)).enviar(Mockito.any());
        assertThat(lembretesRegistrados(consulta)).isEqualTo(1);
    }

    /** A remarcacao passa pelos eventos reais, para exercitar a agenda do M06 de ponta a ponta. */
    @Test
    @DisplayName("Scenario: Consulta remarcada depois do lembrete nao recebe outro")
    void consultaRemarcadaDepoisDoLembreteNaoRecebeOutro() {
        UUID consulta = UUID.randomUUID();
        publicarEAguardar(evento(TipoEvento.CONSULTA_CRIADA, consulta, AGORA.minusSeconds(3600),
                ConsultaPayload.Status.AGENDADA, "Dr. Joao Lima", noFuso(AGORA.plus(Duration.ofHours(12)))));
        assertThat(lembretes.executar()).isEqualTo(1);

        var novoHorario = AGORA.plus(Duration.ofHours(20));
        publicarEAguardar(evento(TipoEvento.CONSULTA_ATUALIZADA, consulta, AGORA.minusSeconds(1800),
                ConsultaPayload.Status.AGENDADA, "Dr. Joao Lima", noFuso(novoHorario)));
        assertThat(((Timestamp) agenda(consulta).get("data_hora")).toInstant())
                .as("a agenda aplicou a remarcacao")
                .isEqualTo(novoHorario);

        assertThat(lembretes.executar())
                .as("um lembrete por vida da consulta, mesmo com horario novo na janela")
                .isZero();
        assertThat(lembretesRegistrados(consulta)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM notificacao_enviada
                WHERE consulta_id = ? AND tipo = 'CONSULTA_ATUALIZADA'
                """, Long.class, consulta))
                .as("o aviso reativo de alteracao do M06 informa o novo horario")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("Scenario: Armazenamento recusa segundo lembrete da mesma consulta")
    void armazenamentoRecusaSegundoLembreteDaMesmaConsulta() {
        UUID consulta = agendar(AGORA.plus(Duration.ofHours(5)), "AGENDADA");
        registrarDireto(consulta, "LEMBRETE_D1");

        assertThatThrownBy(() -> registrarDireto(consulta, "LEMBRETE_D1"))
                .as("a unicidade vale para qualquer escritor SQL, nao so para o servico")
                .isInstanceOf(DataIntegrityViolationException.class);

        registrarDireto(consulta, "CONSULTA_ATUALIZADA");
        registrarDireto(consulta, "CONSULTA_ATUALIZADA");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM notificacao_enviada WHERE consulta_id = ? AND tipo = 'CONSULTA_ATUALIZADA'",
                Long.class, consulta))
                .as("a unicidade e parcial: notificacoes reativas repetem tipo legitimamente")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("Scenario: Falha no envio nao deixa lembrete e a consulta segue elegivel")
    void falhaNoEnvioNaoDeixaLembreteEConsultaSegueElegivel() {
        UUID consulta = agendar(AGORA.plus(Duration.ofHours(5)), "AGENDADA");
        Mockito.doThrow(new IllegalStateException("canal indisponivel"))
                .when(sender).enviar(Mockito.any());

        assertThat(lembretes.executar()).isZero();
        assertThat(lembretesRegistrados(consulta))
                .as("a falha do sender reverte a reserva")
                .isZero();

        Mockito.reset(sender);
        assertThat(lembretes.executar())
                .as("com o canal de volta, a execucao seguinte lembra a consulta")
                .isEqualTo(1);
        assertThat(lembretesRegistrados(consulta)).isEqualTo(1);
    }

    @Test
    @DisplayName("Scenario: Falha em uma consulta nao impede as demais")
    void falhaEmUmaConsultaNaoImpedeAsDemais() {
        UUID falha = agendar(AGORA.plus(Duration.ofHours(2)), "AGENDADA", "falha@hospital.com");
        UUID ok = agendar(AGORA.plus(Duration.ofHours(3)), "AGENDADA", "ok@hospital.com");
        Mockito.doThrow(new IllegalStateException("caixa postal indisponivel"))
                .when(sender).enviar(Mockito.argThat(
                        m -> m != null && "falha@hospital.com".equals(m.destinatario())));

        assertThat(lembretes.executar())
                .as("a execucao informa so o lembrete confirmado")
                .isEqualTo(1);

        assertThat(lembretesRegistrados(falha)).isZero();
        assertThat(lembretesRegistrados(ok)).isEqualTo(1);
    }

    private void registrarDireto(UUID consulta, String tipo) {
        jdbc.update("""
                INSERT INTO notificacao_enviada
                    (id, consulta_id, tipo, destinatario, canal, enviado_em, conteudo)
                VALUES (?, ?, ?, 'maria@hospital.com', 'LOG', ?, 'texto')
                """, UUID.randomUUID(), consulta, tipo, Timestamp.from(AGORA));
    }

    private static OffsetDateTime noFuso(java.time.Instant instante) {
        return OffsetDateTime.ofInstant(instante, ZoneOffset.UTC);
    }
}
