-- Indices do lembrete D-1 (M07). V1 nao e editada: migration aplicada e imutavel.
--
-- Unicidade parcial: no maximo um lembrete D-1 por consulta durante toda a sua vida. Ela e
-- parcial porque as notificacoes reativas repetem tipo legitimamente -- duas remarcacoes
-- geram duas linhas CONSULTA_ATUALIZADA, e uma unicidade por (consulta_id, tipo) recusaria
-- a segunda. E ela que decide a disputa entre execucoes concorrentes, pelo ON CONFLICT da
-- reserva, e e ela tambem que serve a exclusao de consultas ja lembradas na leitura.
CREATE UNIQUE INDEX uq_notificacao_enviada_lembrete_d1
    ON notificacao_enviada (consulta_id) WHERE tipo = 'LEMBRETE_D1';

-- Sustenta o recorte da varredura: igualdade em dois status e intervalo em data_hora. O
-- status vem na frente para restringir a faixa lida; com data_hora na frente, as canceladas
-- e realizadas do intervalo seriam lidas e descartadas.
CREATE INDEX idx_agenda_local_status_data_hora
    ON agenda_local (status, data_hora);
