# Verification — M08

## Suíte direcionada

- `mvn -q -pl historico-service -am -Dit.test='*HistoricoIT,ConsumoHistoricoRabbitMqIT' -Dfailsafe.failIfNoSpecifiedTests=false verify`: aprovada com PostgreSQL 16 e RabbitMQ 3.13 por Testcontainers.
- Resultado: 24 testes de integração, zero falhas, zero erros e zero ignorados.
- Cobertura exercida: persistência/schema, projeção, idempotência concorrente, ordenação com lock real, entradas hostis/retry/DLQ, consumo AMQP e desacoplamento HTTP.

## Mutações D6

- Marca final removida: `ProtecoesEstruturaisTest` falhou em 1/3 testes porque `processados.saveAndFlush` não foi encontrado; `IdempotenciaHistoricoIT`, executado isolando os unitários, falhou nos 3 casos (2 falhas e 1 erro). Após restauração, ambas as suítes passaram com 3/3 testes.
- Upsert incondicional: `OrdenacaoHistoricoIT` falhou em 2/3 testes, por regressão do snapshot antigo e resultado concorrente `AGENDADA`; após restaurar o `WHERE`, 3/3 passaram.
- Recoverer que engole rejeição: `EntradasHostisHistoricoIT` terminou com 8/8 erros por timeout aguardando a DLQ; após restaurar `RejectAndDontRequeueRecoverer`, 8/8 passaram.
- As três mutações foram aplicadas isoladamente e a produção foi restaurada antes da mutação seguinte.

## Gate global (8.1, 8.2, 8.3)

Comandos executados na raiz, nesta ordem:

- `openspec validate add-historico-projection --strict` → `Change 'add-historico-projection' is valid` (exit 0).
- `openspec status --change add-historico-projection` → `Progress: 4/4 artifacts complete` (proposal, specs, design, tasks). A spec delta declara a capability `historico-de-consultas` com 6 Requirements e 15 Scenarios, e os 15 métodos da matriz existem nas classes indicadas.
- `mvn -q clean verify` na raiz, com PostgreSQL 16 e RabbitMQ 3.13 reais por Testcontainers → **BUILD verde** (exit 0).

### Contagem de testes por módulo

| Módulo | Unitários (surefire) | Integração (failsafe) | Total |
|---|---|---|---|
| `shared-contracts` | 120 | 14 | 134 |
| `shared-security` | 69 | 0 | 69 |
| `agendamento-service` | 313 | 431 | 744 |
| `notificacao-service` | 1 | 0 | 1 |
| `historico-service` | 3 | 24 | 27 |
| **Total** | **506** | **469** | **975** |

**975 testes, zero falhas, zero erros e zero ignorados.** Nenhuma classe de teste do repositório usa `@Disabled`, `@Ignore` ou `Assumptions`.

### Cobertura JaCoCo (linhas)

Relatórios regenerados pelo próprio `clean verify` — o agente do JaCoCo instrumenta surefire e failsafe no mesmo `jacoco.exec`, então a cobertura inclui os testes de integração. Nenhum gate, agregação ou `check` foi adicionado: isso pertence ao M10.

| Módulo | Linhas cobertas / elegíveis | Cobertura |
|---|---|---|
| `historico-service` | 49 / 52 | **94,23%** |
| `shared-contracts` | 174 / 181 | 96,13% |
| `shared-security` | 112 / 112 | 100,00% |
| `agendamento-service` | 932 / 956 | 97,49% |
| `notificacao-service` | — | sem código elegível |

**Global ponderado: (49 + 174 + 112 + 932) / (52 + 181 + 112 + 956) = 1267 / 1301 = 97,39%.** O cálculo soma as linhas dos quatro módulos com código elegível; `notificacao-service` só contém `NotificacaoApplication`, excluída pelo `**/*Application.class` do plugin, e por isso não entra no ponderado.

As 3 linhas descobertas do `historico-service` são os construtores protegidos sem argumento exigidos pelo JPA em `ConsultaHistoricoEntity`, `ConsultaEventoEntity` e `EventoProcessadoEntity`. Nenhuma alteração de produção foi feita para elevar o percentual.

## Entrega do PR e clone limpo (7.2b, 8.6)

- **PR #17** — `feature/m08-add-historico-projection` → `develop` — aprovado e mergeado. Merge commit **`06d362f`**.
- A matriz dos 15 Scenarios com seus métodos está no corpo do PR #17, junto do resultado do gate global e dos números de cobertura. Isso conclui a task 7.2b.
- **Clone limpo exigido pela DoD**, executado a partir de `origin/develop` em `06d362f`:

  ```
  git clone -b develop https://github.com/g-andradd/hospital-scheduling-fase3.git <tmp>
  cd <tmp> && mvn -q clean verify
  ```

  Resultado: **BUILD verde** (exit 0), **975 testes**, zero falhas, zero erros e **zero ignorados** — `shared-contracts` 134, `shared-security` 69, `agendamento-service` 744, `notificacao-service` 1, `historico-service` 27. Os números são idênticos aos da árvore de trabalho, o que confirma que nenhum arquivo necessário ficou fora dos commits. O diretório temporário foi removido após a verificação.

## Fechamento corretivo do archive

A sequência prevista em `docs/05-fluxo-de-trabalho.md` §6.7 exige o archive commitado na própria feature branch **antes** do merge, para que código e spec entrem em `develop` no mesmo `--no-ff`. Isso não aconteceu: o PR #17 foi mergeado com a change ainda ativa em `openspec/changes/`. É a mesma falha registrada no M01.

Como a feature branch já foi mergeada, o archive não pode mais ser retroencaixado nela. O fechamento é feito por uma **branch corretiva `chore/m08-archive`**, criada a partir de `origin/develop` em `06d362f`, contendo apenas a promoção da capability e a movimentação da change para `openspec/changes/archive/`. Nenhum código de produção é tocado.

O M08 só estará integralmente encerrado quando o PR corretivo desta branch for mergeado em `develop`.
