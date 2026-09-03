/**
 * Decoradores transacionais dos casos de uso.
 *
 * <p>Cada classe aqui envolve um caso de uso com {@code @Transactional} e delega. A
 * anotacao vive nesta camada, e nao no proprio caso de uso, por duas razoes.
 *
 * <p>A transacao precisa envolver a operacao inteira: no M05 o caso de uso grava a
 * consulta e a linha do outbox, e a garantia do Transactional Outbox e que as duas
 * caem juntas. Anotar o adaptador tornaria cada chamada de porta sua propria
 * transacao, o que destroi tanto o outbox quanto o lock otimista.
 *
 * <p>E o pacote {@code application} permanece sem framework, propriedade que o M01
 * estabeleceu e verificou. Anotar os casos de uso a encerraria em silencio.
 *
 * <p>O custo e reconhecido: seis classes de delegacao, uma linha util cada.
 */
package br.com.fiap.hospital.agendamento.infrastructure.transacao;
