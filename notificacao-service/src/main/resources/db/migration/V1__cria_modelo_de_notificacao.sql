-- Modelo normativo de docs/02-especificacao-funcional.md secao 4.
--
-- ocorrido_em guarda o occurredAt do fato ja aplicado a linha. E o que torna a
-- monotonicidade verificavel: sem ele nao ha com o que comparar o evento que chega,
-- e um fato antigo sobrescreveria um recente sem que nada acusasse.
CREATE TABLE agenda_local (
    consulta_id UUID PRIMARY KEY,
    paciente_id UUID NOT NULL,
    paciente_nome VARCHAR(255) NOT NULL,
    paciente_email VARCHAR(255) NOT NULL,
    medico_nome VARCHAR(255) NOT NULL,
    data_hora TIMESTAMPTZ NOT NULL,
    status VARCHAR(30) NOT NULL,
    ocorrido_em TIMESTAMPTZ NOT NULL,
    atualizado_em TIMESTAMPTZ NOT NULL
);

-- Sem unicidade por (consulta_id, tipo): o M07 precisa distinguir lembrete de
-- confirmacao, e a nao-duplicacao do M06 vem da marca de evento_processado.
CREATE TABLE notificacao_enviada (
    id UUID PRIMARY KEY,
    consulta_id UUID NOT NULL,
    tipo VARCHAR(40) NOT NULL,
    destinatario VARCHAR(255) NOT NULL,
    canal VARCHAR(20) NOT NULL,
    enviado_em TIMESTAMPTZ NOT NULL,
    conteudo TEXT NOT NULL
);

CREATE TABLE evento_processado (
    event_id UUID PRIMARY KEY,
    processado_em TIMESTAMPTZ NOT NULL
);
