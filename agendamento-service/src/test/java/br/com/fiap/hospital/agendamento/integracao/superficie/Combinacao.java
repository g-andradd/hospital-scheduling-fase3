package br.com.fiap.hospital.agendamento.integracao.superficie;

/** Uma tupla atacavel: endpoint, entrada, dimensao e variante. */
public record Combinacao(Endpoint endpoint, Localizacao localizacao, String entrada,
                         Dimensao dimensao, String variante) {

    public String chaveDaEntrada() {
        return endpoint + " | " + localizacao + " " + entrada;
    }

    @Override
    public String toString() {
        return chaveDaEntrada() + " | " + dimensao + " | " + variante;
    }
}
