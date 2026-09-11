package br.com.fiap.hospital.agendamento.integracao;

import static org.assertj.core.api.Assertions.*;
import br.com.fiap.hospital.agendamento.application.*;
import br.com.fiap.hospital.agendamento.domain.*;
import br.com.fiap.hospital.agendamento.domain.exception.ConflitoDeAgendaException;
import br.com.fiap.hospital.agendamento.domain.exception.AlteracaoConcorrenteException;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;

class ExclusaoDeAgendaIT extends M05JpaBase {
    @ParameterizedTest(name="Inserções concorrentes do mesmo {0} não confirmam juntas")
    @ValueSource(strings={"medico","paciente"})
    
    // Scenario: Inserções concorrentes do mesmo médico não confirmam juntas
    // Scenario: Inserções concorrentes do mesmo paciente não confirmam juntas
    void insercoesConcorrentes(String recurso) throws Exception {
        var a=comando(paciente,medico,INICIO);
        var b=comando(recurso.equals("paciente")?paciente:paciente2,recurso.equals("medico")?medico:medico2,INICIO.plusMinutes(10));
        var resultados=correr(()->agendar.executar(a),()->agendar.executar(b));
        exigirUmVencedor(resultados,recurso);
        contagens(1,1);
        assertThat(eventos().getFirst().aggregateId()).isEqualTo(((ConsultaResumo)resultados.stream().filter(ConsultaResumo.class::isInstance).findFirst().orElseThrow()).id());
    }
    @Test @DisplayName("Remarcações concorrentes não criam sobreposição")
    
    // Scenario: Remarcações concorrentes não criam sobreposição
    void remarcacoesConcorrentes() throws Exception {
        var a=criar();
        var b=agendar.executar(comando(paciente2,medico,INICIO.plusDays(1)));
        var resultados=correr(
            ()->atualizar.executar(new AtualizarConsultaCommand(a.id(),INICIO.plusDays(2),null,null,null)),
            ()->atualizar.executar(new AtualizarConsultaCommand(b.id(),INICIO.plusDays(2),null,null,null)));
        exigirUmVencedor(resultados,"medico"); contagens(2,3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM consulta WHERE data_hora=?",Integer.class,INICIO.plusDays(2))).isEqualTo(1);
    }
    @Test @DisplayName("Consultas concorrentes sem recurso em comum são aceitas")
    
    // Scenario: Consultas concorrentes sem recurso em comum são aceitas
    void concorrentesIndependentes() throws Exception {
        assertThat(correr(this::criar,()->agendar.executar(comando(paciente2,medico2,INICIO))))
                .allMatch(ConsultaResumo.class::isInstance);
        contagens(2,2);
    }
    @Test @DisplayName("Períodos adjacentes persistidos não são conflito")
    
    // Scenario: Períodos adjacentes persistidos não são conflito
    // Scenario: Consulta encerrada persistida não bloqueia a agenda
    void adjacenciaEEstadosTerminais() {
        var a=criar();
        agendar.executar(comando(paciente,medico,INICIO.plusMinutes(30)));
        cancelar.executar(new CancelarConsultaCommand(a.id(),"Cancelada"));
        criar();contagens(3,4);
        jdbc.update("UPDATE consulta SET status='REALIZADA' WHERE status='AGENDADA'");
        criar();contagens(4,5);
    }
    @Test @DisplayName("Escrita direta não contorna a exclusão")
    
    // Scenario: Escrita direta não contorna a exclusão
    // Scenario: Alteração de período mantém a exclusão coerente
    void escritaDiretaEAtualizacaoDerivamRange() {
        var c=criar();
        assertThatThrownBy(()->jdbc.update("""
            INSERT INTO consulta(id,paciente_id,medico_id,registrado_por_id,data_hora,duracao_minutos,status,criado_em,atualizado_em)
            SELECT gen_random_uuid(),paciente_id,medico_id,registrado_por_id,data_hora,30,'AGENDADA',criado_em,atualizado_em
            FROM consulta WHERE id=?
            """,c.id())).isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ex_consulta_");
        jdbc.update("UPDATE consulta SET periodo_ocupado='empty'::tstzrange WHERE id=?",c.id());
        assertThat(jdbc.queryForObject("SELECT isempty(periodo_ocupado) FROM consulta WHERE id=?",Boolean.class,c.id())).isFalse();
        jdbc.update("UPDATE consulta SET data_hora=?,duracao_minutos=75 WHERE id=?",INICIO.plusDays(1),c.id());
        assertThat(jdbc.queryForObject("SELECT extract(epoch FROM upper(periodo_ocupado)-lower(periodo_ocupado)) FROM consulta WHERE id=?",Long.class,c.id())).isEqualTo(4500);
        assertThat(jdbc.queryForObject("SELECT lower_inc(periodo_ocupado) AND NOT upper_inc(periodo_ocupado) FROM consulta WHERE id=?",Boolean.class,c.id())).isTrue();
        assertThat(jdbc.queryForList("SELECT condeferrable FROM pg_constraint WHERE conname IN ('ex_consulta_medico_periodo','ex_consulta_paciente_periodo') AND connamespace=current_schema()::regnamespace",Boolean.class)).containsExactly(false,false);
    }
    @Test void outrasViolacoesNaoViraramConflitoDeAgenda() {
        var c=criar();
        assertThatThrownBy(()->tx.executeWithoutResult(s->{
            var original=consultas.buscarPorId(c.id()).orElseThrow();
            var semPaciente=Consulta.reconstituir(UUID.randomUUID(),UUID.randomUUID(),medico,registrante,
                new PeriodoConsulta(INICIO.plusDays(1),30),StatusConsulta.AGENDADA,null,null,AGORA,AGORA);
            consultas.salvar(semPaciente);
        })).isInstanceOf(DataIntegrityViolationException.class);
        contagens(1,1);
    }
    List<Object> correr(Supplier<?> a,Supplier<?> b) throws Exception {
        barreira.armar();
        try(var pool=Executors.newFixedThreadPool(2)) {
            var f1=pool.submit(()->resultado(a));var f2=pool.submit(()->resultado(b));
            return List.of(f1.get(20,TimeUnit.SECONDS),f2.get(20,TimeUnit.SECONDS));
        } finally {barreira.desarmar();}
    }
    private Object resultado(Supplier<?> tarefa) {try{return tarefa.get();}catch(RuntimeException e){return e;}}
    void exigirUmVencedor(List<Object> resultados,String recurso) {
        assertThat(resultados).filteredOn(ConsultaResumo.class::isInstance).hasSize(1);
        assertThat(resultados).as("resultados reais: %s", resultados).filteredOn(e -> e instanceof ConflitoDeAgendaException || e instanceof AlteracaoConcorrenteException).singleElement()
                .satisfies(e->{ if(e instanceof ConflitoDeAgendaException) assertThat(((Exception)e).getMessage().toLowerCase()).contains(recurso); });
    }

    @Test void serializationFailureEhAlteracaoConcorrenteSemRetry() throws Exception {
        var c=criar();
        var serial=new org.springframework.transaction.support.TransactionTemplate(tx.getTransactionManager());
        serial.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_SERIALIZABLE);
        assertThatThrownBy(()->serial.executeWithoutResult(s->{
            var anterior=consultas.buscarPorId(c.id()).orElseThrow();
            try(var pool=Executors.newSingleThreadExecutor()) {
                pool.submit(()->atualizar.executar(new AtualizarConsultaCommand(c.id(),null,null,null,"vencedora"))).get(10,TimeUnit.SECONDS);
            } catch(Exception e){throw new IllegalStateException(e);}
            anterior.atualizar(null,null,"perdedora",AGORA);
            consultas.salvar(anterior); // PostgreSQL real: snapshot serializável já foi superado
        })).isInstanceOf(AlteracaoConcorrenteException.class);
        contagens(1,2);
        assertThat(jdbc.queryForObject("SELECT observacoes FROM consulta WHERE id=?",String.class,c.id())).isEqualTo("vencedora");
    }

    @Test void retirarExclusoesQuebraAProvaConcorrente() throws Exception {
        var definicoes=jdbc.queryForMap("SELECT pg_get_constraintdef((SELECT oid FROM pg_constraint WHERE conname='ex_consulta_medico_periodo' AND connamespace=current_schema()::regnamespace)) AS medico, pg_get_constraintdef((SELECT oid FROM pg_constraint WHERE conname='ex_consulta_paciente_periodo' AND connamespace=current_schema()::regnamespace)) AS paciente");
        jdbc.execute("ALTER TABLE consulta DROP CONSTRAINT ex_consulta_medico_periodo, DROP CONSTRAINT ex_consulta_paciente_periodo");
        try {
            var r=correr(this::criar,()->agendar.executar(comando(paciente2,medico,INICIO)));
            assertThatThrownBy(()->exigirUmVencedor(r,"medico")).isInstanceOf(AssertionError.class);
            contagens(2,2);
        } finally {
            jdbc.execute("TRUNCATE consulta,outbox_evento");
            jdbc.execute("ALTER TABLE consulta ADD CONSTRAINT ex_consulta_medico_periodo "+definicoes.get("medico"));
            jdbc.execute("ALTER TABLE consulta ADD CONSTRAINT ex_consulta_paciente_periodo "+definicoes.get("paciente"));
        }
    }
}
