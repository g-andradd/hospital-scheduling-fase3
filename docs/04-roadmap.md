# Roadmap — 15 Changes

Cada item é **uma change do OpenSpec** e **uma feature branch**. O `change-id` é o mesmo nos dois mundos.

**Como usar:** escolha o próximo item, crie a branch, e passe o bloco "Enunciado da proposta" para o `/opsx:propose`. As notas técnicas obrigatórias devem aparecer no `design.md` gerado — se não aparecerem, a proposta está incompleta e você a devolve.

> ⚠️ Este roadmap é **planejamento**, não spec. A spec de cada change nasce no `/opsx:propose` e só vira verdade em `openspec/specs/` depois do `/opsx:archive`. Não trate os critérios de aceite abaixo como definitivos — trate como o mínimo que a proposta precisa cobrir.

---

## Sequência e paralelismo

```
M00 ─► M01 ─► M02 ─► M03 ─► M04 ─►│ release/0.1.0 │
                                   │
                                   ├─► M05 ─┬─► M06 ─► M07 ─┐
                                   │        └─► M08 ─► M09 ─┤─► release/0.2.0
                                   │                        │
                                   └────────────────────────┴─► M10 ─► M11 ─► M12 ─► M13 ─► M14 ─► release/1.0.0
```

M06/M07 e M08/M09 são paralelizáveis depois do M05. Duas sessões do Claude Code em duas feature branches — é aqui que se ganha tempo.

---

## Release 0.1.0 — Agendamento seguro

### M00 · `bootstrap-monorepo`
**Capability:** `operacao-do-ambiente` · **Branch:** `feature/m00-bootstrap-monorepo`
**Status:** proposta já escrita em `openspec/changes/bootstrap-monorepo/` — serve de referência de formato para as demais.

---

### M01 · `add-agendamento-domain`
**Capability:** `agendamento-de-consultas` · **Fecha:** RF-05 a RF-10

**Objetivo:** o núcleo de regras do sistema, testável em memória, sem Spring.

**Escopo:** pacotes `domain` e `application` do `agendamento-service`. Entidades `Consulta`, `Usuario`, `Paciente`, `Medico`; VOs `PeriodoConsulta`, `Cpf`, `Email`, `Crm`; enums `PerfilUsuario` e `StatusConsulta` com máquina de estados; exceções de negócio; portas de saída; sete casos de uso.

**Notas técnicas obrigatórias**
- `Clock` injetado em tudo que consulta o tempo. Nunca `LocalDateTime.now()` direto.
- `StatusConsulta.podeTransicionarPara()` implementa a máquina de estados: `AGENDADA → CONFIRMADA | CANCELADA | REALIZADA`; `CONFIRMADA → REALIZADA | CANCELADA`; `REALIZADA` e `CANCELADA` são terminais.
- Fakes em memória das portas em `src/test`. Nada de Mockito para as portas.
- Zero import de `org.springframework`, `jakarta.persistence`, `jakarta.validation` ou `com.fasterxml` nestes dois pacotes.

**Critérios de aceite**
- Um cenário de teste por regra: passado, conflito de médico, conflito de paciente, cada transição inválida, cancelamento sem motivo, alteração de consulta em status terminal
- Cobertura de `domain` + `application` ≥ 90%

**Enunciado da proposta**
```
/opsx:propose Núcleo de domínio e casos de uso do agendamento de consultas, sem
framework. Ler docs/01-arquitetura.md seção 4 (regras de negócio) e
docs/02-especificacao-funcional.md seções 1 e 4. Capability
agendamento-de-consultas. Não inclua persistência, HTTP nem mensageria — só
domínio e casos de uso, com portas de saída definidas e fakes em memória
para os testes.
```

---

### M02 · `add-agendamento-persistence`
**Capability:** `agendamento-de-consultas` · **Fecha:** RNF-09, RNF-10

**Objetivo:** adaptadores de persistência satisfazendo as portas do M01.

**Escopo:** migrations Flyway do `agendamento_db`, entidades JPA separadas do domínio, mappers manuais, adaptadores de repositório.

**Notas técnicas obrigatórias**
- Entidades JPA em pacote próprio (`infrastructure.persistence`), **nunca** as entidades de domínio anotadas.
- Mapeamento manual. Sem MapStruct — a banca lê o código.
- A detecção de conflito de agenda é **uma query nativa de sobreposição de intervalo**, filtrando status ativos. Carregar consultas em memória para comparar é reprovação técnica.
- Borda: consulta que termina exatamente quando outra começa **não é conflito**. Precisa de teste.
- `@Version` para lock otimista na `Consulta`.
- UUID como PK via `gen_random_uuid()`, `timestamptz` para datas.

**Critérios de aceite**
- Migration roda do zero e é idempotente
- Teste de integração com Testcontainers Postgres provando que cada adaptador satisfaz o contrato da porta
- Índices em `consulta(medico_id, data_hora)` e `consulta(paciente_id, data_hora)`

**Enunciado da proposta**
```
/opsx:propose Persistência do agendamento com Flyway, JPA e adaptadores das portas
definidas na change add-agendamento-domain. Ler docs/02-especificacao-funcional.md
seção 4 (agendamento_db). Capability agendamento-de-consultas. Atenção especial à
query de detecção de conflito de agenda: sobreposição de intervalo em SQL, com
índice, e teste cobrindo a borda de horários adjacentes.
```

---

### M03 · `add-agendamento-rest-api`
**Capability:** `agendamento-de-consultas` · **Fecha:** RF-05, RF-06, RF-07, RNF-03

**Objetivo:** expor os casos de uso por HTTP, com erros bem formados.

**Escopo:** controllers, DTOs em records com Bean Validation, `@RestControllerAdvice` global, springdoc-openapi. **Ainda sem `@PreAuthorize`** — a segurança é o M04.

**Notas técnicas obrigatórias**
- Erros em RFC 7807 `ProblemDetail`, no formato exato da seção 8 de `docs/01-arquitetura.md`, com `correlationId` e `timestamp`.
- Mapa: `AgendamentoNoPassado`→422, `ConflitoDeAgenda`→409, `ConsultaNaoEncontrada`→404, `TransicaoDeStatusInvalida`→409, `MethodArgumentNotValid`→400 com lista de campos, resto→500 sem stacktrace.
- `GET /api/v1/consultas` paginado com `Pageable` e filtros opcionais `pacienteId`, `medicoId`, `status`, `de`, `ate`.
- Mensagens de validação em português.

**Critérios de aceite**
- `@WebMvcTest` cobrindo happy path e cada tipo de erro por endpoint
- `/swagger-ui.html` lista todos os endpoints

**Enunciado da proposta**
```
/opsx:propose API REST do serviço de agendamento, com DTOs validados, tratamento
global de erros em RFC 7807 e OpenAPI. Ler docs/01-arquitetura.md seção 8 e
docs/02-especificacao-funcional.md seção 3. Capability agendamento-de-consultas.
NÃO inclua autorização — os endpoints ficam abertos nesta change e são protegidos
na change seguinte.
```

---

### M04 · `add-autenticacao-jwt`
**Capability:** `autenticacao-e-autorizacao` · **Fecha:** RF-01 a RF-04, RNF-01, RNF-02

> **Este é o change mais avaliado do enunciado. Não corte nada aqui.**

**Objetivo:** autenticação JWT e a matriz de autorização inteira, testada célula a célula.

**Escopo:** módulo `shared-security` completo, `POST /auth/login`, `@PreAuthorize` em todos os endpoints, regra de propriedade, seed de usuários.

**Notas técnicas obrigatórias**
- Claims: `sub`, `email`, `perfil`, `pacienteId`/`medicoId` quando aplicável, `iat`, `exp`.
- `SecurityConfig` como auto-configuração reaproveitável pelos três serviços — o `historico-service` vai consumir o mesmo filtro no M09.
- `BCryptPasswordEncoder`. Senha jamais em resposta ou log.
- Credencial inválida → 401 **genérico**, sem revelar se o e-mail existe.
- 401 e 403 também em `ProblemDetail`, via `AuthenticationEntryPoint` e `AccessDeniedHandler`.
- **Regra de propriedade no caso de uso ou num `AuthorizationService` dedicado, não só no controller.** Autorização espalhada em controller é o furo mais comum.
- Seed sob o profile `demo`, com as credenciais da seção 5 de `docs/02-especificacao-funcional.md`.

**Critérios de aceite**
- **Um teste de integração por célula** da matriz de autorização (`docs/02-especificacao-funcional.md` §3)
- Casos negativos cobertos: sem token→401, token expirado→401, assinatura adulterada→401, perfil errado→403, paciente lendo consulta alheia→403
- A entrega inclui uma tabela mapeando cada célula da matriz ao teste que a cobre

**Enunciado da proposta**
```
/opsx:propose Autenticação JWT e autorização por perfil nos três serviços, com a
matriz de permissões de docs/02-especificacao-funcional.md seção 3 aplicada ao
serviço de agendamento. Ler também docs/01-arquitetura.md seção 7. Capability
autenticacao-e-autorizacao. Cada célula da matriz precisa virar um Scenario na
spec delta e um teste de integração. Inclua a regra de propriedade: paciente só
acessa os próprios recursos.
```

**➜ Ao fim deste change: abrir `release/0.1.0`.** Ver `docs/05-fluxo-de-trabalho.md` §4.

---

## Release 0.2.0 — Mensageria e serviços satélites

### M05 · `add-event-publishing-outbox`
**Capability:** `mensageria-de-eventos` · **Fecha:** RF-15, RF-20

**Objetivo:** o contrato de eventos e a publicação com garantia transacional.

**Escopo:** `shared-contracts` (envelope, payload, topologia, converter), migration `outbox_evento`, `OutboxEventPublisher`, `OutboxRelay`, configuração de retry e DLQ.

**Notas técnicas obrigatórias**
- `docs/03-contrato-de-eventos.md` é **normativo**. O que estiver lá é implementado literalmente.
- O caso de uso grava consulta + linha do outbox **na mesma transação**. `RabbitTemplate` não aparece no caso de uso.
- Relay com `SELECT ... FOR UPDATE SKIP LOCKED`, lote de até 50, `fixedDelay` de 1s. Falha incrementa `tentativas` e mantém pendente.
- Índice **parcial** em `outbox_evento(publicado_em) WHERE publicado_em IS NULL`.
- Headers AMQP: `x-event-id`, `x-event-type`, `x-correlation-id`.
- `default-requeue-rejected: false`. Sem isso, mensagem envenenada volta pra fila infinitamente e derruba o consumidor.
- Jackson com `JavaTimeModule` e ISO-8601, sem timestamps numéricos.
- `CONSULTA_ATUALIZADA` preenche `alteracoes` com os valores anteriores.

**Critérios de aceite**
- `@DataJpaTest` provando atomicidade: exceção após salvar a consulta deixa o outbox vazio
- Teste com Testcontainers RabbitMQ validando envelope e headers no exchange real
- Teste do relay: pendente vira publicado; falha mantém pendente e incrementa tentativas
- Mensagem envenenada chega à DLQ após 3 tentativas

**Enunciado da proposta**
```
/opsx:propose Contrato de eventos e publicação transacional do serviço de
agendamento para o RabbitMQ, usando Transactional Outbox. docs/03-contrato-de-eventos.md
é normativo — implemente exatamente a topologia, o envelope e as garantias
descritas lá. Capability mensageria-de-eventos. Inclua DLX, DLQs e a configuração
de retry, e trate default-requeue-rejected: false como requisito, não como detalhe.
```

---

### M06 · `add-notificacao-consumer`
**Capability:** `notificacoes-ao-paciente` · **Fecha:** RF-16, RF-19

**Objetivo:** consumir eventos e notificar o paciente, sem efeito duplicado.

**Escopo:** migrations do `notificacao_db`, listener idempotente, agenda local, `NotificationSenderPort` com adaptadores de log e SMTP, templates em português.

**Notas técnicas obrigatórias**
- Esqueleto de idempotência da seção 6 de `docs/03-contrato-de-eventos.md`: checa `evento_processado`, processa, grava — o registro de processado vai **na mesma transação do efeito**, depois dele.
- Restaurar `correlationId` no MDC a partir do header AMQP.
- Cancelamento **atualiza** o status na `agenda_local`, não apaga a linha — o job de lembrete precisa saber que ela existe e está cancelada.
- Adaptadores selecionados por `@ConditionalOnProperty notificacao.sender`, padrão `log`.
- O serviço **nunca faz HTTP para o agendamento**. Tudo que precisa vem no snapshot do evento.

**Critérios de aceite**
- Teste de integração com Postgres e RabbitMQ reais, publicando cada tipo de evento
- Mesmo `eventId` duas vezes → uma única notificação
- Evento de cancelamento gera mensagem de cancelamento, não de confirmação

**Enunciado da proposta**
```
/opsx:propose Serviço de notificações consumindo os eventos de consulta e enviando
confirmações ao paciente, com idempotência por eventId e agenda local alimentada
pelos eventos. Ler docs/01-arquitetura.md seção 5 e docs/03-contrato-de-eventos.md
seção 6. Capability notificacoes-ao-paciente. O serviço não pode fazer nenhuma
chamada HTTP ao serviço de agendamento.
```

---

### M07 · `add-lembrete-24h`
**Capability:** `notificacoes-ao-paciente` · **Fecha:** RF-17

**Objetivo:** o lembrete automático que reduz faltas — o "por quê" do enunciado.

**Escopo:** job `@Scheduled` horário sobre a `agenda_local` e endpoint de disparo manual para a demo.

**Notas técnicas obrigatórias**
- Uma única query traz os candidatos. Nada de N+1 dentro do laço.
- `Clock` injetado; testes com relógio fixo cobrindo as bordas de 23h59 e 24h01.
- Job desabilitado no profile `test`.
- `POST /internal/lembretes/executar`, protegido por perfil MEDICO ou ENFERMEIRO — é o que permite demonstrar o lembrete na apresentação sem esperar uma hora. Documentar no README.

**Critérios de aceite**
- Cenários: 23h59 recebe · 24h01 não recebe · já notificado não recebe de novo · cancelada não recebe · execução sem candidatos não falha

**Enunciado da proposta**
```
/opsx:propose Lembrete automático D-1 no serviço de notificações: job agendado que
varre a agenda local e notifica consultas nas próximas 24 horas ainda não
lembradas. Capability notificacoes-ao-paciente. Inclua endpoint interno de disparo
manual para demonstração, protegido por perfil, e testes com Clock fixo cobrindo
as bordas da janela.
```

---

### M08 · `add-historico-projection`
**Capability:** `historico-de-consultas` · **Fecha:** RF-18, RF-19

**Objetivo:** materializar o read model a partir dos eventos.

**Escopo:** migrations do `historico_db`, listener de projeção, trilha completa em `consulta_evento`.

**Notas técnicas obrigatórias**
- Cada evento faz duas coisas: **insere** em `consulta_evento` (trilha íntegra) e **faz upsert** em `consulta_historico` (snapshot).
- **Proteção contra evento fora de ordem:** só aplica o upsert se `occurredAt >= atualizado_em` da linha existente. O evento antigo ainda entra na trilha, mas não regride o snapshot. Sem isso, um reenvio do relay corrompe o estado.
- `jsonb` mapeado com `@JdbcTypeCode(SqlTypes.JSON)` do Hibernate 6.

**Critérios de aceite**
- Sequência criada → atualizada → cancelada: 1 linha em `consulta_historico` com status CANCELADA, 3 em `consulta_evento`
- Mesmo `eventId` duas vezes → sem duplicata
- Evento antigo chegando depois → trilha ganha a linha, snapshot não regride

**Enunciado da proposta**
```
/opsx:propose Serviço de histórico projetando os eventos de consulta em um read
model, com trilha completa de eventos e snapshot atual. Ler
docs/02-especificacao-funcional.md seção 4 (historico_db) e
docs/03-contrato-de-eventos.md. Capability historico-de-consultas. Trate
explicitamente o caso de evento fora de ordem: a trilha aceita, o snapshot não
regride.
```

---

### M09 · `add-historico-graphql`
**Capability:** `historico-de-consultas` · **Fecha:** RF-11 a RF-14

**Objetivo:** a consulta flexível de histórico que o enunciado pede nominalmente.

**Escopo:** `schema.graphqls`, resolvers, autorização por operação, mutation de correção.

**Notas técnicas obrigatórias**
- O enunciado cita literalmente "listar todos os atendimentos de um paciente ou apenas as futuras" — o filtro `PeriodoFiltro` com `TODAS | FUTURAS | PASSADAS` é requisito, não enfeite.
- `minhasConsultas` resolve o `pacienteId` **do token**, nunca de argumento.
- Paciente passando `pacienteId` de terceiro → `AccessDeniedException`, traduzida para erro GraphQL `FORBIDDEN` — não 500.
- Mutation de correção também grava linha em `consulta_evento` com tipo `CORRECAO_MANUAL` e o id do médico. Trilha de auditoria.
- GraphiQL apenas nos profiles `dev` e `demo`.
- Sem N+1: comprovar com contagem de queries.

**Critérios de aceite**
- Um cenário por linha da tabela de autorização GraphQL
- Filtro `FUTURAS`/`PASSADAS`/`TODAS` testado com dados nas três situações

**Enunciado da proposta**
```
/opsx:propose API GraphQL do histórico de consultas, com filtro por período e
status, autorização por perfil e mutation de correção de registro pelo médico.
Ler docs/01-arquitetura.md seção 6 e docs/02-especificacao-funcional.md seção 3
(tabela do historico-service). Capability historico-de-consultas. Cada linha da
tabela de autorização vira um Scenario e um teste.
```

**➜ Ao fim deste change: abrir `release/0.2.0`.**

---

## Release 1.0.0 — Entrega

### M10 · `add-integration-tests-coverage`
**Capability:** todas · **Fecha:** RNF-04, RNF-06

**Escopo:** teste de contrato cruzado com fixture compartilhado, smoke test e2e, agregação JaCoCo com gate.

**Notas técnicas obrigatórias**
- O **fixture JSON do evento fica em `shared-contracts/src/test/resources`** e é usado pelo produtor e pelos dois consumidores. É isso que garante que produtor e consumidor não divirjam — mais valioso que um e2e frágil subindo três aplicações.
- `scripts/smoke-test.sh`: login → cria consulta → aguarda → verifica notificação → query GraphQL, validando com `jq`, saindo diferente de zero em qualquer falha.
- Gate JaCoCo: 85% global, 90% em `domain` e `application` do agendamento. Build falha abaixo.
- Corrigir lacunas com testes que valem alguma coisa. Teste de getter para inflar cobertura é pior que não ter.

---

### M11 · `add-archunit-observability`
**Capability:** `operacao-do-ambiente` · **Fecha:** RNF-05, RNF-08

**Escopo:** suíte ArchUnit no agendamento, `correlationId` ponta a ponta, Actuator, logs JSON no profile `docker`.

**Notas técnicas obrigatórias**
- Regras ArchUnit: `domain` não depende de `application` nem `infrastructure`; `domain` sem Spring/JPA/Jackson/Validation; classes `*UseCase` com exatamente um método público; entidades JPA só em `infrastructure.persistence`; controllers não injetam repositório, só caso de uso; nada de `System.out`.
- `correlationId`: filtro HTTP gera ou lê `X-Correlation-Id` → MDC → header AMQP no relay → MDC no consumidor. O mesmo id tem que aparecer nos três logs para um único fluxo.
- Actuator expõe `health`, `info`, `metrics`, `prometheus`. **Nunca** `env` ou `beans`.

---

### M12 · `add-docker-compose-demo`
**Capability:** `operacao-do-ambiente` · **Fecha:** RNF-07

**Escopo:** Dockerfiles multi-stage, compose final com os 5 containers + Mailpit, profile `demo` com seed, Makefile.

**Notas técnicas obrigatórias**
- Multi-stage com cache de dependências; runtime `eclipse-temurin:21-jre-alpine`; **usuário não-root**; `HEALTHCHECK` no `/actuator/health`.
- `depends_on` com `condition: service_healthy`.
- Seed demo com **uma consulta nas próximas 24h**, senão o lembrete D-1 não tem o que pegar na apresentação.
- `notificacao.sender=smtp` apontando para o Mailpit — e-mail chegando na tela vale mais que log.
- Validar em execução limpa: `docker compose down -v && make demo`. Só está pronto se passar do zero.

---

### M13 · `add-postman-documentation`
**Capability:** todas

**Escopo:** collection e environment Postman, README final com diagramas, os sete ADRs.

**Notas técnicas obrigatórias**
- Pastas: `00-Auth` (login dos 4 usuários, cada um salvando o token via script), `01-Agendamento`, `02-Historico-GraphQL`, `03-Cenarios-de-Seguranca` (401/403), `04-Cenarios-de-Erro` (422/409/404/400).
- **A collection inteira roda no Runner, na ordem, sem edição manual.** Se precisa colar token à mão, está errada.
- Cada request com `pm.test()` assertando status e campos.
- Diagramas em **Mermaid**, para renderizarem direto no GitHub.
- ADRs: 001 RabbitMQ vs Kafka · 002 três serviços · 003 monorepo multi-módulo · 004 matriz de autorização (a resolução da ambiguidade do enunciado) · 005 JWT stateless · 006 Transactional Outbox · 007 Clean Architecture só no core. Formato: Contexto / Decisão / Alternativas / Consequências / Status.

---

### M14 · `finalize-audit-report`
**Capability:** todas

**Escopo:** script de auditoria dos requisitos, relatório técnico, roteiro de demo, fechamento da release.

**Notas técnicas obrigatórias**
- `scripts/auditoria.sh` percorre cada RF/RNF de `docs/02-especificacao-funcional.md`, verifica a evidência (teste, endpoint ou arquivo) e imprime `REQUISITO | STATUS | EVIDÊNCIA`. Sai diferente de zero se algum falhar. Espelhar o script de 13 seções da Fase 2.
- `docs/relatorio-tecnico.md` em português, tom técnico: contexto, arquitetura, decisões e trade-offs, segurança, mensageria e garantias de entrega, estratégia de testes com números, o que ficou fora e por quê, aprendizados.
- `docs/roteiro-demo.md`: roteiro minuto a minuto de 5 a 8 minutos — Swagger, GraphiQL, RabbitMQ Management, Mailpit, logs com `correlationId`.
- Não converter o relatório para DOCX no Claude Code. Isso é feito na sessão de chat.

**➜ Ao fim deste change: abrir `release/1.0.0` e tagear `v1.0.0`.**

---

## Estimativa

| Change | Complexidade | Sessões |
|---|---|---|
| M00 | Baixa | 1 |
| M01 | Alta | 2 |
| M02 | Média | 1–2 |
| M03 | Média | 1 |
| **M04** | **Alta** | **2** |
| **M05** | **Alta** | **2** |
| M06 | Média | 1–2 |
| M07 | Baixa | 1 |
| M08 | Média | 1 |
| **M09** | **Alta** | **2** |
| M10 | Média | 1–2 |
| M11 | Média | 1 |
| M12 | Média | 1 |
| M13 | Média | 1–2 |
| M14 | Baixa | 1 |

**Total: 19–23 sessões**, mais 3 fechamentos de release. M04, M05 e M09 são os que mais valem nota e os mais fáceis de errar — não os apresse.

## Cortes possíveis, em ordem

Se o prazo apertar, corte nesta ordem, sempre declarando no relatório:

1. **Outbox → publicação direta** atrás da mesma porta `EventPublisherPort`. Perde-se o argumento de consistência, mantém-se o requisito. Vira uma change `MODIFIED` na capability `mensageria-de-eventos`.
2. **M07, lembrete D-1.** O enunciado exige o lembrete disparado por evento (RF-16), que é o M06. O D-1 é enriquecimento.
3. **Logs JSON.** Mantenha o `correlationId`; o encoder JSON é cosmético.
4. **Adaptador SMTP e Mailpit.** Fica só o `LogNotificationSender`.

**Nunca corte:** M04, M09, a matriz de autorização testada, a collection Postman, o README. São os cinco fatores de avaliação explícitos do enunciado.
