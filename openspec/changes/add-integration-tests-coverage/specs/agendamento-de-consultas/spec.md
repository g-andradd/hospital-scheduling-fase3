## ADDED Requirements

### Requirement: Superfície HTTP resistente a entradas hostis, verificada por inventário

Toda entrada que a superfície HTTP do serviço de agendamento aceita SHALL ser exercitada com valores hostis da sua dimensão. Isso inclui o endpoint de autenticação e cada parâmetro de caminho, parâmetro de consulta, corpo e campo do corpo. As dimensões são:

- identificador UUID;
- enumeração;
- data e hora;
- inteiro;
- texto;
- corpo, que abrange JSON malformado, corpo ausente, `null`, formato incompatível, tipo de campo incompatível e tipo de conteúdo inválido.

Nenhuma resposta a entrada hostil SHALL ser 5xx nem expor detalhe interno de implementação. A recusa produzida pela aplicação SHALL ser 4xx em Problem Detail, com `correlationId` e `timestamp`. A recusa produzida pelo servidor antes de a aplicação ver a requisição SHALL ser 4xx, sem detalhe interno.

O conjunto de combinações exigidas — método, rota, entrada, dimensão e variante — SHALL ser derivado da superfície efetivamente registrada pelo serviço, e não de uma lista escrita à mão. Esse conjunto SHALL ser comparado com o conjunto de combinações efetivamente executadas, e a verificação SHALL falhar diante de:

- endpoint registrado sem classificação;
- entrada compatível sem ataque;
- dimensão sem combinação;
- ataque sem entrada correspondente;
- combinação exigida e não executada;
- inventário vazio.

Cada entrada atacada SHALL ter um controle válido, que prove que a requisição alcança a camada pretendida, sem ser barrada antes por autenticação, roteamento ou dado inexistente. A varredura completa SHALL ser executada ao menos duas vezes seguidas, e os controles SHALL continuar respondendo com sucesso depois dela.

#### Scenario: Nenhuma entrada hostil produz 5xx nem vaza detalhe interno
- **WHEN** cada combinação de endpoint, entrada, dimensão e variante é enviada com credencial válida, ou sem credencial no endpoint público de autenticação
- **THEN** nenhuma resposta tem status 5xx
- **AND** nenhum corpo de resposta contém rastreamento de pilha, nome de classe, pacote interno, SQL ou nome de constraint

#### Scenario: Recusa de entrada hostil segue Problem Detail
- **WHEN** a aplicação recusa uma entrada hostil
- **THEN** a resposta é 4xx em Problem Detail, com `type`, `title`, `status`, `correlationId` e `timestamp`
- **AND** quando a recusa vem do servidor antes da aplicação, a resposta é 4xx e não expõe detalhe interno

#### Scenario: Cada entrada compatível é atacada em todas as variantes da sua dimensão
- **WHEN** a superfície registrada é inventariada
- **THEN** cada parâmetro de caminho, parâmetro de consulta, corpo e campo do corpo, inclusive na autenticação, é classificado em uma dimensão
- **AND** todas as variantes obrigatórias daquela dimensão são executadas contra ele

#### Scenario: Endpoint ou entrada nova sem ataque é recusada
- **WHEN** um endpoint novo passa a ser registrado ou um endpoint existente passa a aceitar uma entrada nova, sem ataque correspondente
- **THEN** a verificação de inventário falha identificando o endpoint ou a entrada descoberta

#### Scenario: Ataque órfão ou combinação não executada é recusada
- **WHEN** existe um ataque cuja entrada não é registrada pelo serviço, ou uma combinação exigida não chega a ser executada
- **THEN** a verificação de inventário falha identificando a combinação

#### Scenario: Inventário vazio ou dimensão sem combinação é recusado
- **WHEN** o inventário não encontra endpoint algum, ou uma das seis dimensões fica sem combinação aplicável
- **THEN** a verificação falha, em vez de passar sem verificar nada

#### Scenario: Controle válido prova que o ataque alcança a camada pretendida
- **WHEN** a mesma requisição de uma entrada atacada é enviada com um valor válido
- **THEN** ela responde com o status de sucesso da operação
- **AND** uma entrada cujo controle não responde com sucesso faz a verificação falhar

#### Scenario: Repetição da varredura não degrada o serviço
- **WHEN** a varredura completa é executada duas vezes seguidas
- **THEN** as duas passagens produzem apenas respostas sem 5xx
- **AND** os controles de todos os endpoints continuam respondendo com sucesso ao final
