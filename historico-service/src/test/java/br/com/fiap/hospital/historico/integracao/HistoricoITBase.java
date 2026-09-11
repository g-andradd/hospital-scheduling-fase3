package br.com.fiap.hospital.historico.integracao;

import br.com.fiap.hospital.contracts.ConsultaPayload;
import br.com.fiap.hospital.contracts.EventoEnvelope;
import br.com.fiap.hospital.contracts.MensageriaAutoConfiguration;
import br.com.fiap.hospital.contracts.EventoJson;
import br.com.fiap.hospital.contracts.EventoEnvelopeConverter;
import br.com.fiap.hospital.contracts.TipoEvento;
import br.com.fiap.hospital.historico.infrastructure.messaging.ConsumidorTransacionalDoHistorico;
import br.com.fiap.hospital.historico.infrastructure.persistence.repository.ConsultaEventoJpaRepository;
import br.com.fiap.hospital.historico.infrastructure.persistence.repository.ConsultaHistoricoJpaRepository;
import br.com.fiap.hospital.historico.infrastructure.persistence.repository.EventoProcessadoJpaRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.Mockito;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.Advisor;
import org.springframework.aop.Pointcut;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.amqp.core.Message;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import javax.sql.DataSource;
import org.awaitility.Awaitility;

@SpringBootTest(properties = "logging.level.root=ERROR")
@Import(HistoricoITBase.ConfiguracaoDeTeste.class)
abstract class HistoricoITBase {
    static final Instant INSTANTE_DE_CRIACAO = Instant.parse("2026-09-01T12:00:00Z");

    @DynamicPropertySource
    static void propriedades(DynamicPropertyRegistry registro) {
        ContainersDoHistorico.registrar(registro);
    }

    @SpyBean JdbcTemplate jdbc;
    @Autowired ConsumidorTransacionalDoHistorico consumidor;
    @Autowired ConsultaHistoricoJpaRepository snapshots;
    @Autowired ConsultaEventoJpaRepository eventos;
    @Autowired EventoProcessadoJpaRepository processados;
    @Autowired RabbitTemplate rabbit;
    @Autowired AmqpAdmin admin;
    @Autowired EventoJson json;
    @Autowired DataSource dataSource;
    @SpyBean EventoEnvelopeConverter converter;
    final SincronizacaoDeConcorrencia sincronizacao = new SincronizacaoDeConcorrencia();
    final AtomicInteger tentativasDoConverter = new AtomicInteger();
    private Advisor sincronizadorDoRepositorio;
    private int quantidadeDeAdvisorsAntes;

    @BeforeEach
    void limpar() {
        removerSincronizadorAnterior();
        Mockito.reset(jdbc, converter);
        sincronizacao.desarmar();
        Mockito.doAnswer(invocacao -> {
            tentativasDoConverter.incrementAndGet();
            return invocacao.callRealMethod();
        }).when(converter).fromMessage(Mockito.any(Message.class));
        quantidadeDeAdvisorsAntes = ((Advised) processados).getAdvisors().length;
        sincronizadorDoRepositorio = new DefaultPointcutAdvisor(
                Pointcut.TRUE, (MethodInterceptor) invocacao -> {
                    Object resultado = invocacao.proceed();
                    if (invocacao.getMethod().getName().equals("existsById"))
                        sincronizacao.aposConsultaDeIdempotencia((UUID) invocacao.getArguments()[0], Boolean.TRUE.equals(resultado));
                    return resultado;
                });
        ((Advised) processados).addAdvisor(sincronizadorDoRepositorio);
        if (((Advised) processados).getAdvisors().length != quantidadeDeAdvisorsAntes + 1)
            throw new AssertionError("advisor de sincronização não foi instalado uma única vez");
        Mockito.doAnswer(invocacao -> {
            UUID consultaId = invocacao.getArgument(1, UUID.class);
            return sincronizacao.executarUpsert(consultaId, invocacao::callRealMethod);
        }).when(jdbc).update(Mockito.anyString(), Mockito.any(Object[].class));
        Object alvoDoConsumidor = AopTestUtils.getTargetObject(consumidor);
        Object projetor = AopTestUtils.getTargetObject(ReflectionTestUtils.getField(alvoDoConsumidor, "projetor"));
        ReflectionTestUtils.setField(projetor, "jdbc", jdbc);
        tentativasDoConverter.set(0);
        limparDados();
    }

    void limparDados() {
        jdbc.execute("TRUNCATE evento_processado, consulta_evento, consulta_historico CASCADE");
        admin.purgeQueue(MensageriaAutoConfiguration.HISTORICO);
        admin.purgeQueue(MensageriaAutoConfiguration.HISTORICO + ".dlq");
        admin.purgeQueue(MensageriaAutoConfiguration.NOTIFICACAO);
        admin.purgeQueue(MensageriaAutoConfiguration.NOTIFICACAO + ".dlq");
    }

    @AfterEach
    void desarmarSincronizacao() {
        sincronizacao.desarmar();
        removerSincronizadorAnterior();
    }

    private void removerSincronizadorAnterior() {
        if (sincronizadorDoRepositorio != null) {
            ((Advised) processados).removeAdvisor(sincronizadorDoRepositorio);
            sincronizadorDoRepositorio = null;
            if (((Advised) processados).getAdvisors().length != quantidadeDeAdvisorsAntes)
                throw new AssertionError("advisor de sincronização vazou entre testes");
        }
    }

    void aguardarSegundaTransacaoBloqueadaNoPostgres() {
        Awaitility.await().atMost(10, TimeUnit.SECONDS).until(() -> {
            try (Connection conexao = dataSource.getConnection();
                 PreparedStatement consulta = conexao.prepareStatement("""
                         SELECT EXISTS (
                           SELECT 1 FROM pg_stat_activity atividade
                           JOIN pg_locks bloqueio ON bloqueio.pid = atividade.pid
                           WHERE atividade.datname = current_database()
                             AND atividade.wait_event_type = 'Lock'
                             AND bloqueio.granted = false
                             AND atividade.query LIKE '%consulta_historico%'
                         )
                         """);
                 ResultSet resultado = consulta.executeQuery()) {
                resultado.next();
                return resultado.getBoolean(1);
            } catch (Exception excecao) {
                throw new IllegalStateException(excecao);
            }
        });
    }

    EventoEnvelope<ConsultaPayload> evento(TipoEvento tipo, UUID consultaId, Instant ocorridoEm,
                                           ConsultaPayload.Status status, String observacoes) {
        var paciente = new ConsultaPayload.Paciente(UUID.randomUUID(), "Maria Souza", "maria@hospital.com", "+5561999990000");
        var medico = new ConsultaPayload.Medico(UUID.randomUUID(), "Dr. João Lima", "DF-12345", "Cardiologia");
        var registrante = new ConsultaPayload.Registrante(UUID.randomUUID(), "Ana Enfermeira", ConsultaPayload.Perfil.ENFERMEIRO);
        var payload = new ConsultaPayload(consultaId, status,
                OffsetDateTime.ofInstant(ocorridoEm, ZoneOffset.ofHours(-3)), 30, observacoes,
                status == ConsultaPayload.Status.CANCELADA ? "Cancelada pelo paciente" : null,
                paciente, medico, registrante,
                tipo == TipoEvento.CONSULTA_ATUALIZADA ? Map.of("observacoesAnterior", "anterior") : null);
        return new EventoEnvelope<>(UUID.randomUUID(), tipo, consultaId, ocorridoEm, 1, "correlacao-m08", payload);
    }

    long quantidade(String tabela) { return jdbc.queryForObject("SELECT count(*) FROM " + tabela, Long.class); }

    @TestConfiguration
    static class ConfiguracaoDeTeste {
        @Bean @Primary Clock clockDeTeste() { return Clock.fixed(INSTANTE_DE_CRIACAO, ZoneOffset.UTC); }

    }

    static class SincronizacaoDeConcorrencia {
        private volatile UUID eventoDaGuarda;
        private volatile CyclicBarrier aposVerificacao;
        private volatile UUID consultaRetidaNoUpsert;
        private volatile CountDownLatch upsertExecutado = new CountDownLatch(0);
        private volatile CountDownLatch liberarUpsert = new CountDownLatch(0);
        private final AtomicInteger chamadasAoUpsert = new AtomicInteger();

        void disputarAposVerificacao(UUID eventId) {
            eventoDaGuarda = eventId;
            aposVerificacao = new CyclicBarrier(2);
        }

        void reterNoUpsert(UUID consultaId) {
            consultaRetidaNoUpsert = consultaId;
            upsertExecutado = new CountDownLatch(1);
            liberarUpsert = new CountDownLatch(1);
            chamadasAoUpsert.set(0);
        }

        void aguardarUpsert() throws InterruptedException {
            if (!upsertExecutado.await(10, TimeUnit.SECONDS)) throw new AssertionError("upsert não alcançado");
        }

        void liberarUpsert() { liberarUpsert.countDown(); }

        void desarmar() {
            eventoDaGuarda = null;
            aposVerificacao = null;
            consultaRetidaNoUpsert = null;
            liberarUpsert.countDown();
            upsertExecutado = new CountDownLatch(0);
            liberarUpsert = new CountDownLatch(0);
            chamadasAoUpsert.set(0);
        }

        void aposConsultaDeIdempotencia(UUID eventId, boolean jaProcessado) {
            var barreira = aposVerificacao;
            if (!jaProcessado && eventId.equals(eventoDaGuarda) && barreira != null) aguardar(barreira);
        }

        Object executarUpsert(UUID consultaId, Executavel executavel) throws Throwable {
            if (!consultaId.equals(consultaRetidaNoUpsert)) return executavel.executar();
            if (chamadasAoUpsert.incrementAndGet() != 1) {
                return executavel.executar();
            }
            Object resultado = executavel.executar();
            upsertExecutado.countDown();
            try {
                if (!liberarUpsert.await(10, TimeUnit.SECONDS)) throw new AssertionError("upsert não liberado");
            } catch (InterruptedException excecao) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(excecao);
            }
            return resultado;
        }

        private void aguardar(CyclicBarrier barreira) {
            try { barreira.await(10, TimeUnit.SECONDS); }
            catch (Exception excecao) { throw new IllegalStateException(excecao); }
        }

        @FunctionalInterface
        interface Executavel { Object executar() throws Throwable; }
    }
}
