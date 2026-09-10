package br.com.fiap.hospital.notificacao.integracao;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.fiap.hospital.contracts.ConsultaPayload;
import br.com.fiap.hospital.contracts.EventoEnvelope;
import br.com.fiap.hospital.contracts.MensageriaAutoConfiguration;
import br.com.fiap.hospital.contracts.TipoEvento;
import br.com.fiap.hospital.notificacao.consumer.ConsumidorDeNotificacoes;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Um efeito por eventId, e nada pela metade quando algo falha.
 *
 * <p>A garantia verificada aqui e sobre o <b>estado persistido</b>. O numero de entregas ao
 * canal externo nao e objeto dela: uma tentativa cujo envio conclui e cuja transacao reverte
 * ja entregou a mensagem. Ver D5 do design antes de ler estas asercoes como exactly-once de
 * transporte.
 */
@DisplayName("Idempotencia do consumo")
class IdempotenciaNotificacaoIT extends NotificacaoITBase {

    private static final Instant FATO = Instant.parse("2026-09-11T09:00:00Z");

    /** A fronteira proxificada, para os casos que precisam de duas transacoes reais. */
    @Autowired ConsumidorDeNotificacoes consumidor;

    @Test
    @DisplayName("Scenario: Reentrega do mesmo evento nao notifica duas vezes")
    void reentregaNaoNotificaDuasVezes() {
        UUID consultaId = UUID.randomUUID();
        var evento = evento(TipoEvento.CONSULTA_CRIADA, consultaId, FATO,
                ConsultaPayload.Status.AGENDADA);

        publicarEAguardar(evento);
        var agendaAposPrimeira = agenda(consultaId);

        // Mesma mensagem outra vez: a marca ja existe e o consumidor encerra sem repetir.
        rabbit.convertAndSend(MensageriaAutoConfiguration.EXCHANGE,
                evento.eventType().routingKey(), evento);
        aguardarEstabilizacao();

        assertThat(quantidade("notificacao_enviada"))
                .as("uma unica notificacao para o mesmo eventId")
                .isEqualTo(1);
        assertThat(quantidade("evento_processado")).isEqualTo(1);
        assertThat(quantidade("agenda_local")).isEqualTo(1);
        assertThat(agenda(consultaId))
                .as("a agenda nao pode ser reescrita pela reentrega")
                .isEqualTo(agendaAposPrimeira);
    }

    /**
     * Scenario: Entregas concorrentes confirmam um unico efeito.
     *
     * <p>A barreira libera as duas transacoes <b>depois</b> da consulta de ausencia do
     * eventId: e nesse ponto que ambas se julgam a primeira e seguem para o efeito. Uma
     * perde a disputa pela chave primaria da marca e reverte inteira.
     *
     * <p>Nao se afirma nada sobre quantas vezes o sender foi chamado — as duas podem te-lo
     * chamado antes de a chave decidir. E o limite declarado em D5.
     */
    @Test
    @DisplayName("Scenario: Entregas concorrentes confirmam um unico efeito")
    void entregasConcorrentesConfirmamUmUnicoEfeito() throws Exception {
        UUID consultaId = UUID.randomUUID();
        var evento = evento(TipoEvento.CONSULTA_CRIADA, consultaId, FATO,
                ConsultaPayload.Status.AGENDADA);

        barreira.armar(2);
        ligarBarreiraNaConsultaDeIdempotencia();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> primeira = executor.submit(() -> entregar(evento));
            Future<Boolean> segunda = executor.submit(() -> entregar(evento));

            boolean umaConfirmou = primeira.get(60, TimeUnit.SECONDS);
            boolean outraConfirmou = segunda.get(60, TimeUnit.SECONDS);
            assertThat(umaConfirmou ^ outraConfirmou)
                    .as("exatamente uma das duas transacoes pode confirmar")
                    .isTrue();
        } finally {
            executor.shutdownNow();
            barreira.desarmar();
        }

        assertThat(quantidade("evento_processado")).isEqualTo(1);
        assertThat(quantidade("notificacao_enviada"))
                .as("um unico registro de auditoria confirmado")
                .isEqualTo(1);
        assertThat(quantidade("agenda_local")).isEqualTo(1);
    }

    @Test
    @DisplayName("Scenario: Falha no envio desfaz o efeito inteiro")
    void falhaNoEnvioDesfazOEfeitoInteiro() {
        UUID consultaId = UUID.randomUUID();
        var evento = evento(TipoEvento.CONSULTA_CRIADA, consultaId, FATO,
                ConsultaPayload.Status.AGENDADA);
        Mockito.doThrow(new IllegalStateException("canal indisponivel"))
                .when(sender).enviar(Mockito.any());

        assertThat(entregar(evento))
                .as("a falha do sender tem de propagar para fora da transacao")
                .isFalse();

        assertThat(quantidade("agenda_local"))
                .as("a agenda volta ao estado anterior a tentativa")
                .isZero();
        assertThat(quantidade("notificacao_enviada")).isZero();
        assertThat(quantidade("evento_processado")).isZero();
    }

    /**
     * Scenario: Falha entre o efeito e a marca desfaz a projecao.
     *
     * <p>A falha e injetada na gravacao da marca, que e a ultima operacao. Se a transacao
     * nao cobrisse agenda e auditoria, elas sobreviveriam — que e exatamente o defeito que
     * a fronteira unica existe para impedir.
     */
    @Test
    @DisplayName("Scenario: Falha entre o efeito e a marca desfaz a projecao")
    void falhaEntreEfeitoEMarcaDesfazAProjecao() {
        UUID consultaId = UUID.randomUUID();
        var evento = evento(TipoEvento.CONSULTA_CRIADA, consultaId, FATO,
                ConsultaPayload.Status.AGENDADA);
        injetarFalhaNaMarcaDeProcessado("falha apos o efeito");

        assertThat(entregar(evento)).isFalse();

        assertThat(quantidade("agenda_local")).isZero();
        assertThat(quantidade("notificacao_enviada")).isZero();
        assertThat(quantidade("evento_processado")).isZero();

        // E uma nova entrega ainda processa o evento integralmente.
        removerGanchos();
        assertThat(entregar(evento)).isTrue();
        assertThat(quantidade("agenda_local")).isEqualTo(1);
        assertThat(quantidade("notificacao_enviada")).isEqualTo(1);
        assertThat(quantidade("evento_processado")).isEqualTo(1);
    }

    // --- apoio --------------------------------------------------------------------------

    /**
     * Entrega pela fronteira proxificada, e nao pelo listener.
     *
     * <p>Estes quatro casos precisam observar o resultado da <b>transacao</b> — se ela
     * confirmou ou reverteu. Pela fila isso e invisivel: a mensagem some para a DLQ e o
     * teste teria de inferir o rollback pela ausencia de linhas, sem distinguir "reverteu"
     * de "nunca chegou". A travessia AMQP e provada por ConsumoNotificacaoRabbitMqIT e pelas
     * demais suites; aqui o alvo e a transacao.
     */
    private boolean entregar(EventoEnvelope<ConsultaPayload> evento) {
        try {
            consumidor.consumir(evento);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Da tempo de a reentrega ser consumida, sem depender de contagem global. */
    private void aguardarEstabilizacao() {
        org.awaitility.Awaitility.await()
                .during(java.time.Duration.ofSeconds(3))
                .atMost(java.time.Duration.ofSeconds(10))
                .until(() -> quantidade("evento_processado") == 1);
    }
}
