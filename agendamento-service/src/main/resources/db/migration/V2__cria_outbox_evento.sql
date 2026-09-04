CREATE TABLE outbox_evento (
    id uuid PRIMARY KEY,
    agregado_id uuid NOT NULL,
    tipo_evento varchar(40) NOT NULL,
    payload jsonb NOT NULL,
    routing_key varchar(80) NOT NULL,
    criado_em timestamptz NOT NULL,
    publicado_em timestamptz,
    tentativas numeric NOT NULL DEFAULT 0,
    CONSTRAINT ck_outbox_tentativas CHECK (tentativas >= 0 AND tentativas = trunc(tentativas)),
    CONSTRAINT ck_outbox_envelope CHECK (jsonb_typeof(payload) = 'object')
);
CREATE INDEX ix_outbox_pendente ON outbox_evento(publicado_em) WHERE publicado_em IS NULL;
