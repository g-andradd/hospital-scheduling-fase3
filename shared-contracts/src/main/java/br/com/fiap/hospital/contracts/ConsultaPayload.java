package br.com.fiap.hospital.contracts;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public record ConsultaPayload(
        UUID consultaId, Status status, OffsetDateTime dataHora, int duracaoMinutos,
        String observacoes, String motivoCancelamento, Paciente paciente, Medico medico,
        Registrante registradoPor,
        @JsonInclude(JsonInclude.Include.NON_NULL) Map<String, Object> alteracoes) {
    public ConsultaPayload {
        // Map.copyOf recusa os valores anteriores nulos, que fazem parte do contrato.
        if (alteracoes != null) alteracoes = Collections.unmodifiableMap(new LinkedHashMap<>(alteracoes));
    }
    public enum Status { AGENDADA, CONFIRMADA, CANCELADA, REALIZADA }
    public enum Perfil { MEDICO, ENFERMEIRO, PACIENTE }
    public record Paciente(UUID id, String nome, String email, String telefone) {}
    public record Medico(UUID id, String nome, String crm, String especialidade) {}
    public record Registrante(UUID id, String nome, Perfil perfil) {}
}

