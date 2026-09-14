package br.com.fiap.hospital.agendamento.integracao.superficie;

/**
 * Uma entrada que a superficie aceita, descoberta por introspeccao do handler.
 *
 * @param limiteDeTexto o maximo declarado por {@code @Size}, quando houver
 */
public record Entrada(Endpoint endpoint, Localizacao localizacao, String nome, Dimensao dimensao,
                      boolean obrigatoria, Integer limiteDeTexto) {

    /** Identidade da entrada, independente da dimensao — e o que permite acusar divergencia dela. */
    public String chave() {
        return endpoint + " | " + localizacao + " " + nome;
    }

    @Override
    public String toString() {
        return chave() + " (" + dimensao + ")";
    }
}
