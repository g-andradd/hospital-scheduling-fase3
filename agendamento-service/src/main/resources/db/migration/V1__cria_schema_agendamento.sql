-- Schema inicial do agendamento_db.
--
-- Modelo de dados conforme docs/02-especificacao-funcional.md secao 4.
-- Instantes em timestamptz; identificadores em uuid gerado pelo banco.

CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE usuario (
    id          uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    nome        varchar(150) NOT NULL,
    email       varchar(255) NOT NULL,
    senha_hash  varchar(100) NOT NULL,
    perfil      varchar(20)  NOT NULL,
    ativo       boolean      NOT NULL DEFAULT true,
    criado_em   timestamptz  NOT NULL DEFAULT now(),

    CONSTRAINT uk_usuario_email  UNIQUE (email),
    CONSTRAINT ck_usuario_perfil CHECK (perfil IN ('MEDICO', 'ENFERMEIRO', 'PACIENTE'))
);

CREATE TABLE paciente (
    id               uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    usuario_id       uuid        NOT NULL,
    cpf              varchar(11) NOT NULL,
    data_nascimento  date        NOT NULL,
    telefone         varchar(20),

    CONSTRAINT fk_paciente_usuario FOREIGN KEY (usuario_id) REFERENCES usuario (id),
    CONSTRAINT uk_paciente_usuario UNIQUE (usuario_id),
    CONSTRAINT uk_paciente_cpf     UNIQUE (cpf)
);

CREATE TABLE medico (
    id             uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    usuario_id     uuid        NOT NULL,
    crm            varchar(20) NOT NULL,
    especialidade  varchar(100) NOT NULL,

    CONSTRAINT fk_medico_usuario FOREIGN KEY (usuario_id) REFERENCES usuario (id),
    CONSTRAINT uk_medico_usuario UNIQUE (usuario_id),
    CONSTRAINT uk_medico_crm     UNIQUE (crm)
);

CREATE TABLE consulta (
    id                   uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    paciente_id          uuid        NOT NULL,
    medico_id            uuid        NOT NULL,
    registrado_por_id    uuid        NOT NULL,
    data_hora            timestamptz NOT NULL,
    duracao_minutos      integer     NOT NULL,
    status               varchar(20) NOT NULL,
    observacoes          text,
    motivo_cancelamento  text,
    criado_em            timestamptz NOT NULL,
    atualizado_em        timestamptz NOT NULL,
    -- Lock otimista. Ver D5 do design: vive so aqui, nao no dominio.
    versao               bigint      NOT NULL DEFAULT 0,

    CONSTRAINT fk_consulta_paciente       FOREIGN KEY (paciente_id) REFERENCES paciente (id),
    CONSTRAINT fk_consulta_medico         FOREIGN KEY (medico_id) REFERENCES medico (id),
    CONSTRAINT fk_consulta_registrado_por FOREIGN KEY (registrado_por_id) REFERENCES usuario (id),
    CONSTRAINT ck_consulta_status         CHECK (status IN ('AGENDADA', 'CONFIRMADA', 'REALIZADA', 'CANCELADA')),
    CONSTRAINT ck_consulta_duracao        CHECK (duracao_minutos > 0)
);

-- Servem o recorte da query de conflito: medico_id = ? AND data_hora < ?.
-- A segunda condicao de sobreposicao, sobre data_hora + duracao, e avaliada
-- sobre as linhas ja recortadas por estes indices.
CREATE INDEX ix_consulta_medico_data_hora   ON consulta (medico_id, data_hora);
CREATE INDEX ix_consulta_paciente_data_hora ON consulta (paciente_id, data_hora);
