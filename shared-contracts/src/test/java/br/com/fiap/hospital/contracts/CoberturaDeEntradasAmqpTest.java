package br.com.fiap.hospital.contracts;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.retry.policy.SimpleRetryPolicy;

class CoberturaDeEntradasAmqpTest {
    @Test void todoCampoDoRecordTemAtaques() {verificarCampos(EntradasAmqp.invalidas());}
    private void verificarCampos(List<EntradasAmqp.Caso> casos) {
        assertThat(EntradasAmqp.NULOS).as("exceções de nulidade do contrato normativo")
            .containsExactlyInAnyOrder("payload.observacoes","payload.motivoCancelamento","payload.paciente.telefone");
        var caminhos=new HashSet<String>();
        campos(EventoEnvelope.class,"",caminhos);
        assertThat(caminhos).hasSizeGreaterThan(20);
        for(String campo:caminhos) {
            assertThat(casos).as(campo+" ausente").anyMatch(c->c.campo().equals(campo)&&c.variacao().equals("ausente"));
            assertThat(casos).as(campo+" tipo").anyMatch(c->c.campo().equals(campo)&&c.variacao().equals("tipo"));
            if(!EntradasAmqp.NULOS.contains(campo))
                assertThat(casos).as(campo+" nulo").anyMatch(c->c.campo().equals(campo)&&c.variacao().equals("nulo"));
        }
    }
    private void campos(Class<?> tipo,String prefixo,Set<String> nomes) {
        for(var c:tipo.getRecordComponents()) {
            if(c.getName().equals("alteracoes")) continue; // mapa condicional, coberto separadamente
            String nome=prefixo+c.getName();nomes.add(nome);
            Class<?> interno=c.getName().equals("payload")?ConsultaPayload.class:c.getType();
            if(interno.isRecord())campos(interno,nome+".",nomes);
        }
    }
    @Test void todasAsFamiliasDeMensagemTemClassificacaoRetentavel() {
        assertThat(TipoEvento.values()).hasSize(5);
        assertThat(MensagemInvalidaException.Motivo.values()).hasSize(5);
        verificarClassificacao(MensageriaAutoConfiguration.politicaDeRetry(3));
    }
    private void verificarClassificacao(SimpleRetryPolicy policy) {
        for(var motivo:MensagemInvalidaException.Motivo.values()) {
            var ctx=policy.open(null);
            policy.registerThrowable(ctx,new RuntimeException(new MensagemInvalidaException(motivo,"campo")));
            assertThat(policy.canRetry(ctx)).as(motivo.name()).isTrue();
            policy.registerThrowable(ctx,new MensagemInvalidaException(motivo,"campo"));
            policy.registerThrowable(ctx,new MensagemInvalidaException(motivo,"campo"));
            assertThat(policy.canRetry(ctx)).isFalse();
        }
    }
    @Test void retirarCasoOuClassificacaoQuebraACobertura() {
        var incompleto=EntradasAmqp.invalidas().stream().filter(c->!(c.campo().equals("payload.paciente.nome")&&c.variacao().equals("ausente"))).toList();
        assertThatThrownBy(()->verificarCampos(incompleto)).isInstanceOf(AssertionError.class).hasMessageContaining("payload.paciente.nome ausente");
        var semClassificacao=new SimpleRetryPolicy(3,Map.of(MensagemInvalidaException.class,false),true);
        assertThatThrownBy(()->verificarClassificacao(semClassificacao)).isInstanceOf(AssertionError.class);
    }
}
