package br.com.fiap.hospital.agendamento.domain.exception;

import java.time.OffsetDateTime;

/**
 * Consulta marcada alem do horizonte de agendamento.
 *
 * <p>Mesma natureza de {@link AgendamentoNoPassadoException}: requisicao bem formada
 * recusada por regra de negocio, e por isso 422 e nao 400.
 */
public class AgendamentoForaDoHorizonteException extends RuntimeException {

    public AgendamentoForaDoHorizonteException(
            OffsetDateTime solicitado, OffsetDateTime limite, int horizonteEmMeses) {
        super("Nao e possivel agendar consulta com mais de " + horizonteEmMeses
                + " meses de antecedencia. Solicitado: " + solicitado + ", limite: " + limite);
    }

    private AgendamentoForaDoHorizonteException(String mensagem) {
        super(mensagem);
    }

    /**
     * Recusa pelo <b>fim</b> do periodo: o inicio cabe no horizonte, mas a duracao projeta
     * o termino para depois dele.
     *
     * <p>O instante final nao entra na mensagem porque pode nao ser calculavel: e
     * justamente a duracao que estoura a aritmetica de data que esta recusa evita.
     */
    public static AgendamentoForaDoHorizonteException porFimDoPeriodo(
            OffsetDateTime inicio, int duracaoMinutos, OffsetDateTime limite,
            int horizonteEmMeses) {
        return new AgendamentoForaDoHorizonteException(
                "A consulta terminaria depois do limite de " + horizonteEmMeses
                        + " meses de antecedencia. Inicio: " + inicio + ", duracao: "
                        + duracaoMinutos + " minuto(s), limite: " + limite);
    }
}
