# Tasks — Persistência do agendamento

## 1. Build e configuração

- [x] 1.1 Adicionar ao `agendamento-service` as dependências `spring-boot-starter-data-jpa`, `flyway-core`, `flyway-database-postgresql` e `postgresql`, e verificar que `mvn -q test-compile` resolve
- [x] 1.2 Adicionar em escopo `test` o `spring-boot-testcontainers`, o `testcontainers-postgresql` e o `testcontainers-junit-jupiter`, e verificar que resolvem
- [x] 1.3 Configurar o `application.yml` com datasource, `spring.jpa.hibernate.ddl-auto=validate` (D7) e Flyway habilitado, e verificar que a aplicação recusa subir contra schema divergente
- [x] 1.4 **DESVIADA.** A task pedia ajustar o `contextLoads` do M00; ele foi **removido**. Ajustá-lo exigiria excluir datasource, JPA e Flyway, e com eles o `ConsultaRepositoryAdapter` e o `CasosDeUsoConfig` que dependem dele — sobraria um contexto que não representa o real e passaria verde com a fiação de produção quebrada. A verificação de contexto completo migrou para os cinco `*IT`. Custo declarado: quebra de datasource, JPA ou Flyway só falha no `mvn verify`. Mitigação parcial: `CasosDeUsoConfigTest`, no surefire, pega quebra na fiação dos casos de uso sem exigir banco

## 2. Schema

- [x] 2.1 Criar `V1__cria_schema_agendamento.sql` com `usuario`, `paciente`, `medico` e `consulta` conforme `docs/02-especificacao-funcional.md` §4, com `uuid` PK via `gen_random_uuid()` e `timestamptz` para instantes, e verificar que aplica sobre banco vazio
- [x] 2.2 Declarar as chaves naturais únicas `usuario.email`, `paciente.cpf` e `medico.crm`, e verificar pelos três cenários do Requirement "Unicidade das chaves naturais"
- [x] 2.3 Criar os índices `consulta(medico_id, data_hora)` e `consulta(paciente_id, data_hora)`, e verificar que existem após a migration
- [x] 2.4 Adicionar a coluna `versao` para o lock otimista (D5), e verificar que a entidade JPA a mapeia
- [x] 2.5 Verificar pelos três cenários do Requirement "Schema versionado por migration": provisionamento do zero, reaplicação sem efeito e recusa de subir com schema divergente

## 3. Entidades JPA e mapeamento

- [x] 3.1 Criar `UsuarioEntity`, `PacienteEntity`, `MedicoEntity` e `ConsultaEntity` em `infrastructure.persistence.entity`, com `status` em `@Enumerated(EnumType.STRING)` (D6), e verificar que nenhuma anotação JPA aparece em `domain`
- [x] 3.2 Anotar `ConsultaEntity.versao` com `@Version` (D5), e verificar que o Hibernate incrementa a versão a cada gravação
- [x] 3.3 Criar os mappers manuais em `infrastructure.persistence.mapper`, sem MapStruct, usando `Consulta.reconstituir` e nunca `Consulta.agendar` (D3), e verificar pelo cenário "Consulta gravada no passado é recuperável"
- [x] 3.4 Garantir que o mapper devolve sempre um objeto de domínio desacoplado da entidade gerenciada (D4), e verificar pelo cenário "Operação recusada não deixa registro"

## 4. Adaptadores de repositório

- [x] 4.1 Criar `ConsultaRepositoryAdapter` implementando `ConsultaRepositoryPort`, com `salvar` copiando o estado do domínio sobre a entidade gerenciada (D4), e verificar pelos cenários do Requirement "Durabilidade das consultas"
- [x] 4.2 Implementar as duas buscas de conflito como query nativa de sobreposição de intervalo, com comparações estritas reproduzindo o `[inicio, fim)` (D1), e verificar pelo cenário "Períodos adjacentes persistidos não são conflito"
- [x] 4.3 Filtrar os status ativos dentro da própria query, e verificar pelo cenário "Consulta encerrada persistida não bloqueia a agenda"
- [x] 4.4 Implementar `listar` traduzindo `FiltroDeConsultas` para critérios de banco, e verificar pelos cenários de listagem já existentes na capability
- [x] 4.5 Criar `UsuarioRepositoryAdapter` implementando `UsuarioRepositoryPort`, e verificar que paciente e médico inexistentes devolvem vazio
- [x] 4.6 Traduzir `OptimisticLockingFailureException` para `AlteracaoConcorrenteException` no adaptador (D5), e verificar que nenhum tipo do Spring escapa da camada de infraestrutura

## 5. Domínio e transação

- [x] 5.1 Criar `AlteracaoConcorrenteException` no `domain`, com mensagem em português, e verificar que continua sem import de framework
- [x] 5.2 Criar um decorador `@Transactional` por caso de uso em `infrastructure.transacao` (D4), e verificar que `application` segue sem nenhum import de `org.springframework`
- [x] 5.3 Registrar os casos de uso e os decoradores como beans em `infrastructure.config`, com o `Clock` injetado, e verificar que o contexto sobe com todos resolvidos
- [x] 5.4 Verificar que existe exatamente um decorador para cada caso de uso, sem nenhum faltando

## 6. Testes

- [x] 6.1 Criar `ConsultaRepositoryContractTest` abstrata com as asserções do contrato da porta, escrita apenas contra a interface (D2)
- [x] 6.2 Criar `ConsultaRepositoryFakeTest` estendendo a suíte de contrato com o fake em memória, e verificar que roda no surefire sem container
- [x] 6.3 Criar `ConsultaRepositoryAdapterIT` estendendo a mesma suíte com Testcontainers Postgres, e verificar que roda no failsafe e que as duas implementações passam pelas mesmas asserções
- [x] 6.4 Criar teste de integração para o Requirement "Alteração concorrente da mesma consulta", cobrindo os dois cenários — concorrente recusada e sequenciais aceitas
- [x] 6.5 Verificar por `EXPLAIN` que a query de conflito usa o índice de `consulta(medico_id, data_hora)`, e não varre a tabela
- [x] 6.6 Verificar que os 177 testes do M01 continuam passando sem nenhuma alteração, provando que a persistência não mudou comportamento especificado
- [x] 6.7 Verificar que os 16 `#### Scenario:` da spec delta têm teste correspondente, e registrar a tabela de rastreabilidade no corpo do PR

## 7. Documentação

- [x] 7.1 Atualizar o README com a seção de execução do serviço contra o Postgres do compose, incluindo o que fazer quando a migration falha por schema divergente
- [x] 7.2 Registrar em `docs/01-arquitetura.md` a `AlteracaoConcorrenteException` na lista de exceções da §4 e no mapa da §8, com o status HTTP que o M03 vai usar

## 8. Verificação

- [x] 8.1 `mvn -q clean verify` passa na raiz, sem teste ignorado, com os testes de integração executando contra Testcontainers reais
- [x] 8.2 A migration roda sobre banco vazio e a reaplicação não executa nada
- [x] 8.3 Cobertura do `agendamento-service` ≥ 80%, mantendo `domain` e `application` ≥ 90%
- [x] 8.4 Nenhum import de `org.springframework`, `jakarta.persistence`, `jakarta.validation` ou `com.fasterxml` em `domain` e `application`, comprovado por busca no código-fonte
- [x] 8.5 Nenhuma anotação JPA fora de `infrastructure.persistence`, comprovado por busca no código-fonte
- [x] 8.6 Nenhuma ocorrência de `ddl-auto: update` ou `create` em nenhum `application.yml`
