# Tasks — Bootstrap do monorepo

## 1. POM pai

- [ ] 1.1 Criar `pom.xml` na raiz com `packaging=pom`, `groupId=br.com.fiap.hospital`, `artifactId=hospital-scheduling-fase3`, `version=${revision}`
- [ ] 1.2 Propriedades: `revision=0.1.0-SNAPSHOT`, `java.version=21`, `project.build.sourceEncoding=UTF-8`
- [ ] 1.3 Importar `spring-boot-dependencies` no `dependencyManagement`
- [ ] 1.4 Configurar `maven-compiler-plugin` (release 21, `-parameters`)
- [ ] 1.5 Configurar `maven-surefire-plugin` (`*Test.java`) e `maven-failsafe-plugin` (`*IT.java`, goals `integration-test` e `verify`)
- [ ] 1.6 Configurar `jacoco-maven-plugin` (prepare-agent, report) — sem gate de cobertura ainda
- [ ] 1.7 Configurar `flatten-maven-plugin` com `flattenMode=resolveCiFriendly`
- [ ] 1.8 Declarar os cinco `<module>`

## 2. Módulos

- [ ] 2.1 `shared-contracts`: pom com jackson-databind, packaging jar, pacote `br.com.fiap.hospital.contracts`
- [ ] 2.2 `shared-security`: pom com spring-boot-starter-security e spring-boot-starter-web, pacote `br.com.fiap.hospital.security`
- [ ] 2.3 `agendamento-service`: pom com spring-boot-maven-plugin, classe `AgendamentoApplication`, estrutura `domain/`, `application/`, `infrastructure/{web,persistence,messaging,security}`
- [ ] 2.4 `notificacao-service`: pom, classe `NotificacaoApplication`, estrutura `consumer/`, `domain/`, `scheduler/`, `sender/`, `repository/`
- [ ] 2.5 `historico-service`: pom, classe `HistoricoApplication`, estrutura `consumer/`, `graphql/`, `model/`, `repository/`
- [ ] 2.6 Cada serviço com `application.yml` mínimo (nome da aplicação e porta) e um teste `contextLoads` desabilitado ou trivial que não exija banco

## 3. Infraestrutura local

- [ ] 3.1 `docker/postgres/init.sql` criando `agendamento_db`, `notificacao_db`, `historico_db`
- [ ] 3.2 `docker-compose.yml` com `postgres:16` (volume nomeado, healthcheck `pg_isready`, init montado) e `rabbitmq:3.13-management` (healthcheck `rabbitmq-diagnostics -q ping`), rede própria
- [ ] 3.3 `.env.example` com `POSTGRES_USER`, `POSTGRES_PASSWORD`, `POSTGRES_PORT`, `RABBITMQ_USER`, `RABBITMQ_PASSWORD`, `RABBITMQ_PORT`, `RABBITMQ_MGMT_PORT`, `JWT_SECRET`

## 4. Padronização

- [ ] 4.1 `.editorconfig`: UTF-8, LF, 4 espaços em `.java`, 2 em `.yml`/`.json`, trim trailing whitespace
- [ ] 4.2 Confirmar que `.gitignore` cobre `target/`, `.idea/`, `.env`, `.flattened-pom.xml`

## 5. Documentação

- [ ] 5.1 Atualizar o README com a seção "Executar a infraestrutura", incluindo o aviso sobre `down -v` e o `init.sql`
- [ ] 5.2 Registrar em `docs/adr/` o ADR sobre monorepo multi-módulo (ADR-003)

## 6. Verificação

- [ ] 6.1 `mvn clean verify` passa na raiz, sem warning de plugin sem versão
- [ ] 6.2 `docker compose up -d` sobe Postgres e RabbitMQ, ambos `healthy`
- [ ] 6.3 RabbitMQ Management acessível em `http://localhost:15672`
- [ ] 6.4 Os três databases existem (`\l` no psql)
- [ ] 6.5 `docker compose down -v && docker compose up -d` reproduz o estado do zero
