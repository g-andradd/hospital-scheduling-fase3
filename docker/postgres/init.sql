-- Cria um database por servico. Autonomia de dados sem o custo de tres instancias.
--
-- Este script so e executado pelo entrypoint do Postgres quando o volume esta VAZIO.
-- Depois do primeiro boot, alterar este arquivo nao tem efeito: e preciso rodar
-- `docker compose down -v` para descartar o volume e reprovisionar do zero.

CREATE DATABASE agendamento_db;
CREATE DATABASE notificacao_db;
CREATE DATABASE historico_db;

COMMENT ON DATABASE agendamento_db IS 'agendamento-service — consultas, usuarios e outbox';
COMMENT ON DATABASE notificacao_db IS 'notificacao-service — agenda local e notificacoes enviadas';
COMMENT ON DATABASE historico_db  IS 'historico-service — read model e trilha de eventos';
