# notificacoes-ao-paciente

## Purpose

Avisa o paciente sobre os fatos da sua consulta a partir dos eventos publicados pelo agendamento, mantendo uma agenda local própria e o registro auditável do que cada processamento confirmado produziu, com efeito único por evento e sem depender da disponibilidade do serviço de agendamento.

## Requirements

### Requirement: Agenda local materializável por qualquer evento

O sistema SHALL consumir os cinco tipos normativos de evento de consulta e manter, para cada consulta, uma única linha de agenda local com paciente, contato, médico, horário e status.

Todo evento válido SHALL poder materializar a linha por inserção ou atualização, inclusive quando o evento de criação daquela consulta ainda não foi processado — o snapshot do envelope é autossuficiente. O cancelamento SHALL registrar o status cancelado e SHALL NOT remover a linha.

#### Scenario: Criação materializa a agenda local
- **WHEN** um evento de criação de consulta é recebido
- **THEN** existe uma linha de agenda local para aquela consulta com paciente, contato, médico, horário e status do evento

#### Scenario: Evento posterior sem criação prévia materializa a agenda
- **WHEN** um evento de atualização, confirmação, cancelamento ou realização é o primeiro recebido para uma consulta
- **THEN** a linha de agenda é criada a partir do snapshot desse evento
- **AND** o processamento não depende de o evento de criação ter sido recebido antes

#### Scenario: Atualização altera a agenda sem duplicá-la
- **WHEN** um evento de atualização da mesma consulta é recebido depois da criação
- **THEN** continua existindo uma única linha para aquela consulta
- **AND** horário, médico e demais dimensões passam a refletir esse evento

#### Scenario: Cancelamento preserva a linha e marca o status
- **WHEN** um evento de cancelamento é recebido
- **THEN** a linha da agenda continua existindo
- **AND** seu status passa a indicar cancelamento

### Requirement: Agenda monotônica por instante do fato

A agenda SHALL ser substituída somente quando o `occurredAt` recebido for maior ou igual ao instante do fato já aplicado à linha. Um fato estritamente anterior SHALL NOT regredir a agenda.

Todo evento válido e ainda não processado SHALL receber marca de processamento, inclusive quando não altera a agenda. A decisão SHALL ser atômica no armazenamento. Para instantes iguais, a ordem de chegada SHALL decidir, porque o contrato não declara sequência por agregado.

#### Scenario: Evento antigo é processado sem regredir a agenda
- **WHEN** um evento válido com `occurredAt` anterior ao fato já aplicado chega depois de um evento mais novo
- **THEN** ele é marcado como processado
- **AND** nenhum campo da agenda volta ao valor anterior

#### Scenario: Atualização antiga não ressuscita consulta cancelada
- **WHEN** um evento de cancelamento é aplicado e, em seguida, chega um evento de atualização com `occurredAt` anterior ao cancelamento
- **THEN** o status da agenda permanece cancelado
- **AND** nenhuma notificação de alteração é enviada ao paciente

#### Scenario: Fatos no mesmo instante seguem a ordem de chegada
- **WHEN** dois eventos distintos da mesma consulta têm o mesmo `occurredAt` e são recebidos em sequência
- **THEN** ambos são marcados como processados
- **AND** o segundo recebido determina a agenda

### Requirement: Notificações reativas ao paciente

O sistema SHALL enviar ao paciente uma notificação quando aplicar à agenda um evento de criação, de atualização ou de cancelamento, com conteúdo em português correspondente ao fato ocorrido.

O sistema SHALL NOT enviar notificação para os eventos de confirmação e de realização, nem para um evento que não foi aplicado à agenda por ser anterior ao fato vigente.

Toda transação de processamento confirmada que tenha enviado uma notificação SHALL conter exatamente um registro dela, com consulta, tipo, destinatário, canal, instante e conteúdo. O registro representa o **resultado persistido** do processamento, e não um livro-razão completo do transporte externo: quando o envio conclui e a transação não confirma, pode existir mensagem entregue sem registro correspondente.

#### Scenario: Criação notifica o agendamento da consulta
- **WHEN** um evento de criação é aplicado
- **THEN** o paciente recebe uma notificação de consulta agendada
- **AND** o envio fica registrado com destinatário, canal e conteúdo

#### Scenario: Cancelamento notifica cancelamento, não confirmação
- **WHEN** um evento de cancelamento é aplicado
- **THEN** o paciente recebe uma notificação de cancelamento
- **AND** o conteúdo enviado não é o de confirmação de agendamento

#### Scenario: Atualização notifica a alteração
- **WHEN** um evento de atualização é aplicado
- **THEN** o paciente recebe uma notificação informando a alteração da consulta

#### Scenario: Confirmação e realização não geram notificação
- **WHEN** um evento de confirmação ou de realização é aplicado
- **THEN** nenhuma notificação nova é registrada para aquela consulta
- **AND** a agenda continua sendo atualizada normalmente

#### Scenario: Evento não aplicado não gera notificação obsoleta
- **WHEN** um evento de criação, atualização ou cancelamento não é aplicado por ser anterior ao fato vigente
- **THEN** nenhuma notificação é enviada nem registrada para ele
- **AND** ele ainda assim é marcado como processado

### Requirement: Efeito único por eventId

O sistema SHALL produzir no máximo um efeito persistido para o mesmo `eventId`. A atualização da agenda, o registro da notificação e a marca de evento processado SHALL pertencer à mesma transação, e a marca SHALL ser gravada depois do efeito, conforme o contrato normativo de consumo.

A garantia recai sobre o estado persistido. O número de entregas ao canal externo SHALL NOT ser objeto desta garantia: uma tentativa cujo envio conclui e cuja transação não confirma pode ter entregue a mensagem sem deixar qualquer registro.

Se qualquer etapa falhar — inclusive o envio da notificação — a transação SHALL NOT deixar agenda alterada, registro de envio ou marca de processamento parcial. Entregas concorrentes do mesmo `eventId` SHALL manter a mesma garantia sobre o estado persistido.

#### Scenario: Reentrega do mesmo evento não notifica duas vezes
- **WHEN** o mesmo evento válido é entregue novamente depois de processado
- **THEN** continua existindo um único registro de notificação para aquele evento
- **AND** a agenda local e a marca de processamento não são alteradas de novo

#### Scenario: Entregas concorrentes confirmam um único efeito
- **WHEN** duas entregas do mesmo `eventId` são processadas concorrentemente
- **THEN** somente uma transação confirma agenda, registro de envio e marca
- **AND** ao final existem um único registro de notificação e uma única marca para aquele evento

#### Scenario: Falha no envio desfaz o efeito inteiro
- **WHEN** o envio da notificação falha durante o processamento de um evento
- **THEN** a agenda local permanece como estava antes da tentativa
- **AND** não existe registro de envio nem marca de processamento desse evento

#### Scenario: Falha entre o efeito e a marca desfaz a projeção
- **WHEN** ocorre uma falha depois de alterar agenda e auditoria e antes de confirmar a marca de processado
- **THEN** nada permanece persistido daquela tentativa
- **AND** uma nova entrega ainda pode processar o evento integralmente

### Requirement: Rejeição segura de entradas não processáveis

O consumidor SHALL usar as três tentativas e a rejeição sem requeue definidas em `mensageria-de-eventos`. JSON malformado, campo obrigatório inválido, metadado incompatível, versão de envelope diferente de 1 ou falha persistente de processamento SHALL terminar na DLQ normativa, sem repetição infinita.

Uma entrada rejeitada SHALL NOT criar ou alterar agenda, registro de envio ou marca de processamento. Depois da rejeição, o consumidor SHALL continuar apto a processar uma mensagem válida.

#### Scenario: Mensagem malformada não deixa efeito e chega à DLQ
- **WHEN** uma mensagem destinada à notificação contém JSON ou estrutura inválidos
- **THEN** ela é tentada três vezes e rejeitada para a DLQ sem requeue infinito
- **AND** nenhuma tabela do serviço registra efeito da mensagem

#### Scenario: Versão desconhecida é rejeitada
- **WHEN** o envelope recebido declara uma versão diferente de 1
- **THEN** o processamento de negócio não é executado e a mensagem chega à DLQ após três tentativas
- **AND** agenda, auditoria e marca permanecem inalteradas

#### Scenario: Falha persistente reverte cada tentativa
- **WHEN** o processamento de um evento válido falha em todas as três tentativas
- **THEN** cada transação é revertida e a mensagem termina na DLQ
- **AND** não existe agenda parcial, registro de envio nem marca desse evento

#### Scenario: Mensagem válida posterior continua sendo processada
- **WHEN** uma mensagem válida chega depois de uma entrada enviada à DLQ
- **THEN** o consumidor permanece ativo e confirma normalmente seu efeito

### Requirement: Notificação desacoplada do serviço de agendamento

O serviço SHALL obter todos os dados necessários exclusivamente do envelope e do snapshot do evento. O processamento SHALL NOT chamar o serviço de agendamento nem depender de sua disponibilidade.

Campo obrigatório ausente SHALL ser tratado como falha de contrato e seguir a rejeição segura, sem tentativa de completar dados por chamada remota.

#### Scenario: Evento completo notifica sem consulta remota
- **WHEN** um evento válido contém o snapshot normativo completo
- **THEN** agenda e notificação são produzidas usando somente a mensagem
- **AND** a indisponibilidade HTTP do agendamento não interfere no processamento

#### Scenario: Campo obrigatório ausente não dispara busca no produtor
- **WHEN** o evento não contém um campo obrigatório para a notificação
- **THEN** ele é rejeitado conforme a política de mensagens inválidas
- **AND** nenhuma chamada ao agendamento é realizada e nenhum efeito parcial é persistido

### Requirement: Canal de envio selecionável e sem segredo embutido

O envio SHALL atravessar uma única porta de saída, com adaptador escolhido por configuração e adaptador de registro em log como padrão. O repositório SHALL NOT conter credencial, token ou endereço fixo de provedor externo de correio; nome de propriedade, referência a variável de ambiente e valor local de desenvolvimento são permitidos.

A troca de adaptador SHALL NOT alterar o efeito persistido: agenda e auditoria SHALL ser as mesmas independentemente do canal selecionado.

#### Scenario: Sem configuração, o canal padrão é o de log
- **WHEN** o serviço sobe sem definir o canal de envio
- **THEN** o adaptador de log é o utilizado
- **AND** nenhum provedor externo é contactado

#### Scenario: Canal selecionado por configuração substitui o padrão
- **WHEN** o canal de envio é configurado para o adaptador de correio
- **THEN** apenas esse adaptador é utilizado
- **AND** a agenda e o registro de auditoria produzidos são os mesmos do canal padrão

### Requirement: Instantes derivados de fonte de tempo injetada

Os instantes que o serviço registra — atualização da agenda, envio da notificação e marca de processamento — SHALL vir de uma fonte de tempo injetada, e não do relógio lido diretamente no ponto de uso.

#### Scenario: Instantes registrados são governados pela fonte de tempo
- **WHEN** um evento é processado com uma fonte de tempo fixa
- **THEN** os instantes de atualização da agenda, de envio e de processamento correspondem a esse instante

### Requirement: Correlação preservada durante o processamento

O consumidor SHALL restaurar no contexto de log o identificador de correlação já validado do envelope, durante cada tentativa de processamento, e SHALL limpá-lo ao final da tentativa, inclusive quando ela falha.

#### Scenario: Correlação vale durante a tentativa e não vaza depois
- **WHEN** um evento é processado, com sucesso ou com falha
- **THEN** o identificador de correlação do envelope está disponível no contexto de log durante o processamento
- **AND** ao término da tentativa o contexto está limpo, sem vazar para a tentativa seguinte

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
