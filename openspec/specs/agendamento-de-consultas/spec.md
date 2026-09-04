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

A listagem SHALL ser paginada, devolvendo, além dos elementos da página, o número da página, o tamanho aplicado e o total de elementos que satisfazem o filtro.

O tamanho de página SHALL ter um teto. Um pedido de página maior que o teto SHALL ser atendido com o teto aplicado, e não recusado nem servido integralmente.

A paginação SHALL ser resolvida pelo armazenamento, que devolve apenas os elementos da página pedida.

#### Scenario: Recuperação por identificador
- **WHEN** é solicitada a consulta correspondente a um identificador existente
- **THEN** a consulta correspondente é devolvida

#### Scenario: Recuperação de identificador inexistente
- **WHEN** é solicitada a consulta correspondente a um identificador que não existe
- **THEN** a operação é recusada com um erro de recurso não encontrado

#### Scenario: Listagem sem filtros
- **WHEN** as consultas são listadas sem nenhum filtro
- **THEN** a primeira página das consultas registradas é devolvida
- **AND** o total informado corresponde à quantidade de consultas registradas

#### Scenario: Listagem filtrada
- **WHEN** as consultas são listadas com filtro de paciente, de médico, de status ou de intervalo de datas
- **THEN** apenas as consultas que satisfazem simultaneamente todos os filtros informados são devolvidas

#### Scenario: Listagem sem resultados
- **WHEN** as consultas são listadas com um filtro que nenhuma consulta satisfaz
- **THEN** uma página vazia é devolvida, sem erro
- **AND** o total informado é zero

#### Scenario: Navegação entre páginas
- **WHEN** é pedida uma página posterior à primeira
- **THEN** os elementos devolvidos são os daquela página
- **AND** nenhum elemento se repete entre páginas consecutivas

#### Scenario: Tamanho de página acima do teto
- **WHEN** é pedida uma página com tamanho maior que o teto configurado
- **THEN** a página é devolvida com o teto aplicado
- **AND** a requisição não é recusada

#### Scenario: Paginação resolvida na origem
- **WHEN** é pedida uma página de uma listagem com muitos elementos
- **THEN** o armazenamento devolve apenas os elementos daquela página

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

### Requirement: Exposição HTTP das operações de consulta

O sistema SHALL expor por HTTP o registro, a alteração, a confirmação, o cancelamento, a recuperação e a listagem de consultas, sob o caminho `/api/v1/consultas`.

Uma requisição bem-sucedida de registro SHALL responder `201 Created` com o cabeçalho `Location` apontando para o recurso criado. As demais operações bem-sucedidas SHALL responder `200 OK` com a representação resultante da consulta.

Nesta etapa os endpoints SHALL estar acessíveis sem autenticação; o controle de acesso por perfil é acrescentado posteriormente.

#### Scenario: Registro bem-sucedido
- **WHEN** é enviada uma requisição de registro com paciente, médico, registrante e instante futuro válidos
- **THEN** a resposta é `201 Created`
- **AND** o cabeçalho `Location` aponta para o recurso da consulta criada
- **AND** o corpo apresenta a consulta com status `AGENDADA`

#### Scenario: Alteração bem-sucedida
- **WHEN** é enviada uma requisição de alteração para uma consulta existente e alterável
- **THEN** a resposta é `200 OK`
- **AND** o corpo apresenta a consulta já alterada

#### Scenario: Campo ausente na alteração preserva o valor atual
- **WHEN** é enviada uma alteração que informa apenas o novo instante, sem observações
- **THEN** a resposta é `200 OK`
- **AND** as observações registradas anteriormente permanecem inalteradas

#### Scenario: Confirmação bem-sucedida
- **WHEN** é enviada uma requisição de confirmação para uma consulta agendada
- **THEN** a resposta é `200 OK`
- **AND** o corpo apresenta a consulta com status `CONFIRMADA`

#### Scenario: Cancelamento bem-sucedido
- **WHEN** é enviada uma requisição de cancelamento com motivo para uma consulta ativa
- **THEN** a resposta é `200 OK`
- **AND** o corpo apresenta a consulta com status `CANCELADA` e o motivo registrado

#### Scenario: Recuperação bem-sucedida
- **WHEN** é solicitada por HTTP uma consulta existente
- **THEN** a resposta é `200 OK` com a representação da consulta

#### Scenario: Operação executada dentro de uma transação
- **WHEN** uma operação de escrita é recusada por violação de regra de negócio depois de a consulta ter sido carregada
- **THEN** nenhuma alteração é persistida

### Requirement: Erros no formato Problem Detail

Toda resposta de erro SHALL usar o formato RFC 7807, com os campos `type`, `title`, `status`, `detail` e `instance`, acrescidos de `correlationId` e `timestamp`.

O campo `type` SHALL identificar a categoria do erro por uma URI estável, distinta por categoria. Nenhuma resposta de erro SHALL expor stack trace ou detalhe interno de implementação.

Toda condição de erro que o sistema é capaz de produzir SHALL ter tratamento explícito; nenhuma delas SHALL resultar em `500` por ausência de tratador.

#### Scenario: Agendamento no passado
- **WHEN** uma requisição tenta registrar consulta em instante já passado
- **THEN** a resposta é `422` no formato Problem Detail

#### Scenario: Conflito de agenda
- **WHEN** uma requisição tenta ocupar horário já ocupado por consulta ativa
- **THEN** a resposta é `409` no formato Problem Detail

#### Scenario: Recurso não encontrado
- **WHEN** uma requisição referencia consulta, paciente ou médico inexistente
- **THEN** a resposta é `404` no formato Problem Detail
- **AND** o `detail` identifica qual recurso não foi encontrado

#### Scenario: Transição de status inválida
- **WHEN** uma requisição tenta uma transição que a máquina de estados não admite
- **THEN** a resposta é `409` no formato Problem Detail

#### Scenario: Cancelamento sem motivo
- **WHEN** uma requisição de cancelamento não informa motivo
- **THEN** a resposta é `422` no formato Problem Detail

#### Scenario: Alteração concorrente
- **WHEN** uma alteração é recusada porque a consulta foi modificada por outra operação
- **THEN** a resposta é `409` no formato Problem Detail
- **AND** o `type` é distinto do usado para conflito de agenda
- **AND** o `detail` orienta a recarregar o recurso e repetir a operação

#### Scenario: Argumento malformado
- **WHEN** uma requisição carrega um valor que não satisfaz o formato exigido pelo domínio
- **THEN** a resposta é `400` no formato Problem Detail

#### Scenario: Validação de corpo com múltiplos campos inválidos
- **WHEN** uma requisição chega com mais de um campo inválido
- **THEN** a resposta é `400` no formato Problem Detail
- **AND** o corpo relaciona todos os campos inválidos, cada um com sua mensagem

#### Scenario: Falha inesperada não vaza detalhe interno
- **WHEN** ocorre uma falha para a qual não há tratamento específico
- **THEN** a resposta é `500` no formato Problem Detail
- **AND** o corpo não contém stack trace nem nome de classe interna

#### Scenario: Correlação presente em toda resposta de erro
- **WHEN** qualquer resposta de erro é produzida
- **THEN** ela apresenta `correlationId` e `timestamp` preenchidos

### Requirement: Documentação da API

O sistema SHALL publicar a especificação OpenAPI da API e uma interface de navegação sobre ela.

#### Scenario: Especificação disponível
- **WHEN** a especificação OpenAPI é solicitada
- **THEN** ela é devolvida contendo todos os endpoints de consulta expostos

#### Scenario: Interface de navegação disponível
- **WHEN** a interface de documentação é acessada
- **THEN** ela apresenta os endpoints e seus contratos de requisição e resposta
