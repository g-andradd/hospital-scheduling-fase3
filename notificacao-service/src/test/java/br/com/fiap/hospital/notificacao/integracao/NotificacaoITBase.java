package br.com.fiap.hospital.notificacao.integracao;

import br.com.fiap.hospital.contracts.ConsultaPayload;
import br.com.fiap.hospital.contracts.EventoEnvelope;
import br.com.fiap.hospital.contracts.MensageriaAutoConfiguration;
import br.com.fiap.hospital.contracts.TipoEvento;
import br.com.fiap.hospital.notificacao.repository.EventoProcessadoRepository;
import br.com.fiap.hospital.notificacao.repository.NotificacaoEnviadaRepository;
import br.com.fiap.hospital.notificacao.sender.NotificationSenderPort;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.Mockito;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base das suites do M06: aplicacao real, PostgreSQL real, RabbitMQ real.
 *
 * <p>As mensagens entram pela topologia de verdade, publicadas no exchange normativo, e
 * atravessam converter, retry e listener de producao. Chamar o processador direto seria
 * mais rapido e provaria menos: nao diria nada sobre o listener, sobre a transacao da
 * fronteira nem sobre a desserializacao do envelope.
 */
@SpringBootTest(properties = "logging.level.root=ERROR")
@Import(NotificacaoITBase.ConfiguracaoDeTeste.class)
abstract class NotificacaoITBase {

    /** Instante do relogio do servico. Nao participa da ordenacao dos fatos. */
    static final Instant AGORA = Instant.parse("2026-09-11T12:00:00Z");

    @DynamicPropertySource
    static void propriedades(DynamicPropertyRegistry registro) {
        ContainersDeNotificacao.registrar(registro);
    }

    /**
     * Espia o JdbcTemplate na <b>base</b>, e nao numa subclasse.
     *
     * <p>Um @SpyBean declarado so numa suite muda a chave do cache de contexto e cria um
     * segundo contexto — com um segundo listener na mesma fila. As duas aplicacoes passam a
     * competir pelas mensagens, e a suite que espiava recebe zero invocacoes porque quem
     * consumiu foi a outra. Declarar aqui mantem um unico contexto para todas as suites que
     * herdam esta base.
     */
    @SpyBean JdbcTemplate jdbc;
    @Autowired RabbitTemplate rabbit;
    /**
     * Espiado para poder falhar sob comando.
     *
     * <p>{@code doThrow} funciona num spy de interface porque nao precisa invocar metodo real
     * — diferente de {@code callRealMethod()}, que nao existe para um proxy do Spring Data.
     */
    @SpyBean EventoProcessadoRepository processados;

    /** Espiado apenas para ancorar a barreira de concorrencia. */
    @SpyBean br.com.fiap.hospital.notificacao.consumer.ProcessadorDeEventos processador;
    @Autowired NotificacaoEnviadaRepository auditoria;

    /**
     * Espia o adaptador real ativo, em vez de substitui-lo.
     *
     * <p>Assim se observa exatamente o que foi entregue ao canal, sem trocar o componente
     * que a configuracao selecionou — a prova continua sendo sobre o adaptador de
     * producao.
     */
    @SpyBean NotificationSenderPort sender;

    @Autowired org.springframework.amqp.core.AmqpAdmin admin;

    /** Barreira de concorrencia, desarmada por padrao. Ver {@link Barreira}. */
    final Barreira barreira = new Barreira();

    @BeforeEach
    void limpar() {
        Mockito.reset(sender, jdbc, processados, processador);
        barreira.desarmar();
        jdbc.execute("TRUNCATE agenda_local, notificacao_enviada, evento_processado");
        admin.purgeQueue(MensageriaAutoConfiguration.NOTIFICACAO, false);
        admin.purgeQueue(MensageriaAutoConfiguration.NOTIFICACAO + ".dlq", false);
    }

    /**
     * Sincroniza duas transacoes no ponto exato da corrida.
     *
     * <p>Fica na base e nasce desarmada: uma barreira ativa por padrao travaria toda suite
     * que nao a usa. O ponto de liberacao e <b>depois</b> da consulta de ausencia do
     * eventId e <b>antes</b> do upsert — e ali que as duas entregas se julgam a primeira.
     * Coloca-la no sender seria pior: a primeira transacao ja teria o lock da linha, a
     * segunda nao chegaria ao envio, e o teste travaria ou passaria sem exercitar a
     * disputa que diz exercitar.
     */
    static final class Barreira {
        private volatile java.util.concurrent.CyclicBarrier barreira;

        void armar(int participantes) {
            barreira = new java.util.concurrent.CyclicBarrier(participantes);
        }

        void desarmar() {
            barreira = null;
        }

        void aposConsultaDeIdempotencia() {
            var atual = barreira;
            if (atual == null) return;
            try {
                atual.await(20, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new IllegalStateException("barreira de concorrencia", e);
            }
        }
    }

    /**
     * Barreira na entrada do processador.
     *
     * <p>E o ponto exigido: a consulta de ausencia do eventId acontece na fronteira, antes de
     * o processador ser chamado, e o upsert acontece dentro dele. Liberar aqui e liberar
     * depois da checagem e antes do efeito — as duas entregas ja se julgaram a primeira.
     *
     * <p>Nao pode ficar no sender: a primeira transacao ja teria o lock da linha da agenda, a
     * segunda nao chegaria ao envio, e o teste travaria ou passaria sem exercitar a disputa.
     *
     * <p>E nao pode ficar no {@code JdbcTemplate}: {@code update(String, Object...)} e
     * varargs, o matcher nao casa a chamada, o stub nunca se aplica e a barreira jamais
     * dispara — as duas transacoes rodam em serie, as duas "confirmam" e o teste passa a
     * medir nada. {@code ProcessadorDeEventos} e classe com metodo de aridade fixa, entao o
     * stub casa e {@code callRealMethod()} funciona.
     */
    void ligarBarreiraNaConsultaDeIdempotencia() {
        Mockito.doAnswer(invocacao -> {
            barreira.aposConsultaDeIdempotencia();
            return invocacao.callRealMethod();
        }).when(processador).processar(Mockito.any());
    }

    /** Faz a gravacao da marca falhar, para provar o rollback do efeito ja aplicado. */
    void injetarFalhaNaMarcaDeProcessado(String mensagem) {
        Mockito.doThrow(new IllegalStateException(mensagem))
                .when(processados).saveAndFlush(Mockito.any());
    }

    /** Devolve os ganchos ao comportamento real. */
    void removerGanchos() {
        Mockito.reset(jdbc, processados, processador);
    }

    // --- DLQ ----------------------------------------------------------------------------

    /**
     * Espera a mensagem <b>daquele</b> evento chegar a DLQ, por identidade.
     *
     * <p>A identidade e lida das propriedades — messageId ou o header do contrato —, e nao do
     * corpo. Casar pelo corpo funcionaria so para mensagens bem formadas: a de JSON invalido,
     * que e justamente o caso mais importante, nao contem eventId nenhum e a espera estouraria
     * por timeout dizendo que a DLQ nao recebeu nada.
     */
    org.springframework.amqp.core.Message aguardarNaDlq(UUID eventId) {
        var dlq = MensageriaAutoConfiguration.NOTIFICACAO + ".dlq";
        var encontrada = new java.util.concurrent.atomic.AtomicReference<
                org.springframework.amqp.core.Message>();
        Awaitility.await().atMost(Duration.ofSeconds(30)).until(() -> {
            var mensagem = rabbit.receive(dlq, 500);
            if (mensagem == null) return false;
            if (ehDoEvento(mensagem, eventId)) {
                encontrada.set(mensagem);
                return true;
            }
            return false;
        });
        return encontrada.get();
    }

    private boolean ehDoEvento(org.springframework.amqp.core.Message mensagem, UUID eventId) {
        var propriedades = mensagem.getMessageProperties();
        String alvo = eventId.toString();
        return alvo.equals(propriedades.getMessageId())
                || alvo.equals(String.valueOf((Object) propriedades.getHeader("x-event-id")))
                || new String(mensagem.getBody()).contains(alvo);
    }

    // --- publicacao ---------------------------------------------------------------------

    /**
     * Publica no exchange normativo e espera <b>este</b> evento ser processado.
     *
     * <p>A espera e pelo eventId, e nao pela contagem de marcas. Esperar a contagem subir
     * parece equivalente e nao e: uma mensagem em voo de um teste anterior pode incrementa-la
     * depois do TRUNCATE, liberando a espera antes de a mensagem certa ter sido processada.
     * O sintoma disso e uma suite que passa isolada e falha em conjunto — que foi
     * exatamente o que aconteceu aqui.
     */
    void publicarEAguardar(EventoEnvelope<ConsultaPayload> evento) {
        rabbit.convertAndSend(MensageriaAutoConfiguration.EXCHANGE,
                evento.eventType().routingKey(), evento);
        Awaitility.await().atMost(Duration.ofSeconds(20))
                .until(() -> processados.existsById(evento.eventId()));
    }

    // --- eventos ------------------------------------------------------------------------

    EventoEnvelope<ConsultaPayload> evento(TipoEvento tipo, UUID consultaId, Instant ocorridoEm,
                                           ConsultaPayload.Status status) {
        return evento(tipo, consultaId, ocorridoEm, status, "Dr. Joao Lima",
                OffsetDateTime.ofInstant(Instant.parse("2026-09-20T14:30:00Z"), ZoneOffset.UTC));
    }

    EventoEnvelope<ConsultaPayload> evento(TipoEvento tipo, UUID consultaId, Instant ocorridoEm,
                                           ConsultaPayload.Status status, String medicoNome,
                                           OffsetDateTime dataHora) {
        var paciente = new ConsultaPayload.Paciente(
                UUID.randomUUID(), "Maria Souza", "maria@hospital.com", "+5561999990000");
        var medico = new ConsultaPayload.Medico(
                UUID.randomUUID(), medicoNome, "DF-12345", "Cardiologia");
        var registrante = new ConsultaPayload.Registrante(
                UUID.randomUUID(), "Ana Enfermeira", ConsultaPayload.Perfil.ENFERMEIRO);
        var payload = new ConsultaPayload(consultaId, status, dataHora, 30, "observacao",
                status == ConsultaPayload.Status.CANCELADA ? "Cancelada pelo paciente" : null,
                paciente, medico, registrante,
                tipo == TipoEvento.CONSULTA_ATUALIZADA ? Map.of("observacoesAnterior", "anterior") : null);
        return new EventoEnvelope<>(UUID.randomUUID(), tipo, consultaId, ocorridoEm, 1,
                "correlacao-m06", payload);
    }

    // --- leitura ------------------------------------------------------------------------

    Map<String, Object> agenda(UUID consultaId) {
        List<Map<String, Object>> linhas = jdbc.queryForList(
                "SELECT * FROM agenda_local WHERE consulta_id = ?", consultaId);
        return linhas.isEmpty() ? Map.of() : linhas.getFirst();
    }

    long quantidade(String tabela) {
        return jdbc.queryForObject("SELECT count(*) FROM " + tabela, Long.class);
    }

    @TestConfiguration
    static class ConfiguracaoDeTeste {
        /** Relogio fixo: torna asseraveis os instantes que o servico registra. */
        @Bean @Primary Clock clockDeTeste() { return Clock.fixed(AGORA, ZoneOffset.UTC); }
    }
}
