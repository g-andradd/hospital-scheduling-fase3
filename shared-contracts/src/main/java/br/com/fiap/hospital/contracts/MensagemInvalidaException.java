package br.com.fiap.hospital.contracts;

import org.springframework.amqp.support.converter.MessageConversionException;

/** Recusa de entrada AMQP: a cadeia de retry a trata antes de qualquer efeito. */
public class MensagemInvalidaException extends MessageConversionException {
    public enum Motivo { JSON, ESTRUTURA, CAMPO, VERSAO, METADADOS }
    private final Motivo motivo;
    public MensagemInvalidaException(Motivo motivo, String campo) {
        super("Mensagem de evento invalida: " + motivo + " em " + campo);
        this.motivo = motivo;
    }
    public Motivo motivo() { return motivo; }
}

