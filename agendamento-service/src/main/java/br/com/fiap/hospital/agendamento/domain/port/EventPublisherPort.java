package br.com.fiap.hospital.agendamento.domain.port;

import br.com.fiap.hospital.agendamento.domain.EventoDeConsulta;

/**
 * Porta de saida para a publicacao de eventos de dominio.
 *
 * <p>O contrato termina aqui: "houve esta mudanca de estado". Envelope, routing key,
 * outbox e RabbitMQ sao decisoes do adaptador, no M05, e nenhuma delas vaza para o
 * dominio nem para os casos de uso.
 */
public interface EventPublisherPort {

    void publicar(EventoDeConsulta evento);
}
