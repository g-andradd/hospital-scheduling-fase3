package br.com.fiap.hospital.agendamento.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * Quem esta pedindo a operacao.
 *
 * <p>E parametro obrigatorio dos casos de uso que expoem dados de consulta, e essa e a
 * garantia: um caso de uso novo que exponha consulta <b>nao compila</b> sem receber o
 * solicitante. Comparado a uma anotacao esquecida, que passa aberta e em silencio, a
 * diferenca e entre erro de compilacao e vazamento.
 *
 * <p>Sem tipo de framework. O contexto de seguranca e lido na borda web, que constroi
 * este record.
 *
 * @param pacienteId identificador de paciente, quando o perfil for PACIENTE
 */
public record SolicitanteAutenticado(
        UUID usuarioId, PerfilUsuario perfil, UUID pacienteId) {

    public SolicitanteAutenticado {
        Objects.requireNonNull(usuarioId, "O usuario solicitante e obrigatorio");
        Objects.requireNonNull(perfil, "O perfil do solicitante e obrigatorio");
    }

    /** Perfil sujeito a regra de propriedade. */
    public boolean ePaciente() {
        return perfil == PerfilUsuario.PACIENTE;
    }

    /** Responde se o solicitante e o titular da consulta informada. */
    public boolean eTitularDe(Consulta consulta) {
        return pacienteId != null && pacienteId.equals(consulta.pacienteId());
    }
}
