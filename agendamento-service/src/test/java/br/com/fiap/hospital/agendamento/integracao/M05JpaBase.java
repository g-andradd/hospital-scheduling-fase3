package br.com.fiap.hospital.agendamento.integracao;

import br.com.fiap.hospital.agendamento.application.*;
import br.com.fiap.hospital.agendamento.domain.*;
import br.com.fiap.hospital.agendamento.domain.port.*;
import br.com.fiap.hospital.agendamento.infrastructure.messaging.*;
import br.com.fiap.hospital.agendamento.infrastructure.persistence.*;
import br.com.fiap.hospital.agendamento.infrastructure.persistence.mapper.*;
import br.com.fiap.hospital.agendamento.infrastructure.persistence.entity.*;
import br.com.fiap.hospital.agendamento.infrastructure.persistence.repository.*;
import br.com.fiap.hospital.agendamento.infrastructure.transacao.*;
import br.com.fiap.hospital.contracts.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;

@DataJpaTest(showSql=false,properties={"logging.level.root=ERROR","spring.jpa.open-in-view=false"})
@AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation=Propagation.NOT_SUPPORTED)
@Import({M05JpaBase.Montagem.class,ConsultaRepositoryAdapter.class,ConsultaMapper.class,
    UsuarioRepositoryAdapter.class,UsuarioMapper.class,OutboxRepository.class,OutboxEventPublisher.class,EventoJson.class})
abstract class M05JpaBase {
    static final Clock RELOGIO=Clock.fixed(Instant.parse("2026-09-04T12:00:00Z"),ZoneId.of("America/Sao_Paulo"));
    static final OffsetDateTime AGORA=OffsetDateTime.now(RELOGIO);
    static final OffsetDateTime INICIO=OffsetDateTime.parse("2026-10-05T14:00:00-03:00");
    @DynamicPropertySource static void propriedades(DynamicPropertyRegistry r) {
        ContainerPostgres.registrarPropriedadesEmEsquema(r,"m05_jpa");
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired ConsultaRepositoryPort consultas;
    @Autowired ConsultaComBarreira barreira;
    @Autowired FalhaDePublicacao falha;
    @Autowired OutboxEventPublisher publisher;
    @Autowired OutboxRepository outbox;
    @Autowired EventoJson json;
    @Autowired AgendarConsultaUseCaseTransacional agendar;
    @Autowired AtualizarConsultaUseCaseTransacional atualizar;
    @Autowired ConfirmarConsultaUseCaseTransacional confirmar;
    @Autowired CancelarConsultaUseCaseTransacional cancelar;
    @Autowired UsuarioJpaRepository usuarios;
    @Autowired PacienteJpaRepository pacientes;
    @Autowired MedicoJpaRepository medicos;
    UUID paciente,paciente2,medico,medico2,registrante;
    @BeforeEach void prepararM05() {
        falha.ponto="nenhum"; barreira.desarmar();
        jdbc.execute("TRUNCATE consulta,outbox_evento,paciente,medico,usuario CASCADE");
        paciente=paciente("Maria","maria@hospital.com","52998224725");
        paciente2=paciente("José","jose@hospital.com","11144477735");
        medico=medico("João","joao@hospital.com","SP-12345");
        medico2=medico("Clara","clara@hospital.com","SP-54321");
        registrante=usuario("Ana","ana@hospital.com",PerfilUsuario.ENFERMEIRO).getId();
    }
    UsuarioEntity usuario(String nome,String email,PerfilUsuario perfil) {
        return usuarios.save(new UsuarioEntity(UUID.randomUUID(),nome,email,"hash-nao-publicavel",perfil,true,AGORA));
    }
    UUID paciente(String nome,String email,String cpf) {
        return pacientes.save(new PacienteEntity(UUID.randomUUID(),usuario(nome,email,PerfilUsuario.PACIENTE),
                cpf,LocalDate.of(1990,1,1),null)).getId();
    }
    UUID medico(String nome,String email,String crm) {
        return medicos.save(new MedicoEntity(UUID.randomUUID(),usuario(nome,email,PerfilUsuario.MEDICO),crm,"Cardiologia")).getId();
    }
    AgendarConsultaCommand comando(UUID p,UUID m,OffsetDateTime data) {
        return new AgendarConsultaCommand(p,m,registrante,data,30,null);
    }
    ConsultaResumo criar() { return agendar.executar(comando(paciente,medico,INICIO)); }
    List<EventoEnvelope<ConsultaPayload>> eventos() {
        return jdbc.query("SELECT payload::text FROM outbox_evento ORDER BY criado_em,id",(r,n)->json.ler(r.getString(1)));
    }
    void contagens(int quantidadeConsultas,int quantidadeEventos) {
        tx.executeWithoutResult(s->{
            org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("SELECT count(*) FROM consulta",Integer.class)).isEqualTo(quantidadeConsultas);
            org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_evento",Integer.class)).isEqualTo(quantidadeEventos);
        });
    }
    @TestConfiguration
    static class Montagem {
        @Bean Clock clockM05(){return RELOGIO;}
        @Bean TransactionTemplate transacaoM05(PlatformTransactionManager m){return new TransactionTemplate(m);}
        @Bean @Primary ConsultaComBarreira consultasComBarreira(ConsultaRepositoryAdapter real){return new ConsultaComBarreira(real);}
        @Bean @Primary FalhaDePublicacao publicacaoComFalha(OutboxEventPublisher real){return new FalhaDePublicacao(real);}
        @Bean AgendarConsultaUseCaseTransacional agendarM05(ConsultaRepositoryPort c,UsuarioRepositoryPort u,EventPublisherPort e,Clock clock){
            return new AgendarConsultaUseCaseTransacional(new AgendarConsultaUseCase(c,u,e,clock));}
        @Bean AtualizarConsultaUseCaseTransacional atualizarM05(ConsultaRepositoryPort c,UsuarioRepositoryPort u,EventPublisherPort e,Clock clock){
            return new AtualizarConsultaUseCaseTransacional(new AtualizarConsultaUseCase(c,u,e,clock));}
        @Bean ConfirmarConsultaUseCaseTransacional confirmarM05(ConsultaRepositoryPort c,EventPublisherPort e,Clock clock){
            return new ConfirmarConsultaUseCaseTransacional(new ConfirmarConsultaUseCase(c,e,clock));}
        @Bean CancelarConsultaUseCaseTransacional cancelarM05(ConsultaRepositoryPort c,EventPublisherPort e,Clock clock){
            return new CancelarConsultaUseCaseTransacional(new CancelarConsultaUseCase(c,e,clock));}
    }
    static class FalhaDePublicacao implements EventPublisherPort {
        final OutboxEventPublisher real; volatile String ponto="nenhum";
        FalhaDePublicacao(OutboxEventPublisher r){real=r;}
        public void publicar(EventoDeConsulta evento) {
            if(ponto.equals("antes")) throw new FalhaInjetada();
            real.publicar(evento);
            if(ponto.equals("depois")) throw new FalhaInjetada();
        }
    }
    static class FalhaInjetada extends RuntimeException {}
    /** Instrumentação: todas as consultas/escritas continuam no adaptador e no banco reais. */
    public static class ConsultaComBarreira implements ConsultaRepositoryPort {
        final ConsultaRepositoryPort real;
        volatile CyclicBarrier ponto;
        final ThreadLocal<Integer> leituras=ThreadLocal.withInitial(()->0);
        public ConsultaComBarreira(ConsultaRepositoryPort r){real=r;}
        void armar(){ponto=new CyclicBarrier(2);}
        void desarmar(){ponto=null;leituras.remove();}
        public Consulta salvar(Consulta c) {
            if(ponto!=null) {
                org.assertj.core.api.Assertions.assertThat(leituras.get()).as("duas pré-queries reais nesta transação").isEqualTo(2);
                try {ponto.await(10,TimeUnit.SECONDS);} catch(Exception e){throw new IllegalStateException(e);}
            }
            leituras.remove();
            return real.salvar(c);
        }
        public Optional<Consulta> buscarPorId(UUID id){return real.buscarPorId(id);}
        public List<Consulta> buscarAtivasDoMedicoNoPeriodo(UUID id,PeriodoConsulta p){
            var resultado=real.buscarAtivasDoMedicoNoPeriodo(id,p);contarLeitura();return resultado;}
        public List<Consulta> buscarAtivasDoPacienteNoPeriodo(UUID id,PeriodoConsulta p){
            var resultado=real.buscarAtivasDoPacienteNoPeriodo(id,p);contarLeitura();return resultado;}
        private void contarLeitura() {
            if (leituras.get()==0 && org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
                org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override public void afterCompletion(int status) {leituras.remove();}
                    });
            }
            leituras.set(leituras.get()+1);
        }
        public Pagina<Consulta> listar(FiltroDeConsultas f){return real.listar(f);}
    }
}
