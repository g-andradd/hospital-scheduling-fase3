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

## Evidência pendente

Permanecem pendentes apenas a matriz no corpo do PR (7.2b) e o clone limpo/archive (8.6), ambos dependentes de commit, push e aprovação do PR pelo Gabriel.
