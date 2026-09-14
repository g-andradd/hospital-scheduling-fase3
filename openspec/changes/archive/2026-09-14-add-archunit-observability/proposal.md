## Why

Dois requisitos não funcionais de `operacao-do-ambiente` ainda não têm garantia automática:

- **RNF-05.** A regra de dependência da Clean Architecture do `agendamento-service` é hoje uma
  convenção. O ADR-007 promete que "o ArchUnit falha o build quando alguém a cruza", e nenhuma
  suíte faz isso.
- **RNF-08.** O `correlationId` nasce no filtro HTTP do agendamento, vai para o outbox, para o
  envelope e para o header AMQP, e é restaurado no MDC do consumidor da notificação. Mas o
  consumidor do histórico não o restaura, os dois serviços satélites não têm filtro HTTP e
  nenhuma verificação prova que um único fluxo aparece com o mesmo id nos três logs.

O M11 fecha esses dois requisitos antes do M12, que depende de `/actuator/health` para o
`HEALTHCHECK` e de logs legíveis por máquina no profile `docker`.

Release alvo: **1.0.0**. Change do roadmap: **M11 · `add-archunit-observability`**. Fecha
**RNF-05** e **RNF-08**.

## What Changes

A implementação segue duas fases, ambas deste change:
- **P0** entrega o essencial verificável por testes dirigidos: ArchUnit, correlação HTTP,
  correlação AMQP e Actuator.
- **P1** configura os logs JSON, evolui o smoke e, só então, fecha a prova ponta a ponta do
  RNF-08 nos três logs.

P0 não prova o fluxo nos três logs, e o M11 só está concluído com P0 e P1.

**P0 — essencial:**

- **Suíte ArchUnit no `agendamento-service`**, só em escopo de teste. Ela verifica:
  - a direção das dependências entre `domain`, `application` e `infrastructure`;
  - os imports proibidos no `domain`: Spring, JPA, Jackson e Validation;
  - um único método público de comportamento por `*UseCase`;
  - entidades JPA somente em `infrastructure.persistence`;
  - controllers sem repositório e com operações de negócio passando pelos decoradores
    transacionais;
  - ausência de `System.out` e `System.err` no código principal.

  O `JwtService` no `AutenticacaoController` é a única exceção nominal, porque a emissão do
  token pertence à fronteira HTTP (`docs/01` §7).
- **Correlação HTTP nos serviços satélites.** `notificacao-service` e `historico-service` ganham
  um filtro de correlação equivalente ao do agendamento:
  - honra `X-Correlation-Id` não vazio e gera UUID na ausência;
  - devolve o id no header da resposta e no atributo da requisição, inclusive em respostas 401 e
    403;
  - restaura o MDC anterior ao fim da requisição.

  O filtro existente do agendamento não é movido nem refatorado.
- **Correlação assíncrona completa, provada por testes dirigidos.** O consumidor do histórico
  restaura o `correlationId` do envelope no MDC durante o processamento e o limpa ao final, como
  a notificação já faz. Entram só dois logs de sucesso, sem regra nova: evento publicado pelo
  relay e evento projetado pelo histórico. A notificação reaproveita a linha real do
  `LogNotificationSender`.
- **Actuator seguro nos três serviços**, com `spring-boot-starter-actuator` e
  `micrometer-registry-prometheus`:
  - exposição exata de `health`, `info`, `metrics` e `prometheus`;
  - `health` público e sem detalhes;
  - raiz do actuator, `info`, `metrics` e `prometheus` exigem JWT válido de qualquer perfil;
  - `env`, `beans` e o curinga nunca são expostos;
  - a `SecurityFilterChain` continua única e compartilhada.

**P1 — complemento:**

- **Logs JSON no profile `docker`.** Os três serviços usam o suporte nativo do Spring Boot 3.5
  (`logging.structured.format.console=logstash`) em `application-docker.yml`, com MDC como campo
  estruturado e identificação do serviço. O profile padrão continua em texto.
- **Prova ponta a ponta no smoke existente.** Sobre os logs JSON, `scripts/smoke-test.sh` passa a:
  - subir os serviços com `docker`, e o agendamento com `demo,docker`;
  - enviar um `X-Correlation-Id` derivado do `RUN_ID` na criação da consulta;
  - conferir o header devolvido;
  - exigir, nos três logs da execução corrente, o registro JSON do fluxo com `correlationId`
    igual ao enviado, identificado pela consulta criada.

  Continuam valendo os códigos, prazos, isolamento e limpeza do M10. Uma execução real fecha a
  prova; negativos sintéticos cobrem registro ausente e correlação divergente.
- **Sensibilidade mínima:** cinco mutações pequenas, sequenciais e restauradas byte a byte.
- **Documentação e verificação final:**
  - README, `docs/01` §9, status do ADR-007 e rastreabilidade de RNF-05/RNF-08;
  - gate global, clone limpo e archive.

## Capabilities

### New Capabilities

Nenhuma.

### Modified Capabilities

- `operacao-do-ambiente`: cinco Requirements ADDED, 23 Scenarios.
  - **Fronteiras da Clean Architecture verificadas automaticamente** (RNF-05);
  - **Correlação HTTP consistente nos três serviços** (RNF-08);
  - **Correlação assíncrona observável ponta a ponta** (RNF-08);
  - **Endpoints operacionais mínimos e seguros**;
  - **Logs estruturados no profile docker**.

  Nenhum Requirement promovido é modificado. `mensageria-de-eventos`, `notificacoes-ao-paciente`
  e `autenticacao-e-autorizacao` já cobrem envelope, headers, MDC do consumidor da notificação e
  recusa 401 de recurso protegido; seus Requirements não são repetidos nem reescritos.

## Impact

- **POMs:**
  - raiz com a versão do ArchUnit centralizada;
  - agendamento com ArchUnit em `test`;
  - três serviços com Actuator e o registry Prometheus, versões do BOM do Spring Boot.
- **`shared-security`:** caminhos operacionais autenticados na mesma cadeia compartilhada, sem
  curinga `/actuator/**`.
- **Serviços:**
  - notificação e histórico ganham o filtro de correlação;
  - o histórico ganha o MDC no consumidor e o log de projeção;
  - o agendamento ganha o log de publicação no relay;
  - `application.yml` com exposição e política de saúde, e `application-docker.yml` com o
    formato estruturado.
- **Testes:**
  - suíte ArchUnit;
  - correlação HTTP nos três serviços;
  - MDC do histórico;
  - Actuator nos três serviços;
  - verificação estrutural dos logs no `quality-gates`;
  - ajustes pontuais do `RoteiroDeSmokeTest`.
- **Scripts e docs:** `scripts/smoke-test.sh`, README, `docs/01`, ADR-007, `docs/02` e `docs/04`
  (rastreabilidade do M11).

**O que NÃO muda:**

- **Mensageria:** contrato, envelope, headers, routing keys e topologia; outbox, relay
  transacional, at-least-once e idempotência dos consumidores.
- **API e dados:** schema GraphQL, migrations e seeds; regras de negócio; matrizes de
  autorização e suas contagens; perfis que decidem operações de negócio.
- **Proteções já existentes:** o filtro de correlação do agendamento, o MDC do consumidor da
  notificação, a restauração de MDC no relay e todas as proteções do M10 — gate, auditoria,
  guarda de infraestrutura, inventários de superfície e smoke.
- **Nada de M12:** Dockerfile, Compose das aplicações, Mailpit, seed de consultas, Makefile e
  `HEALTHCHECK`.
- **Nada de M13/M14:** collection Postman, relatório técnico, script de auditoria global e
  release.
- **Versão:** `CHANGELOG.md` e versão do projeto não mudam.

**Riscos principais**, com mitigação no `design.md`:

- a auditoria do M10 reconhece só anotações de teste do JUnit;
- a exposição do Actuator e os inventários de superfície;
- o nome das propriedades de logging estruturado do Spring Boot 3.5;
- o parser de porta do smoke com logs JSON;
- o eco de `X-Correlation-Id` arbitrário;
- um segundo contexto Spring com listener ativo roubando mensagens;
- logs de sucesso emitidos antes do commit.

**Critérios de aceite:**

- os 23 Scenarios com evidência;
- suítes dirigidas verdes e `mvn -q clean verify` global verde, com auditoria e pisos de
  85%/90%/90%;
- uma execução real do smoke com JSON e o mesmo `correlationId` nos três logs;
- as cinco mutações vermelhas e restauradas;
- clone limpo com `clean verify` verde;
- archive na feature branch antes do merge.
