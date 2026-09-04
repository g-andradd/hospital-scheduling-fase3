# Especificação Funcional — Requisitos Rastreáveis

Cada requisito tem um id. Todo PR deve citar os ids que fecha. A auditoria final (M14) percorre esta lista.

> **Rastreabilidade com o OpenSpec.** Os ids RF/RNF são a linguagem da FIAP e existem para a auditoria da entrega. A verdade operacional do que está construído vive em `openspec/specs/<capability>/spec.md`, no formato `Requirement` + `Scenario`. A coluna **Capability** abaixo faz a ponte entre os dois — um RF é fechado quando os Scenarios da capability correspondente estão implementados e testados.

| Capability OpenSpec | Requisitos que cobre |
|---|---|
| `autenticacao-e-autorizacao` | RF-01 a RF-04, RNF-01, RNF-02 |
| `agendamento-de-consultas` | RF-05 a RF-10, RNF-03, RNF-09, RNF-10 |
| `mensageria-de-eventos` | RF-15, RF-20 |
| `notificacoes-ao-paciente` | RF-16, RF-17, RF-19 |
| `historico-de-consultas` | RF-11 a RF-14, RF-18 |
| `operacao-do-ambiente` | RNF-04 a RNF-08 |

## 1. Requisitos funcionais

### Autenticação e usuários

| Id | Requisito | Origem | Change |
|---|---|---|---|
| RF-01 | O sistema autentica usuário por e-mail e senha e devolve um JWT com o perfil | Enunciado §1 | M04 |
| RF-02 | Existem três perfis: `MEDICO`, `ENFERMEIRO`, `PACIENTE` | Enunciado §1 | M04 |
| RF-03 | Requisição sem token válido em endpoint protegido retorna 401 | Enunciado §1 | M04 |
| RF-04 | Requisição com token válido mas perfil sem permissão retorna 403 | Enunciado §1 | M04 |

### Agendamento

| Id | Requisito | Origem | Change |
|---|---|---|---|
| RF-05 | Médico ou enfermeiro registra uma nova consulta (paciente, médico, data/hora) | Enunciado §2 | M01, M03 |
| RF-06 | Médico ou enfermeiro modifica uma consulta existente (remarcação, observações) | Enunciado §2 | M01, M03 |
| RF-07 | Consulta pode ser cancelada, com motivo obrigatório | Derivado | M01, M03 |
| RF-08 | O sistema recusa consulta com data/hora no passado (422) | Derivado | M01 |
| RF-09 | O sistema recusa consulta que conflite com a agenda do médico ou do paciente (409) | Derivado | M01 |
| RF-10 | Transições de status inválidas são recusadas (409) | Derivado | M01 |

### Histórico e consultas (GraphQL)

| Id | Requisito | Origem | Change |
|---|---|---|---|
| RF-11 | O histórico é consultável via GraphQL, com filtro por período (todas / futuras / passadas) e status | Enunciado §2 | M09 |
| RF-12 | Paciente consulta **apenas** as próprias consultas; tentativa de ver as de outro retorna 403 | Enunciado §1 | M09 |
| RF-13 | Médico pode editar/corrigir o registro histórico de uma consulta | Enunciado §1 | M09 |
| RF-14 | Enfermeiro tem acesso de leitura ao histórico de qualquer paciente | Enunciado §1 | M09 |

### Comunicação assíncrona e notificações

| Id | Requisito | Origem | Change |
|---|---|---|---|
| RF-15 | Ao criar ou editar uma consulta, o agendamento publica mensagem no broker | Enunciado §4 | M05 |
| RF-16 | O serviço de notificações consome a mensagem e envia lembrete ao paciente | Enunciado §4 | M06 |
| RF-17 | O serviço de notificações envia lembrete automático D-1 para consultas das próximas 24h | Enunciado "Problema" | M07 |
| RF-18 | O serviço de histórico consome os mesmos eventos e materializa o read model | Enunciado §3 | M08 |
| RF-19 | Mensagem processada duas vezes não gera efeito duplicado (idempotência por `eventId`) | Boas práticas | M06, M08 |
| RF-20 | Mensagem que falha após as tentativas configuradas vai para a DLQ, sem perda | Boas práticas | M05 |

## 2. Requisitos não funcionais

| Id | Requisito | Change |
|---|---|---|
| RNF-01 | Senhas armazenadas com BCrypt; nunca em log ou resposta | M04 |
| RNF-02 | API stateless, sem sessão de servidor | M04 |
| RNF-03 | Erros no formato RFC 7807 Problem Detail | M03 |
| RNF-04 | Cobertura de linha global ≥ 85% (JaCoCo) | M10 |
| RNF-05 | Regra de dependência da Clean Architecture verificada por ArchUnit no `agendamento-service` | M11 |
| RNF-06 | Testes de integração com Testcontainers reais (Postgres + RabbitMQ), sem mock de infra | M10 |
| RNF-07 | Ambiente completo sobe com um `docker compose up` | M12 |
| RNF-08 | `correlationId` propagado por HTTP e AMQP, visível nos logs dos três serviços | M11 |
| RNF-09 | Schema versionado com Flyway; nenhuma alteração de schema fora de migration | M02 |
| RNF-10 | Cada serviço com seu próprio database — nenhuma tabela compartilhada | M02 |

## 3. Matriz de autorização

Consolidada a partir do enunciado §1 e §2 (ver ADR-004 para a resolução da ambiguidade).

### agendamento-service — REST

| Endpoint | Método | MEDICO | ENFERMEIRO | PACIENTE |
|---|---|:---:|:---:|:---:|
| `/auth/login` | POST | público | público | público |
| `/api/v1/consultas` | POST | ✅ | ✅ | ❌ 403 |
| `/api/v1/consultas/{id}` | PUT | ✅ | ✅ | ❌ 403 |
| `/api/v1/consultas/{id}/cancelar` | PATCH | ✅ | ✅ | ❌ 403 |
| `/api/v1/consultas/{id}/confirmar` | PATCH | ✅ | ✅ | ✅ (só a própria) |
| `/api/v1/consultas/{id}` | GET | ✅ | ✅ | ✅ (só a própria) |
| `/api/v1/consultas` | GET | ✅ | ✅ | ✅ (filtro forçado ao próprio id) |

### historico-service — GraphQL

| Operação | MEDICO | ENFERMEIRO | PACIENTE |
|---|:---:|:---:|:---:|
| `consultasDoPaciente(pacienteId:)` | ✅ qualquer | ✅ qualquer | ✅ **apenas o próprio id**, senão 403 |
| `minhasConsultas` | ✅ | ✅ | ✅ |
| `consultasDoMedico(medicoId:)` | ✅ | ✅ | ❌ 403 |
| `consulta(id:)` | ✅ | ✅ | ✅ (só se for sua) |
| `corrigirRegistroHistorico` (mutation) | ✅ | ❌ 403 | ❌ 403 |

**Teste obrigatório:** para cada célula ✅/❌ desta matriz existe um teste de integração. É o item de segurança que a banca mais consegue verificar objetivamente.

## 4. Modelo de domínio

### agendamento_db

```
usuario           (id, nome, email UK, senha_hash, perfil, ativo, criado_em)
paciente          (id, usuario_id FK UK, cpf UK, data_nascimento, telefone)
medico            (id, usuario_id FK UK, crm UK, especialidade)
consulta          (id, paciente_id FK, medico_id FK, registrado_por_id FK,
                   data_hora, duracao_minutos, status, observacoes,
                   motivo_cancelamento, criado_em, atualizado_em, versao, periodo_ocupado tstzrange)
outbox_evento     (id, agregado_id, tipo_evento, payload jsonb, routing_key,
                   criado_em, publicado_em, tentativas)
```

Índices: `consulta(medico_id, data_hora)`, `consulta(paciente_id, data_hora)`, `outbox_evento(publicado_em) WHERE publicado_em IS NULL`.

No M05, periodo_ocupado é NOT NULL e derivado por trigger BEFORE INSERT OR UPDATE em UTC, inclusive para SQL direto. As constraints ex_consulta_medico_periodo e ex_consulta_paciente_periodo usam EXCLUDE USING gist, igualdade UUID via btree_gist e sobreposição de range apenas em AGENDADA/CONFIRMADA. São NOT DEFERRABLE; [início,fim) permite adjacência. V3 faz diagnóstico prévio e backfill, recusando dados ativos inconsistentes sem reparação silenciosa.

Os timestamps preservam instantes, não o deslocamento original. payload do outbox contém o envelope completo, id=eventId, agregado_id=aggregateId e criado_em=occurredAt. tentativas é numeric inteiro não negativo, sem teto de 32 bits; publicado_em fica nulo até confirmação do relay. A ordenação do lote por tentativas/criado_em/id não é resolvida pelo índice parcial de publicado_em.

### notificacao_db

```
agenda_local        (consulta_id PK, paciente_id, paciente_nome, paciente_email,
                     medico_nome, data_hora, status, atualizado_em)
notificacao_enviada (id, consulta_id, tipo, destinatario, canal, enviado_em, conteudo)
evento_processado   (event_id PK, processado_em)
```

### historico_db

```
consulta_historico  (id PK, paciente_id, paciente_nome, medico_id, medico_nome,
                     especialidade, data_hora, status, observacoes,
                     criado_em, atualizado_em)
consulta_evento     (id, consulta_id FK, tipo_evento, ocorrido_em, payload jsonb)
evento_processado   (event_id PK, processado_em)
```

`consulta_evento` guarda a trilha completa de mudanças — é o que torna "histórico" mais do que uma cópia da tabela de consultas, e dá material para o relatório.

## 5. Dados de seed (profile `demo`)

Para a banca conseguir testar sem cadastrar nada:

| Perfil | E-mail | Senha |
|---|---|---|
| MEDICO | `medico@hospital.com` | `Senha@123` |
| ENFERMEIRO | `enfermeiro@hospital.com` | `Senha@123` |
| PACIENTE | `paciente@hospital.com` | `Senha@123` |
| PACIENTE (segundo, para testar o 403) | `paciente2@hospital.com` | `Senha@123` |

Mais 5 consultas pré-existentes: 2 passadas realizadas, 1 cancelada, 2 futuras. Carregadas por migration Flyway sob o profile `demo`.
