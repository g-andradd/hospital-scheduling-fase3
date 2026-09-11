## ADDED Requirements

### Requirement: Lembrete D-1 para consultas ativas nas próximas 24 horas

O serviço SHALL enviar ao paciente um lembrete de cada consulta da agenda local cujo status seja agendado ou confirmado e cujo horário esteja no intervalo `(agora, agora + 24 horas]` — estritamente depois do instante da execução e no máximo 24 horas depois dele —, desde que a consulta ainda não tenha recebido lembrete D-1. O instante da execução SHALL vir da fonte de tempo injetada, e nunca do relógio do banco.

Consultas canceladas ou realizadas SHALL NOT receber lembrete, qualquer que seja o horário. Uma execução sem candidatos SHALL terminar com sucesso, sem envio e sem registro.

#### Scenario: Consulta a 23h59 da execução recebe lembrete
- **WHEN** uma consulta agendada ocorre 23 horas e 59 minutos depois do instante da execução
- **THEN** o paciente recebe o lembrete D-1
- **AND** o lembrete fica registrado para aquela consulta

#### Scenario: Consulta exatamente a 24 horas recebe lembrete
- **WHEN** uma consulta agendada ocorre exatamente 24 horas depois do instante da execução
- **THEN** o paciente recebe o lembrete D-1

#### Scenario: Consulta a 24h01 não recebe lembrete
- **WHEN** uma consulta agendada ocorre 24 horas e 1 minuto depois do instante da execução
- **THEN** nenhum lembrete é enviado nem registrado para ela nessa execução

#### Scenario: Consulta no instante da execução ou no passado não recebe lembrete
- **WHEN** uma consulta agendada ocorre exatamente no instante da execução, ou antes dele
- **THEN** nenhum lembrete é enviado nem registrado para ela

#### Scenario: Consultas agendadas e confirmadas são lembradas
- **WHEN** uma consulta agendada e outra confirmada estão dentro da janela
- **THEN** ambas recebem o lembrete D-1

#### Scenario: Consultas canceladas e realizadas nunca são lembradas
- **WHEN** uma consulta cancelada e outra realizada estão dentro da janela
- **THEN** nenhuma delas recebe lembrete
- **AND** nenhum registro de lembrete é criado para elas

#### Scenario: Execução sem candidatos termina sem efeito
- **WHEN** a varredura é executada sem nenhuma consulta elegível na agenda local
- **THEN** a execução termina com sucesso informando zero lembretes
- **AND** nenhum envio é feito e nenhum registro é criado

### Requirement: Lembrete entregue pelo canal configurado e registrado

O lembrete SHALL atravessar a mesma porta de saída e o mesmo adaptador selecionado das notificações reativas. O conteúdo SHALL estar em português e identificar paciente, médico, data e horário da consulta no fuso `America/Sao_Paulo`, o mesmo em que o agendamento expressa a data da consulta.

Cada lembrete confirmado SHALL deixar exatamente um registro com consulta, tipo `LEMBRETE_D1`, destinatário, canal, instante — vindo da fonte de tempo injetada — e conteúdo idêntico ao entregue ao canal.

`LEMBRETE_D1` é tipo local do registro de envios. Ele SHALL NOT integrar os tipos normativos de evento, as routing keys nem a topologia de mensageria, e o lembrete SHALL NOT publicar mensagem no broker.

#### Scenario: Lembrete confirmado fica registrado como foi entregue
- **WHEN** um lembrete é enviado com uma fonte de tempo fixa
- **THEN** existe um único registro de tipo `LEMBRETE_D1` para a consulta
- **AND** o registro tem o e-mail do paciente como destinatário, o canal usado, o instante da fonte de tempo e o mesmo conteúdo entregue ao canal

#### Scenario: Conteúdo informa médico e horário local da consulta
- **WHEN** o lembrete de uma consulta marcada para 12/09/2026 às 14:30 UTC é enviado
- **THEN** o texto, em português, cita o paciente e o médico
- **AND** informa a data 12/09/2026 e o horário 11:30, horário de São Paulo

#### Scenario: Tipo do lembrete fica fora do contrato de eventos
- **WHEN** lembretes são enviados e registrados
- **THEN** os tipos normativos de evento continuam sendo exatamente os cinco do contrato
- **AND** nenhuma mensagem é publicada no broker em razão do lembrete

### Requirement: No máximo um lembrete D-1 por consulta

Cada consulta SHALL ter no máximo um lembrete D-1 confirmado durante toda a sua vida, identificado pela consulta e pelo tipo `LEMBRETE_D1`. A unicidade SHALL ser garantida pelo armazenamento, e não por verificação prévia seguida de gravação: execuções sequenciais ou concorrentes — automáticas, manuais ou ambas — SHALL confirmar um único lembrete por consulta, e duas execuções que alcançam a mesma consulta ao mesmo tempo SHALL produzir uma única entrega ao canal. Uma consulta remarcada depois de lembrada SHALL NOT receber segundo lembrete.

Se o envio falhar, a tentativa daquela consulta SHALL ser revertida sem deixar lembrete registrado; a consulta SHALL continuar elegível para uma execução seguinte enquanto permanecer na janela, e a falha SHALL NOT impedir o lembrete das demais consultas da mesma execução.

A garantia recai sobre o estado persistido. O número de entregas ao canal externo SHALL NOT ser objeto dela quando o envio conclui e a confirmação da tentativa falha: nesse caso pode existir lembrete entregue sem registro, e uma execução seguinte pode entregá-lo de novo.

#### Scenario: Execução repetida não lembra de novo
- **WHEN** a varredura é executada duas vezes em sequência com a mesma consulta na janela
- **THEN** existe um único registro de lembrete para ela
- **AND** a segunda execução informa zero lembretes e não aciona o canal

#### Scenario: Execuções concorrentes confirmam e entregam um único lembrete
- **WHEN** duas execuções da varredura selecionam a mesma consulta e tentam lembrá-la ao mesmo tempo
- **THEN** somente uma confirma o lembrete e o canal é acionado uma única vez
- **AND** a soma dos lembretes informados pelas duas execuções é um

#### Scenario: Consulta remarcada depois do lembrete não recebe outro
- **WHEN** uma consulta já lembrada é remarcada para outro horário dentro da janela e a varredura executa de novo
- **THEN** nenhum segundo lembrete é enviado nem registrado para ela

#### Scenario: Armazenamento recusa segundo lembrete da mesma consulta
- **WHEN** uma segunda gravação de lembrete D-1 para a mesma consulta chega ao armazenamento
- **THEN** ela é recusada
- **AND** registros de notificações reativas da mesma consulta continuam aceitos mais de uma vez

#### Scenario: Falha no envio não deixa lembrete e a consulta segue elegível
- **WHEN** o canal falha ao enviar o lembrete de uma consulta
- **THEN** nenhum registro de lembrete permanece para ela
- **AND** uma execução seguinte, com o canal disponível, envia e registra o lembrete

#### Scenario: Falha em uma consulta não impede as demais
- **WHEN** o envio falha para uma consulta e funciona para outra na mesma execução
- **THEN** a outra consulta é lembrada e registrada
- **AND** a execução informa somente o lembrete confirmado

### Requirement: Seleção de candidatos em comando único no armazenamento

A varredura SHALL obter todos os candidatos de uma execução com um único comando de leitura, no qual a janela, os status elegíveis e a ausência de lembrete anterior são avaliados pelo PostgreSQL. Os limites da janela SHALL ser enviados como parâmetros calculados a partir da fonte de tempo injetada, e o comando SHALL NOT usar o relógio do banco. O número de comandos de leitura de uma execução SHALL NOT crescer com o número de candidatos.

#### Scenario: Recortes aplicados no comando enviado ao armazenamento
- **WHEN** a varredura é executada
- **THEN** o comando de leitura efetivamente enviado ao PostgreSQL contém o recorte de status, os dois limites da janela como parâmetros e a exclusão de consultas já lembradas
- **AND** não contém referência ao relógio do banco

#### Scenario: Mais candidatos não multiplicam comandos de leitura
- **WHEN** a varredura é executada com um candidato e, depois, com vinte candidatos
- **THEN** cada execução emite exatamente um comando de leitura
- **AND** o número de gravações de cada execução é igual ao número de lembretes confirmados

### Requirement: Execução automática periódica e configurável

O serviço SHALL executar a varredura automaticamente, de hora em hora por padrão, com expressão de agendamento configurável e execução automática habilitada quando nada for configurado. No profile `test` a execução automática SHALL NOT existir. A execução automática e o disparo manual SHALL aplicar a mesma regra, pela mesma operação.

#### Scenario: Sem configuração, a varredura automática é horária
- **WHEN** o serviço sobe fora do profile de teste sem configurar o agendamento do lembrete
- **THEN** a varredura automática está registrada para executar no início de cada hora

#### Scenario: Expressão configurada substitui a padrão
- **WHEN** o agendamento do lembrete é configurado com outra expressão
- **THEN** a varredura automática passa a seguir a expressão configurada

#### Scenario: Profile de teste não executa automaticamente
- **WHEN** o serviço sobe no profile `test`
- **THEN** não existe varredura automática registrada
- **AND** uma consulta elegível na agenda não recebe lembrete até que a varredura seja disparada explicitamente

#### Scenario: Execução automática e disparo manual aplicam a mesma regra
- **WHEN** a varredura é acionada pelo agendamento automático e pelo disparo manual
- **THEN** ambos executam a mesma operação de lembrete, sem regra própria

### Requirement: Disparo manual protegido por perfil

`POST /internal/lembretes/executar` SHALL executar a varredura imediatamente e responder 200 com o JSON `{"lembretesEnviados": <n>}`, em que `n` é a quantidade de lembretes confirmados na execução, inclusive zero.

O endpoint SHALL exigir token válido do mesmo emissor e segredo dos demais serviços, e SHALL autorizar apenas os perfis `MEDICO` e `ENFERMEIRO`. Token ausente, expirado ou inválido SHALL receber 401, e perfil sem permissão SHALL receber 403, ambos em Problem Detail com o `type` de segurança do projeto e sem executar a varredura. O serviço SHALL usar a cadeia de segurança compartilhada, sem cadeia própria, e as recusas 401 e 403 SHALL continuar sendo produzidas por ela.

Falha que impeça a leitura inicial dos candidatos SHALL responder 500 em `application/problem+json`, com `type` `https://hospital.fiap.br/erros/erro-interno`, título e detalhe genéricos em português, `correlationId`, `timestamp` e `instance`. A resposta SHALL NOT conter SQL, nome de tabela, credencial, nome de exceção ou stack trace, e nenhum lembrete SHALL ser enviado ou registrado.

#### Scenario: Médico dispara a execução
- **WHEN** um médico autenticado chama o endpoint com uma consulta elegível na agenda
- **THEN** a resposta é 200 com `lembretesEnviados` igual a 1
- **AND** o lembrete fica registrado

#### Scenario: Enfermeiro dispara a execução
- **WHEN** um enfermeiro autenticado chama o endpoint com uma consulta elegível na agenda
- **THEN** a resposta é 200 com `lembretesEnviados` igual a 1
- **AND** o lembrete fica registrado

#### Scenario: Disparo sem candidatos responde zero
- **WHEN** um médico autenticado chama o endpoint sem nenhuma consulta elegível
- **THEN** a resposta é 200 com `lembretesEnviados` igual a 0

#### Scenario: Paciente é recusado sem executar a varredura
- **WHEN** um paciente autenticado chama o endpoint com uma consulta elegível na agenda
- **THEN** a resposta é 403 em Problem Detail de acesso negado
- **AND** nenhum lembrete é enviado nem registrado

#### Scenario: Sem token recebe 401
- **WHEN** o endpoint é chamado sem credencial, com uma consulta elegível na agenda
- **THEN** a resposta é 401 em Problem Detail de não autenticado
- **AND** nenhum lembrete é enviado nem registrado

#### Scenario: Token expirado recebe 401
- **WHEN** o endpoint é chamado com token do mesmo emissor já expirado, com uma consulta elegível na agenda
- **THEN** a resposta é 401 em Problem Detail de não autenticado
- **AND** nenhum lembrete é enviado nem registrado

#### Scenario: Token inválido recebe 401
- **WHEN** o endpoint é chamado com token malformado ou assinado com outro segredo, com uma consulta elegível na agenda
- **THEN** a resposta é 401 em Problem Detail de não autenticado
- **AND** nenhum lembrete é enviado nem registrado

#### Scenario: Falha na leitura inicial dos candidatos responde 500 em Problem Detail
- **WHEN** um médico autenticado dispara a execução, com uma consulta elegível na agenda, e o armazenamento falha ao ler os candidatos
- **THEN** a resposta é 500 com `application/problem+json` e `type` `https://hospital.fiap.br/erros/erro-interno`
- **AND** o corpo traz detalhe genérico em português, `correlationId`, `timestamp` e `instance`, sem SQL, nome de tabela, credencial, nome de exceção ou stack trace
- **AND** nenhum lembrete é enviado nem registrado
