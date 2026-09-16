package br.com.fiap.hospital.agendamento.integracao.superficie;

/** Onde a entrada chega na requisicao. */
public enum Localizacao {
    CAMINHO,
    CONSULTA,
    /** O corpo inteiro, como documento. */
    CORPO,
    /** Um campo do corpo JSON. */
    CAMPO
}
