package br.com.fiap.hospital.contracts;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerEndpoint;
import org.springframework.amqp.rabbit.listener.adapter.MessageListenerAdapter;
import org.springframework.amqp.support.converter.MessageConverter;

class EntradasHostisAmqpIT extends RabbitITBase {
    @ParameterizedTest(name="Entradas hostis isoladas, consumidor preservado: {0}")
    @ValueSource(strings={"notificacao.consultas","historico.consultas"})
    
    // Scenario: Payload malformado chega à DLQ após três tentativas
    // Scenario: Campo obrigatório ausente ou inválido é isolado
    // Scenario: Versão desconhecida não alcança o processamento
    // Scenario: Metadados incompatíveis não alteram a desserialização
    void catalogoCompletoAteDlq(String fila) {
        Map<String,Message> casos=new LinkedHashMap<>();
        EntradasAmqp.invalidas().forEach(c->casos.put(c.toString(),EntradasAmqp.mensagem(c.corpo())));
        for(String header:List.of("x-event-id","x-event-type","x-correlation-id")) {
            var m=EntradasAmqp.mensagem(EntradasAmqp.fixture());
            m.getMessageProperties().getHeaders().remove(header);
            casos.put(header+" ausente",m);
            m=EntradasAmqp.mensagem(EntradasAmqp.fixture());
            m.getMessageProperties().setHeader(header,"divergente");
            casos.put(header+" divergente",m);
        }
        for(String header:List.of("__TypeId__","__ContentTypeId__","__KeyTypeId__")) {
            var m=EntradasAmqp.mensagem(EntradasAmqp.fixture());
            m.getMessageProperties().setHeader(header,"java.lang.Runtime");
            casos.put(header,m);
        }
        var tipo=EntradasAmqp.mensagem(EntradasAmqp.fixture());
        tipo.getMessageProperties().setContentType("application/x-java-serialized-object");
        casos.put("contentType",tipo);
        var utf8=EntradasAmqp.mensagem(EntradasAmqp.fixture());
        byte[] bytes=utf8.getBody().clone();
        int posicaoNome=EntradasAmqp.fixture().indexOf("\"nome\"");
        int inicio=EntradasAmqp.fixture().indexOf('"',EntradasAmqp.fixture().indexOf(':',posicaoNome)+1)+1;
        bytes[inicio]=(byte)0x80;
        casos.put("utf8 malformado",new Message(bytes,utf8.getMessageProperties()));
        var probe=new Probe(converter);
        var efeito=new ListenerDeFixture();
        var container=container(fila,probe,efeito,8);
        try {
            casos.forEach((nome,msg)->{ msg.getMessageProperties().setHeader("teste-caso",nome); rabbit.send(X,"consulta.criada",msg); });
            await().atMost(Duration.ofSeconds(150)).untilAsserted(()-> {
                assertThat(admin.getQueueInfo(N+".dlq").getMessageCount()).isEqualTo(casos.size());
                assertThat(admin.getQueueInfo(H+".dlq").getMessageCount()).isEqualTo(casos.size());
            });
            assertThat(efeito.chamadas.get()).isZero();
            for(String dlq:List.of(N+".dlq",H+".dlq")) {
                Set<String> recebidos=new HashSet<>();
                for(int i=0;i<casos.size();i++) {
                    var m=receber(dlq);
                    String nome=m.getMessageProperties().getHeader("teste-caso");
                    assertThat(recebidos.add(nome)).as(nome+" duplicado").isTrue();
                    assertThat(m.getBody()).as(nome).isEqualTo(casos.get(nome).getBody());
                    assertThat(m.getMessageProperties().getXDeathHeader()).isNotEmpty();
                }
                assertThat(recebidos).containsExactlyInAnyOrderElementsOf(casos.keySet());
            }
            casos.keySet().forEach(nome->{
                var instantes=probe.instantes.get(nome);
                assertThat(instantes).as(nome).hasSize(3);
                assertThat((instantes.get(1)-instantes.get(0))/1e9).as(nome+" pausa 1s").isBetween(0.8,15.0);
                assertThat((instantes.get(2)-instantes.get(1))/1e9).as(nome+" pausa 2s").isBetween(1.7,15.0);
            });
            rabbit.send(X,"consulta.criada",EntradasAmqp.mensagem(EntradasAmqp.fixture()));
            await().atMost(Duration.ofSeconds(10)).untilAsserted(()->assertThat(efeito.sucessos.get()).isEqualTo(1));
            assertThat(container.isRunning()).isTrue();
            assertThat(rabbit.receive(fila+".dlq")).isNull();
        } finally { container.stop(); }
    }
    @ParameterizedTest(name="Recuperação na terceira tentativa e falha persistente: {0}")
    @ValueSource(strings={"notificacao.consultas","historico.consultas"})
    
    // Scenario: Falha transitória recupera dentro do limite
    // Scenario: Falha persistente não derruba o consumidor
    void falhasDoListener(String fila) {
        for(int falhas:List.of(2,Integer.MAX_VALUE)) {
    var probe=new Probe(converter);
            var efeito=new ListenerDeFixture(); efeito.falhas=falhas;
            var container=container(fila,probe,efeito,1);
            try {
                var msg=EntradasAmqp.mensagem(EntradasAmqp.fixture());
                msg.getMessageProperties().setHeader("teste-caso","listener");
                rabbit.send(X,"consulta.criada",msg);
                await().atMost(Duration.ofSeconds(15)).untilAsserted(()->assertThat(efeito.chamadas.get()).isEqualTo(3));
                if(falhas==2) {
                    assertThat(efeito.sucessos.get()).isEqualTo(1);
                    assertThat(rabbit.receive(N+".dlq")).isNull();
                    assertThat(rabbit.receive(H+".dlq")).isNull();
                } else {
                    assertThat(receber(N+".dlq").getBody()).isEqualTo(msg.getBody());
                    assertThat(receber(H+".dlq").getBody()).isEqualTo(msg.getBody());
                    assertThat(efeito.sucessos.get()).isZero();
                    efeito.falhas=0;
                    rabbit.send(X,"consulta.criada",EntradasAmqp.mensagem(EntradasAmqp.fixture()));
                    await().atMost(Duration.ofSeconds(10)).untilAsserted(()->assertThat(efeito.sucessos.get()).isEqualTo(1));
                }
                assertThat(probe.instantes.get("listener")).hasSize(3);
                assertThat(container.isRunning()).isTrue();
            } finally { container.stop(); }
        }
    }
    private org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer container(
            String fila,Probe probe,ListenerDeFixture listener,int concorrencia) {
        var adapter=new MessageListenerAdapter(listener,probe);
        adapter.setDefaultListenerMethod("consumir");
        var endpoint=new SimpleRabbitListenerEndpoint();
        endpoint.setId("fixture-"+UUID.randomUUID());
        endpoint.setQueueNames(fila); endpoint.setMessageListener(adapter);
        var container=factory.createListenerContainer(endpoint);
        container.setConcurrentConsumers(concorrencia); container.setPrefetchCount(1);
        container.start();
        return container;
    }
    static class Probe implements MessageConverter {
        final MessageConverter real;
        final Map<String,List<Long>> instantes=new ConcurrentHashMap<>();
        Probe(MessageConverter real){this.real=real;}
        public Object fromMessage(Message m) {
            String nome=m.getMessageProperties().getHeader("teste-caso");
            if(nome!=null) instantes.computeIfAbsent(nome,k->new CopyOnWriteArrayList<>()).add(System.nanoTime());
            return real.fromMessage(m);
        }
        public Message toMessage(Object o,MessageProperties p){return real.toMessage(o,p);}
    }
    public static class ListenerDeFixture {
        final AtomicInteger chamadas=new AtomicInteger();
        final AtomicInteger sucessos=new AtomicInteger();
        volatile int falhas;
        public void consumir(EventoEnvelope<?> e) {
            if(chamadas.incrementAndGet()<=falhas) throw new IllegalStateException("Falha controlada de processamento");
            sucessos.incrementAndGet();
        }
    }
}
