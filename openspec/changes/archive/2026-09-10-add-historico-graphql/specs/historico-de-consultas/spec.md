# historico-de-consultas

## ADDED Requirements

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
