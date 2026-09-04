package br.com.fiap.hospital.agendamento.domain.exception;

import br.com.fiap.hospital.agendamento.domain.StatusConsulta;

/** Transicao recusada pela maquina de estados da consulta. Mapeada para 409. */
public class TransicaoDeStatusInvalidaException extends RuntimeException {

    public TransicaoDeStatusInvalidaException(StatusConsulta origem, StatusConsulta destino) {
        super("Transicao de status invalida: " + origem + " para " + destino);
    }

    public TransicaoDeStatusInvalidaException(String mensagem) {
        super(mensagem);
    }
}
