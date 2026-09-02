# Fluxo de Trabalho — OpenSpec + GitFlow

Dois processos rodam encaixados. O **OpenSpec** governa *o que* é construído e quando a spec vira verdade. O **GitFlow** governa *onde* o código vive e quando vira release.

---

## 1. Os papéis

| Papel | Quem | O que faz |
|---|---|---|
| Product Owner / Revisor | Gabriel | Aprova proposta antes do código, revisa PR, aprova release |
| Gestor / Engenheiro de Prompt | Claude (chat) | Mantém o roadmap e o `openspec/config.yaml`, escreve o enunciado de cada `/opsx:propose`, audita a entrega contra os critérios de aceite |
| Engenheiro de Software | Claude Code | Roda `/opsx:propose`, `/opsx:apply` e `/opsx:archive`, implementa, testa, abre PR |

A regra que não muda: **nenhuma linha de código antes de uma proposta aprovada.** O OpenSpec só torna isso mecânico — a proposta agora é um artefato versionado, não uma mensagem de chat.

---

## 2. O ciclo de uma change

```
   roadmap                 OpenSpec                       Git
─────────────────────────────────────────────────────────────────────────
                                                    git checkout develop
   escolhe o próximo  ──►                           git pull
   change-id                                        git checkout -b feature/m04-add-autenticacao-jwt
                            │
                            ▼
                       /opsx:propose
                       cria changes/<id>/
                         proposal.md
                         design.md
                         tasks.md
                         specs/<capability>/spec.md
                            │
                            ▼
                    ◄── GABRIEL REVISA ──►     commit: "docs(openspec): propõe <id>"
                       aprova ou ajusta
                            │
                            ▼
                       /opsx:apply
                       implementa a checklist          commits incrementais
                       marca [x] em tasks.md
                            │
                            ▼
                       openspec validate
                       mvn verify                      push, abre PR → develop
                            │
                            ▼
                    ◄── GABRIEL APROVA O PR ──►
                            │
                            ▼
                       /opsx:archive                   merge --no-ff em develop
                       move para archive/              apaga a branch
                       promove deltas p/ specs/        commit: "docs(openspec): arquiva <id>"
```

**Ponto importante:** o `/opsx:archive` acontece **depois** do merge em `develop`, nunca antes. `openspec/specs/` representa o que está construído e integrado. Arquivar uma change cujo PR ainda não foi mergeado faz a spec mentir.

---

## 3. GitFlow

### Branches permanentes

| Branch | Papel | Regra |
|---|---|---|
| `main` | Somente releases | Só recebe merge de `release/*` ou `hotfix/*`. Todo commit em `main` tem tag. |
| `develop` | Integração | Recebe merge de `feature/*`, `release/*` (back-merge) e `hotfix/*` (back-merge) |

### Branches temporárias

| Prefixo | Sai de | Volta para | Nomeação |
|---|---|---|---|
| `feature/` | `develop` | `develop` | `feature/m04-add-autenticacao-jwt` — número do milestone + change-id do OpenSpec |
| `release/` | `develop` | `main` **e** `develop` | `release/0.1.0` |
| `hotfix/` | `main` | `main` **e** `develop` | `hotfix/0.1.1` |

Uma feature branch = uma change do OpenSpec. Sempre. Se durante a implementação aparecer escopo que não está na proposta, isso é **outra change**, não um commit a mais nesta branch.

### Merges

Sempre `--no-ff`. O commit de merge é o que torna o histórico legível para a banca — dá para ver cada milestone como um bloco.

```bash
git checkout develop
git merge --no-ff feature/m04-add-autenticacao-jwt
git branch -d feature/m04-add-autenticacao-jwt
```

---

## 4. Plano de releases

Três releases. Cada uma é uma fatia demonstrável, não um marco burocrático.

| Versão | Fecha após | Entrega | Requisitos fechados |
|---|---|---|---|
| **0.1.0** | `add-autenticacao-jwt` (M04) | Agendamento seguro ponta a ponta: domínio, persistência, REST e autenticação com os três perfis | RF-01 a RF-10 |
| **0.2.0** | `add-historico-graphql` (M09) | Arquitetura completa: eventos, notificações, lembrete D-1, histórico e GraphQL | RF-11 a RF-20 |
| **1.0.0** | `finalize-audit-report` (M14) | Entrega do Tech Challenge: qualidade, ambiente, Postman, documentação e relatório | RNF-01 a RNF-10 |

### Como fechar uma release

```bash
# 1. abrir
git checkout develop
git checkout -b release/0.1.0

# 2. estabilizar — só correção, versão e documentação. Nenhuma funcionalidade nova.
#    - subir <revision> no POM pai para 0.1.0
#    - atualizar CHANGELOG.md
#    - rodar mvn verify e o smoke test
#    - conferir que openspec/changes/ está vazio e archive/ tem as changes da release

git commit -am "chore(release): prepara versão 0.1.0"

# 3. fechar em main
git checkout main
git merge --no-ff release/0.1.0
git tag -a v0.1.0 -m "Release 0.1.0 — agendamento seguro"
git push origin main --tags

# 4. devolver para develop
git checkout develop
git merge --no-ff release/0.1.0
#    subir <revision> para 0.2.0-SNAPSHOT
git commit -am "chore: inicia ciclo 0.2.0-SNAPSHOT"
git push origin develop

git branch -d release/0.1.0
```

**Regra da branch de release:** entra correção de bug, ajuste de versão e documentação. Não entra funcionalidade. Se aparecer funcionalidade faltando, ela vira uma feature em `develop` e entra na próxima release.

---

## 5. Convenção de commits

Conventional Commits em português.

```
<tipo>(<escopo>): <descrição no imperativo, minúscula, sem ponto final>
```

| Tipo | Uso |
|---|---|
| `feat` | Nova funcionalidade |
| `fix` | Correção de bug |
| `refactor` | Mudança interna sem alterar comportamento |
| `test` | Adição ou ajuste de teste |
| `docs` | Documentação, incluindo artefatos do OpenSpec |
| `chore` | Build, dependências, versão, configuração |
| `perf` | Melhoria de desempenho |

Escopos: `agendamento`, `notificacao`, `historico`, `contracts`, `security`, `infra`, `docs`, `openspec`.

Exemplos reais deste projeto:

```
feat(agendamento): adiciona caso de uso de agendar consulta
feat(security): implementa filtro de autenticação JWT
fix(notificacao): evita reenvio de lembrete já registrado
test(historico): cobre filtro de período FUTURAS no GraphQL
docs(openspec): propõe add-event-publishing-outbox
chore(release): prepara versão 0.1.0
```

---

## 6. Definition of Done de uma change

Uma change só é arquivada quando:

1. Todas as tasks de `tasks.md` estão marcadas `[x]`
2. Todos os `#### Scenario:` da spec delta têm teste automatizado correspondente — a conferência é manual, na revisão do PR; não existe comando de verificação nesta versão do OpenSpec
3. `mvn -q clean verify` passa na raiz, sem teste ignorado
4. Cobertura do módulo tocado ≥ 80%; global ≥ 85% a partir do M10
6. ArchUnit verde no `agendamento-service`
7. Documentação afetada atualizada no mesmo PR (README, ADR, Postman)
8. PR aprovado pelo Gabriel e mergeado em `develop` com `--no-ff`
9. `/opsx:archive` executado e commitado

---

## 7. Quando algo dá errado

**A proposta foi aprovada mas na implementação percebeu-se que o design não fecha.**
Pare. Rode `/opsx:update` para revisar a proposta — ele atualiza `design.md` e o delta de spec de forma consistente. Peça reaprovação. Não implemente diferente do que está escrito — a spec desatualizada é pior do que spec nenhuma, porque dá falsa confiança.

**Apareceu escopo novo no meio da feature.**
Se é pequeno e claramente parte da mesma capability, adicione uma task e registre no `proposal.md`. Se muda o objetivo, é outra change.

**Uma change ficou grande demais.**
Divida. Duas changes de escopo claro valem mais que uma de escopo difuso. Ajuste o roadmap e avise o Gabriel.

**Bug encontrado numa capability já arquivada.**
Nova change, tipo correção, com delta `## MODIFIED Requirements` na capability afetada. Se estiver em produção — no nosso caso, já tagueado em `main` — vira `hotfix/`.
