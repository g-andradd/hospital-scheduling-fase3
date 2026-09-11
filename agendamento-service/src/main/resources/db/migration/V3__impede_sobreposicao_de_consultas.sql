CREATE EXTENSION IF NOT EXISTS btree_gist;

-- A verificacao e o backfill veem o mesmo conjunto, sem escrita concorrente.
LOCK TABLE consulta IN ACCESS EXCLUSIVE MODE;
SET LOCAL TIME ZONE 'UTC';

DO $$
DECLARE conflito record;
BEGIN
    SELECT a.id AS primeira, b.id AS segunda,
           CASE WHEN a.medico_id = b.medico_id THEN 'medico' ELSE 'paciente' END AS recurso
      INTO conflito
      FROM consulta a JOIN consulta b ON a.id < b.id
     WHERE a.status IN ('AGENDADA', 'CONFIRMADA')
       AND b.status IN ('AGENDADA', 'CONFIRMADA')
       AND (a.medico_id = b.medico_id OR a.paciente_id = b.paciente_id)
       AND tstzrange(a.data_hora, a.data_hora + make_interval(mins => a.duracao_minutos), '[)')
           && tstzrange(b.data_hora, b.data_hora + make_interval(mins => b.duracao_minutos), '[)')
     ORDER BY a.id, b.id LIMIT 1;
    IF FOUND THEN
        RAISE EXCEPTION 'M05: consultas ativas % e % sobrepostas para o mesmo %. Corrija os dados antes de repetir a migration; nenhum registro foi ajustado automaticamente.',
            conflito.primeira, conflito.segunda, conflito.recurso;
    END IF;
END $$;

ALTER TABLE consulta ADD COLUMN periodo_ocupado tstzrange;

-- Nao marcar IMMUTABLE: a adicao de intervalo a timestamptz depende da zona.
-- A zona local da funcao fixa o calculo e o trigger cobre tambem SQL direto.
CREATE FUNCTION calcula_periodo_ocupado() RETURNS trigger
LANGUAGE plpgsql SET timezone = 'UTC' AS $$
BEGIN
    NEW.periodo_ocupado := tstzrange(NEW.data_hora,
        NEW.data_hora + make_interval(mins => NEW.duracao_minutos), '[)');
    RETURN NEW;
END $$;

CREATE TRIGGER tr_consulta_periodo_ocupado
BEFORE INSERT OR UPDATE ON consulta
FOR EACH ROW EXECUTE FUNCTION calcula_periodo_ocupado();

UPDATE consulta SET periodo_ocupado = tstzrange(data_hora,
    data_hora + make_interval(mins => duracao_minutos), '[)');
ALTER TABLE consulta ALTER COLUMN periodo_ocupado SET NOT NULL;

ALTER TABLE consulta ADD CONSTRAINT ex_consulta_medico_periodo
    EXCLUDE USING gist (medico_id WITH =, periodo_ocupado WITH &&)
    WHERE (status IN ('AGENDADA', 'CONFIRMADA')) NOT DEFERRABLE;
ALTER TABLE consulta ADD CONSTRAINT ex_consulta_paciente_periodo
    EXCLUDE USING gist (paciente_id WITH =, periodo_ocupado WITH &&)
    WHERE (status IN ('AGENDADA', 'CONFIRMADA')) NOT DEFERRABLE;
