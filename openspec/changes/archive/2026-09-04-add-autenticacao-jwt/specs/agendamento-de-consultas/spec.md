# agendamento-de-consultas

## MODIFIED Requirements

### Requirement: Consulta e listagem de consultas

O sistema SHALL permitir recuperar uma consulta pelo seu identificador e listar consultas filtrando por paciente, por médico, por status e por intervalo de datas.

A listagem SHALL ser paginada, devolvendo, além dos elementos da página, o número da página, o tamanho aplicado e o total de elementos que satisfazem o filtro.

O tamanho de página SHALL ter um teto. Um pedido de página maior que o teto SHALL ser atendido com o teto aplicado, e não recusado nem servido integralmente.

A paginação SHALL ser resolvida pelo armazenamento, que devolve apenas os elementos da página pedida.

O conjunto de consultas visível na listagem SHALL depender do perfil do solicitante. Para um usuário que seja paciente, a listagem SHALL apresentar apenas as consultas de que ele é o titular, independentemente dos filtros informados.

#### Scenario: Recuperação por identificador
- **WHEN** é solicitada a consulta correspondente a um identificador existente
- **THEN** a consulta correspondente é devolvida

#### Scenario: Recuperação de identificador inexistente
- **WHEN** é solicitada a consulta correspondente a um identificador que não existe
- **THEN** a operação é recusada com um erro de recurso não encontrado

#### Scenario: Listagem sem filtros
- **WHEN** um solicitante sem recorte por identidade lista consultas sem nenhum filtro
- **THEN** a primeira página das consultas registradas é devolvida
- **AND** o total informado corresponde à quantidade de consultas registradas

#### Scenario: Listagem recortada pela identidade do paciente
- **WHEN** um solicitante que é paciente lista consultas
- **THEN** apenas as consultas de que ele é o titular são devolvidas
- **AND** o total informado corresponde apenas a essas consultas

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
