# Changelog

Formato baseado em [Keep a Changelog](https://keepachangelog.com/pt-BR/1.1.0/).
Versionamento semântico. Cada entrada corresponde a uma change do OpenSpec,
arquivada em `openspec/changes/archive/`.

## [0.1.0] — 2026-09-04

Primeira release. Entrega o **agendamento seguro ponta a ponta**: domínio,
persistência, API REST e autenticação com os três perfis. Fecha RF-01 a RF-10.

### Adicionado

- **`bootstrap-monorepo`** — POM pai multi-módulo com versão centralizada em
  `${revision}`, cinco módulos Maven, PostgreSQL 16 e RabbitMQ 3.13 por
  `docker compose`, separação surefire/failsafe. Capability
  `operacao-do-ambiente`.
- **`add-agendamento-domain`** — núcleo de domínio sem framework: entidades,
  value objects, máquina de estados no enum `StatusConsulta`, seis casos de uso
  e três portas de saída. Regras de conflito de agenda com intervalo semiaberto
  `[inicio, fim)`. Capability `agendamento-de-consultas`.
- **`add-agendamento-persistence`** — Flyway, JPA e adaptadores das portas.
  Detecção de conflito como query de sobreposição em SQL, com índice. Fronteira
  transacional por decorador em `infrastructure`, preservando `application` sem
  framework. Fecha RNF-09 e RNF-10.
- **`add-agendamento-rest-api`** — API REST com DTOs validados, erros em
  RFC 7807 Problem Detail, paginação com teto e OpenAPI. Fecha RNF-03.
- **`add-autenticacao-jwt`** — autenticação JWT e autorização por perfil, com a
  matriz de permissões aplicada célula a célula. Regra de propriedade na
  assinatura dos casos de uso. Capability `autenticacao-e-autorizacao`.
  Fecha RF-01 a RF-04, RNF-01 e RNF-02.

### Garantias estruturais

Três classes existem para impedir que uma omissão futura passe despercebida,
cada uma nascida de uma falha real:

- `CoberturaDoMapaDeErrosTest` — varre `domain.exception` e exige tratador para
  cada exceção. Uma exceção nova sem handler quebra o build no momento em que é
  criada.
- `CoberturaDeAutorizacaoTest` — exige `@PreAuthorize` em todo método de
  controller.
- `MatrizDeAutorizacaoIT` — lê a tabela de permissões de
  `docs/02-especificacao-funcional.md` e gera um caso por célula, com asserção
  de que encontrou exatamente 21 células.
- `EntradasHostisIT` — ataca os endpoints com entradas malformadas e de borda,
  exigindo que nenhuma resposta seja 5xx.

### Correções durante o desenvolvimento

- Validação passou a preceder a mutação do agregado na alteração de consulta.
  Sob JPA, mutar a entidade gerenciada antes de recusar persistiria a alteração
  rejeitada no commit, sem ninguém chamar `salvar`.
- `Consulta.atualizar` deixou de sobrescrever observações com `null`, o que
  apagava registro clínico numa remarcação.
- Exceções de request do Spring MVC passaram a ser tratadas como 4xx em vez de
  cair no tratador genérico como 500.

### Conhecido e adiado

- Conflito de agenda não é atômico sob concorrência: duas requisições
  simultâneas podem passar pela verificação antes de qualquer commit. Correção
  planejada para o M05, com constraint de exclusão no PostgreSQL.
- A ampliação do `EntradasHostisIT` para cobrir todas as dimensões de entrada
  por varredura está planejada para o M10.
