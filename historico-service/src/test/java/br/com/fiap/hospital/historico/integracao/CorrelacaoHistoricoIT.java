package br.com.fiap.hospital.historico.integracao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import br.com.fiap.hospital.contracts.ConsultaPayload;
import br.com.fiap.hospital.contracts.EventoEnvelope;
import br.com.fiap.hospital.contracts.MensageriaAutoConfiguration;
import br.com.fiap.hospital.contracts.TipoEvento;
import br.com.fiap.hospital.historico.infrastructure.messaging.ConsumidorTransacionalDoHistorico;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;

/**
 * A correlacao do envelope vale durante a projecao e nao sobrevive a tentativa (RNF-08).
 *
 * <p>Reutiliza a {@link HistoricoITBase} como esta, com o listener real: o MDC e lido pelo
 * {@code JdbcTemplate} que a base ja espia, no instante do upsert da projecao. O registro de
 * log e observado por um appender em memoria no logger do consumidor, sem nenhuma
 * configuracao de contexto (D11).
 */
@DisplayName("Correlação no consumo do histórico")
class CorrelacaoHistoricoIT extends HistoricoITBase {

    private static final Instant FATO = Instant.parse("2026-09-12T09:00:00Z");

    /** O que o MDC continha em cada upsert da projecao. */
    private final ConcurrentLinkedQueue<Map<String, String>> contextos = new ConcurrentLinkedQueue<>();

    private final Logger loggerDoConsumidor = (Logger) LoggerFactory.getLogger(ConsumidorTransacionalDoHistorico.class);
    private final ListAppender<ILoggingEvent> registros = new ListAppender<>();
    private Level nivelAnterior;

    @BeforeEach
    void observarRegistros() {
        MDC.clear();
        nivelAnterior = loggerDoConsumidor.getLevel();
        loggerDoConsumidor.setLevel(Level.INFO);
        registros.start();
        loggerDoConsumidor.addAppender(registros);
    }

    @AfterEach
    void pararDeObservar() {
        loggerDoConsumidor.detachAppender(registros);
        registros.stop();
        loggerDoConsumidor.setLevel(nivelAnterior);
        MDC.clear();
    }

    @Test
    @DisplayName("Scenario: Consumidor do histórico mantém e limpa a correlação — durante a projeção")
    void correlacaoValeDuranteAProjecao() {
        capturarContextoNoUpsert(false);
        var evento = comCorrelacao(evento(TipoEvento.CONSULTA_CRIADA, UUID.randomUUID(), FATO,
                ConsultaPayload.Status.AGENDADA, "observacao"));

        rabbit.convertAndSend(MensageriaAutoConfiguration.EXCHANGE, evento.eventType().routingKey(), evento);
        Awaitility.await().atMost(Duration.ofSeconds(20)).until(() -> processados.existsById(evento.eventId()));

        assertThat(contextos).isNotEmpty()
                .as("o correlationId do envelope precisa valer durante a projecao")
                .allSatisfy(contexto -> assertThat(contexto).containsEntry("correlationId", evento.correlationId()));
        Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> !projetados(evento).isEmpty());
        assertThat(projetados(evento)).singleElement().satisfies(registro -> {
            assertThat(registro.getLevel()).isEqualTo(Level.INFO);
            assertThat(registro.getFormattedMessage())
                    .isEqualTo("Evento projetado eventId=" + evento.eventId() + " tipo=CONSULTA_CRIADA consultaId="
                            + evento.aggregateId());
            assertThat(registro.getMDCPropertyMap()).containsEntry("correlationId", evento.correlationId());
        });
    }

    /**
     * A limpeza, verificada na propria thread que processou.
     *
     * <p>Pelo listener ela seria invisivel: a mensagem seguinte sobrescreve o valor logo no
     * inicio. Por isso a fronteira proxificada e invocada pela thread do teste.
     */
    @Test
    @DisplayName("Scenario: Consumidor do histórico mantém e limpa a correlação — limpa ao fim, inclusive em falha")
    void contextoELimpoAoFimDaTentativa() {
        consumidor.consumir(comCorrelacao(evento(TipoEvento.CONSULTA_CRIADA, UUID.randomUUID(), FATO,
                ConsultaPayload.Status.AGENDADA, "observacao")));

        assertThat(MDC.getCopyOfContextMap())
                .as("apos uma tentativa bem-sucedida o contexto nao pode sobrar na thread")
                .isNullOrEmpty();

        capturarContextoNoUpsert(true);
        var comFalha = comCorrelacao(evento(TipoEvento.CONSULTA_CRIADA, UUID.randomUUID(), FATO,
                ConsultaPayload.Status.AGENDADA, "observacao"));

        assertThat(catchThrowable(() -> consumidor.consumir(comFalha))).isInstanceOf(RuntimeException.class);
        assertThat(contextos).singleElement().satisfies(
                contexto -> assertThat(contexto).containsEntry("correlationId", comFalha.correlationId()));
        assertThat(MDC.getCopyOfContextMap())
                .as("e muito menos depois de uma tentativa que falhou")
                .isNullOrEmpty();
        assertThat(projetados(comFalha)).as("tentativa que falhou nao registra projecao").isEmpty();
    }

    @Test
    @DisplayName("Scenario: Consumidor do histórico mantém e limpa a correlação — cada tentativa do retry")
    void cadaTentativaVeOCorrelationIdDoEnvelope() {
        capturarContextoNoUpsert(true);
        var falho = comCorrelacao(evento(TipoEvento.CONSULTA_CRIADA, UUID.randomUUID(), FATO,
                ConsultaPayload.Status.AGENDADA, "observacao"));

        rabbit.convertAndSend(MensageriaAutoConfiguration.EXCHANGE, falho.eventType().routingKey(), falho);

        // Aguardar o evento exato na DLQ fecha o ciclo retry -> rollback -> DLQ antes de liberar
        // a proxima suite, que trunca tabelas e purga filas.
        assertThat(aguardarNaDlq(falho.eventId())).isNotNull();
        assertThat(contextos).as("exatamente as tres tentativas do contrato").hasSize(3)
                .allSatisfy(contexto -> assertThat(contexto).containsEntry("correlationId", falho.correlationId()));
        assertThat(projetados(falho)).isEmpty();
    }

    @Test
    @DisplayName("duplicata descartada não registra projeção")
    void duplicataNaoRegistraProjecao() {
        var evento = comCorrelacao(evento(TipoEvento.CONSULTA_CRIADA, UUID.randomUUID(), FATO,
                ConsultaPayload.Status.AGENDADA, "observacao"));

        consumidor.consumir(evento);
        consumidor.consumir(evento);

        assertThat(projetados(evento)).hasSize(1);
        assertThat(quantidade("evento_processado")).isOne();
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    /**
     * Le o MDC no upsert da projecao, pelo espiao que a base ja declara.
     *
     * <p>Capturar e falhar sao a mesma resposta: um {@code doThrow} separado substituiria a
     * captura.
     */
    private void capturarContextoNoUpsert(boolean falhar) {
        Mockito.doAnswer(invocacao -> {
            Map<String, String> copia = MDC.getCopyOfContextMap();
            contextos.add(copia == null ? Map.of() : Map.copyOf(copia));
            if (falhar) throw new IllegalStateException("projecao indisponivel");
            return invocacao.callRealMethod();
        }).when(jdbc).update(Mockito.anyString(), Mockito.any(Object[].class));
    }

    private List<ILoggingEvent> projetados(EventoEnvelope<ConsultaPayload> evento) {
        return registros.list.stream()
                .filter(registro -> registro.getFormattedMessage().startsWith("Evento projetado eventId=" + evento.eventId()))
                .toList();
    }

    private static EventoEnvelope<ConsultaPayload> comCorrelacao(EventoEnvelope<ConsultaPayload> evento) {
        return new EventoEnvelope<>(evento.eventId(), evento.eventType(), evento.aggregateId(), evento.occurredAt(),
                evento.version(), "correlacao-m11-" + UUID.randomUUID(), evento.payload());
    }

    private Message aguardarNaDlq(UUID eventId) {
        String dlq = MensageriaAutoConfiguration.HISTORICO + ".dlq";
        String alvo = eventId.toString();
        return Awaitility.await().atMost(Duration.ofSeconds(30)).until(() -> {
            Message mensagem = rabbit.receive(dlq, 500);
            if (mensagem == null) return null;
            var propriedades = mensagem.getMessageProperties();
            boolean doEvento = alvo.equals(propriedades.getMessageId())
                    || alvo.equals(String.valueOf((Object) propriedades.getHeader("x-event-id")))
                    || new String(mensagem.getBody()).contains(alvo);
            return doEvento ? mensagem : null;
        }, java.util.Objects::nonNull);
    }
}
