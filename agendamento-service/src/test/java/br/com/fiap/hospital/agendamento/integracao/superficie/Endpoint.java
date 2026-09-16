package br.com.fiap.hospital.agendamento.integracao.superficie;

/** Metodo HTTP e padrao de rota, como o Spring MVC os registra. {@code *} e qualquer metodo. */
public record Endpoint(String metodo, String rota) {

    @Override
    public String toString() {
        return metodo + " " + rota;
    }
}
