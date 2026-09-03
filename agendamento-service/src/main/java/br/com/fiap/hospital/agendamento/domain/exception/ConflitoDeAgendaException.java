package br.com.fiap.hospital.agendamento.domain.exception;

/** Sobreposicao com consulta ativa do mesmo medico ou do mesmo paciente. Mapeada para 409. */
public class ConflitoDeAgendaException extends RuntimeException {

    public ConflitoDeAgendaException(String mensagem) {
        super(mensagem);
    }

    public static ConflitoDeAgendaException doMedico(String periodo) {
        return new ConflitoDeAgendaException(
                "O medico ja possui consulta ativa no periodo " + periodo);
    }

    public static ConflitoDeAgendaException doPaciente(String periodo) {
        return new ConflitoDeAgendaException(
                "O paciente ja possui consulta ativa no periodo " + periodo);
    }
}
