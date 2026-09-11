package br.com.fiap.hospital.notificacao.sender;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.MailSender;
import org.springframework.stereotype.Component;

/**
 * Canal de correio, ativado por {@code notificacao.sender=smtp}.
 *
 * <p>Host, porta e remetente vem de configuracao; nao ha credencial nem endereco de
 * provedor no codigo. Falha de envio propaga: quem decide o que fazer com ela e a
 * fronteira transacional, que reverte o efeito inteiro.
 */
@Component
@ConditionalOnProperty(name = "notificacao.sender", havingValue = "smtp")
public class SmtpNotificationSender implements NotificationSenderPort {

    private final MailSender mailSender;
    private final String remetente;

    SmtpNotificationSender(MailSender mailSender,
                           @org.springframework.beans.factory.annotation.Value("${notificacao.remetente}") String remetente) {
        this.mailSender = mailSender;
        this.remetente = remetente;
    }

    @Override
    public void enviar(Mensagem mensagem) {
        SimpleMailMessage email = new SimpleMailMessage();
        email.setFrom(remetente);
        email.setTo(mensagem.destinatario());
        email.setSubject(mensagem.assunto());
        email.setText(mensagem.corpo());
        mailSender.send(email);
    }

    @Override
    public String canal() { return "SMTP"; }
}
