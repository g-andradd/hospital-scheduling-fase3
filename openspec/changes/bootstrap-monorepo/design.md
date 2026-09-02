# Design — Bootstrap do monorepo

## Decisões

### Versionamento com `${revision}`

O POM pai declara `<revision>0.1.0-SNAPSHOT</revision>` e todos os módulos usam `<version>${revision}</version>`. Subir a versão do projeto inteiro vira a alteração de uma linha.

Isso exige o `flatten-maven-plugin` — sem ele, o POM publicado carrega o placeholder literal `${revision}` e quebra qualquer consumidor. Configurar com `flattenMode=resolveCiFriendly` e `goal: flatten` na fase `process-resources`, mais `clean` na fase `clean`.

### Separação surefire / failsafe

`surefire` roda `*Test.java` (unitários, rápidos, sem container). `failsafe` roda `*IT.java` (integração, com Testcontainers) na fase `verify`. A separação permite `mvn test` rápido no ciclo curto e `mvn verify` completo antes do PR.

### Três databases, uma instância Postgres

Cada serviço tem seu próprio database (`agendamento_db`, `notificacao_db`, `historico_db`), respeitando a autonomia dos serviços, mas todos na mesma instância do compose. Isolamento lógico sem o custo de três containers.

Criados por script em `docker/postgres/init.sql`, montado em `/docker-entrypoint-initdb.d/`. Esse diretório só é executado quando o volume está vazio — recriar os databases exige `docker compose down -v`. Documentar isso no README evita uma hora de depuração no futuro.

### Healthchecks desde o início

Postgres com `pg_isready` e RabbitMQ com `rabbitmq-diagnostics -q ping`. Os serviços de aplicação vão depender deles com `condition: service_healthy` no M12 — deixar os healthchecks prontos agora evita retrabalho.

## Alternativas consideradas

**BOM separado para as versões.** Descartado: com cinco módulos no mesmo repositório, o `dependencyManagement` do POM pai já resolve. Um BOM só se paga quando módulos são publicados e consumidos externamente.

**Gradle.** Descartado: o enunciado e o restante do curso são Maven, e a banca lê Maven.

## Riscos

| Risco | Mitigação |
|---|---|
| Porta 5432 ou 5672 já ocupada na máquina do avaliador | Mapear portas via variável no `.env`, com o padrão documentado |
| `flatten-maven-plugin` deixando `.flattened-pom.xml` sujo no repositório | Adicionar ao `.gitignore` |
