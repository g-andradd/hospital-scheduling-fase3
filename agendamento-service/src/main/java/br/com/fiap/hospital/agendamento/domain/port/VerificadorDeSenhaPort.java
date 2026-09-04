package br.com.fiap.hospital.agendamento.domain.port;

/**
 * Porta de saida para a verificacao de senha.
 *
 * <p>O dominio precisa saber se a senha confere, nao como o hash e calculado. O
 * algoritmo e decisao de infraestrutura.
 */
public interface VerificadorDeSenhaPort {

    boolean confere(String senhaEmClaro, String hash);

    /**
     * Consome o mesmo tempo de uma verificacao real, sem comparar com nada.
     *
     * <p>Existe para o caso de o e-mail nao existir. Sem isto, a rota sem usuario
     * responde em microssegundos e a rota com usuario gasta as dezenas de milissegundos
     * do algoritmo — diferenca medivel de fora, que enumera usuarios com precisao.
     */
    void consumirTempoDeVerificacao();
}
