# agendamento-de-consultas

## MODIFIED Requirements

### Requirement: Durabilidade das consultas

O sistema SHALL persistir consultas, usuários, pacientes e médicos de forma durável, de modo que o estado sobreviva ao reinício do serviço.

Uma consulta recuperada após o reinício SHALL apresentar os mesmos dados que tinha ao ser gravada, incluindo o instante, a duração, o status, as observações e o motivo de cancelamento. Para o horário, a igualdade SHALL ser definida pelo instante; o deslocamento de fuso informado originalmente não tem garantia de preservação.

#### Scenario: Consulta registrada sobrevive ao reinício
- **WHEN** uma consulta é registrada e o serviço é reiniciado
- **THEN** a consulta continua recuperável pelo seu identificador
- **AND** seus dados são preservados, comparando o horário pelo instante

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

#### Scenario: Leitura com outro deslocamento preserva o instante
- **WHEN** uma consulta gravada com um offset é recuperada em uma sessão configurada com outro fuso
- **THEN** o instante recuperado é igual ao gravado, mesmo que o deslocamento ou a representação textual sejam diferentes

### Requirement: Detecção de conflito resolvida pelo armazenamento

O sistema SHALL determinar conflito de agenda consultando o armazenamento com o período e os status já como critério de busca, sem transferir a agenda completa do médico ou do paciente para avaliação em memória.

O critério de sobreposição SHALL tratar o período como intervalo semiaberto, com início inclusivo e fim exclusivo, de forma idêntica à regra já especificada.

O armazenamento SHALL impedir que consultas ativas (`AGENDADA` ou `CONFIRMADA`) com períodos sobrepostos para o mesmo médico ou o mesmo paciente sejam confirmadas, inclusive quando transações concorrentes tenham ambas verificado ausência de conflito antes de escrever. A regra SHALL valer tanto para inserção como para alteração, inclusive por escrita direta no armazenamento.

A operação perdedora SHALL produzir erro de conflito de agenda para exclusão definitiva (`23P01`) ou alteração concorrente para falha transitória (`40P01`/`40001`), sem retry automático, mapeado para `409 ProblemDetail` com `correlationId` e `timestamp`, sem detalhe interno do banco. Sua transação SHALL NOT persistir alteração da consulta nem evento de outbox. Consultas `CANCELADA` e `REALIZADA` SHALL NOT bloquear o período.

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

#### Scenario: Inserções concorrentes do mesmo médico não confirmam juntas
- **WHEN** duas transações para pacientes distintos verificam agenda livre e tentam inserir consultas sobrepostas do mesmo médico
- **THEN** apenas uma é confirmada e a outra é recusada por conflito de agenda ou alteração concorrente
- **AND** existe apenas uma consulta nova e um evento correspondente persistidos

#### Scenario: Inserções concorrentes do mesmo paciente não confirmam juntas
- **WHEN** duas transações para médicos distintos verificam agenda livre e tentam inserir consultas sobrepostas do mesmo paciente
- **THEN** apenas uma é confirmada e a outra é recusada por conflito de agenda ou alteração concorrente
- **AND** existe apenas uma consulta nova e um evento correspondente persistidos

#### Scenario: Remarcações concorrentes não criam sobreposição
- **WHEN** duas consultas distintas são alteradas concorrentemente para períodos sobrepostos do mesmo médico ou paciente após ambas passarem pela pré-verificação
- **THEN** apenas uma alteração é confirmada e a outra é recusada por conflito de agenda ou alteração concorrente
- **AND** a consulta perdedora conserva o estado anterior e não produz evento adicional

#### Scenario: Conflito concorrente pela API retorna Problem Detail
- **WHEN** duas requisições autorizadas de registro disputam o mesmo período e recurso
- **THEN** uma responde 201 e a outra responde 409 com o type de conflito de agenda ou alteração concorrente, correlationId e timestamp
- **AND** nenhuma resposta é 5xx e existe apenas uma consulta nova com seu evento no outbox
- **AND** a resposta não expõe SQL, constraint, stack trace ou nome de classe

#### Scenario: Escrita direta não contorna a exclusão
- **WHEN** uma escrita direta tenta inserir ou alterar uma consulta ativa para sobrepor outra do mesmo médico ou paciente
- **THEN** o armazenamento recusa a escrita, mesmo sem executar a pré-verificação da aplicação

#### Scenario: Alteração de período mantém a exclusão coerente
- **WHEN** horário ou duração de uma consulta são modificados e o novo período é confrontado com consultas ativas
- **THEN** a exclusão usa o novo período completo e permite apenas adjacência ou ausência de sobreposição

#### Scenario: Consultas concorrentes sem recurso em comum são aceitas
- **WHEN** duas transações inserem períodos sobrepostos com médicos e pacientes distintos
- **THEN** ambas são confirmadas normalmente
