package br.com.fiap.hospital.notificacao.integracao;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.fiap.hospital.contracts.ConsultaPayload;
import br.com.fiap.hospital.contracts.TipoEvento;
import br.com.fiap.hospital.notificacao.consumer.ConsumidorDeNotificacoes;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * A correlacao vale durante a tentativa e nao sobrevive a ela.
 *
 * <p>As duas metades importam. A primeira e obvia; a segunda e a que quebra em silencio: sem
 * a limpeza, o identificador de uma mensagem fica na thread do container e aparece nos logs
 * da mensagem seguinte. O log passa a mentir exatamente quando alguem vai usa-lo para
 * rastrear um problema.
 */
@DisplayName("Correlacao no consumo")
class CorrelacaoNotificacaoIT extends NotificacaoITBase {

    private static final Instant FATO = Instant.parse("2026-09-11T09:00:00Z");

    /** O que o MDC continha no momento em que o envio aconteceu. */
    private final ConcurrentLinkedQueue<Map<String, String>> contextos = new ConcurrentLinkedQueue<>();

    @Autowired ConsumidorDeNotificacoes consumidor;

    @Test
    @DisplayName("Scenario: Correlacao vale durante a tentativa e nao vaza depois")
    void correlacaoValeDuranteOProcessamento() {
        capturarContextoNoEnvio(false);
        UUID consultaId = UUID.randomUUID();

        publicarEAguardar(evento(TipoEvento.CONSULTA_CRIADA, consultaId, FATO,
                ConsultaPayload.Status.AGENDADA));

        assertThat(contextos).hasSize(1);
        assertThat(contextos.peek())
                .as("o correlationId do envelope precisa valer durante o processamento")
                .containsEntry("correlationId", "correlacao-m06");
    }

    /**
     * A limpeza, verificada na propria thread.
     *
     * <p>Aqui a fronteira proxificada e invocada pela thread do teste de proposito: o
     * vazamento acontece <b>na thread que processou</b>, e so quem esta nela consegue
     * observar o MDC depois do retorno. Pelo listener isso seria invisivel, porque a
     * mensagem seguinte sobrescreve o valor logo no inicio — o defeito passaria despercebido
     * ate alguem depurar um log errado em producao.
     *
     * <p>Este caso nao afirma travessia AMQP; ela e provada nas demais suites.
     */
    @Test
    @DisplayName("o contexto de log e limpo ao fim da tentativa, inclusive quando ela falha")
    void contextoELimpoAoFimDaTentativa() {
        MDC.clear();
        var comSucesso = evento(TipoEvento.CONSULTA_CRIADA, UUID.randomUUID(), FATO,
                ConsultaPayload.Status.AGENDADA);

        consumidor.consumir(comSucesso);

        assertThat(MDC.getCopyOfContextMap())
                .as("apos uma tentativa bem-sucedida o contexto nao pode sobrar na thread")
                .isNullOrEmpty();

        Mockito.doThrow(new IllegalStateException("canal indisponivel"))
                .when(sender).enviar(Mockito.any());
        var comFalha = evento(TipoEvento.CONSULTA_CRIADA, UUID.randomUUID(), FATO,
                ConsultaPayload.Status.AGENDADA);

        assertThat(org.assertj.core.api.Assertions
                .catchThrowable(() -> consumidor.consumir(comFalha)))
                .isInstanceOf(RuntimeException.class);
        assertThat(MDC.getCopyOfContextMap())
                .as("e muito menos depois de uma tentativa que falhou — o caso em que rastrear importa")
                .isNullOrEmpty();
    }

    @Test
    @DisplayName("cada tentativa do retry ve o correlationId do proprio envelope")
    void cadaTentativaVeOCorrelationIdDoEnvelope() {
        capturarContextoNoEnvio(true);
        var falho = evento(TipoEvento.CONSULTA_CRIADA, UUID.randomUUID(), FATO,
                ConsultaPayload.Status.AGENDADA);

        rabbit.convertAndSend(br.com.fiap.hospital.contracts.MensageriaAutoConfiguration.EXCHANGE,
                falho.eventType().routingKey(), falho);

        // Esperar contextos.size() >= 3 nao basta: a terceira tentativa ainda estaria
        // revertendo e publicando na DLQ quando o teste terminasse, e a mensagem em voo
        // atravessaria o @BeforeEach seguinte — que trunca as tabelas e purga as filas.
        // O sintoma seria uma suite vizinha falhando por efeito que ela nao produziu.
        // Aguardar o evento exato na DLQ fecha o ciclo retry -> rollback -> DLQ.
        assertThat(aguardarNaDlq(falho.eventId()))
                .as("o ciclo precisa terminar antes de liberar a proxima suite")
                .isNotNull();

        assertThat(contextos)
                .as("exatamente as tres tentativas do contrato, nem uma a mais")
                .hasSize(3);
        assertThat(contextos)
                .as("todas veem a correlacao do envelope, e nao a de outra mensagem")
                .allSatisfy(contexto ->
                        assertThat(contexto).containsEntry("correlationId", "correlacao-m06"));
    }

    /**
     * Le o MDC da thread que processa, no instante do envio.
     *
     * <p>Capturar e falhar precisam ser a mesma resposta: um {@code doThrow} separado
     * substituiria a captura e o teste observaria zero contextos.
     */
    private void capturarContextoNoEnvio(boolean falhar) {
        Mockito.doAnswer(invocacao -> {
            Map<String, String> copia = MDC.getCopyOfContextMap();
            contextos.add(copia == null ? Map.of() : Map.copyOf(copia));
            if (falhar) throw new IllegalStateException("canal indisponivel");
            return invocacao.callRealMethod();
        }).when(sender).enviar(Mockito.any());
    }
}
