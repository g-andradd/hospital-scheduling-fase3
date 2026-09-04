package br.com.fiap.hospital.agendamento.infrastructure.messaging;

import br.com.fiap.hospital.agendamento.domain.EventoDeConsulta;
import br.com.fiap.hospital.agendamento.domain.port.EventPublisherPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Adaptador PROVISORIO da porta de eventos.
 *
 * <p>Existe apenas para que o contexto suba: os casos de uso publicam a cada mudanca
 * de estado desde o M01, e sem nenhum bean da porta a aplicacao nao inicia. Ele
 * registra o evento em log e nao entrega nada a lugar nenhum.
 *
 * <p>O adaptador de verdade — outbox transacional e publicacao no RabbitMQ — e o M05,
 * e este bean e removido la. Ele e declarado explicitamente em
 * {@code CasosDeUsoConfig}, e nao por anotacao de estereotipo: registrar por
 * {@code @Component} com {@code @ConditionalOnMissingBean} nao funciona fora de uma
 * auto-configuracao, porque a ordem de avaliacao nao e garantida.
 */
public class EventPublisherLogAdapter implements EventPublisherPort {

    private static final Logger log = LoggerFactory.getLogger(EventPublisherLogAdapter.class);

    @Override
    public void publicar(EventoDeConsulta evento) {
        log.info("Evento de consulta nao publicado (adaptador provisorio ate o M05): {} para a consulta {} em {}",
                evento.tipo(), evento.consultaId(), evento.ocorridoEm());
    }
}
