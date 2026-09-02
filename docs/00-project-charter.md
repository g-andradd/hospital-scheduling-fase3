# Project Charter — Tech Challenge Fase 3
## Sistema de Agendamento e Histórico de Consultas Hospitalares

| Campo | Valor |
|---|---|
| Curso | FIAP Pós Tech — Arquitetura e Desenvolvimento Java |
| Fase | 03 |
| Repositório | `g-andradd/hospital-scheduling-fase3` |
| Autor | Gabriel Andrade Almeida |
| Metodologia | Spec-Driven Development com **OpenSpec** + **GitFlow**, três papéis |
| Data de abertura | 2026-09-02 |

---

## 1. Papéis do projeto

| Papel | Quem | Responsabilidade |
|---|---|---|
| **Product Owner / Revisor** | Gabriel | Aprova specs, revisa PRs, decide trade-offs, valida a entrega. **É o único que executa Git**: commits, pushes, merges, tags e PRs |
| **Gestor / Engenheiro de Prompt** | Claude (chat) | Mantém o charter, o `openspec/config.yaml` e o roadmap; escreve o enunciado de cada `/opsx:propose`; audita entregas contra os critérios de aceite |
| **Engenheiro de Software** | Claude Code | Roda `/opsx:propose`, `/opsx:apply` e `/opsx:archive`; implementa, testa, mantém o build verde. **Não executa Git** — entrega os comandos prontos ao Gabriel |

**Regra de ouro:** nenhuma linha de código é escrita sem uma proposta aprovada. Se o Claude Code precisar tomar uma decisão que a spec não cobre, ele **para e pergunta** em vez de improvisar.

O OpenSpec torna essa regra mecânica: a proposta deixa de ser uma mensagem de chat e vira um artefato versionado (`openspec/changes/<id>/`), revisado antes do código e arquivado depois do merge. O fluxo completo está em [`docs/05-fluxo-de-trabalho.md`](05-fluxo-de-trabalho.md).

---

## 2. Problema (extraído do enunciado)

Ambientes hospitalares precisam de um sistema que garanta:

1. Agendamento eficaz de consultas.
2. Gerenciamento do histórico de pacientes.
3. Envio de lembretes automáticos para reduzir faltas.
4. Acesso controlado por perfil — médicos, enfermeiros e pacientes.

## 3. Objetivo

Backend **simplificado e modular**, com foco em **segurança** e **comunicação assíncrona**, escalável e apoiado em boas práticas de autenticação, autorização e integração entre serviços.

## 4. Fora de escopo

- Frontend / aplicativo. A entrega é backend.
- Integração com provedores reais de SMS/WhatsApp. O envio é abstraído atrás de uma porta, com adaptador de log (padrão) e SMTP para demonstração local via Mailpit.
- Prontuário eletrônico, prescrição, faturamento, TISS/TUSS.
- Deploy em nuvem. A entrega roda com `docker compose up`.
- Multi-tenancy, alta disponibilidade, tuning de performance.

## 5. Decisões arquiteturais fechadas

| # | Decisão | Alternativa descartada | Motivo |
|---|---|---|---|
| D1 | **RabbitMQ** como broker | Kafka | Caso de uso é pub/sub simples com fanout para dois consumidores; AMQP dá DLQ, retry e roteamento por tópico com muito menos infraestrutura. Demo mais confiável. |
| D2 | **3 serviços**: agendamento, notificação, histórico | 2 serviços (histórico embutido) | Cobre o item opcional do enunciado e demonstra CQRS-lite: escrita no agendamento, leitura no histórico via projeção de eventos. |
| D3 | **Monorepo Maven multi-módulo** | Multi-repo | Um `git clone` + um `docker compose up` para a banca avaliar. POM pai centraliza versões; módulo `shared-contracts` evita duplicar o contrato de eventos. |
| D4 | **Clean Architecture no core, camadas simples nas bordas** | Clean nos três | O agendamento concentra as regras de negócio e merece o isolamento (com ArchUnit). Notificação e histórico são essencialmente adaptadores de evento; Clean ali seria boilerplate sem retorno. |
| D5 | **JWT stateless (HS256)** emitido pelo agendamento e validado pelos três | Sessão HTTP / Basic Auth | Basic Auth não propaga identidade entre serviços. O enunciado pede Spring Security com níveis de acesso; JWT resolve autenticação e autorização distribuída sem estado compartilhado. |
| D6 | **Transactional Outbox** no agendamento | Publicação direta no `@Transactional` | Evita a inconsistência clássica "salvou no banco mas não publicou o evento" (ou o inverso). É o argumento técnico mais forte do relatório. Marcado como corte possível se o prazo apertar (ver M05). |
| D7 | **PostgreSQL, um banco por serviço** | Banco único compartilhado | Autonomia dos serviços. Rodam como três databases na mesma instância Postgres do compose — isolamento lógico sem custo operacional. |

Cada decisão vira um ADR em `docs/adr/`.

## 6. Ambiguidades do enunciado e como foram resolvidas

O enunciado tem duas passagens em tensão:

> Seção 1: "**Enfermeiros:** podem registrar consultas e acessar o histórico."
> Seção 2: "**Serviço de Agendamento:** médicos e enfermeiros poderão registrar novas consultas e **modificar** consultas existentes."

**Resolução adotada:** a Seção 2 é mais específica sobre o serviço de agendamento e prevalece ali — médicos e enfermeiros criam e modificam consultas. A Seção 1 governa o **serviço de histórico**: médicos leem e corrigem registros históricos, enfermeiros apenas leem, pacientes leem somente os próprios.

Essa resolução está registrada em `docs/adr/ADR-004-matriz-de-autorizacao.md` e deve aparecer no relatório técnico — mostrar que a ambiguidade foi identificada e decidida conscientemente vale mais do que escolher em silêncio.

## 7. Definition of Done (por change)

A lista completa está em [`docs/05-fluxo-de-trabalho.md`](05-fluxo-de-trabalho.md) §6. Em resumo, uma change só é arquivada quando todas as tasks estão marcadas, a verificação do OpenSpec e o `mvn clean verify` passam, cada `#### Scenario:` da spec delta tem teste correspondente, a cobertura atende o mínimo, o ArchUnit está verde, a documentação afetada foi atualizada no mesmo PR, e o Gabriel aprovou o merge em `develop`.

## 7.1 Releases

Três releases, conforme `docs/05-fluxo-de-trabalho.md` §4:

| Versão | Fecha após | Entrega |
|---|---|---|
| `0.1.0` | M04 | Agendamento seguro ponta a ponta |
| `0.2.0` | M09 | Mensageria, notificações e histórico GraphQL |
| `1.0.0` | M14 | Entrega do Tech Challenge |

## 8. Definition of Done (do projeto)

- [ ] Todos os requisitos funcionais RF-01..RF-14 implementados e testados.
- [ ] `docker compose up` sobe o ambiente completo e o fluxo ponta a ponta funciona.
- [ ] Collection Postman + environment cobrindo todos os endpoints, incluindo os casos 401/403.
- [ ] README com arquitetura, endpoints, instruções de execução e diagramas.
- [ ] ADRs escritos.
- [ ] Relatório técnico em DOCX.
- [ ] Repositório público no GitHub com histórico GitFlow legível: `main` com três tags, `develop` com os merges `--no-ff` de cada change.
- [ ] `openspec/changes/` vazio e `openspec/archive/` com as 15 changes arquivadas.
- [ ] `openspec/specs/` refletindo as seis capabilities construídas.
