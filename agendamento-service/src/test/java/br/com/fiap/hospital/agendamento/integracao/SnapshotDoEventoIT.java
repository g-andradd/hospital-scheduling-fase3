package br.com.fiap.hospital.agendamento.integracao;

import static org.assertj.core.api.Assertions.*;
import br.com.fiap.hospital.agendamento.application.*;
import br.com.fiap.hospital.agendamento.domain.*;
import br.com.fiap.hospital.contracts.*;
import java.util.*;
import org.junit.jupiter.api.*;

class SnapshotDoEventoIT extends M05JpaBase {
    @Test @DisplayName("Snapshot de criação é autossuficiente")
    
    // Scenario: Snapshot de criação é autossuficiente
    void criacaoCompletaSemDadosPrivados() {
        var c=criar();var e=eventos().getFirst();var p=e.payload();
        assertThat(e.aggregateId()).isEqualTo(c.id());
        assertThat(p.consultaId()).isEqualTo(c.id());
        assertThat(p.paciente()).isEqualTo(new ConsultaPayload.Paciente(paciente,"Maria","maria@hospital.com",null));
        assertThat(p.medico()).isEqualTo(new ConsultaPayload.Medico(medico,"João","SP-12345","Cardiologia"));
        assertThat(p.registradoPor()).isEqualTo(new ConsultaPayload.Registrante(registrante,"Ana",ConsultaPayload.Perfil.ENFERMEIRO));
        assertThat(p.observacoes()).isNull();assertThat(p.motivoCancelamento()).isNull();assertThat(p.alteracoes()).isNull();
        assertThat(json.escrever(e)).doesNotContain("senha","hash","cpf","52998224725","hash-nao-publicavel");
    }
    @Test @DisplayName("Atualização inclui apenas valores anteriores alterados")
    
    // Scenario: Atualização inclui apenas valores anteriores alterados
    void alteracoesCapturamAntesDaMutacao() {
        var c=criar();
        atualizar.executar(new AtualizarConsultaCommand(c.id(),INICIO.plusDays(1),45,medico2,"nova observação"));
        var e=evento(TipoEvento.CONSULTA_ATUALIZADA);var a=e.payload().alteracoes();
        assertThat(a).containsOnlyKeys("dataHoraAnterior","duracaoMinutosAnterior","medicoIdAnterior","observacoesAnterior");
        assertThat(java.time.OffsetDateTime.parse(a.get("dataHoraAnterior").toString())).isEqualTo(INICIO);
        assertThat(a.get("duracaoMinutosAnterior")).isEqualTo(30);
        assertThat(a.get("medicoIdAnterior").toString()).isEqualTo(medico.toString());
        assertThat(a).containsEntry("observacoesAnterior",null);
        assertThat(e.payload().medico().nome()).isEqualTo("Clara");
        assertThat(e.payload().dataHora().toInstant()).isEqualTo(INICIO.plusDays(1).toInstant());
        jdbc.update("DELETE FROM outbox_evento WHERE tipo_evento='CONSULTA_ATUALIZADA'");
        atualizar.executar(new AtualizarConsultaCommand(c.id(),null,null,null,"limpa"));
        assertThat(evento(TipoEvento.CONSULTA_ATUALIZADA).payload().alteracoes()).containsOnlyKeys("observacoesAnterior").containsEntry("observacoesAnterior","nova observação");
    }
    @Test @DisplayName("Atualização sem diferença preserva o contrato")
    
    // Scenario: Atualização sem diferença preserva o contrato
    void semDiferencaEOffsetEquivalente() {
        var c=criar();
        atualizar.executar(new AtualizarConsultaCommand(c.id(),INICIO.withOffsetSameInstant(java.time.ZoneOffset.ofHours(5)),null,null,null));
        assertThat(evento(TipoEvento.CONSULTA_ATUALIZADA).payload().alteracoes()).isEmpty();
    }
    @Test @DisplayName("Mudanças de status têm snapshot posterior")
    
    // Scenario: Mudanças de status têm snapshot posterior
    void statusConfirmadaCanceladaERealizada() {
        var c=criar();
        confirmar.executar(c.id(),new SolicitanteAutenticado(registrante,PerfilUsuario.ENFERMEIRO,null));
        assertThat(evento(TipoEvento.CONSULTA_CONFIRMADA).payload().status()).isEqualTo(ConsultaPayload.Status.CONFIRMADA);
        cancelar.executar(new CancelarConsultaCommand(c.id(),"Solicitação"));
        assertThat(evento(TipoEvento.CONSULTA_CANCELADA).payload().motivoCancelamento()).isEqualTo("Solicitação");
        tx.executeWithoutResult(s-> {
            var consulta=Consulta.reconstituir(UUID.randomUUID(),paciente,medico,registrante,
                    new PeriodoConsulta(AGORA.minusDays(1),30),StatusConsulta.CONFIRMADA,null,null,AGORA.minusDays(2),AGORA.minusDays(2));
            consulta.registrarRealizacao(AGORA);
            consultas.salvar(consulta);
            publisher.publicar(EventoDeConsulta.de(consulta,TipoEventoConsulta.REALIZADA));
        });
        var realizada=evento(TipoEvento.CONSULTA_REALIZADA);
        assertThat(realizada.payload().status()).isEqualTo(ConsultaPayload.Status.REALIZADA);
        assertThat(realizada.payload().alteracoes()).isNull();
    }
    @Test @DisplayName("Mudança posterior não reescreve o passado")
    
    // Scenario: Mudança posterior não reescreve o passado
    void snapshotPersistidoIndependeDosCadastros() {
        var c=criar();var antes=eventos().getFirst();
        jdbc.update("UPDATE usuario SET nome='Nome posterior',email=id||'@hospital.com'");
        jdbc.update("UPDATE medico SET especialidade='Outra'");
        atualizar.executar(new AtualizarConsultaCommand(c.id(),null,null,null,"posterior"));
        assertThat(evento(TipoEvento.CONSULTA_CRIADA)).isEqualTo(antes);
        assertThat(evento(TipoEvento.CONSULTA_ATUALIZADA).payload().paciente().nome()).isEqualTo("Nome posterior");
    }
    EventoEnvelope<ConsultaPayload> evento(TipoEvento tipo) {
        return eventos().stream().filter(e->e.eventType()==tipo).findFirst().orElseThrow();
    }
}
