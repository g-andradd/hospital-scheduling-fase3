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

### Requirement: Consulta flexível do histórico por paciente e por médico

O sistema SHALL expor por GraphQL a leitura do histórico de um paciente e de um médico, aceitando um filtro opcional com período, lista de status e intervalo explícito.

O período SHALL ter três valores: `TODAS` não aplica recorte temporal; `FUTURAS` seleciona registros com `dataHora >= instante atual`; `PASSADAS` seleciona registros com `dataHora < instante atual`. O intervalo explícito SHALL tratar `de` como inclusivo e `ate` como exclusivo. Período, intervalo e status SHALL ser combinados por conjunção, e a seleção de status SHALL casar qualquer valor da lista informada.

O instante atual SHALL vir de uma fonte de tempo injetada, nunca do relógio lido diretamente no ponto de uso. O resultado SHALL ter ordenação total e determinística por `dataHora` e, em empate, por identificador do registro.

#### Scenario: Período TODAS não recorta pelo relógio
- **WHEN** o histórico de um paciente com registros passados, presentes e futuros é consultado com período `TODAS`
- **THEN** todos os registros daquele paciente são devolvidos
- **AND** nenhum registro é omitido por causa do instante atual

#### Scenario: Período FUTURAS devolve apenas o que ainda não passou
- **WHEN** o histórico é consultado com período `FUTURAS`
- **THEN** somente registros com `dataHora` maior ou igual ao instante atual são devolvidos

#### Scenario: Período PASSADAS devolve apenas o que já ocorreu
- **WHEN** o histórico é consultado com período `PASSADAS`
- **THEN** somente registros com `dataHora` estritamente anterior ao instante atual são devolvidos

#### Scenario: Registro exatamente no instante atual pertence ao futuro
- **WHEN** existe um registro cujo `dataHora` é exatamente o instante atual
- **THEN** ele aparece na consulta com período `FUTURAS`
- **AND** não aparece na consulta com período `PASSADAS`

#### Scenario: Intervalo explícito inclui o início e exclui o fim
- **WHEN** o filtro informa `de` e `ate` e existem registros exatamente em cada extremo
- **THEN** o registro em `de` é devolvido
- **AND** o registro em `ate` não é devolvido

#### Scenario: Filtro por status seleciona os valores informados
- **WHEN** o filtro informa um ou mais status
- **THEN** somente registros cujo status pertence à lista informada são devolvidos

#### Scenario: Lista de status vazia não restringe o resultado
- **WHEN** o filtro informa uma lista de status vazia
- **THEN** o resultado é o mesmo que o de uma consulta sem filtro de status

#### Scenario: Período, intervalo e status são combinados por conjunção
- **WHEN** o filtro informa período, intervalo e status simultaneamente
- **THEN** apenas os registros que satisfazem todas as condições ao mesmo tempo são devolvidos

#### Scenario: Ausência de registros devolve coleção vazia
- **WHEN** nenhum registro satisfaz o filtro informado
- **THEN** a operação responde com uma coleção vazia e sem erro

#### Scenario: Ordenação é total e determinística
- **WHEN** a mesma consulta é executada repetidamente sobre os mesmos dados, incluindo registros com `dataHora` idêntico
- **THEN** os registros vêm ordenados por `dataHora` e, em empate, por identificador
- **AND** a ordem é idêntica em todas as execuções

### Requirement: Consulta de um registro do histórico por identificador

O sistema SHALL permitir consultar um único registro do histórico pelo identificador da consulta, devolvendo as dimensões do snapshot. Identificador inexistente SHALL produzir uma recusa de recurso não encontrado, e não um erro inesperado nem um registro vazio.

#### Scenario: Identificador existente devolve o registro
- **WHEN** um registro do histórico é consultado por um identificador existente e o solicitante está autorizado
- **THEN** o registro é devolvido com paciente, médico, especialidade, horário, status e observações do snapshot

#### Scenario: Identificador inexistente é recusado como não encontrado
- **WHEN** um registro é consultado por um identificador que não existe
- **THEN** a resposta carrega o código de erro estável de recurso não encontrado
- **AND** nenhuma resposta de erro interno é produzida

### Requirement: Autorização por operação e perfil no histórico

Cada operação GraphQL SHALL aplicar a matriz de autorização normativa do histórico, com uma decisão explícita por perfil. `consultasDoPaciente` e `consulta` SHALL ser acessíveis a MEDICO e ENFERMEIRO sobre qualquer registro e ao PACIENTE somente sobre os próprios. `consultasDoMedico` SHALL ser negada ao PACIENTE. `minhasConsultas` SHALL ser exclusiva do PACIENTE e resolver a identidade do paciente somente a partir do token, sem aceitar identificador como argumento. `corrigirRegistroHistorico` SHALL ser exclusiva do MEDICO.

Nenhuma operação SHALL confiar em identificador de identidade enviado pelo cliente para decidir acesso. Uma operação nova sem decisão de autorização declarada SHALL NOT ficar acessível silenciosamente.

#### Scenario: Cada célula da matriz é honrada
- **WHEN** cada operação do histórico é invocada por cada um dos três perfis, conforme a matriz normativa da documentação
- **THEN** as células permitidas respondem com sucesso
- **AND** as células proibidas respondem com o código de erro estável de acesso negado

#### Scenario: Paciente não alcança registro de terceiro por identificador
- **WHEN** um PACIENTE consulta o histórico informando o identificador de outro paciente, ou consulta um registro que não é seu
- **THEN** a operação é negada com o código de acesso negado
- **AND** nenhum dado do outro paciente é devolvido, nem parcialmente

#### Scenario: Consultas do paciente autenticado vêm do token
- **WHEN** um PACIENTE invoca a operação das próprias consultas sem informar identificador algum
- **THEN** são devolvidas exatamente as consultas vinculadas ao paciente do token

#### Scenario: Perfis clínicos não usam a operação do paciente
- **WHEN** um MEDICO ou um ENFERMEIRO invoca a operação das próprias consultas do paciente
- **THEN** a operação é negada com o código de acesso negado
- **AND** os demais caminhos de leitura do histórico continuam disponíveis para esses perfis

#### Scenario: Operação sem decisão de autorização é recusada pela cobertura
- **WHEN** uma operação exposta pelo histórico não declara decisão de autorização
- **THEN** a verificação estrutural do serviço falha, apontando a operação desprotegida

### Requirement: Fronteira autenticada e erros estáveis do endpoint GraphQL

O endpoint GraphQL SHALL exigir autenticação na fronteira HTTP: requisição sem token ou com token inválido SHALL ser recusada antes de qualquer execução de operação. O serviço SHALL NOT emitir tokens nem oferecer autenticação própria; a identidade vem do mesmo token dos demais serviços.

Recusa de autorização, recurso inexistente e entrada inválida SHALL ser traduzidos para erros GraphQL com códigos estáveis de acesso negado, não encontrado e requisição inválida, nunca para erro interno. Erro inesperado SHALL NOT expor SQL, rastreamento de pilha ou nome de classe.

A mesma política de códigos e de sanitização SHALL valer para as duas naturezas de falha: a recusa do documento durante a análise e a validação contra o schema, anterior à execução de qualquer resolver, e a exceção lançada durante a execução de um resolver. Falha anterior à execução SHALL produzir o código estável de requisição inválida, SHALL NOT executar resolver algum e SHALL NOT persistir efeito. A interface interativa de exploração SHALL estar disponível apenas nos profiles de desenvolvimento e demonstração.

#### Scenario: Requisição sem token é recusada na fronteira
- **WHEN** uma operação GraphQL é enviada sem token
- **THEN** a requisição é recusada como não autenticada
- **AND** nenhum resolver é executado

#### Scenario: Token inválido é recusado na fronteira
- **WHEN** uma operação GraphQL é enviada com token expirado ou com assinatura adulterada
- **THEN** a requisição é recusada como não autenticada, sem revelar qual parte do token falhou

#### Scenario: Acesso negado não vira erro interno
- **WHEN** um perfil sem permissão invoca uma operação
- **THEN** a resposta contém erro com o código estável de acesso negado
- **AND** a resposta não é um erro interno do servidor

#### Scenario: Entrada inválida é recusada como requisição inválida
- **WHEN** uma operação é enviada com campo desconhecido no input, valor de enum inexistente ou argumento de tipo incompatível, recusados na análise do documento
- **THEN** a resposta contém erro com o código estável de requisição inválida
- **AND** nenhum resolver é executado e nenhum efeito é persistido
- **WHEN** uma operação chega ao resolver com valor fora do domínio aceito pela regra de negócio
- **THEN** a resposta também contém erro com o código estável de requisição inválida

#### Scenario: Falha inesperada não vaza detalhe interno
- **WHEN** ocorre uma falha inesperada, seja na análise do documento, seja durante a execução de uma operação
- **THEN** a mensagem devolvida é genérica
- **AND** não contém SQL, rastreamento de pilha nem nome de classe

#### Scenario: Interface interativa existe apenas em dev e demo
- **WHEN** o serviço sobe nos profiles de desenvolvimento ou demonstração
- **THEN** a interface interativa de exploração responde
- **AND** no profile padrão ela não está disponível

#### Scenario: Caminhos não relacionados continuam negados
- **WHEN** um caminho HTTP que ninguém liberou explicitamente é requisitado no histórico
- **THEN** ele permanece inacessível
- **AND** a admissão do endpoint GraphQL não abre nenhum outro caminho

### Requirement: Correção manual auditada do registro do histórico

O sistema SHALL permitir que um MEDICO corrija um registro do histórico. Os campos corrigíveis SHALL ser nome do paciente, nome do médico, especialidade, horário, status e observações. Os identificadores da consulta, do paciente e do médico SHALL ser imutáveis e SHALL NOT ser alterados pela correção.

O identificador da consulta SHALL servir apenas como seletor do registro alvo. O contrato de entrada SHALL NOT expor campo de autor nem campo de identificador de consulta, de paciente ou de médico entre os corrigíveis, e um campo desconhecido SHALL ser recusado antes da execução da operação.

A correção SHALL exigir justificativa não vazia e ao menos um campo efetivamente alterado. O status informado SHALL pertencer ao conjunto válido de status, mas a correção SHALL NOT aplicar as transições de estado do agendamento, por ser correção de registro e não ato de agenda. O autor SHALL ser obtido exclusivamente do token.

Na mesma transação, o sistema SHALL atualizar o snapshot, marcar seu instante de atualização e acrescentar à trilha um fato local de correção contendo autor, justificativa, campos corrigidos e valores anterior e posterior. Se qualquer etapa falhar, nada SHALL permanecer alterado. A correção SHALL NOT registrar marca de evento processado nem publicar evento.

#### Scenario: Correção válida altera o snapshot e registra a trilha
- **WHEN** um MEDICO corrige campos permitidos de um registro existente, com justificativa
- **THEN** o snapshot passa a refletir os valores corrigidos e seu instante de atualização avança
- **AND** a trilha ganha um fato de correção com autor, justificativa e valores anterior e posterior

#### Scenario: Correção sem alteração efetiva é recusada
- **WHEN** a correção não altera valor algum em relação ao snapshot atual
- **THEN** a operação é recusada como requisição inválida
- **AND** nenhum fato de correção é acrescentado à trilha

#### Scenario: Justificativa ausente ou vazia é recusada
- **WHEN** a correção é enviada sem justificativa ou com justificativa em branco
- **THEN** a operação é recusada como requisição inválida
- **AND** o snapshot permanece inalterado

#### Scenario: Identificadores não existem como campos corrigíveis
- **WHEN** o contrato de entrada da correção é inspecionado
- **THEN** o identificador da consulta aparece apenas como seletor do registro alvo, sem contraparte corrigível
- **AND** não existe campo corrigível de identificador de paciente nem de médico
- **AND** depois de uma correção confirmada, os três identificadores do snapshot permanecem os originais

#### Scenario: Autor da correção vem do token
- **WHEN** o contrato de entrada da correção é inspecionado e, em seguida, um MEDICO autenticado confirma uma correção
- **THEN** o contrato não expõe campo algum de autor
- **AND** o autor registrado na trilha é o médico identificado pelo token

#### Scenario: Campo desconhecido de entrada é recusado antes da execução
- **WHEN** a correção é enviada com um campo que não pertence ao contrato de entrada, como um autor ou um identificador
- **THEN** a operação é recusada com o código estável de requisição inválida, antes de a correção ser executada
- **AND** nenhum efeito é persistido no snapshot nem na trilha

#### Scenario: Status corrigido precisa pertencer ao domínio válido
- **WHEN** a correção informa um status fora do conjunto válido
- **THEN** a operação é recusada como requisição inválida

#### Scenario: Falha na auditoria desfaz a correção inteira
- **WHEN** a gravação do fato de correção falha depois da alteração do snapshot
- **THEN** o snapshot volta ao estado anterior
- **AND** nenhum fato de correção permanece na trilha

#### Scenario: Correções concorrentes não perdem alteração
- **WHEN** duas correções do mesmo registro são processadas concorrentemente
- **THEN** ambas aparecem na trilha
- **AND** o snapshot final reflete a última correção confirmada, sem perder a anterior silenciosamente

### Requirement: Convivência entre correção manual e projeção de eventos

O fato de correção SHALL ser um tipo local da trilha do histórico e SHALL NOT alterar os tipos normativos de evento, as routing keys nem a topologia de mensageria.

Depois de uma correção, um evento válido com instante do fato anterior ao instante da correção SHALL NOT regredir o snapshot, e um evento válido com instante posterior SHALL voltar a atualizá-lo, seguindo a regra de monotonicidade já vigente. A correção SHALL NOT interferir na idempotência por evento.

#### Scenario: Evento antigo não desfaz a correção
- **WHEN** um evento válido com instante anterior ao da correção é recebido depois dela
- **THEN** ele é acrescentado à trilha e marcado como processado
- **AND** os campos corrigidos do snapshot permanecem como a correção os deixou

#### Scenario: Evento posterior volta a atualizar o snapshot
- **WHEN** um evento válido com instante posterior ao da correção é recebido
- **THEN** o snapshot passa a refletir esse evento

#### Scenario: Correção não altera o contrato de mensageria
- **WHEN** a trilha é inspecionada após uma correção
- **THEN** o fato de correção existe apenas no histórico
- **AND** nenhum evento é publicado e nenhum tipo normativo de evento é acrescentado ao contrato

#### Scenario: Correção não registra marca de evento processado
- **WHEN** uma correção é confirmada
- **THEN** nenhuma marca de evento processado é criada para ela

### Requirement: Leitura eficiente e apoiada pelo armazenamento

Os filtros de período, intervalo, status, paciente e médico SHALL ser aplicados pelo armazenamento, e não por seleção em memória sobre um resultado maior. Uma consulta raiz SHALL executar uma quantidade de comandos de leitura independente do número de registros devolvidos.

O esquema SHALL possuir os índices que sustentam as consultas efetivamente expostas, criados por migration nova, sem alterar a migration existente do read model.

#### Scenario: Filtro é aplicado no armazenamento
- **WHEN** uma consulta filtrada é executada
- **THEN** a seleção acontece no comando enviado ao banco
- **AND** registros que não satisfazem o filtro não são carregados para serem descartados depois

#### Scenario: Resultado maior não multiplica comandos de leitura
- **WHEN** a mesma consulta raiz devolve um registro e depois muitos registros
- **THEN** a quantidade de comandos de leitura executados é a mesma nos dois casos

#### Scenario: Índices das consultas expostas existem no esquema
- **WHEN** o esquema é inspecionado após as migrations
- **THEN** existem índices cobrindo a consulta por paciente e horário e a consulta por médico e horário
- **AND** a migration original do read model permanece inalterada

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

### Requirement: Entrada extrema da superfície GraphQL é recusada antes de qualquer efeito

Uma entrada sintaticamente válida que o histórico não consiga atender de forma íntegra SHALL ser recusada na própria requisição, com código estável e sanitizado, e não SHALL falhar depois — nem no repositório, nem na escrita do snapshot, nem na trilha.

Identificador recebido como `ID` SHALL ser validado como UUID antes de alcançar o repositório, e o identificador inválido SHALL ser recusado com `BAD_REQUEST`, seja ele informado por variável ou por literal no documento.

Os limites temporais do filtro e a data corrigida SHALL ser recusados com `BAD_REQUEST` quando estiverem fora da faixa temporal que o armazenamento representa, antes de qualquer consulta ou escrita. Violação de integridade não relacionada SHALL continuar exposta como falha real, e não SHALL ser convertida em erro do cliente.

Os textos da correção SHALL aceitar apenas caracteres representáveis pelo armazenamento e pela trilha de auditoria. Os campos `pacienteNome`, `medicoNome` e `especialidade` SHALL respeitar o limite de tamanho declarado na migração que os criou, e não um limite arbitrário. `justificativa` e `observacoes` SHALL recusar caracteres incompatíveis com a persistência, preservando a semântica existente de campo ausente e de nulo explícito.

Corpo HTTP nulo ou estruturalmente inválido SHALL ser recusado na fronteira com 4xx sanitizado, antes de qualquer execução GraphQL.

Nenhuma dessas recusas SHALL alterar o snapshot ou a trilha.

#### Scenario: Identificador inválido é recusado antes do repositório
- **WHEN** uma operação recebe um identificador que não é UUID — malformado, vazio, número ou lista —, por variável ou por literal no documento
- **THEN** a resposta tem código `BAD_REQUEST`, sem erro interno
- **AND** o repositório não é consultado e nada é escrito
- **AND** um identificador válido continua respondendo normalmente

#### Scenario: Limite temporal fora da faixa persistível é recusado antes da consulta
- **WHEN** `filtro.de` ou `filtro.ate` recebe um instante fora da faixa que o armazenamento representa
- **THEN** a resposta tem código `BAD_REQUEST`, sem erro interno
- **AND** um limite imediatamente dentro da faixa continua listando normalmente

#### Scenario: Data corrigida fora da faixa persistível é recusada antes da escrita
- **WHEN** a correção informa `dataHora` fora da faixa que o armazenamento representa
- **THEN** a resposta tem código `BAD_REQUEST`
- **AND** o snapshot permanece como estava e nenhuma trilha é registrada

#### Scenario: Texto da correção com caractere não representável é recusado
- **WHEN** qualquer texto da correção contém caractere de controle incompatível, nas bordas ou no meio
- **THEN** a resposta tem código `BAD_REQUEST`
- **AND** o snapshot permanece como estava e nenhuma trilha é registrada
- **AND** texto legítimo com acento, quebra de linha e tabulação continua aceito

#### Scenario: Texto da correção acima do limite da coluna é recusado
- **WHEN** `pacienteNome`, `medicoNome` ou `especialidade` excede o tamanho declarado na migração que criou a coluna
- **THEN** a resposta tem código `BAD_REQUEST`
- **AND** um valor exatamente no limite continua sendo aceito
- **AND** os campos de texto livre não ganham teto arbitrário

#### Scenario: Corpo HTTP inválido é recusado na fronteira
- **WHEN** a requisição chega com corpo nulo, ausente ou estruturalmente inválido
- **THEN** a resposta é 4xx, sem rastreamento de pilha, nome de classe, SQL ou nome de tabela
- **AND** nenhuma operação é executada

#### Scenario: Recusa de entrada extrema não altera snapshot nem trilha
- **WHEN** qualquer entrada extrema é recusada, em qualquer das fronteiras acima
- **THEN** o snapshot da consulta alvo permanece idêntico ao anterior à requisição
- **AND** a contagem da trilha permanece a mesma
