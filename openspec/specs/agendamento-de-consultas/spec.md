# agendamento-de-consultas

## Purpose

Governa o ciclo de vida de uma consulta hospitalar: quando ela pode ser registrada, remarcada, confirmada ou cancelada, e o que torna cada uma dessas operações inválida. É onde vivem as regras que protegem a agenda de médicos e pacientes contra sobreposição de horário, marcação retroativa e transições de estado incoerentes.

## Requirements

### Requirement: Registro de consulta

O sistema SHALL registrar uma consulta associando paciente, médico, o usuário que a registrou e um instante de início, atribuindo-lhe o status inicial `AGENDADA`.

A consulta ocupa um período que começa no instante informado e dura um número configurável de minutos, com padrão de 30.

#### Scenario: Consulta registrada com sucesso
- **WHEN** uma consulta é registrada para um paciente e um médico existentes, em um instante futuro e sem sobreposição com a agenda de nenhum dos dois
- **THEN** a consulta passa a existir com status `AGENDADA`
- **AND** o período da consulta começa no instante informado e dura a duração configurada

#### Scenario: Registro em instante passado é recusado
- **WHEN** uma consulta é registrada para um instante anterior ao momento corrente
- **THEN** a operação é recusada com um erro de agendamento no passado
- **AND** nenhuma consulta passa a existir

#### Scenario: Registro no instante corrente é recusado
- **WHEN** uma consulta é registrada exatamente para o momento corrente
- **THEN** a operação é recusada com um erro de agendamento no passado

#### Scenario: Conflito com a agenda do médico é recusado
- **WHEN** uma consulta é registrada para um médico que já possui outra consulta ativa cujo período se sobrepõe ao período solicitado
- **THEN** a operação é recusada com um erro de conflito de agenda
- **AND** nenhuma consulta passa a existir

#### Scenario: Conflito com a agenda do paciente é recusado
- **WHEN** uma consulta é registrada para um paciente que já possui outra consulta ativa cujo período se sobrepõe ao período solicitado
- **THEN** a operação é recusada com um erro de conflito de agenda

#### Scenario: Períodos adjacentes não são conflito
- **WHEN** uma consulta é registrada começando exatamente no instante em que outra consulta do mesmo médico termina
- **THEN** a consulta é registrada com sucesso

#### Scenario: Consulta encerrada não bloqueia a agenda
- **WHEN** uma consulta é registrada em um período que se sobrepõe ao de uma consulta cancelada ou já realizada
- **THEN** a consulta é registrada com sucesso

#### Scenario: Registro para paciente inexistente é recusado
- **WHEN** uma consulta é registrada referenciando um paciente que não existe
- **THEN** a operação é recusada com um erro de recurso não encontrado

### Requirement: Alteração de consulta

O sistema SHALL permitir alterar o instante, o médico e as observações de uma consulta que ainda não tenha chegado a um status terminal, aplicando as mesmas regras de validade do registro.

#### Scenario: Remarcação bem-sucedida
- **WHEN** uma consulta com status `AGENDADA` é remarcada para outro instante futuro livre na agenda do médico e do paciente
- **THEN** a consulta passa a ocupar o novo período
- **AND** o status permanece inalterado

#### Scenario: Remarcação para o passado é recusada
- **WHEN** uma consulta é remarcada para um instante anterior ao momento corrente
- **THEN** a operação é recusada com um erro de agendamento no passado
- **AND** a consulta permanece no período original

#### Scenario: Remarcação com conflito é recusada
- **WHEN** uma consulta é remarcada para um período que se sobrepõe ao de outra consulta ativa do mesmo médico ou do mesmo paciente
- **THEN** a operação é recusada com um erro de conflito de agenda

#### Scenario: A própria consulta não conflita consigo mesma
- **WHEN** uma consulta é alterada mantendo o mesmo período e trocando apenas as observações
- **THEN** a alteração é aceita

#### Scenario: Alteração de consulta em status terminal é recusada
- **WHEN** uma consulta com status `CANCELADA` ou `REALIZADA` é alterada
- **THEN** a operação é recusada com um erro de transição de status inválida
- **AND** a consulta permanece inalterada

#### Scenario: Alteração de consulta inexistente é recusada
- **WHEN** é solicitada a alteração de uma consulta que não existe
- **THEN** a operação é recusada com um erro de recurso não encontrado

### Requirement: Máquina de estados da consulta

O sistema SHALL admitir apenas as transições de status `AGENDADA → CONFIRMADA`, `AGENDADA → CANCELADA`, `AGENDADA → REALIZADA`, `CONFIRMADA → REALIZADA` e `CONFIRMADA → CANCELADA`. Os status `REALIZADA` e `CANCELADA` são terminais e não admitem nenhuma transição de saída.

#### Scenario: Transições válidas a partir de AGENDADA
- **WHEN** uma consulta com status `AGENDADA` transiciona para `CONFIRMADA`, `CANCELADA` ou `REALIZADA`
- **THEN** a transição é aceita e o status é atualizado

#### Scenario: Transições válidas a partir de CONFIRMADA
- **WHEN** uma consulta com status `CONFIRMADA` transiciona para `REALIZADA` ou `CANCELADA`
- **THEN** a transição é aceita e o status é atualizado

#### Scenario: Retorno a AGENDADA é recusado
- **WHEN** uma consulta com status `CONFIRMADA` transiciona para `AGENDADA`
- **THEN** a transição é recusada com um erro de transição de status inválida

#### Scenario: Saída de status terminal é recusada
- **WHEN** uma consulta com status `CANCELADA` ou `REALIZADA` transiciona para qualquer outro status
- **THEN** a transição é recusada com um erro de transição de status inválida

#### Scenario: Transição para o mesmo status é recusada
- **WHEN** uma consulta transiciona para o status que já possui
- **THEN** a transição é recusada com um erro de transição de status inválida

### Requirement: Confirmação de consulta

O sistema SHALL permitir confirmar uma consulta que ainda não tenha chegado a um status terminal, levando-a ao status `CONFIRMADA`.

#### Scenario: Confirmação bem-sucedida
- **WHEN** uma consulta com status `AGENDADA` é confirmada
- **THEN** o status passa a ser `CONFIRMADA`

#### Scenario: Confirmação de consulta cancelada é recusada
- **WHEN** uma consulta com status `CANCELADA` é confirmada
- **THEN** a operação é recusada com um erro de transição de status inválida

#### Scenario: Confirmação de consulta já realizada é recusada
- **WHEN** uma consulta com status `REALIZADA` é confirmada
- **THEN** a operação é recusada com um erro de transição de status inválida

### Requirement: Cancelamento de consulta

O sistema SHALL permitir cancelar uma consulta que ainda não tenha chegado a um status terminal, exigindo um motivo de cancelamento não vazio e registrando-o junto à consulta.

#### Scenario: Cancelamento bem-sucedido
- **WHEN** uma consulta com status `AGENDADA` ou `CONFIRMADA` é cancelada com um motivo informado
- **THEN** o status passa a ser `CANCELADA`
- **AND** o motivo fica registrado na consulta

#### Scenario: Cancelamento sem motivo é recusado
- **WHEN** uma consulta é cancelada sem motivo, ou com um motivo vazio ou composto apenas de espaços
- **THEN** a operação é recusada por motivo obrigatório
- **AND** a consulta permanece no status anterior

#### Scenario: Cancelamento de consulta já realizada é recusado
- **WHEN** uma consulta com status `REALIZADA` é cancelada
- **THEN** a operação é recusada com um erro de transição de status inválida

#### Scenario: Cancelamento de consulta já cancelada é recusado
- **WHEN** uma consulta com status `CANCELADA` é cancelada novamente
- **THEN** a operação é recusada com um erro de transição de status inválida

### Requirement: Consulta e listagem de consultas

O sistema SHALL permitir recuperar uma consulta pelo seu identificador e listar consultas filtrando por paciente, por médico, por status e por intervalo de datas.

#### Scenario: Recuperação por identificador
- **WHEN** é solicitada a consulta correspondente a um identificador existente
- **THEN** a consulta correspondente é devolvida

#### Scenario: Recuperação de identificador inexistente
- **WHEN** é solicitada a consulta correspondente a um identificador que não existe
- **THEN** a operação é recusada com um erro de recurso não encontrado

#### Scenario: Listagem sem filtros
- **WHEN** as consultas são listadas sem nenhum filtro
- **THEN** todas as consultas registradas são devolvidas

#### Scenario: Listagem filtrada
- **WHEN** as consultas são listadas com filtro de paciente, de médico, de status ou de intervalo de datas
- **THEN** apenas as consultas que satisfazem simultaneamente todos os filtros informados são devolvidas

#### Scenario: Listagem sem resultados
- **WHEN** as consultas são listadas com um filtro que nenhuma consulta satisfaz
- **THEN** uma lista vazia é devolvida, sem erro

### Requirement: Publicação de evento a cada mudança de estado

O sistema SHALL publicar um evento de domínio a cada mudança de estado de uma consulta — registro, alteração, confirmação e cancelamento — identificando a consulta afetada e o tipo da mudança. Operação recusada por violação de regra de negócio SHALL NOT publicar evento.

#### Scenario: Registro publica evento
- **WHEN** uma consulta é registrada com sucesso
- **THEN** exatamente um evento de consulta criada é publicado, referenciando a consulta registrada

#### Scenario: Cada mudança de estado publica o evento correspondente
- **WHEN** uma consulta é alterada, confirmada ou cancelada com sucesso
- **THEN** exatamente um evento é publicado, do tipo correspondente à operação realizada

#### Scenario: Operação recusada não publica evento
- **WHEN** qualquer operação sobre uma consulta é recusada por violação de regra de negócio
- **THEN** nenhum evento é publicado

### Requirement: Validação dos identificadores de pessoa

O sistema SHALL recusar a criação de CPF, e-mail e CRM que não satisfaçam o formato esperado, impedindo que um valor inválido chegue a ser representado.

#### Scenario: CPF inválido é recusado
- **WHEN** é criado um CPF cujos dígitos verificadores não conferem, ou que não tenha onze dígitos
- **THEN** a criação é recusada por formato inválido

#### Scenario: CPF válido é aceito e normalizado
- **WHEN** é criado um CPF válido informado com pontuação
- **THEN** a criação é aceita
- **AND** o valor passa a ser representado apenas por dígitos

#### Scenario: E-mail inválido é recusado
- **WHEN** é criado um e-mail sem arroba, sem domínio ou vazio
- **THEN** a criação é recusada por formato inválido

#### Scenario: CRM inválido é recusado
- **WHEN** é criado um CRM que não siga o formato de unidade federativa seguida de número
- **THEN** a criação é recusada por formato inválido
