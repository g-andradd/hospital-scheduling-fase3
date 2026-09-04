# agendamento-de-consultas

## ADDED Requirements

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

## MODIFIED Requirements

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
