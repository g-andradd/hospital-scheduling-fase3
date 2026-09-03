# agendamento-de-consultas

## ADDED Requirements

### Requirement: Durabilidade das consultas

O sistema SHALL persistir consultas, usuários, pacientes e médicos de forma durável, de modo que o estado sobreviva ao reinício do serviço.

Uma consulta recuperada após o reinício SHALL apresentar exatamente os mesmos dados que tinha ao ser gravada, incluindo o instante com seu deslocamento de fuso, a duração, o status, as observações e o motivo de cancelamento.

#### Scenario: Consulta registrada sobrevive ao reinício
- **WHEN** uma consulta é registrada e o serviço é reiniciado
- **THEN** a consulta continua recuperável pelo seu identificador
- **AND** todos os seus dados são idênticos aos gravados

#### Scenario: Mudança de estado é persistida
- **WHEN** uma consulta é confirmada ou cancelada e em seguida recuperada de uma nova leitura
- **THEN** o status recuperado é o resultante da operação
- **AND** o motivo de cancelamento, quando houver, foi preservado

#### Scenario: Operação recusada não deixa registro
- **WHEN** uma operação sobre uma consulta é recusada por violação de regra de negócio
- **THEN** a consulta recuperada em uma nova leitura permanece no estado anterior à tentativa
- **AND** nenhuma alteração parcial foi gravada

#### Scenario: Consulta gravada no passado é recuperável
- **WHEN** é recuperada uma consulta cujo horário já passou
- **THEN** a consulta é devolvida normalmente
- **AND** nenhuma regra de agendamento futuro é aplicada à leitura

### Requirement: Detecção de conflito resolvida pelo armazenamento

O sistema SHALL determinar conflito de agenda consultando o armazenamento com o período e os status já como critério de busca, sem transferir a agenda completa do médico ou do paciente para avaliação em memória.

O critério de sobreposição SHALL tratar o período como intervalo semiaberto, com início inclusivo e fim exclusivo, de forma idêntica à regra já especificada.

#### Scenario: Conflito é detectado contra dados persistidos
- **WHEN** já existe consulta ativa persistida que se sobrepõe ao período solicitado
- **THEN** o registro é recusado com erro de conflito de agenda

#### Scenario: Períodos adjacentes persistidos não são conflito
- **WHEN** já existe consulta ativa persistida que termina exatamente no instante em que o período solicitado começa
- **THEN** o registro é aceito

#### Scenario: Consulta encerrada persistida não bloqueia a agenda
- **WHEN** a consulta persistida que se sobrepõe ao período solicitado está cancelada ou realizada
- **THEN** o registro é aceito

#### Scenario: Busca de conflito é delimitada na origem
- **WHEN** a agenda de um médico contém muitas consultas fora do período solicitado
- **THEN** apenas as consultas ativas que se sobrepõem ao período são devolvidas pelo armazenamento

### Requirement: Schema versionado por migration

O schema do banco SHALL ser criado e evoluído exclusivamente por migrations versionadas, sem geração automática a partir do mapeamento objeto-relacional.

#### Scenario: Provisionamento a partir de banco vazio
- **WHEN** as migrations são aplicadas sobre um banco vazio
- **THEN** todas as tabelas e índices do serviço passam a existir
- **AND** o serviço opera normalmente sobre o schema resultante

#### Scenario: Reaplicação não repete migrations já aplicadas
- **WHEN** as migrations são aplicadas sobre um banco que já está na versão corrente
- **THEN** nenhuma alteração é executada
- **AND** a operação termina com sucesso

#### Scenario: Schema divergente do esperado interrompe a subida
- **WHEN** o serviço sobe contra um banco cujo schema não corresponde às migrations conhecidas
- **THEN** a inicialização falha explicitamente
- **AND** nenhuma alteração de estrutura é feita automaticamente para acomodar a divergência

### Requirement: Unicidade das chaves naturais

O armazenamento SHALL recusar o cadastro de um segundo usuário com o mesmo e-mail, de um segundo paciente com o mesmo CPF ou de um segundo médico com o mesmo CRM.

#### Scenario: E-mail duplicado é recusado
- **WHEN** é gravado um usuário com e-mail já existente
- **THEN** a operação é recusada
- **AND** o registro original permanece inalterado

#### Scenario: CPF duplicado é recusado
- **WHEN** é gravado um paciente com CPF já existente
- **THEN** a operação é recusada

#### Scenario: CRM duplicado é recusado
- **WHEN** é gravado um médico com CRM já existente
- **THEN** a operação é recusada

### Requirement: Alteração concorrente da mesma consulta

Quando duas alterações concorrentes atingirem a mesma consulta, o sistema SHALL aplicar apenas uma delas e recusar a outra com um erro de alteração concorrente, em vez de sobrescrever silenciosamente.

#### Scenario: Segunda alteração concorrente é recusada
- **WHEN** duas alterações da mesma consulta são aplicadas concorrentemente a partir do mesmo estado inicial
- **THEN** uma delas é confirmada
- **AND** a outra é recusada com erro de alteração concorrente
- **AND** o estado final corresponde exatamente à alteração confirmada

#### Scenario: Alterações sequenciais não são afetadas
- **WHEN** duas alterações da mesma consulta acontecem uma após a outra, cada uma partindo do estado corrente
- **THEN** ambas são aplicadas com sucesso
