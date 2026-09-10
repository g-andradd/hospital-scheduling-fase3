package br.com.fiap.hospital.historico.infrastructure.messaging;

import br.com.fiap.hospital.contracts.ConsultaPayload;
import br.com.fiap.hospital.contracts.EventoEnvelope;
import br.com.fiap.hospital.contracts.MensageriaAutoConfiguration;
import br.com.fiap.hospital.historico.infrastructure.persistence.entity.EventoProcessadoEntity;
import br.com.fiap.hospital.historico.infrastructure.persistence.repository.EventoProcessadoJpaRepository;
import java.time.Clock;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Fronteira única: efeito, trilha e marca de idempotência pertencem à mesma transação. */
@Component
public class ConsumidorTransacionalDoHistorico {
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
        if (processados.existsById(evento.eventId())) return;
        projetor.projetar(evento);
        processados.saveAndFlush(new EventoProcessadoEntity(evento.eventId(), clock.instant()));
    }
}
