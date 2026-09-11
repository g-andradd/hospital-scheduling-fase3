package br.com.fiap.hospital.contracts;

public enum TipoEvento {
    CONSULTA_CRIADA("consulta.criada"),
    CONSULTA_ATUALIZADA("consulta.atualizada"),
    CONSULTA_CONFIRMADA("consulta.confirmada"),
    CONSULTA_CANCELADA("consulta.cancelada"),
    CONSULTA_REALIZADA("consulta.realizada");

    private final String routingKey;
    TipoEvento(String routingKey) { this.routingKey = routingKey; }
    public String routingKey() { return routingKey; }
}

