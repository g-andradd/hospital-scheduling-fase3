# Persistência do agendamento

## Why

O M01 entregou as regras de agendamento e três portas de saída sem nenhuma implementação de produção. Hoje o `agendamento-service` sobe, mas nada que ele decide sobrevive ao processo: as consultas existem apenas nos fakes em memória dos testes.

Este change dá corpo às portas. É também onde a decisão mais consequente do M01 é cobrada: as buscas de conflito chegam recortadas por período e por status ativo justamente para obrigar o adaptador a resolver a sobreposição **no banco**, com índice — e não carregando a agenda do médico em memória para comparar em Java.

A ordem importa. Fazer a persistência antes do HTTP significa que o M03 vai expor casos de uso que já funcionam ponta a ponta contra um Postgres real, em vez de descobrir problemas de mapeamento e de transação no meio de um `@WebMvcTest`.

## What Changes

**Schema** (`agendamento-service/src/main/resources/db/migration`)
- Migration Flyway criando `usuario`, `paciente`, `medico` e `consulta` conforme `docs/02-especificacao-funcional.md` §4
- Índices `consulta(medico_id, data_hora)` e `consulta(paciente_id, data_hora)`
- Chaves naturais únicas: `usuario.email`, `paciente.cpf`, `medico.crm`
- `uuid` como PK com `gen_random_uuid()`, `timestamptz` para instantes

**Persistência** (`infrastructure.persistence`)
- Entidades JPA `UsuarioEntity`, `PacienteEntity`, `MedicoEntity`, `ConsultaEntity` — em pacote próprio, nunca as entidades de domínio anotadas
- `ConsultaEntity` com `@Version` para lock otimista
- Mappers manuais domínio ↔ entidade, sem MapStruct
- `ConsultaRepositoryAdapter` e `UsuarioRepositoryAdapter` implementando as portas do M01
- Detecção de conflito como query nativa de sobreposição de intervalo, reproduzindo o `[inicio, fim)` do domínio

**Transação** (`infrastructure`)
- Decorador `@Transactional` por caso de uso, mantendo `application` livre de framework e garantindo uma transação por operação

**Domínio**
- `AlteracaoConcorrenteException`, para o adaptador traduzir a falha de lock otimista sem vazar tipo do Spring

**Testes**
- Suíte de contrato compartilhada, executada contra o fake em memória **e** contra o adaptador real com Testcontainers Postgres
- **A verificação do contexto Spring completo passa a ser responsabilidade dos testes `*IT`.** O `contextLoads` criado no M00 é removido: o contexto deste serviço agora exige datasource, JPA e Flyway, e um teste que os excluísse carregaria um contexto que não se parece com o real — passaria verde com a fiação de produção quebrada. Consequência aceita e declarada: **quebra em datasource, JPA ou Flyway não falha mais no `mvn test`, só no `mvn verify`.** Um teste de surefire (`CasosDeUsoConfigTest`) cobre a parte mais provável — fiação dos casos de uso e do relógio — sem banco

## Capabilities

### New Capabilities

Nenhuma.

### Modified Capabilities
- `agendamento-de-consultas`: ganha requisitos de durabilidade, de schema versionado, de unicidade das chaves naturais e de alteração concorrente. Nenhum requisito existente muda de comportamento — a persistência preserva o que o M01 especificou, e é isso que a suíte de contrato compartilhada prova.

## Impact

- **Capability:** `agendamento-de-consultas` (modificada)
- **Requisitos fechados:** RNF-09, RNF-10
- **Release alvo:** `0.1.0`
- **Branch:** `feature/m02-add-agendamento-persistence`
- **Módulos tocados:** `agendamento-service` (`infrastructure.persistence`, `infrastructure.config`, `domain` para uma exceção, `src/test`) e o POM do módulo

## O que NÃO muda nesta change

- **As regras de negócio.** `domain` e `application` do M01 permanecem como estão, com a única exceção da nova `AlteracaoConcorrenteException`. Nenhum caso de uso é reescrito, nenhuma assinatura de porta muda. Se a implementação exigisse mudar uma porta, o design do M01 estaria errado — e não está.
- **`application` continua sem framework.** A propriedade que o M01 verificou na task 8.4 é preservada: o `@Transactional` fica num decorador em `infrastructure`, não nos casos de uso.
- **HTTP.** Nenhum controller, DTO de request/response ou `ProblemDetail`. É o M03. A `AlteracaoConcorrenteException` nasce aqui, mas seu mapeamento para 409 é lá.
- **Autenticação.** Nenhum JWT, `@PreAuthorize` ou verificação de senha. A coluna `senha_hash` é criada, mas nada a lê nem a popula. É o M04.
- **Seed de dados.** Nenhum usuário, paciente ou médico é inserido. As migrations criam estrutura, não conteúdo. O seed do profile `demo` é do M04.
- **`outbox_evento`.** A tabela não é criada aqui, apesar de aparecer no modelo de dados de `docs/02-especificacao-funcional.md` §4. Ela pertence ao Transactional Outbox e nasce no M05, junto do relay que a consome. Criar a tabela agora deixaria schema morto no banco.
- **Mensageria.** Nenhum RabbitMQ, nenhuma tabela de outbox, nenhuma publicação real. A `EventPublisherPort` ganha um adaptador **provisório** (`EventPublisherLogAdapter`) que apenas registra o evento em log: os casos de uso publicam a cada mudança de estado desde o M01, e sem nenhum bean da porta o contexto Spring não sobe. Ele é declarado explicitamente como `@Bean` em `CasosDeUsoConfig` — `@Component` com `@ConditionalOnMissingBean` não tem ordem de avaliação garantida fora de auto-configuração. O adaptador de verdade, com outbox transacional, é o M05, e **remover este bean é task daquele change**.
- **ArchUnit.** As restrições continuam respeitadas por construção e verificadas por busca; a suíte que as automatiza é o M11.
