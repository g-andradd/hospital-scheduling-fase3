## ADDED Requirements

### Requirement: Superfície GraphQL resistente a entradas hostis, verificada pelo schema

Toda entrada que o endpoint GraphQL do histórico aceita SHALL ser exercitada com valores hostis da sua dimensão. Isso inclui cada argumento de cada consulta e mutação e cada campo de cada tipo de entrada, entregues por variáveis, além do documento GraphQL e do corpo HTTP que o transporta. As dimensões são:

- identificador, para o tipo `ID`;
- enumeração;
- data e hora;
- texto, para o tipo `String`;
- inteiro, exclusivamente para o tipo `Int`;
- corpo, que abrange JSON malformado, corpo ausente, `null`, formato incompatível, tipo de conteúdo inválido, documento GraphQL inválido, variáveis incompatíveis e objeto de entrada com formato incompatível.

Um escalar sem dimensão definida nesta especificação — inclusive `Float` e `Boolean` — SHALL fazer o inventário falhar até que a especificação o classifique.

Nenhuma resposta a entrada hostil SHALL ter status 5xx, erro de classificação interna ou detalhe interno exposto, como SQL, rastreamento de pilha ou nome de classe. A recusa durante a análise ou a execução da operação SHALL usar os códigos estáveis de erro do histórico. A recusa na fronteira HTTP, antes da execução GraphQL, SHALL ser 4xx sem detalhe interno. Entrada recusada SHALL NOT persistir efeito.

O conjunto de combinações exigidas — operação, entrada, dimensão e variante — SHALL ser derivado do schema efetivamente servido e comparado com o conjunto de combinações efetivamente executadas. A verificação SHALL falhar diante de:

- operação, argumento ou campo de entrada sem classificação;
- entrada sem ataque;
- dimensão presente no schema sem combinação;
- ataque sem entrada correspondente no schema;
- combinação exigida e não executada;
- inventário vazio.

Cada operação e cada entrada atacada SHALL ter um controle válido, com credencial de um perfil autorizado, que alcance o resolver e responda sem erro. A varredura completa SHALL ser executada ao menos duas vezes seguidas, e os controles SHALL continuar respondendo sem erro depois dela. As verificações de autorização e de erros GraphQL já existentes SHALL permanecer válidas.

#### Scenario: Nenhuma entrada hostil GraphQL produz 5xx, erro interno ou vazamento
- **WHEN** cada combinação de operação, entrada, dimensão e variante é enviada ao endpoint GraphQL com credencial de perfil autorizado
- **THEN** nenhuma resposta tem status 5xx nem erro de classificação interna
- **AND** nenhum corpo de resposta contém SQL, rastreamento de pilha, nome de classe ou pacote interno

#### Scenario: Recusa de entrada hostil GraphQL usa código estável e não persiste efeito
- **WHEN** uma entrada hostil é recusada na análise do documento, na coerção das variáveis ou na execução da operação
- **THEN** o erro traz um dos códigos estáveis de requisição inválida, recurso não encontrado ou acesso negado
- **AND** quando a recusa ocorre na fronteira HTTP, a resposta é 4xx sem detalhe interno
- **AND** nenhuma entrada recusada altera o snapshot ou a trilha do histórico

#### Scenario: Cada argumento e campo de entrada é atacado em todas as variantes da dimensão
- **WHEN** o schema servido é inventariado
- **THEN** cada argumento das quatro consultas e da mutação, e cada campo dos tipos de entrada, é classificado em uma dimensão
- **AND** todas as variantes obrigatórias daquela dimensão são executadas contra ele, além das variantes de documento e de corpo HTTP
- **AND** uma entrada de escalar sem dimensão definida nesta especificação faz o inventário falhar

#### Scenario: Operação, argumento ou campo de entrada novo sem ataque é recusado
- **WHEN** o schema passa a declarar uma operação, um argumento ou um campo de entrada sem ataque correspondente
- **THEN** a verificação de inventário falha identificando o elemento descoberto

#### Scenario: Ataque órfão ou combinação não executada é recusada na superfície GraphQL
- **WHEN** existe um ataque cuja entrada não consta do schema servido, ou uma combinação exigida não chega a ser executada
- **THEN** a verificação de inventário falha identificando a combinação

#### Scenario: Inventário GraphQL vazio ou dimensão sem combinação é recusado
- **WHEN** o inventário não encontra operação alguma, ou uma dimensão presente no schema fica sem combinação aplicável
- **THEN** a verificação falha, em vez de passar sem verificar nada

#### Scenario: Controle válido alcança o resolver
- **WHEN** a mesma operação de uma entrada atacada é enviada com valores válidos e credencial de perfil autorizado
- **THEN** ela responde sem erro, com o resultado produzido pelo resolver
- **AND** uma entrada cujo controle não responde sem erro faz a verificação falhar

#### Scenario: Repetição da varredura GraphQL não degrada o serviço
- **WHEN** a varredura completa é executada duas vezes seguidas
- **THEN** as duas passagens produzem apenas respostas sem 5xx nem erro interno
- **AND** os controles de todas as operações continuam respondendo sem erro ao final
