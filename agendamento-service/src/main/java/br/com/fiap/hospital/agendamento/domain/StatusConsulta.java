package br.com.fiap.hospital.agendamento.domain;

import java.util.Objects;

/**
 * Estados possiveis de uma consulta e a maquina de estados que os liga.
 *
 * <p>A tabela de transicoes vive aqui, e nao espalhada pelos casos de uso, porque
 * a legalidade de uma transicao depende apenas do par (origem, destino) — nada de
 * contexto externo. Concentrar a regra e o que impede que "agendar" e "remarcar"
 * divirjam sobre o que e permitido.
 *
 * <pre>
 *   AGENDADA   -> CONFIRMADA | CANCELADA | REALIZADA
 *   CONFIRMADA -> REALIZADA  | CANCELADA
 *   REALIZADA  -> terminal
 *   CANCELADA  -> terminal
 * </pre>
 */
public enum StatusConsulta {

    AGENDADA,
    CONFIRMADA,
    REALIZADA,
    CANCELADA;

    /**
     * Responde se a transicao deste status para {@code destino} e permitida.
     *
     * <p>Transicao para o proprio status e recusada: nao ha mudanca de estado, e
     * aceita-la publicaria um evento sem fato de negocio correspondente.
     */
    public boolean podeTransicionarPara(StatusConsulta destino) {
        Objects.requireNonNull(destino, "O status de destino e obrigatorio");
        return switch (this) {
            case AGENDADA -> destino == CONFIRMADA || destino == CANCELADA || destino == REALIZADA;
            case CONFIRMADA -> destino == REALIZADA || destino == CANCELADA;
            case REALIZADA, CANCELADA -> false;
        };
    }

    /** Status terminal nao admite nenhuma transicao de saida. */
    public boolean terminal() {
        return this == REALIZADA || this == CANCELADA;
    }

    /**
     * Consulta ativa ocupa a agenda do medico e do paciente. Consulta cancelada ou
     * realizada nao ocupa — e por isso nao gera conflito com um novo agendamento.
     */
    public boolean ativa() {
        return !terminal();
    }
}
