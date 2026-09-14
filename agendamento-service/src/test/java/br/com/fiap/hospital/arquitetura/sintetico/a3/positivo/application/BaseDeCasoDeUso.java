package br.com.fiap.hospital.arquitetura.sintetico.a3.positivo.application;

public abstract class BaseDeCasoDeUso<C> {
    public abstract Object executar(C comando);

    public String herdado() {
        return "herdado";
    }
}
