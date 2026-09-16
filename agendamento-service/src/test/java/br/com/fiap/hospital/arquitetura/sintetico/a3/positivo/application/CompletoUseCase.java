package br.com.fiap.hospital.arquitetura.sintetico.a3.positivo.application;

import java.util.Objects;

/** executar mais construtor publico, estatico publico, herdado, bridge de generico e metodos de Object. */
public class CompletoUseCase extends BaseDeCasoDeUso<String> {
    private final String prefixo;

    public CompletoUseCase(String prefixo) {
        this.prefixo = prefixo;
    }

    public static CompletoUseCase padrao() {
        return new CompletoUseCase("padrao");
    }

    @Override
    public Object executar(String comando) {
        return prefixo + comando;
    }

    @Override
    public boolean equals(Object outro) {
        return outro instanceof CompletoUseCase caso && Objects.equals(prefixo, caso.prefixo);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(prefixo);
    }

    @Override
    public String toString() {
        return "CompletoUseCase[" + prefixo + "]";
    }
}
