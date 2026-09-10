CREATE TABLE consulta_historico (
    id UUID PRIMARY KEY,
    paciente_id UUID NOT NULL,
    paciente_nome VARCHAR(255) NOT NULL,
    medico_id UUID NOT NULL,
    medico_nome VARCHAR(255) NOT NULL,
    especialidade VARCHAR(255) NOT NULL,
    data_hora TIMESTAMPTZ NOT NULL,
    status VARCHAR(30) NOT NULL,
    observacoes TEXT,
    criado_em TIMESTAMPTZ NOT NULL,
    atualizado_em TIMESTAMPTZ NOT NULL
);

CREATE TABLE consulta_evento (
    id UUID PRIMARY KEY,
    consulta_id UUID NOT NULL REFERENCES consulta_historico(id),
    tipo_evento VARCHAR(40) NOT NULL,
    ocorrido_em TIMESTAMPTZ NOT NULL,
    payload JSONB NOT NULL
);

CREATE TABLE evento_processado (
    event_id UUID PRIMARY KEY,
    processado_em TIMESTAMPTZ NOT NULL
);
