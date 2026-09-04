package br.com.fiap.hospital.contracts;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.amqp.core.*;

final class EntradasAmqp {
    static final ObjectMapper MAPPER=new ObjectMapper();
    static String fixture() {
        try(var in=EntradasAmqp.class.getResourceAsStream("/evento-consulta.json")) {
            return new String(Objects.requireNonNull(in).readAllBytes(),StandardCharsets.UTF_8);
        } catch(IOException e) { throw new UncheckedIOException(e); }
    }
    static ObjectNode arvore() {
        try { return (ObjectNode)MAPPER.readTree(fixture()); }
        catch(IOException e) { throw new UncheckedIOException(e); }
    }
    record Caso(String campo,String variacao,String corpo) {
        @Override public String toString() { return campo+":"+variacao; }
    }
    static List<Caso> invalidas() {
        List<Caso> casos=new ArrayList<>();
        percorrer(arvore(),"",casos);
        casos.add(new Caso("envelope","json","{"));
        casos.add(new Caso("envelope","null","null"));
        casos.add(new Caso("envelope","array","[]"));
        casos.add(new Caso("envelope","trailing",fixture()+" {}"));
        adicionar(casos,"version","desconhecida",IntNode.valueOf(2));
        adicionar(casos,"eventId","uuid",TextNode.valueOf("1-1-1-1-1"));
        adicionar(casos,"eventType","enum",TextNode.valueOf("NOVO_EVENTO"));
        adicionar(casos,"occurredAt","offset",TextNode.valueOf("2026-09-02T13:45:00-03:00"));
        adicionar(casos,"payload.dataHora","sem-offset",TextNode.valueOf("2026-09-10T14:00:00"));
        adicionar(casos,"payload.dataHora","data",TextNode.valueOf("invalida"));
        adicionar(casos,"payload.duracaoMinutos","negativo",IntNode.valueOf(-1));
        adicionar(casos,"payload.duracaoMinutos","overflow",LongNode.valueOf(2147483648L));
        adicionar(casos,"payload.duracaoMinutos","fracao",DoubleNode.valueOf(1.5));
        adicionar(casos,"payload.consultaId","divergente",TextNode.valueOf(UUID.randomUUID().toString()));
        adicionar(casos,"payload.status","incompativel",TextNode.valueOf("REALIZADA"));
        return casos;
    }
    static final Set<String> NULOS=Set.of("payload.observacoes","payload.motivoCancelamento","payload.paciente.telefone");
    static void percorrer(ObjectNode objeto,String prefixo,List<Caso> casos) {
        objeto.fields().forEachRemaining(e -> {
            String path=prefixo.isEmpty()?e.getKey():prefixo+"."+e.getKey();
            adicionar(casos,path,"ausente",null);
            if(!NULOS.contains(path)) adicionar(casos,path,"nulo",NullNode.instance);
            adicionar(casos,path,"tipo",ArrayNode.class.cast(MAPPER.createArrayNode()));
            if(e.getValue().isObject()) percorrer((ObjectNode)e.getValue(),path,casos);
        });
    }
    static void adicionar(List<Caso> casos,String path,String variante,JsonNode valor) {
        ObjectNode root=arvore(); String[] partes=path.split("\\.");
        ObjectNode parent=root;
        for(int i=0;i<partes.length-1;i++) parent=(ObjectNode)parent.get(partes[i]);
        if(valor==null) parent.remove(partes[partes.length-1]); else parent.set(partes[partes.length-1],valor);
        casos.add(new Caso(path,variante,root.toString()));
    }
    static Message mensagem(String body) {
        var properties=new MessageProperties();
        properties.setContentType("application/json");
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        properties.setHeader("x-event-id",arvore().get("eventId").textValue());
        properties.setHeader("x-event-type","CONSULTA_CRIADA");
        properties.setHeader("x-correlation-id","correlacao-fixture-m05");
        return new Message(body.getBytes(StandardCharsets.UTF_8),properties);
    }
}
