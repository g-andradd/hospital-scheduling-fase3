package br.com.fiap.hospital.agendamento.integracao;

import static org.assertj.core.api.Assertions.*;
import br.com.fiap.hospital.agendamento.application.*;
import br.com.fiap.hospital.agendamento.domain.*;
import br.com.fiap.hospital.agendamento.domain.exception.ConflitoDeAgendaException;
import java.util.List;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.IllegalTransactionStateException;

class AtomicidadeDoOutboxIT extends M05JpaBase {
    @Test @DisplayName("Operação aceita confirma consulta e evento juntos")
    
    // Scenario: Operação aceita confirma consulta e evento juntos
    void commitsDosQuatroCasosDeEscrita() {
        var c=criar(); contagens(1,1);
        atualizar.executar(new AtualizarConsultaCommand(c.id(),null,null,null,"nova")); contagens(1,2);
        confirmar.executar(c.id(),new SolicitanteAutenticado(registrante,PerfilUsuario.ENFERMEIRO,null)); contagens(1,3);
        cancelar.executar(new CancelarConsultaCommand(c.id(),"Solicitação")); contagens(1,4);
        assertThat(eventos()).extracting(e->e.eventType().name()).containsExactlyInAnyOrder(
                "CONSULTA_CRIADA","CONSULTA_ATUALIZADA","CONSULTA_CONFIRMADA","CONSULTA_CANCELADA");
    }
    @ParameterizedTest(name="Rollback antes/depois do outbox: {0}") @ValueSource(strings={"antes","depois","sql"})
    
    // Scenario: Falha depois de salvar consulta desfaz a escrita
    // Scenario: Falha depois de salvar outbox desfaz ambas as escritas
    void falhaDesfazCriacaoEAlteracao(String ponto) {
        falha.ponto=ponto;
        if(ponto.equals("sql")) jdbc.execute("ALTER TABLE outbox_evento ADD CONSTRAINT falha_teste CHECK (false)");
        try {assertThatThrownBy(this::criar).isInstanceOf(RuntimeException.class); contagens(0,0);}
        finally {if(ponto.equals("sql"))jdbc.execute("ALTER TABLE outbox_evento DROP CONSTRAINT falha_teste");}
        falha.ponto="nenhum";
        var c=criar();
        falha.ponto=ponto;
        if(ponto.equals("sql")) jdbc.execute("ALTER TABLE outbox_evento ADD CONSTRAINT falha_teste CHECK (false) NOT VALID");
        try {
            assertThatThrownBy(()->atualizar.executar(new AtualizarConsultaCommand(c.id(),null,null,null,"nao persistir"))).isInstanceOf(RuntimeException.class);
            contagens(1,1);
            tx.executeWithoutResult(s->assertThat(consultas.buscarPorId(c.id()).orElseThrow().observacoes()).isNull());
        } finally {if(ponto.equals("sql"))jdbc.execute("ALTER TABLE outbox_evento DROP CONSTRAINT falha_teste");}
    }
    @Test @DisplayName("Regra de negócio recusada não produz evento")
    
    // Scenario: Regra de negócio recusada não produz evento
    // Scenario: Conflito é detectado contra dados persistidos
    void conflitoNaoCriaEvento() {
        criar();
        assertThatThrownBy(()->agendar.executar(comando(paciente2,medico,INICIO))).isInstanceOf(ConflitoDeAgendaException.class);
        contagens(1,1);
    }
    @Test void publisherExigeTransacao() {
        assertThatThrownBy(()->publisher.publicar(null)).isInstanceOf(IllegalTransactionStateException.class);
    }
}
