package br.com.fiap.hospital.historico.infrastructure.messaging;

import br.com.fiap.hospital.contracts.ConsultaPayload;
import br.com.fiap.hospital.contracts.EventoEnvelope;
import br.com.fiap.hospital.contracts.MensageriaAutoConfiguration;
import br.com.fiap.hospital.historico.infrastructure.persistence.entity.EventoProcessadoEntity;
import br.com.fiap.hospital.historico.infrastructure.persistence.repository.EventoProcessadoJpaRepository;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Fronteira única: efeito, trilha e marca de idempotência pertencem à mesma transação. */
@Component
public class ConsumidorTransacionalDoHistorico {
    private static final Logger log = LoggerFactory.getLogger(ConsumidorTransacionalDoHistorico.class);
    static final String CORRELATION_ID = "correlationId";

    private final EventoProcessadoJpaRepository processados;
    private final ProjetorDoHistorico projetor;
    private final Clock clock;

    ConsumidorTransacionalDoHistorico(EventoProcessadoJpaRepository processados, ProjetorDoHistorico projetor,
                                      Clock clock) {
        this.processados = processados;
        this.projetor = projetor;
        this.clock = clock;
    }

    @RabbitListener(queues = MensageriaAutoConfiguration.HISTORICO)
    @Transactional
    public void consumir(EventoEnvelope<ConsultaPayload> evento) {
        MDC.put(CORRELATION_ID, evento.correlationId());
        try {
            if (processados.existsById(evento.eventId())) return;
            projetor.projetar(evento);
            processados.saveAndFlush(new EventoProcessadoEntity(evento.eventId(), clock.instant()));
            // Emitido antes do commit que o proxy faz no retorno: registra a tentativa bem-sucedida
            // ate aqui, e nao prova o efeito. Falha de commit traz a reentrega e um novo registro;
            // o efeito confirmado continua sendo o estado persistido.
            log.info("Evento projetado eventId={} tipo={} consultaId={}",
                    evento.eventId(), evento.eventType(), evento.aggregateId());
        } finally {
            // MDC.clear(), como o contrato normativo da secao 6 de docs/03-contrato-de-eventos.md:
            // sem a limpeza, o id de uma mensagem vaza para a proxima na mesma thread do container.
            MDC.clear();
        }
    }
}
