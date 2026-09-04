package br.com.fiap.hospital.contracts;

import static br.com.fiap.hospital.contracts.MensagemInvalidaException.Motivo.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.*;
import java.util.*;

/** Codec exclusivo do contrato, sem alterar o ObjectMapper da API HTTP. */
public class EventoJson {
    private final ObjectMapper mapper = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
            .build();
    private static final TypeReference<EventoEnvelope<ConsultaPayload>> TIPO = new TypeReference<>() {};

    public String escrever(EventoEnvelope<ConsultaPayload> evento) {
        try {
            String json = mapper.writeValueAsString(evento);
            ler(json); // Tambem recusa um evento inconsistente produzido internamente.
            return json;
        } catch (JsonProcessingException e) {
            throw new MensagemInvalidaException(JSON, "envelope");
        }
    }

    public EventoEnvelope<ConsultaPayload> ler(String json) {
        try {
            JsonNode n = mapper.readTree(json);
            objeto(n, "envelope", Set.of("eventId","eventType","aggregateId","occurredAt","version","correlationId","payload"), Set.of());
            uuid(n, "eventId"); uuid(n, "aggregateId");
            TipoEvento tipo = enumeracao(n, "eventType", TipoEvento.class);
            inteiro(n, "version");
            exigir(n.get("version").intValue() == 1, VERSAO, "version");
            String ocorrido = texto(n,"occurredAt");
            OffsetDateTime ocorridoEm = data(ocorrido,"occurredAt");
            exigir(ocorridoEm.getOffset().equals(ZoneOffset.UTC), CAMPO, "occurredAt");
            texto(n,"correlationId");
            JsonNode p = n.get("payload");
            objeto(p, "payload", Set.of("consultaId","status","dataHora","duracaoMinutos","observacoes",
                    "motivoCancelamento","paciente","medico","registradoPor"), Set.of("alteracoes"));
            uuid(p,"consultaId");
            exigir(n.get("aggregateId").equals(p.get("consultaId")), CAMPO,"aggregateId");
            ConsultaPayload.Status status = enumeracao(p,"status",ConsultaPayload.Status.class);
            data(texto(p,"dataHora"),"dataHora");
            inteiro(p,"duracaoMinutos");
            exigir(p.get("duracaoMinutos").intValue()>0, CAMPO,"duracaoMinutos");
            textoNulo(p,"observacoes"); textoNulo(p,"motivoCancelamento");
            if(status == ConsultaPayload.Status.CANCELADA) texto(p,"motivoCancelamento");
            Set<ConsultaPayload.Status> estados = switch(tipo) {
                case CONSULTA_CRIADA -> Set.of(ConsultaPayload.Status.AGENDADA);
                case CONSULTA_ATUALIZADA -> Set.of(ConsultaPayload.Status.AGENDADA,ConsultaPayload.Status.CONFIRMADA);
                case CONSULTA_CONFIRMADA -> Set.of(ConsultaPayload.Status.CONFIRMADA);
                case CONSULTA_CANCELADA -> Set.of(ConsultaPayload.Status.CANCELADA);
                case CONSULTA_REALIZADA -> Set.of(ConsultaPayload.Status.REALIZADA);
            };
            exigir(estados.contains(status), CAMPO,"status");
            JsonNode paciente = p.get("paciente");
            objeto(paciente,"paciente",Set.of("id","nome","email","telefone"),Set.of());
            uuid(paciente,"id"); texto(paciente,"nome"); texto(paciente,"email"); textoNulo(paciente,"telefone");
            JsonNode medico = p.get("medico");
            objeto(medico,"medico",Set.of("id","nome","crm","especialidade"),Set.of());
            uuid(medico,"id"); texto(medico,"nome"); texto(medico,"crm"); texto(medico,"especialidade");
            JsonNode registrante = p.get("registradoPor");
            objeto(registrante,"registradoPor",Set.of("id","nome","perfil"),Set.of());
            uuid(registrante,"id"); texto(registrante,"nome"); enumeracao(registrante,"perfil",ConsultaPayload.Perfil.class);
            if(tipo == TipoEvento.CONSULTA_ATUALIZADA) {
                JsonNode a = p.get("alteracoes");
                objeto(a,"alteracoes",Set.of(),Set.of("dataHoraAnterior","duracaoMinutosAnterior","medicoIdAnterior","observacoesAnterior"));
                if(a.has("dataHoraAnterior")) data(texto(a,"dataHoraAnterior"),"dataHoraAnterior");
                if(a.has("duracaoMinutosAnterior")) { inteiro(a,"duracaoMinutosAnterior"); exigir(a.get("duracaoMinutosAnterior").intValue()>0,CAMPO,"duracaoMinutosAnterior"); }
                if(a.has("medicoIdAnterior")) uuid(a,"medicoIdAnterior");
                if(a.has("observacoesAnterior")) textoNulo(a,"observacoesAnterior");
            } else exigir(!p.has("alteracoes"), CAMPO,"alteracoes");
            return mapper.readValue(json, TIPO);
        } catch (JsonProcessingException e) {
            throw new MensagemInvalidaException(JSON, "envelope");
        }
    }
    private static void objeto(JsonNode n,String caminho,Set<String> obrigatorios,Set<String> opcionais) {
        exigir(n != null && n.isObject(), ESTRUTURA,caminho);
        for(String c:obrigatorios) exigir(n.has(c),CAMPO,caminho+"."+c);
        n.fieldNames().forEachRemaining(c -> exigir(obrigatorios.contains(c)||opcionais.contains(c),CAMPO,caminho+"."+c));
    }
    private static String texto(JsonNode n,String c) {
        exigir(n.has(c)&&n.get(c).isTextual()&&!n.get(c).textValue().isBlank(),CAMPO,c);
        return n.get(c).textValue();
    }
    private static void textoNulo(JsonNode n,String c) {
        exigir(n.has(c)&&(n.get(c).isNull()||n.get(c).isTextual()),CAMPO,c);
    }
    private static void inteiro(JsonNode n,String c) {
        exigir(n.has(c)&&n.get(c).isIntegralNumber()&&n.get(c).canConvertToInt(),CAMPO,c);
    }
    private static UUID uuid(JsonNode n,String c) {
        String valor=texto(n,c);
        try {
            UUID id=UUID.fromString(valor);
            exigir(id.toString().equalsIgnoreCase(valor),CAMPO,c);
            return id;
        } catch(IllegalArgumentException e) { throw new MensagemInvalidaException(CAMPO,c); }
    }
    private static OffsetDateTime data(String valor,String c) {
        try { return OffsetDateTime.parse(valor); }
        catch(DateTimeException e) { throw new MensagemInvalidaException(CAMPO,c); }
    }
    private static <E extends Enum<E>> E enumeracao(JsonNode n,String c,Class<E> tipo) {
        String valor=texto(n,c);
        try { return Enum.valueOf(tipo,valor); }
        catch(IllegalArgumentException e) { throw new MensagemInvalidaException(CAMPO,c); }
    }
    private static void exigir(boolean condicao,MensagemInvalidaException.Motivo motivo,String campo) {
        if(!condicao) throw new MensagemInvalidaException(motivo,campo);
    }
}

