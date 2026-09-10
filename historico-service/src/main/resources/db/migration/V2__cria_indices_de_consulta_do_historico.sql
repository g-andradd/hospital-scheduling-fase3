-- Os indices que o M08 adiou de proposito, agora que existem consultas reais.
--
-- As duas dimensoes pelas quais as queries do M09 recortam sao paciente e medico, e todo
-- filtro temporal e a ordenacao usam data_hora — por isso ela e a segunda coluna, e nao
-- um indice separado.
--
-- Nao ha indice por status: sao quatro valores sobre a tabela inteira, baixa seletividade,
-- e o status nunca aparece sozinho nas consultas expostas. Criar um agora seria supor um
-- plano que ninguem mediu.
CREATE INDEX idx_consulta_historico_paciente_data_hora
    ON consulta_historico (paciente_id, data_hora);

CREATE INDEX idx_consulta_historico_medico_data_hora
    ON consulta_historico (medico_id, data_hora);
