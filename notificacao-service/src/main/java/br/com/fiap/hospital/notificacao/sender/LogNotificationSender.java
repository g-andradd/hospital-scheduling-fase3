package br.com.fiap.hospital.notificacao.sender;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Canal padrao: registra a mensagem no log.
 *
 * <p>{@code matchIfMissing = true} e deliberado — sem configuracao alguma o servico
 * precisa subir e funcionar sem contactar nada externo. Exigir a propriedade faria um
 * ambiente novo falhar na inicializacao por um motivo que nao e de negocio.
 */
@Component
@ConditionalOnProperty(name = "notificacao.sender", havingValue = "log", matchIfMissing = true)
public class LogNotificationSender implements NotificationSenderPort {

    private static final Logger log = LoggerFactory.getLogger(LogNotificationSender.class);

    @Override
    public void enviar(Mensagem mensagem) {
        log.info("Notificacao para {}: {} | {}",
                mensagem.destinatario(), mensagem.assunto(), mensagem.corpo());
    }

    @Override
    public String canal() { return "LOG"; }
}
