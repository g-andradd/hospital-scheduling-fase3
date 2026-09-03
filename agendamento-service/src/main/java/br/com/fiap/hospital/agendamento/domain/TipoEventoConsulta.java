package br.com.fiap.hospital.agendamento.domain;

/**
 * Tipos de mudanca de estado que geram evento. Espelha as routing keys de
 * docs/03-contrato-de-eventos.md secao 2, mas sem nenhum acoplamento com AMQP: a
 * traducao para routing key e para o envelope acontece no adaptador, no M05.
 */
public enum TipoEventoConsulta {

    CRIADA,
    ATUALIZADA,
    CONFIRMADA,
    CANCELADA,
    REALIZADA
}
