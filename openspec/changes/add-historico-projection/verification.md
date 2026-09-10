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

## Evidência pendente

Permanecem pendentes JaCoCo, gate global, validação OpenSpec e evidência de clone limpo/PR.
