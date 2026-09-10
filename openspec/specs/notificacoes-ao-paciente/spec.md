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
