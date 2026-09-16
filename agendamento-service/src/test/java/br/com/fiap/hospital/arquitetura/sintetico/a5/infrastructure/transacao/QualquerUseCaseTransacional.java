package br.com.fiap.hospital.arquitetura.sintetico.a5.infrastructure.transacao;

import br.com.fiap.hospital.arquitetura.sintetico.a5.application.QualquerUseCase;

public class QualquerUseCaseTransacional {
    private final QualquerUseCase delegado;

    public QualquerUseCaseTransacional(QualquerUseCase delegado) {
        this.delegado = delegado;
    }

    public Object executar(Object comando) {
        return delegado.executar(comando);
    }
}
