# operacao-do-ambiente

## ADDED Requirements

### Requirement: Build reprodutível do monorepo

O projeto SHALL ser construído por um único comando Maven na raiz, compilando todos os módulos com versão centralizada.

#### Scenario: Build completo a partir de um clone limpo
- **WHEN** um desenvolvedor executa `mvn clean verify` na raiz do repositório recém-clonado
- **THEN** todos os cinco módulos compilam sem erro
- **AND** nenhum plugin é resolvido sem versão explícita
- **AND** o build termina com sucesso

#### Scenario: Alteração de versão em um único ponto
- **WHEN** a propriedade `revision` do POM pai é alterada
- **THEN** todos os módulos passam a ser construídos com a nova versão
- **AND** os POMs gerados não contêm o placeholder `${revision}` literal

### Requirement: Ambiente de infraestrutura local

O ambiente SHALL prover PostgreSQL e RabbitMQ prontos para uso por um único comando, sem configuração manual.

#### Scenario: Subida da infraestrutura
- **WHEN** um desenvolvedor executa `docker compose up -d` com o `.env` preenchido a partir do `.env.example`
- **THEN** o container do PostgreSQL atinge o estado `healthy`
- **AND** o container do RabbitMQ atinge o estado `healthy`
- **AND** o painel de gerenciamento do RabbitMQ responde na porta configurada

#### Scenario: Isolamento de dados por serviço
- **WHEN** a infraestrutura sobe pela primeira vez
- **THEN** existem os databases `agendamento_db`, `notificacao_db` e `historico_db`
- **AND** nenhum deles compartilha tabelas com os demais

#### Scenario: Recriação do ambiente do zero
- **WHEN** um desenvolvedor executa `docker compose down -v` seguido de `docker compose up -d`
- **THEN** os três databases são recriados
- **AND** o ambiente volta ao estado inicial sem intervenção manual

### Requirement: Separação entre testes unitários e de integração

O build SHALL executar testes unitários e de integração em fases distintas, identificados por convenção de nome.

#### Scenario: Execução apenas dos testes unitários
- **WHEN** um desenvolvedor executa `mvn test`
- **THEN** apenas as classes terminadas em `Test` são executadas
- **AND** nenhum container do Testcontainers é iniciado

#### Scenario: Execução da suíte completa
- **WHEN** um desenvolvedor executa `mvn verify`
- **THEN** as classes terminadas em `Test` e em `IT` são executadas
