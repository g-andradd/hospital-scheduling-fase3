package br.com.fiap.hospital.agendamento.contrato;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Compara o envelope que o produtor emitiu com o exemplar canonico do contrato.
 *
 * <p>A comparacao e estrita: mesmo conjunto de campos em todos os niveis, mesmo tipo de no e
 * mesmo valor — inclusive o texto de {@code dataHora}, e nao apenas o instante. As unicas
 * excecoes sao os campos que o produtor gera no momento do fato. Eles nao sao ignorados: cada
 * um e validado quanto ao formato e as relacoes que o contrato impoe, contra o que so o
 * produtor sabe (a consulta criada, o instante do fato e os headers enviados).
 */
public final class ComparacaoComFixture {

    /** O que so o produtor conhece: a consulta criada, o instante do fato e os headers. */
    public record Contexto(UUID consultaId, Instant ocorridoEm, String headerEventId,
                           String headerCorrelationId) {}

    /** Campos gerados na producao: validados por relacao, nao comparados por igualdade. */
    static final Set<String> GERADOS = Set.of(
            "eventId", "aggregateId", "payload.consultaId", "occurredAt", "correlationId");

    private ComparacaoComFixture() {}

    public static List<String> divergencias(JsonNode exemplar, JsonNode produzido,
                                            Contexto contexto) {
        List<String> divergencias = new ArrayList<>();
        comparar("", exemplar, produzido, divergencias);
        validarGerados(produzido, contexto, divergencias);
        return divergencias;
    }

    private static void comparar(String caminho, JsonNode esperado, JsonNode obtido,
                                 List<String> divergencias) {
        if (GERADOS.contains(caminho)) {
            if (!obtido.isTextual()) {
                divergencias.add("tipo divergente em " + caminho + ": esperado texto, obtido "
                        + obtido.getNodeType());
            }
            return;
        }
        if (esperado.getNodeType() != obtido.getNodeType()) {
            divergencias.add("tipo divergente em " + rotulo(caminho) + ": esperado "
                    + esperado.getNodeType() + ", obtido " + obtido.getNodeType());
            return;
        }
        if (esperado.isObject()) {
            Set<String> nomes = new TreeSet<>();
            esperado.fieldNames().forEachRemaining(nomes::add);
            obtido.fieldNames().forEachRemaining(nomes::add);
            for (String nome : nomes) {
                String filho = caminho.isEmpty() ? nome : caminho + "." + nome;
                if (!obtido.has(nome)) {
                    divergencias.add("campo ausente no produzido: " + filho);
                } else if (!esperado.has(nome)) {
                    divergencias.add("campo a mais no produzido: " + filho);
                } else {
                    comparar(filho, esperado.get(nome), obtido.get(nome), divergencias);
                }
            }
        } else if (esperado.isArray()) {
            if (esperado.size() != obtido.size()) {
                divergencias.add("tamanho divergente em " + rotulo(caminho) + ": esperado "
                        + esperado.size() + ", obtido " + obtido.size());
                return;
            }
            for (int i = 0; i < esperado.size(); i++) {
                comparar(caminho + "[" + i + "]", esperado.get(i), obtido.get(i), divergencias);
            }
        } else if (!esperado.equals(obtido)) {
            divergencias.add("valor divergente em " + rotulo(caminho) + ": esperado " + esperado
                    + ", obtido " + obtido);
        }
    }

    private static void validarGerados(JsonNode produzido, Contexto contexto,
                                       List<String> divergencias) {
        String eventId = texto(produzido, "eventId");
        if (!uuidCanonico(eventId)) {
            divergencias.add("eventId nao e UUID canonico: " + eventId);
        } else if (!eventId.equals(contexto.headerEventId())) {
            divergencias.add("eventId diverge do header x-event-id: " + eventId + " / "
                    + contexto.headerEventId());
        }

        String aggregateId = texto(produzido, "aggregateId");
        String consultaId = texto(produzido.path("payload"), "consultaId");
        if (!uuidCanonico(aggregateId)) {
            divergencias.add("aggregateId nao e UUID canonico: " + aggregateId);
        }
        if (!uuidCanonico(consultaId)) {
            divergencias.add("payload.consultaId nao e UUID canonico: " + consultaId);
        }
        if (aggregateId != null && !aggregateId.equals(consultaId)) {
            divergencias.add("aggregateId diverge de payload.consultaId: " + aggregateId + " / "
                    + consultaId);
        }
        if (consultaId != null && !consultaId.equals(String.valueOf(contexto.consultaId()))) {
            divergencias.add("payload.consultaId diverge da consulta criada: " + consultaId + " / "
                    + contexto.consultaId());
        }

        String occurredAt = texto(produzido, "occurredAt");
        if (occurredAt == null || !occurredAt.endsWith("Z")) {
            divergencias.add("occurredAt nao esta em UTC com Z: " + occurredAt);
        } else {
            try {
                if (!Instant.parse(occurredAt).equals(contexto.ocorridoEm())) {
                    divergencias.add("occurredAt diverge do instante do fato: " + occurredAt
                            + " / " + contexto.ocorridoEm());
                }
            } catch (DateTimeParseException e) {
                divergencias.add("occurredAt nao e ISO-8601: " + occurredAt);
            }
        }

        String correlationId = texto(produzido, "correlationId");
        if (correlationId == null || correlationId.isBlank()) {
            divergencias.add("correlationId vazio");
        } else if (!correlationId.equals(contexto.headerCorrelationId())) {
            divergencias.add("correlationId diverge do header x-correlation-id: " + correlationId
                    + " / " + contexto.headerCorrelationId());
        }
    }

    private static String texto(JsonNode no, String campo) {
        JsonNode valor = no.get(campo);
        return valor != null && valor.isTextual() ? valor.textValue() : null;
    }

    private static boolean uuidCanonico(String valor) {
        if (valor == null) {
            return false;
        }
        try {
            return UUID.fromString(valor).toString().equals(valor);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static String rotulo(String caminho) {
        return caminho.isEmpty() ? "(raiz)" : caminho;
    }
}
