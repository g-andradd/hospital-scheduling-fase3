# Catálogo de decisões arquiteturais

As decisões abaixo estão aceitas. Cada ADR preserva contexto, alternativas e consequências e
aponta para a change OpenSpec em que a decisão entrou no projeto.

| ADR | Decisão resumida | Status | Origem OpenSpec |
|---|---|---|---|
| [ADR-001 — RabbitMQ para eventos de consulta](ADR-001-rabbitmq.md) | RabbitMQ desacopla os serviços por eventos versionados. | Aceita | [`add-event-publishing-outbox`](../../openspec/changes/archive/2026-09-04-add-event-publishing-outbox/) |
| [ADR-002 — Três serviços](ADR-002-tres-servicos.md) | Agendamento, notificação e histórico têm responsabilidades separadas. | Aceita | [`bootstrap-monorepo`](../../openspec/changes/archive/2026-09-02-bootstrap-monorepo/) |
| [ADR-003 — Monorepo Maven](ADR-003-monorepo-multimodulo.md) | Um reactor centraliza versões e verifica os módulos juntos. | Aceita | [`bootstrap-monorepo`](../../openspec/changes/archive/2026-09-02-bootstrap-monorepo/) |
| [ADR-004 — Matriz de autorização executável](ADR-004-matriz-de-autorizacao.md) | A tabela normativa de perfis também gera evidência automatizada. | Aceita | [`add-autenticacao-jwt`](../../openspec/changes/archive/2026-09-04-add-autenticacao-jwt/) |
| [ADR-005 — JWT stateless](ADR-005-jwt-stateless.md) | O agendamento emite JWT validado localmente pelos serviços. | Aceita | [`add-autenticacao-jwt`](../../openspec/changes/archive/2026-09-04-add-autenticacao-jwt/) |
| [ADR-006 — Transactional Outbox](ADR-006-transactional-outbox.md) | Consulta e evento são confirmados juntos antes do relay. | Aceita | [`add-event-publishing-outbox`](../../openspec/changes/archive/2026-09-04-add-event-publishing-outbox/) |
| [ADR-007 — Clean Architecture no core](ADR-007-clean-architecture-so-no-core.md) | O core do agendamento mantém dependências apontando para dentro. | Aceita | [`add-agendamento-domain`](../../openspec/changes/archive/2026-09-02-add-agendamento-domain/) |
