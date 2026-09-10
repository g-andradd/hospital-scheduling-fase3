# historico-de-consultas

## Purpose

Mantém uma projeção consultável do estado atual das consultas e uma trilha completa dos fatos recebidos, com efeito único por evento e sem regressão diante de reentregas ou chegada fora de ordem.

## Requirements

### Requirement: Projeção completa dos eventos de consulta

O sistema SHALL consumir os cinco tipos normativos de evento de consulta e, para cada fato aceito, manter um único snapshot atual em `consulta_historico` e acrescentar o fato à trilha `consulta_evento`.

A trilha SHALL preservar o identificador, o tipo, o instante do fato e o payload completo recebidos. O snapshot SHALL conter as dimensões de consulta, paciente, médico, especialidade, horário, status e observações fornecidas pelo evento.

#### Scenario: Sequência completa atualiza snapshot e trilha
- **WHEN** os eventos de criação, atualização e cancelamento de uma consulta são recebidos nessa ordem
- **THEN** existe um snapshot da consulta com o estado cancelado mais recente
- **AND** a trilha contém três fatos distintos na ordem de `occurredAt`

#### Scenario: Todos os tipos normativos são projetáveis
- **WHEN** é recebido um evento válido de criação, atualização, confirmação, cancelamento ou realização
- **THEN** seu fato é preservado na trilha
- **AND** seu snapshot pode materializar ou atualizar a consulta sem depender de evento anterior

### Requirement: Idempotência transacional por eventId

O sistema SHALL produzir no máximo um efeito persistido para o mesmo `eventId`. A alteração do snapshot, a inserção na trilha e o registro do evento processado SHALL pertencer à mesma transação, e o registro de processado SHALL ocorrer depois dos efeitos da projeção.

Se qualquer etapa falhar, a transação SHALL NOT deixar snapshot, fato ou marca de processamento parcial. Entregas concorrentes do mesmo `eventId` SHALL manter a mesma garantia.

#### Scenario: Reentrega sequencial não duplica efeito
- **WHEN** o mesmo evento válido é entregue novamente depois de processado
- **THEN** a trilha continua com uma única ocorrência desse `eventId`
- **AND** o snapshot e a marca de processamento não são duplicados nem alterados

#### Scenario: Reentregas concorrentes não confirmam dois efeitos
- **WHEN** duas entregas do mesmo `eventId` são processadas concorrentemente
- **THEN** somente uma transação confirma a projeção e a marca de processamento
- **AND** ao final existem um único fato na trilha e um único efeito no snapshot

#### Scenario: Falha entre o efeito e a marca desfaz a projeção
- **WHEN** ocorre uma falha depois de alterar a projeção e antes de confirmar o registro de processado
- **THEN** snapshot, trilha e marca de processamento permanecem como estavam antes da tentativa
- **AND** uma nova entrega ainda pode processar o evento integralmente

### Requirement: Snapshot monotônico por instante do fato

Todo evento válido e ainda não processado SHALL entrar na trilha. O snapshot SHALL ser substituído somente quando o `occurredAt` recebido for maior ou igual ao `atualizado_em` já projetado; um fato estritamente anterior SHALL NOT regredir seus campos.

A decisão SHALL ser atômica no armazenamento, inclusive quando eventos da mesma consulta são processados concorrentemente. `atualizado_em` SHALL representar o `occurredAt` do fato aplicado, não o horário de consumo.

#### Scenario: Evento antigo amplia a trilha sem regredir o snapshot
- **WHEN** um evento válido com `occurredAt` anterior ao snapshot atual chega depois de um evento mais novo
- **THEN** o evento antigo é acrescentado à trilha e marcado como processado
- **AND** nenhum campo nem o `atualizado_em` do snapshot volta ao valor anterior

#### Scenario: Eventos fora de ordem processados concorrentemente preservam o mais novo
- **WHEN** dois eventos ainda não processados da mesma consulta, com instantes diferentes, disputam a atualização do snapshot
- **THEN** ambos aparecem uma vez na trilha
- **AND** o snapshot final corresponde ao evento com maior `occurredAt`

#### Scenario: Fatos no mesmo instante seguem a ordem de chegada
- **WHEN** dois eventos distintos da mesma consulta têm o mesmo `occurredAt` e são recebidos em sequência
- **THEN** ambos são preservados na trilha
- **AND** o segundo evento recebido torna-se o snapshot atual

### Requirement: Rejeição segura de entradas não processáveis

O consumidor SHALL usar as três tentativas e a rejeição sem requeue definidas em `mensageria-de-eventos`. JSON malformado, campo obrigatório inválido, metadado incompatível, versão de envelope diferente de 1 ou falha persistente de projeção SHALL terminar nas DLQs normativas, sem repetição infinita.

Uma entrada rejeitada SHALL NOT criar ou alterar snapshot, trilha ou registro de processamento. Depois da rejeição, o consumidor SHALL continuar apto a processar uma mensagem válida.

#### Scenario: Mensagem malformada não deixa efeito e chega à DLQ
- **WHEN** uma mensagem destinada ao histórico contém JSON ou estrutura inválidos
- **THEN** ela é tentada três vezes e rejeitada para a DLQ sem requeue infinito
- **AND** nenhuma tabela do histórico registra efeito da mensagem

#### Scenario: Versão desconhecida é rejeitada
- **WHEN** o envelope recebido declara uma versão diferente de 1
- **THEN** o processamento de negócio não é executado e a mensagem chega à DLQ após três tentativas
- **AND** snapshot, trilha e registro de processamento permanecem inalterados

#### Scenario: Falha persistente da projeção reverte cada tentativa
- **WHEN** a projeção de um evento válido falha em todas as três tentativas
- **THEN** cada transação é revertida e a mensagem termina na DLQ
- **AND** não existe trilha, snapshot parcial nem marca de processamento desse evento

#### Scenario: Mensagem válida posterior continua sendo processada
- **WHEN** uma mensagem válida chega depois de uma entrada enviada à DLQ
- **THEN** o consumidor permanece ativo e confirma normalmente sua projeção

### Requirement: Projeção autossuficiente e desacoplada

O histórico SHALL obter todos os dados necessários exclusivamente do envelope e do snapshot do evento. O processamento SHALL NOT chamar o serviço de agendamento nem depender de sua disponibilidade.

Campos obrigatórios ausentes SHALL ser tratados como falha de contrato e seguir a rejeição segura, sem tentativa de completar dados por chamada remota.

#### Scenario: Evento completo materializa o histórico sem consulta remota
- **WHEN** um evento válido contém o snapshot normativo completo
- **THEN** o histórico materializa paciente, médico, especialidade, horário, status e observações usando somente a mensagem
- **AND** a indisponibilidade HTTP do agendamento não interfere no processamento

#### Scenario: Campo obrigatório ausente não dispara busca no produtor
- **WHEN** o evento não contém um campo obrigatório para a projeção
- **THEN** ele é rejeitado conforme a política de mensagens inválidas
- **AND** nenhuma chamada ao agendamento é realizada e nenhuma projeção parcial é persistida

### Requirement: Dimensões preservadas para consulta futura

O snapshot SHALL persistir separadamente o identificador e o nome do paciente, o identificador e o nome do médico, a especialidade, `dataHora` como instante, status e observações. Esses valores SHALL refletir o fato mais recente aplicável e permitir que a futura API consulte por período, status, paciente e médico sem reconstruir dados do JSON da trilha.

#### Scenario: Snapshot mantém as dimensões do evento aplicado
- **WHEN** um evento mais novo altera horário, médico, status ou observações
- **THEN** as dimensões correspondentes do snapshot passam a refletir os valores posteriores do payload
- **AND** os demais valores permanecem iguais ao snapshot recebido

