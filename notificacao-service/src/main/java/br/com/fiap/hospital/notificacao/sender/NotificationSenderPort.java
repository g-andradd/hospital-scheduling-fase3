package br.com.fiap.hospital.notificacao.sender;

/**
 * Unica saida de notificacao do servico.
 *
 * <p>O adaptador ativo e escolhido por {@code notificacao.sender}. Quem chama nao sabe
 * qual e, e o efeito persistido nao muda com a escolha.
 */
public interface NotificationSenderPort {

    void enviar(Mensagem mensagem);

    /** Nome do canal, gravado na auditoria. */
    String canal();

    record Mensagem(String destinatario, String assunto, String corpo) { }
}
