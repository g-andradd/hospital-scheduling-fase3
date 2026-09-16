package br.com.fiap.hospital.historico.infrastructure.graphql;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Entrada da correcao manual, com a presenca de cada campo preservada.
 *
 * <p>{@code consultaId} seleciona o alvo e nao tem contraparte corrigivel; nao ha campo de
 * autor, de {@code pacienteId} nem de {@code medicoId}. Isso e contrato de schema, e nao
 * defesa em tempo de execucao: um campo desses enviado pelo cliente e recusado na analise
 * do documento, antes de qualquer resolver rodar. Descrever esses campos como "ignorados"
 * sugeriria um codigo de defesa que nao existe nem precisa existir.
 *
 * <p>A entrada e montada do mapa bruto, e nao ligada campo a campo, porque <b>ausente e
 * nulo significam coisas diferentes</b>: ausente e "nao corrigir"; nulo so vale em
 * {@code observacoes}, onde limpa o registro. Um record ligado diretamente entregaria
 * {@code null} nos dois casos e apagaria a distincao.
 */
public record CorrigirRegistroHistoricoInput(
        UUID consultaId,
        String justificativa,
        String pacienteNome,
        String medicoNome,
        String especialidade,
        OffsetDateTime dataHora,
        String status,
        String observacoes,
        Set<String> presentes) {

    public static final String PACIENTE_NOME = "pacienteNome";
    public static final String MEDICO_NOME = "medicoNome";
    public static final String ESPECIALIDADE = "especialidade";
    public static final String DATA_HORA = "dataHora";
    public static final String STATUS = "status";
    public static final String OBSERVACOES = "observacoes";

    /** Os campos que a correcao aceita alterar. A identidade do registro nao esta aqui. */
    public static final Set<String> CORRIGIVEIS =
            Set.of(PACIENTE_NOME, MEDICO_NOME, ESPECIALIDADE, DATA_HORA, STATUS, OBSERVACOES);

    public CorrigirRegistroHistoricoInput {
        presentes = presentes == null ? Set.of() : Set.copyOf(presentes);
    }

    static CorrigirRegistroHistoricoInput de(Map<String, Object> bruto) {
        return new CorrigirRegistroHistoricoInput(
                bruto.get("consultaId") == null ? null : UUID.fromString(texto(bruto, "consultaId")),
                TextoDaCorrecao.representavel(texto(bruto, "justificativa"), "justificativa"),
                TextoDaCorrecao.nome(texto(bruto, PACIENTE_NOME), PACIENTE_NOME),
                TextoDaCorrecao.nome(texto(bruto, MEDICO_NOME), MEDICO_NOME),
                TextoDaCorrecao.nome(texto(bruto, ESPECIALIDADE), ESPECIALIDADE),
                (OffsetDateTime) bruto.get(DATA_HORA),
                texto(bruto, STATUS),
                TextoDaCorrecao.representavel(texto(bruto, OBSERVACOES), OBSERVACOES),
                bruto.keySet());
    }

    private static String texto(Map<String, Object> bruto, String campo) {
        Object valor = bruto.get(campo);
        return valor == null ? null : valor.toString();
    }

    public boolean informou(String campo) {
        return presentes.contains(campo);
    }
}
