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
├── .gitattributes                   # *.sh com fim de linha LF
├── scripts/
│   ├── smoke-test.sh                # smoke ponta a ponta sem Compose: infraestrutura efêmera por RUN_ID (M10)
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
        ├── infrastructure/messaging/   # ConsumidorTransacionalDoHistorico, ProjetorDoHistorico e HistoricoConfig
        ├── infrastructure/persistence/ # entidades e repositórios JPA
        ├── infrastructure/leitura/     # ConsultasDoHistorico: predicados do filtro no armazenamento
        ├── infrastructure/correcao/    # CorrecaoDeRegistroHistorico: correção atômica com auditoria
        └── infrastructure/graphql/     # resolvers, schema.graphqls, escalar DateTime e os dois caminhos de erro
└── quality-gates/                   # módulo técnico (M10), sem aplicação e sem Spring, construído por último
    └── src/main/java/br/com/fiap/hospital/qualidade/
                                     # relatório agregado JaCoCo, auditoria de execução, gate de cobertura
```

O reactor tem **sete projetos Maven**:
- a raiz;
- os cinco módulos de código;
- o `quality-gates`, sexto módulo filho, com os cinco módulos de código como dependências
  internas em escopo `compile`.

Na fase `verify`, o `quality-gates` roda o relatório agregado, a auditoria de execução e o gate
de cobertura, nessa ordem. Os testes dele verificam a topologia do reactor, a exclusão de
cobertura, a infraestrutura real dos ITs e o roteiro de smoke.

O `scripts/smoke-test.sh` **não usa Compose** nem imagens das aplicações:
- sobe PostgreSQL 16 e RabbitMQ 3.13 efêmeros, identificados pelo `RUN_ID`;
- executa os três serviços como processos `java -jar` dos `*-exec.jar`;
- remove, ao final, somente o que criou.

O Compose local e os containers `hospital-postgres` e `hospital-rabbitmq` ficam intactos.

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

Host, porta e remetente do adaptador SMTP vêm de variáveis de ambiente; não há credencial nem
endereço de provedor no repositório.

### Consumo (M06)

O consumidor AMQP é a única fronteira do serviço. Para cada envelope válido, na mesma
transação, ele atualiza a `agenda_local`, envia a notificação reativa quando o fato a exige e
registra o envio; só então grava o `eventId` em `evento_processado`. A ordem — efeito, depois
marca — vem da seção 6 de `docs/03-contrato-de-eventos.md`.

**Todos os cinco tipos** materializam a agenda por upsert, inclusive quando a criação daquela
consulta ainda não foi processada: o snapshot do evento é autossuficiente, e depender da
ordem deixaria a consulta sem linha após uma entrega fora de ordem ou um replay da DLQ. O
upsert é condicionado a `occurredAt >= ocorrido_em`, avaliado pelo PostgreSQL sob o lock da
linha: fato anterior é marcado como processado, mas não regride a agenda nem dispara
notificação obsoleta. Empate de instante segue a ordem de chegada, porque o contrato não
declara sequência por agregado. Cancelamento **atualiza** o status e nunca remove a linha — o
lembrete do M07 precisa distinguir "cancelada" de "inexistente".

Notificam apenas criação, atualização e cancelamento, conforme a tabela acima; confirmação e
realização atualizam a agenda em silêncio. A notificação está atrelada à **aplicação** do
fato, não ao seu recebimento.

Todos os instantes que o serviço registra — `atualizado_em`, `enviado_em` e `processado_em` —
vêm do `Clock` injetado. O instante do fato (`ocorrido_em`) vem do envelope: é o critério de
ordenação, e derivá-lo do relógio de consumo faria um replay reordenar a agenda.

O serviço não chama o agendamento: o `ConsultaPayload` completo é sua única fonte. Envelope
inválido, versão desconhecida e falha persistente são rejeitados para
`notificacao.consultas.dlq` após três tentativas, sem marca de processamento nem linha
parcial. `correlationId` é restaurado no MDC durante cada tentativa e limpo no `finally`.

**Garantia de entrega, dita como ela é.** O efeito **persistido** é exatamente-uma-vez por
`eventId`: agenda, auditoria e marca commitam juntas, e a chave primária de
`evento_processado` serializa a confirmação sob entregas concorrentes. O **envio externo** é
ao-menos-uma-vez — o sender pode concluir e a transação reverter, e a tentativa seguinte
reenvia; sob concorrência, ambas as entregas podem alcançar o canal antes de a chave decidir.
Disso decorre um limite da auditoria: `notificacao_enviada` registra o que transações
confirmadas produziram, e **não** todo envio que saiu. Eliminar essa janela exigiria um outbox
próprio do serviço de notificação, que nenhum requisito atual pede.

Rollback operacional: parar o consumidor e preservar banco, fila, DLQ, agenda e auditoria.
Não apagar marcas processadas — uma versão corrigida retoma sem duplicar efeito.

### Lembrete D-1 (M07)

A varredura lê a `agenda_local` com **um único comando**: consultas `AGENDADA` ou `CONFIRMADA`
com horário em `(agora, agora + 24h]` — estritamente no futuro e até 24 horas à frente,
inclusive — e ainda sem lembrete. `agora` vem do `Clock` injetado, e os dois limites vão como
parâmetros: o relógio do banco não participa. `CANCELADA` e `REALIZADA` nunca são lembradas, o
que só é possível porque o consumo atualiza o status em vez de apagar a linha. O índice
`agenda_local(status, data_hora)` sustenta o recorte, e o plano do comando real é medido em
teste com massa representativa.

Cada candidato é lembrado em transação própria, com a **reserva antes do envio**: o registro
`LEMBRETE_D1` entra em `notificacao_enviada` por `INSERT ... ON CONFLICT DO NOTHING` sobre a
unicidade parcial `notificacao_enviada(consulta_id) WHERE tipo = 'LEMBRETE_D1'`, e só então o
sender é chamado. Duas execuções concorrentes — o job e o disparo manual, ou duas instâncias —
disputam a mesma chave: a segunda espera a primeira e, se ela confirmou, não envia. A unicidade
vale durante toda a vida da consulta, então uma remarcação posterior não gera segundo lembrete —
o aviso reativo de alteração informa o novo horário. A unicidade é parcial porque as
notificações reativas repetem tipo legitimamente. `LEMBRETE_D1` é tipo local do registro, fora
de `TipoEvento`, das routing keys e da topologia.

Falha do sender reverte só aquela consulta, que continua elegível na execução seguinte; as
demais seguem, e a resposta conta só os lembretes confirmados. Falha na leitura inicial dos
candidatos propaga: o job registra o erro e tenta na hora seguinte, e o endpoint responde 500
em Problem Detail `https://hospital.fiap.br/erros/erro-interno`, com `correlationId`,
`timestamp` e `instance`, sem SQL nem exceção na resposta. O tratador é restrito ao controller do
lembrete e relança `AccessDeniedException` e `AuthenticationException`, para que 401 e 403
continuem saindo de `RespostaDeSeguranca`.

O job `@Scheduled` roda no início de cada hora (`notificacao.lembrete.cron`, padrão
`0 0 * * * *`), habilitado por padrão e **ausente** no profile `test`. `POST
/internal/lembretes/executar` dispara o mesmo caso de uso, restrito a MEDICO e ENFERMEIRO:
o serviço consome a cadeia de `shared-security` com `/internal/**` como caminho autenticado, e
a célula de cada perfil está na matriz de `docs/02-especificacao-funcional.md` §3. O texto do
lembrete formata o horário em `America/Sao_Paulo`, o fuso em que o agendamento deriva a data.

**Garantia:** no máximo um lembrete **persistido** por consulta. O **envio externo** continua
ao-menos-uma-vez numa janela: se o sender conclui e a confirmação falha, não fica registro, e a
execução seguinte reenvia.

## 6. historico-service

Read model puro. Não aceita escrita por HTTP exceto a correção de registro pelo médico (RF-13).

O consumidor AMQP é a única fronteira de projeção. Para cada envelope válido, na mesma
transação, ele atualiza `consulta_historico` por `INSERT ... ON CONFLICT DO UPDATE ... WHERE`
e grava a trilha imutável em `consulta_evento`; só então registra `eventId` em
`evento_processado`. A condição do upsert é avaliada pelo PostgreSQL sob o lock da linha:
evento antigo entra na trilha, mas não regride o snapshot. Empates de `occurredAt` respeitam a
ordem de chegada, porque o contrato não declara sequência por agregado.

O histórico não chama o agendamento: o `ConsultaPayload` completo é sua única fonte. O
consumidor aplica retry contratual de três tentativas; envelope inválido, versão desconhecida e
falha persistente são rejeitados para `historico.consultas.dlq`, sem marca de processamento nem
linha parcial na trilha.

Rollback operacional: parar o consumidor e preservar banco, filas, DLQ, trilha e marcas; corrigir e retomar, sem apagar a auditoria.

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

`minhasConsultas` resolve o `pacienteId` a partir do JWT e **é exclusiva do perfil PACIENTE**:
médico e enfermeiro recebem `FORBIDDEN` nela e leem o histórico por `consultasDoPaciente`,
`consultasDoMedico` e `consulta(id:)`. A operação não aceita identificador como argumento.

Autorização por resolver, com `@PreAuthorize`, e uma checagem extra de propriedade fora do corpo
do resolver: se o perfil é `PACIENTE`, o alvo tem de bater com o do token, senão `FORBIDDEN`.
Cada célula da matriz da §3 de `docs/02-especificacao-funcional.md` vira um caso de teste lido
do próprio documento, e uma operação nova sem decisão de autorização quebra a varredura estrutural.

**Semântica do filtro.** `TODAS` não recorta pelo relógio; `FUTURAS` seleciona `dataHora >= agora`;
`PASSADAS`, `dataHora < agora` — um registro no instante exato pertence ao futuro. O intervalo é
`[de, ate)`. Período, intervalo e status são combinados por AND, lista de status vazia não restringe,
e a ordenação é `dataHora, id`, total e determinística. `agora` vem do `Clock` injetado, e todo
predicado é aplicado pelo PostgreSQL.

**Correção manual (RF-13).** `corrigirRegistroHistorico` é exclusiva de MEDICO. Corrige nome do
paciente, nome do médico, especialidade, `dataHora`, status e observações; `consultaId` apenas
seleciona o alvo, e não existe campo corrigível de identificador nem de autor — o autor vem do
token. Campo ausente não corrige; nulo explícito só é aceito em `observacoes`, onde limpa o registro.
O status precisa pertencer ao enum válido, mas a máquina de transições do agendamento **não** se
aplica: corrigir um status errado é o caso de uso. Na mesma transação, o serviço trava a linha,
aplica a correção, avança `atualizado_em` pelo `Clock` e grava em `consulta_evento` uma linha
`CORRECAO_MANUAL` com autor, justificativa e valores antes/depois. Falha em qualquer ponto reverte
tudo. A correção não grava `evento_processado` e não publica evento; `CORRECAO_MANUAL` é tipo local
da trilha e não entra em `TipoEvento`, routing key ou topologia. Como a correção avança
`atualizado_em`, a regra de monotonicidade do M08 continua valendo: evento anterior não a desfaz,
evento posterior a sobrescreve.

**Erros.** Duas naturezas de falha, mesma política. O que é recusado na análise e validação do
documento — campo desconhecido, enum inexistente, tipo incompatível — é normalizado por um
`WebGraphQlInterceptor` para `extensions.code=BAD_REQUEST`, sem que resolver algum execute. O que é
lançado dentro do resolver passa pelo `DataFetcherExceptionResolver`: `FORBIDDEN`, `NOT_FOUND`,
`BAD_REQUEST` de domínio e, para o inesperado, mensagem genérica sem SQL, stack trace ou nome de
classe — a causa vai para o log do serviço. Token ausente ou inválido é recusado antes dos dois, na
fronteira HTTP.

**Segurança e GraphiQL.** O histórico não emite token: consome o filtro e o segredo de
`shared-security`. A cadeia compartilhada passou a ler duas listas configuráveis — caminhos
autenticados (padrão `/api/**`) e caminhos públicos adicionais (padrão vazio) —, e continua sendo
uma única `SecurityFilterChain` com `denyAll` por omissão. O histórico acrescenta `/graphql` à
primeira em qualquer profile; **apenas** `dev` e `demo` habilitam a interface GraphiQL e acrescentam
`/graphiql` à segunda. No profile padrão a interface fica desabilitada e o caminho cai no `denyAll`.

**Índices.** `V2` cria `consulta_historico(paciente_id, data_hora)` e `(medico_id, data_hora)`, sem
tocar em `V1`. Não há índice por status: baixa seletividade sobre quatro valores, e o status nunca
aparece sozinho nas consultas expostas. O plano de execução real é verificado em teste.

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

Mapa de exceções: `AgendamentoNoPassado` → 422, `AgendamentoForaDoHorizonte` → 422 (início **e** fim do período, que não pode ultrapassar o horizonte), `DateTimeException` → 400, `ConflitoDeAgenda` → 409, `RecursoNaoEncontrado` → 404, `TransicaoDeStatusInvalida` → 409, `MotivoDeCancelamentoObrigatorio` → 422, `TextoComCaractereInvalido` → 422, `AlteracaoConcorrente` → 409, `CredencialInvalida` → 401, `AcessoNegado` → 403, `IllegalArgumentException` → 400 (inclui limite de intervalo fora da faixa temporal suportada), `MethodArgumentNotValid` → 400, `HttpMessageConversionException` na leitura do corpo → 400, `AccessDenied` (Spring Security) → 403, `AuthenticationException` → 401.

As recusas de segurança têm `type` próprio e distinto entre si:

| Situação | Status | `type` |
|---|:---:|---|
| Credencial ausente, inválida, vencida ou adulterada | 401 | `https://hospital.fiap.br/erros/nao-autenticado` |
| Autenticado, mas o perfil não alcança a operação | 403 | `https://hospital.fiap.br/erros/acesso-negado` |

Os dois chegam por **dois caminhos diferentes**, e ambos precisam de tratamento. A recusa da cadeia de filtros passa pelo `AuthenticationEntryPoint` e pelo `AccessDeniedHandler`, que respondem antes de a requisição alcançar o `@RestControllerAdvice`. Já a recusa do `@PreAuthorize` é lançada **dentro** da invocação do controller e sobe pelo advice — sem tratador nominal ali, cairia no catch-all e viraria 500: recusa de autorização respondida como falha de servidor.

O detalhe é fixo por categoria e não menciona o que faltou. "Token expirado" informaria que o token já foi válido; "usuário não encontrado" no login transformaria o endpoint em oráculo de e-mails cadastrados.

`RecursoNaoEncontrado` é a **base** de `ConsultaNaoEncontrada`, `PacienteNaoEncontrado` e `MedicoNaoEncontrado`. Mapear a base cobre os três com uma entrada só, e um recurso novo em change futura não exige mexer neste mapa — só herdar. O `detail` do `ProblemDetail` vem da mensagem da exceção concreta, então o cliente continua sabendo qual recurso faltou.

Os dois **409** têm `type` distinto, e a distinção não é cosmética. `conflito-de-agenda` é definitivo: repetir a requisição dá o mesmo resultado, e o cliente precisa escolher outro horário. `alteracao-concorrente` é transitório: recarregar o recurso e repetir a operação normalmente funciona. O status HTTP não carrega essa diferença — o `type` da RFC 7807 é o identificador estável da categoria, e é onde o cliente programa sua reação. Colapsar os dois obrigaria o cliente a interpretar o `detail`, que é prosa em português destinada a humanos.

`AgendamentoForaDoHorizonte` é 422 pela mesma razão que `AgendamentoNoPassado`: a requisição está bem formada e é recusada por regra de negócio. O horizonte de agendamento é de **24 meses** — decisão de produto, não limite técnico: agenda hospitalar não se planeja com mais de dois anos, e um pedido além disso é quase sempre erro de digitação no ano. O limite também fecha uma classe de falha, porque uma data no ano 999999999 atravessava o domínio e só estourava na aritmética.

`DateTimeException` → 400 é rede de segurança para qualquer outro caminho que estoure cálculo de data. O `detail` é estável, porque a mensagem do JDK cita tipo e campo internos.

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
| Cobertura ≥ 85% global, ≥ 90% em `domain` e `application` | Gate no build, não meta aspiracional. A partir do M10 é regra efetiva: o `quality-gates` mede linhas no relatório agregado dos cinco módulos e reprova `mvn -q clean verify` abaixo do piso |
| Gate global sem filtro de teste | A auditoria de execução recusa seleção, exclusão, omissão e tolerância — por parâmetro, pelos POMs ou no código de teste —, e o gate só aceita evidência da sessão corrente. A garantia é por suíte e família de relatórios, não por método |
| Infraestrutura real verificada | O `InfraestruturaRealTest` recusa dublê de banco ou broker, banco em memória e imagem fora da versão adotada, e cada módulo prova em runtime, num `InfraestruturaReal*IT`, só a infraestrutura que usa |
| Exemplar do contrato em cópia única | `evento-consulta.json` vive só no `shared-contracts` e chega aos serviços por test-jar; produtor e consumidores o exercitam pelo broker real |
| Um teste de integração por célula da matriz de autorização | É o item de segurança que a banca consegue verificar objetivamente |

### Cortes conscientes

Não usamos: MapStruct, Lombok, `@MockBean` para infraestrutura, banco em memória (H2). Cada uma dessas escolhas troca velocidade de escrita por perda de fidelidade ou de legibilidade — trocas ruins num projeto que vai ser lido e avaliado.

## 9. Publicação transacional e concorrência (M05)

Os decoradores de infrastructure/transacao continuam delimitando a transação REQUIRED dos quatro casos de escrita. OutboxEventPublisher exige MANDATORY; JdbcTemplate participa da mesma conexão transacional gerenciada pelo JpaTransactionManager. O fato inclui snapshots imutáveis antes/depois da alteração. Serialização ou gravação recusada desfaz a consulta junto com o evento.

O relay é um bean transacional separado do scheduler. Seleciona até 50 pendentes por tentativas/criado_em/id com FOR UPDATE SKIP LOCKED, mantém locks durante a publicação e só marca sucesso com ACK sem return. Contador numeric não tem limite artificial; falhas permanecem pendentes, priorizadas depois de eventos novos. At-least-once é parte do contrato: queda entre ACK e commit local reenvia o mesmo eventId/envelope. M06/M08 devem deduplicar.

O filtro HTTP conserva o correlationId no atributo/resposta e no MDC durante a cadeia. O publisher o grava no envelope; o relay recupera esse valor minutos depois e restaura o MDC anterior ao terminar cada evento. dataHora deriva do instante em America/Sao_Paulo na data da consulta, inclusive regras históricas; occurredAt é UTC e não muda com a publicação tardia.

V3 acrescenta periodo_ocupado, derivado por trigger em UTC, e exclusões GiST independentes para médico e paciente ativos. O intervalo é semiaberto. As pré-queries antecipam mensagens de conflito; a constraint garante corretude inclusive para outro escritor SQL. A expressão timestamptz + interval não é indexada nem declarada falsamente IMMUTABLE.

Em saveAndFlush, somente 23P01 das duas constraints nomeadas vira ConflitoDeAgendaException. Nome vem do diagnóstico estruturado do driver, pois o Hibernate pode omiti-lo. 40P01 e 40001 viram AlteracaoConcorrenteException, assim como lock otimista, sem afirmar que o horário está ocupado. Os dois tipos continuam 409 distintos. Nenhuma query é feita após o erro e nenhuma exceção é engolida para tentar commit.

Não há retry automático: decisão consciente para o ambiente demonstrativo, onde o IT força a corrida. Múltiplas instâncias ou tráfego concorrente real exigem reavaliar retentativa limitada da transação inteira.

A configuração compartilhada de RabbitMQ aplica a topologia literal do contrato, validação estrita de envelope/headers e retry incluindo conversão. default-requeue-rejected=false é obrigatório. DLQs não têm consumidor/reenvio automático. Procedimentos e diagnóstico estão no [README](../README.md#operação-dos-eventos-de-consulta-m05); decisões em [ADR-001](adr/ADR-001-rabbitmq.md) e [ADR-006](adr/ADR-006-transactional-outbox.md).
