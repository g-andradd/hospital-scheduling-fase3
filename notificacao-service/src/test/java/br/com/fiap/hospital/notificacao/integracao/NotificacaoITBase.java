package br.com.fiap.hospital.notificacao.integracao;

import br.com.fiap.hospital.contracts.ConsultaPayload;
import br.com.fiap.hospital.contracts.EventoEnvelope;
import br.com.fiap.hospital.contracts.MensageriaAutoConfiguration;
import br.com.fiap.hospital.contracts.TipoEvento;
import br.com.fiap.hospital.notificacao.lembrete.RegistradorDeLembrete;
import br.com.fiap.hospital.notificacao.lembrete.ServicoDeLembretes;
import br.com.fiap.hospital.notificacao.repository.EventoProcessadoRepository;
import br.com.fiap.hospital.notificacao.repository.NotificacaoEnviadaRepository;
import br.com.fiap.hospital.notificacao.sender.NotificationSenderPort;
import br.com.fiap.hospital.security.JwtProperties;
import br.com.fiap.hospital.security.JwtService;
import br.com.fiap.hospital.security.UsuarioAutenticado;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.Mockito;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Base das suites do M06 e do M07: aplicacao real, PostgreSQL real, RabbitMQ real.
 *
 * <p>As mensagens entram pela topologia de verdade, publicadas no exchange normativo, e
 * atravessam converter, retry e listener de producao. Chamar o processador direto seria
 * mais rapido e provaria menos: nao diria nada sobre o listener, sobre a transacao da
 * fronteira nem sobre a desserializacao do envelope.
 *
 * <p>Tudo o que as suites precisam — espioes, {@code MockMvc}, gravador de SQL, relogio —
 * fica <b>aqui</b>, e nunca numa suite. Uma declaracao local mudaria a chave do cache de
 * contexto e criaria um segundo contexto, com um segundo listener competindo pela mesma
 * fila. O profile {@code test} vem de src/test/resources, e com ele o agendador do lembrete
 * nao existe: a varredura so roda quando o teste a dispara.
 */
@SpringBootTest(properties = "logging.level.root=ERROR")
@AutoConfigureMockMvc
@Import(NotificacaoITBase.ConfiguracaoDeTeste.class)
abstract class NotificacaoITBase {

    /** Instante do relogio do servico. Nao participa da ordenacao dos fatos. */
    static final Instant AGORA = Instant.parse("2026-09-11T12:00:00Z");

    /** O nome que a DDL de falha usa; a limpeza devolve a tabela se um teste abortar. */
    static final String AGENDA_INDISPONIVEL = "agenda_local_indisponivel";

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

    /** O caso de uso do lembrete, espiado para contar execucoes. */
    @SpyBean ServicoDeLembretes lembretes;

    /** A operacao por candidato, espiada para ancorar a barreira da reserva concorrente. */
    @SpyBean RegistradorDeLembrete registrador;

    @Autowired org.springframework.amqp.core.AmqpAdmin admin;
    @Autowired MockMvc mvc;
    @Autowired JwtService jwtService;
    @Autowired JwtProperties propriedadesJwt;
    @Autowired RelogioDeTeste relogio;

    /** Barreira de concorrencia, desarmada por padrao. Ver {@link Barreira}. */
    final Barreira barreira = new Barreira();

    @BeforeEach
    void limpar() {
        Mockito.reset(sender, jdbc, processados, processador, lembretes, registrador);
        barreira.desarmar();
        relogio.fixar(AGORA);
        SqlEmitido.desarmar();
        jdbc.execute("ALTER TABLE IF EXISTS " + AGENDA_INDISPONIVEL + " RENAME TO agenda_local");
        jdbc.execute("TRUNCATE agenda_local, notificacao_enviada, evento_processado");
        admin.purgeQueue(MensageriaAutoConfiguration.NOTIFICACAO, false);
        admin.purgeQueue(MensageriaAutoConfiguration.NOTIFICACAO + ".dlq", false);
    }

    /**
     * Sincroniza duas transacoes no ponto exato da corrida.
     *
     * <p>Fica na base e nasce desarmada: uma barreira ativa por padrao travaria toda suite
     * que nao a usa. No consumo, o ponto de liberacao e <b>depois</b> da consulta de ausencia
     * do eventId e <b>antes</b> do upsert — e ali que as duas entregas se julgam a primeira.
     * No lembrete, e <b>depois</b> da leitura dos candidatos e <b>antes</b> da reserva — e
     * ali que as duas execucoes enxergam a mesma consulta como ainda nao lembrada.
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
            aguardar();
        }

        void antesDaReserva() {
            aguardar();
        }

        private void aguardar() {
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

    /**
     * Barreira na entrada da operacao por candidato do lembrete.
     *
     * <p>As duas execucoes ja leram os candidatos e ainda nao reservaram. Liberadas juntas,
     * elas disputam a mesma chave da unicidade parcial — que e a corrida que a reserva antes
     * do envio precisa vencer com uma unica chamada ao canal.
     */
    void ligarBarreiraAntesDaReserva() {
        Mockito.doAnswer(invocacao -> {
            barreira.antesDaReserva();
            return invocacao.callRealMethod();
        }).when(registrador).lembrar(Mockito.any());
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

    // --- agenda e lembretes (M07) -------------------------------------------------------

    /** Semeia a agenda por SQL, com horario exato: as bordas da janela sao valores, nao faixas. */
    UUID agendar(Instant dataHora, String status) {
        return agendar(dataHora, status, "maria@hospital.com");
    }

    UUID agendar(Instant dataHora, String status, String email) {
        UUID consultaId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO agenda_local
                    (consulta_id, paciente_id, paciente_nome, paciente_email, medico_nome,
                     data_hora, status, ocorrido_em, atualizado_em)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                consultaId, UUID.randomUUID(), "Maria Souza", email, "Dr. Joao Lima",
                Timestamp.from(dataHora), status, Timestamp.from(AGORA), Timestamp.from(AGORA));
        return consultaId;
    }

    long lembretesRegistrados() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM notificacao_enviada WHERE tipo = 'LEMBRETE_D1'", Long.class);
    }

    long lembretesRegistrados(UUID consultaId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM notificacao_enviada WHERE tipo = 'LEMBRETE_D1' AND consulta_id = ?",
                Long.class, consultaId);
    }

    /** Relogio fixo, mas ajustavel por teste; a limpeza o devolve a {@link #AGORA}. */
    void fixarRelogio(Instant instante) {
        relogio.fixar(instante);
    }

    // --- identidades --------------------------------------------------------------------

    /** Token do mesmo emissor e segredo do agendamento, emitido no relogio do teste. */
    String tokenDe(String perfil) {
        return jwtService.emitir(identidade(perfil));
    }

    /** Emitido um dia antes do relogio do teste, com validade de oito horas. */
    String tokenExpiradoDe(String perfil) {
        var ontem = new JwtService(propriedadesJwt,
                Clock.fixed(relogio.instant().minus(Duration.ofDays(1)), ZoneOffset.UTC));
        return ontem.emitir(identidade(perfil));
    }

    /** Bem formado e em prazo, mas assinado com outro segredo. */
    String tokenDeOutroSegredo(String perfil) {
        var alheio = new JwtService(
                new JwtProperties("outro-segredo-de-teste-com-mais-de-32-bytes", Duration.ofHours(8),
                        propriedadesJwt.emissor()),
                relogio);
        return alheio.emitir(identidade(perfil));
    }

    private static UsuarioAutenticado identidade(String perfil) {
        return new UsuarioAutenticado(UUID.randomUUID(), perfil.toLowerCase() + "@hospital.com",
                perfil, perfil.equals("PACIENTE") ? UUID.randomUUID() : null,
                perfil.equals("MEDICO") ? UUID.randomUUID() : null);
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

    /**
     * Um relogio fixo que o teste pode reposicionar.
     *
     * <p>Continua fixo: nada nele avanca sozinho. Reposiciona-lo permite colocar uma consulta
     * de horario exigido pela spec dentro da janela, sem trocar o contexto — trocar o bean
     * por suite criaria o segundo contexto que esta base existe para evitar.
     */
    static final class RelogioDeTeste extends Clock {
        private volatile Instant instante = AGORA;

        void fixar(Instant novo) {
            instante = novo;
        }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zona) { return Clock.fixed(instante, zona); }
        @Override public Instant instant() { return instante; }
    }

    @TestConfiguration
    static class ConfiguracaoDeTeste {
        /** Relogio fixo: torna asseraveis os instantes que o servico registra. */
        @Bean @Primary RelogioDeTeste clockDeTeste() { return new RelogioDeTeste(); }

        /** Embrulha a DataSource para registrar o SQL real. Ver {@link SqlEmitido}. */
        @Bean
        static BeanPostProcessor gravadorDeSql() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String nome) {
                    return bean instanceof DataSource original
                            && !(bean instanceof SqlEmitido.Gravador)
                            ? SqlEmitido.embrulhar(original)
                            : bean;
                }
            };
        }
    }
}
