package br.com.fiap.hospital.notificacao.consumer;

import br.com.fiap.hospital.contracts.ConsultaPayload;
import br.com.fiap.hospital.contracts.EventoEnvelope;
import br.com.fiap.hospital.contracts.MensageriaAutoConfiguration;
import br.com.fiap.hospital.notificacao.domain.EventoProcessado;
import br.com.fiap.hospital.notificacao.repository.EventoProcessadoRepository;
import java.time.Clock;
import org.slf4j.MDC;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Fronteira unica: efeito, auditoria e marca pertencem a mesma transacao.
 *
 * <p>A ordem efeito -> marca vem do contrato normativo da secao 6 de
 * docs/03-contrato-de-eventos.md. Ela e verificada por cobertura <b>estrutural</b>, e nao
 * por teste de rollback: dentro de uma unica transacao o rollback desfaz a marca de
 * qualquer jeito, entao o teste comportamental passaria nas duas ordens e nao protegeria
 * nada. O que a ordem protege e contra a marca sair desta transacao numa refatoracao
 * futura — REQUIRES_NEW, commit antecipado, excecao engolida.
 */
@Component
public class ConsumidorDeNotificacoes {

    static final String CORRELATION_ID = "correlationId";

    private final EventoProcessadoRepository processados;
    private final ProcessadorDeEventos processador;
    private final Clock clock;

    ConsumidorDeNotificacoes(EventoProcessadoRepository processados,
                             ProcessadorDeEventos processador, Clock clock) {
        this.processados = processados;
        this.processador = processador;
        this.clock = clock;
    }

    @RabbitListener(queues = MensageriaAutoConfiguration.NOTIFICACAO)
    @Transactional
    public void consumir(EventoEnvelope<ConsultaPayload> evento) {
        MDC.put(CORRELATION_ID, evento.correlationId());
        try {
            if (processados.existsById(evento.eventId())) return;
            processador.processar(evento);
            processados.saveAndFlush(new EventoProcessado(evento.eventId(), clock.instant()));
        } finally {
            // MDC.clear(), e nao remove(): e o que o contrato normativo da secao 6 de
            // docs/03-contrato-de-eventos.md especifica. Sem a limpeza, o id de uma
            // mensagem vaza para a proxima na mesma thread do container — e o log passa a
            // mentir exatamente quando alguem vai le-lo.
            MDC.clear();
        }
    }
}
