package br.com.fiap.hospital.historico.infrastructure.graphql;

/** Excecoes que o resolver lanca e o tradutor de execucao converte em codigo estavel. */
public final class ExcecoesDoHistorico {

    private ExcecoesDoHistorico() { }

    public static class RegistroNaoEncontrado extends RuntimeException {
        public RegistroNaoEncontrado(String mensagem) { super(mensagem); }
    }

    public static class CorrecaoInvalida extends RuntimeException {
        public CorrecaoInvalida(String mensagem) { super(mensagem); }
    }
}
