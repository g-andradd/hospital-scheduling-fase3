# Arquitetura da Solução

## 1. Visão geral

```
                              ┌──────────────────────────────┐
                              │        Cliente / Postman      │
                              └───────────────┬───────────────┘
                    REST + JWT                │                GraphQL + JWT
                ┌──────────────────────────────┴──────────────────────────┐
                ▼                                                          ▼
   ┌────────────────────────────┐                        ┌──────────────────────────────┐
   │   agendamento-service      │                        │     historico-service        │
   │   :8081                    │                        │     :8083                    │
   │                            │                        │                              │
   │  • /auth/login (emite JWT) │                        │  • /graphql                  │
   │  • CRUD de consultas       │                        │  • read model de consultas   │
   │  • Clean Architecture      │                        │  • projeção via eventos      │
   │  • Transactional Outbox    │                        │                              │
   └─────────────┬──────────────┘                        └──────────────▲───────────────┘
                 │ publica                                              │ consome
                 ▼                                                      │
        ┌────────────────────────────────────────────────────────────────────────┐
        │          RabbitMQ — exchange topic  hospital.consultas                  │
        │   routing keys: consulta.criada | consulta.atualizada | consulta.cancelada │
        │   DLX: hospital.consultas.dlx                                           │
        └───────────────────────────────┬────────────────────────────────────────┘
                                        │ consome
                                        ▼
                         ┌──────────────────────────────┐
                         │   notificacao-service        │
                         │   :8082                      │
                         │                              │
                         │  • confirmação imediata      │
                         │  • lembrete agendado D-1     │
                         │  • porta NotificationSender  │
                         └──────────────┬───────────────┘
                                        ▼
                              Log (padrão) ou SMTP → Mailpit :8025

   PostgreSQL :5432 — databases: agendamento_db | notificacao_db | historico_db
```

## 2. Stack

| Camada | Tecnologia |
|---|---|
| Linguagem | Java 21 (LTS) |
| Framework | Spring Boot 3.5.x |
| Build | Maven multi-módulo com `${revision}` |
| Persistência | PostgreSQL 16 + Spring Data JPA + Flyway |
| Mensageria | RabbitMQ 3.13 + Spring AMQP |
| Segurança | Spring Security 6 + JWT (HS256, `jjwt` ou `spring-security-oauth2-jose`) |
| API de leitura | Spring for GraphQL |
| Testes | JUnit 5, Mockito, AssertJ, Testcontainers (Postgres + RabbitMQ), ArchUnit |
| Cobertura | JaCoCo |
| Observabilidade | Spring Boot Actuator, logs estruturados com `correlationId` via MDC |
| Empacotamento | Dockerfile multi-stage por serviço + `docker-compose.yml` |

## 3. Estrutura do monorepo

```
hospital-scheduling-fase3/
├── pom.xml                          # POM pai — dependencyManagement, plugins, ${revision}
├── docker-compose.yml
├── Makefile                         # up, down, logs, demo, clean
├── .env.example
├── .gitignore
├── README.md
├── docker/
│   └── postgres/init.sql            # cria os três databases
├── scripts/
│   ├── smoke-test.sh                # e2e com o compose no ar
│   └── auditoria.sh                 # confere cada RF/RNF contra sua evidência
├── docs/
│   ├── 00-project-charter.md
│   ├── 01-arquitetura.md
│   ├── 02-especificacao-funcional.md
│   ├── 03-contrato-de-eventos.md
│   ├── 04-roadmap.md
│   ├── 05-fluxo-de-trabalho.md
│   ├── adr/
│   └── diagramas/
├── openspec/                        # Spec-Driven Development
│   ├── config.yaml                  # contexto + regras, injetados no planejamento
│   ├── specs/<capability>/spec.md   # o que JÁ está construído
│   ├── changes/<change-id>/         # propostas em andamento
│   └── changes/archive/             # changes concluídas
├── .claude/                         # skills e comandos do OpenSpec (openspec init)
├── postman/
│   ├── hospital-fase3.postman_collection.json
│   └── hospital-fase3.postman_environment.json
├── shared-contracts/                # POJOs dos eventos + constantes de exchange/routing key
│   └── src/main/java/br/com/fiap/hospital/contracts/
├── shared-security/                 # filtro JWT, JwtProperties, resolver de perfil
│   └── src/main/java/br/com/fiap/hospital/security/
├── agendamento-service/
│   └── src/main/java/br/com/fiap/hospital/agendamento/
│       ├── domain/                  # entidades, VOs, exceções, portas — ZERO Spring
│       ├── application/             # casos de uso, DTOs de entrada/saída
│       └── infrastructure/
│           ├── web/                 # controllers, advice, request/response
│           ├── persistence/         # JPA entities, repositories, mappers
│           ├── messaging/           # outbox publisher, RabbitMQ config
│           └── security/            # config do Spring Security, emissão de JWT
├── notificacao-service/
│   └── src/main/java/br/com/fiap/hospital/notificacao/
│       ├── consumer/                # listeners AMQP
│       ├── domain/                  # Lembrete, AgendaLocal
│       ├── scheduler/               # job D-1
│       ├── sender/                  # porta + adaptadores Log/SMTP
│       └── repository/
└── historico-service/
    └── src/main/java/br/com/fiap/hospital/historico/
        ├── consumer/                # projeta eventos no read model
        ├── graphql/                 # controllers @QueryMapping, schema.graphqls
        ├── model/
        └── repository/
```

## 4. agendamento-service — Clean Architecture

**Regra de dependência:** `infrastructure → application → domain`. Nunca o inverso.

### domain
- Entidades: `Consulta`, `Usuario`, `Paciente`, `Medico`
- Value objects: `PeriodoConsulta`, `Cpf`, `Email`, `Crm`
- Enums: `PerfilUsuario`, `StatusConsulta`
- Exceções de negócio: `ConflitoDeAgendaException`, `TransicaoDeStatusInvalidaException`, `AgendamentoNoPassadoException`, `MotivoDeCancelamentoObrigatorioException`, `AlteracaoConcorrenteException`
- `AlteracaoConcorrenteException` é lançada pelo adaptador de persistência ao traduzir a falha de lock otimista. Ela mora no `domain` para que nenhum tipo do Spring suba pela camada de aplicação, e é o que permite ao M03 montar o `ProblemDetail` sem conhecer exceções de persistência
- Exceções de recurso inexistente: `RecursoNaoEncontradoException` (base abstrata) e os subtipos `ConsultaNaoEncontradaException`, `PacienteNaoEncontradoException`, `MedicoNaoEncontradoException`. A base existe para que o mapa da §8 precise de uma entrada só e o tratador do M03 capture a família inteira; cada subtipo carrega a mensagem do seu próprio recurso, de modo que um paciente inexistente não responda "Consulta não encontrada"
- Portas de saída: `ConsultaRepositoryPort`, `UsuarioRepositoryPort`, `EventPublisherPort`

Restrição dura: **nenhum import de `org.springframework`, `jakarta.persistence` ou `com.fasterxml` no pacote `domain`** — verificado por ArchUnit.

### application
Um caso de uso por arquivo, cada um com um único método público:
- `AgendarConsultaUseCase`
- `AtualizarConsultaUseCase`
- `CancelarConsultaUseCase`
- `ConfirmarConsultaUseCase`
- `BuscarConsultaPorIdUseCase`
- `ListarConsultasUseCase`
- `AutenticarUsuarioUseCase`

### infrastructure
Adaptadores. É o único lugar onde Spring, JPA, AMQP e HTTP aparecem.

### Regras de negócio do agendamento
1. Consulta não pode ser marcada no passado.
2. Um médico não pode ter duas consultas ativas com sobreposição de horário (duração padrão 30 min, configurável).
3. Um paciente não pode ter duas consultas ativas com sobreposição de horário.
4. Transições válidas de status: `AGENDADA → CONFIRMADA | CANCELADA | REALIZADA`, `CONFIRMADA → REALIZADA | CANCELADA`. `REALIZADA` e `CANCELADA` são terminais.
5. Consulta cancelada ou realizada não pode ser reagendada — cria-se uma nova.
6. Toda mudança de estado gera um evento no outbox, na mesma transação.

## 5. notificacao-service

Dois gatilhos de lembrete:

| Gatilho | Quando | O que envia |
|---|---|---|
| **Reativo** | Ao consumir `consulta.criada` / `consulta.atualizada` / `consulta.cancelada` | Confirmação imediata ao paciente |
| **Proativo** | Job `@Scheduled` de hora em hora | Lembrete para consultas que ocorrem nas próximas 24h e ainda não tiveram lembrete enviado |

Para o job proativo funcionar sem chamar o agendamento, o serviço mantém uma **agenda local** (tabela `agenda_local`) alimentada pelos eventos. Isso mantém o desacoplamento — o notificacao-service nunca faz HTTP para o agendamento.

`NotificationSenderPort` com dois adaptadores selecionados por profile:
- `LogNotificationSender` (padrão, `notificacao.sender=log`)
- `SmtpNotificationSender` (`notificacao.sender=smtp`, aponta para Mailpit no compose — dá uma demo visual de e-mail chegando)

Toda notificação enviada é persistida em `notificacao_enviada` para auditoria e para evitar reenvio.

## 6. historico-service

Read model puro. Não aceita escrita por HTTP exceto a correção de registro pelo médico (RF-13).

```graphql
type Query {
  consultasDoPaciente(pacienteId: ID!, filtro: FiltroConsulta): [ConsultaHistorico!]!
  minhasConsultas(filtro: FiltroConsulta): [ConsultaHistorico!]!
  consulta(id: ID!): ConsultaHistorico
  consultasDoMedico(medicoId: ID!, filtro: FiltroConsulta): [ConsultaHistorico!]!
}

input FiltroConsulta {
  periodo: PeriodoFiltro   # TODAS | FUTURAS | PASSADAS
  status: [StatusConsulta!]
  de: DateTime
  ate: DateTime
}
```

`minhasConsultas` resolve o `pacienteId` a partir do JWT — é o caminho do paciente, que nunca informa um id de terceiro.

Autorização por resolver, com `@PreAuthorize`, e uma checagem extra de propriedade: se o perfil é `PACIENTE`, o `pacienteId` do argumento tem de bater com o do token, senão `403`.

## 7. Segurança

```
POST /auth/login  { email, senha }  →  { accessToken, expiresIn, perfil }
```

Claims do JWT: `sub` (id do usuário), `email`, `perfil`, `pacienteId` ou `medicoId` quando aplicável, `iat`, `exp`.

Os três serviços compartilham o segredo via variável de ambiente `JWT_SECRET` e validam o token com o mesmo filtro, vindo do módulo `shared-security`. Senhas com `BCryptPasswordEncoder`.

Cadeia de filtros: `SecurityFilterChain` stateless, CSRF desabilitado (API), `/auth/login`, `/actuator/health` e `/v3/api-docs/**` liberados, todo o resto autenticado.

## 8. Tratamento de erros

`@RestControllerAdvice` global devolvendo **RFC 7807 Problem Detail**:

```json
{
  "type": "https://hospital.fiap.br/erros/conflito-de-agenda",
  "title": "Conflito de agenda",
  "status": 409,
  "detail": "O médico já possui consulta entre 14:00 e 14:30 em 10/09/2026",
  "instance": "/api/v1/consultas",
  "correlationId": "0f2a...",
  "timestamp": "2026-09-02T13:00:00Z"
}
```

Mapa de exceções: `AgendamentoNoPassado` → 422, `ConflitoDeAgenda` → 409, `RecursoNaoEncontrado` → 404, `TransicaoDeStatusInvalida` → 409, `MotivoDeCancelamentoObrigatorio` → 422, `AlteracaoConcorrente` → 409, `IllegalArgumentException` → 400, `MethodArgumentNotValid` → 400, `AccessDenied` → 403, `Authentication` → 401.

`RecursoNaoEncontrado` é a **base** de `ConsultaNaoEncontrada`, `PacienteNaoEncontrado` e `MedicoNaoEncontrado`. Mapear a base cobre os três com uma entrada só, e um recurso novo em change futura não exige mexer neste mapa — só herdar. O `detail` do `ProblemDetail` vem da mensagem da exceção concreta, então o cliente continua sabendo qual recurso faltou.

Os dois **409** têm `type` distinto, e a distinção não é cosmética. `conflito-de-agenda` é definitivo: repetir a requisição dá o mesmo resultado, e o cliente precisa escolher outro horário. `alteracao-concorrente` é transitório: recarregar o recurso e repetir a operação normalmente funciona. O status HTTP não carrega essa diferença — o `type` da RFC 7807 é o identificador estável da categoria, e é onde o cliente programa sua reação. Colapsar os dois obrigaria o cliente a interpretar o `detail`, que é prosa em português destinada a humanos.

`IllegalArgumentException` entra no mapa **explicitamente**. Ela é lançada pelos value objects do domínio (`Cpf`, `Email`, `Crm`) quando o formato não confere, e sem essa linha cairia no handler genérico de 500 — um erro de entrada respondido como falha de servidor. A Bean Validation normalmente a intercepta antes, no DTO, mas o domínio é chamável por outros caminhos e não pode depender disso.

## 9. Observabilidade

- `correlationId` gerado no filtro de entrada (ou lido do header `X-Correlation-Id`), colocado no MDC, propagado no header da mensagem AMQP e restaurado no MDC do consumidor. Rastreia um fluxo ponta a ponta nos três logs.
- Actuator com `health`, `info`, `metrics` expostos.
- Log em JSON no profile `docker`.

## 10. Convenções de código e teste

Estas convenções são injetadas em toda requisição de planejamento pelo `context:` do `openspec/config.yaml`. Aqui fica a versão com a justificativa.

### Código

| Convenção | Por quê |
|---|---|
| Pacote raiz `br.com.fiap.hospital` | Namespace único do projeto |
| Clean Architecture **apenas** no `agendamento-service` | É onde as regras vivem. Nos outros dois seria boilerplate sem retorno — eles são adaptadores de evento |
| Records para DTOs | Imutabilidade e menos ruído |
| Mapeamento domínio ↔ entidade **manual** | Sem MapStruct: a banca lê o código, e mapeamento gerado esconde o que está acontecendo |
| `Clock` injetado, nunca `LocalDateTime.now()` | Sem isso não há como testar as regras de janela temporal (conflito de agenda, lembrete D-1) de forma determinística |
| Erros em RFC 7807 `ProblemDetail` | Padrão, com `correlationId` para amarrar ao log |
| Schema só por migration Flyway | `ddl-auto: update` torna o schema imprevisível e não versionado |
| Mensagens de validação e erro em português | O sistema é hospitalar brasileiro; a banca também lê em português |

### Teste

| Convenção | Por quê |
|---|---|
| `*Test.java` no surefire, `*IT.java` no failsafe | `mvn test` fica rápido no ciclo curto; `mvn verify` roda tudo antes do PR |
| Infraestrutura real via Testcontainers | Mockar broker e banco esconde exatamente os erros que importam aqui: transação, idempotência, DLQ, índice |
| Cobertura ≥ 85% global, ≥ 90% em `domain` e `application` | Gate no build, não meta aspiracional |
| Um teste de integração por célula da matriz de autorização | É o item de segurança que a banca consegue verificar objetivamente |

### Cortes conscientes

Não usamos: MapStruct, Lombok, `@MockBean` para infraestrutura, banco em memória (H2). Cada uma dessas escolhas troca velocidade de escrita por perda de fidelidade ou de legibilidade — trocas ruins num projeto que vai ser lido e avaliado.
