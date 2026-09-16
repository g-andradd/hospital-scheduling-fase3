## ADDED Requirements

### Requirement: Pacote Postman versionado e autoexecutável
O projeto SHALL fornecer uma collection Postman e um environment local versionados, importáveis no Collection Runner e executáveis integralmente na ordem declarada sem editar requests, colar tokens ou preencher variáveis durante a execução. O pacote SHALL usar apenas credenciais públicas do profile `demo`, SHALL iniciar cada execução sem depender de tokens ou identificadores persistidos por execução anterior e SHALL manter vazios os valores derivados que não são dados fixos do ambiente.

#### Scenario: Collection e environment são importáveis
- **WHEN** os dois artefatos versionados são carregados por um runner compatível com o formato Postman
- **THEN** ambos são aceitos sem conversão manual, arquivo adicional ou dependência de recurso remoto para executar, admitindo apenas a URI normativa de schema como metadado do formato

#### Scenario: Pastas obrigatórias preservam a ordem
- **WHEN** a collection é inspecionada
- **THEN** suas cinco pastas de primeiro nível são, nesta ordem, `00-Auth`, `01-Agendamento`, `02-Historico-GraphQL`, `03-Cenarios-de-Seguranca` e `04-Cenarios-de-Erro`

#### Scenario: Quatro logins salvam tokens independentes
- **WHEN** a pasta `00-Auth` é executada com o profile `demo`
- **THEN** médico, enfermeiro, paciente e segundo paciente são autenticados e cada resposta salva seu `accessToken` em uma variável distinta para os requests seguintes

#### Scenario: Execução completa não requer intervenção
- **WHEN** a collection inteira é executada contra um ambiente completo e saudável
- **THEN** os requests terminam na ordem declarada com todas as asserções aprovadas e sem edição manual, colagem de token ou entrada interativa

#### Scenario: Nova execução não depende da anterior
- **WHEN** a collection é executada novamente, inclusive com a opção do Runner que preserva valores locais
- **THEN** ela limpa ou sobrescreve tokens e valores derivados antes de usá-los, cria os próprios dados de trabalho e termina novamente com todas as asserções aprovadas

#### Scenario: Toda requisição comprova status e contrato mínimo
- **WHEN** qualquer request da collection recebe uma resposta
- **THEN** seu script executa pelo menos uma asserção de status e uma asserção sobre campos ou códigos relevantes daquela resposta

### Requirement: Jornada de validação cobre as APIs entregues
A collection SHALL demonstrar o fluxo principal de agendamento REST, a leitura e a correção autorizada do histórico GraphQL e o disparo manual do lembrete, usando variáveis encadeadas entre requests e respeitando a consistência eventual. Ela SHALL comprovar separadamente recusas de autenticação, autorização e entrada sem transformar respostas GraphQL com erro em sucesso da jornada.

#### Scenario: Jornada REST encadeia o ciclo da consulta
- **WHEN** a pasta `01-Agendamento` é executada
- **THEN** uma consulta futura é criada, seu identificador é salvo e as operações seguintes de leitura, alteração e mudança de estado usam esse mesmo identificador

#### Scenario: Histórico projetado é lido por GraphQL
- **WHEN** a consulta criada ainda não apareceu no read model e a pasta `02-Historico-GraphQL` é executada
- **THEN** a collection repete a leitura dentro de um limite explícito e só prossegue quando o snapshot contém o estado final confirmado e a observação atualizada pela jornada REST, falhando se o limite terminar

#### Scenario: Médico corrige o histórico e a correção é observada
- **WHEN** o token do médico envia uma correção válida para a consulta da execução
- **THEN** a resposta GraphQL não contém erro e uma leitura posterior comprova os campos corrigidos

#### Scenario: Lembrete manual usa perfil permitido
- **WHEN** o disparo manual do lembrete é executado com token de médico ou enfermeiro
- **THEN** a resposta tem status de sucesso e informa numericamente quantos lembretes foram enviados

#### Scenario: Ausência de token produz 401
- **WHEN** um endpoint protegido é chamado sem credencial
- **THEN** a pasta `03-Cenarios-de-Seguranca` comprova status 401 e o contrato de erro sem dado interno

#### Scenario: Perfil sem permissão produz 403
- **WHEN** paciente tenta executar uma operação reservada a médico ou enfermeiro
- **THEN** a pasta `03-Cenarios-de-Seguranca` comprova a recusa 403 e que a operação não foi aceita

#### Scenario: Entrada de negócio inválida produz 422
- **WHEN** a pasta `04-Cenarios-de-Erro` envia um agendamento no passado ou cancelamento sem motivo
- **THEN** a resposta é 422 em Problem Detail com tipo estável

#### Scenario: Conflito de agenda produz 409
- **WHEN** a pasta `04-Cenarios-de-Erro` tenta ocupar o mesmo período ativo da consulta criada
- **THEN** a resposta é 409 em Problem Detail e identifica conflito sem alterar a consulta original

#### Scenario: Recurso inexistente produz 404
- **WHEN** a pasta `04-Cenarios-de-Erro` busca um identificador de consulta válido mas inexistente
- **THEN** a resposta é 404 em Problem Detail

#### Scenario: Requisição malformada produz 400
- **WHEN** a pasta `04-Cenarios-de-Erro` envia identificador, parâmetro ou corpo em formato inválido
- **THEN** a resposta é 400 em Problem Detail e não vaza nome de classe ou detalhe interno

### Requirement: README final orienta avaliação e operação
O README SHALL ser uma porta de entrada suficiente para entender a arquitetura, preparar o ambiente, executar o sistema, importar e rodar a collection e localizar as APIs e os documentos normativos. Os diagramas SHALL usar Mermaid válido e distinguir o fluxo síncrono REST/GraphQL do fluxo assíncrono por outbox e RabbitMQ.

#### Scenario: Leitor encontra um caminho completo do zero
- **WHEN** uma pessoa abre apenas o README em um clone limpo
- **THEN** encontra pré-requisitos, comandos do ambiente, credenciais `demo`, URLs dos serviços, catálogo dos endpoints REST e operações GraphQL, importação dos artefatos Postman e instruções do Runner sem depender de informação contraditória

#### Scenario: Arquitetura e sequência usam Mermaid coerente
- **WHEN** os blocos Mermaid do README são inspecionados
- **THEN** existe ao menos um `flowchart` estrutural e um `sequenceDiagram` ponta a ponta com os três serviços, PostgreSQL, RabbitMQ e Mailpit, usando participantes e relações verificáveis no texto

#### Scenario: Links locais da documentação são válidos
- **WHEN** os links relativos do README e do índice de ADRs são resolvidos a partir de seus arquivos
- **THEN** cada destino local existe e permanece dentro do repositório

### Requirement: Sete decisões arquiteturais consolidadas e rastreáveis
O projeto SHALL manter exatamente os ADRs 001 a 007, cada um com as seções `Contexto`, `Decisão`, `Alternativas`, `Consequências` e `Status`, além de um índice navegável. Cada ADR SHALL identificar e ligar o archive OpenSpec em que a decisão foi materializada, sem reescrever retrospectivamente a decisão técnica.

#### Scenario: Catálogo contém os sete ADRs
- **WHEN** `docs/adr/README.md` é consultado
- **THEN** os ADRs 001 a 007 aparecem uma vez, em ordem, com título, status, decisão resumida e link para o documento

#### Scenario: Formato dos ADRs é uniforme
- **WHEN** os sete ADRs são inspecionados
- **THEN** cada um contém uma única ocorrência das cinco seções obrigatórias na mesma ordem

#### Scenario: Origem OpenSpec de cada decisão é navegável
- **WHEN** o vínculo de origem de qualquer ADR é seguido
- **THEN** ele aponta para o diretório arquivado do change definido no roadmap para aquela decisão
