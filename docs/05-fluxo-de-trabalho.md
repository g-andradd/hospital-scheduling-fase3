# Fluxo de Trabalho — OpenSpec + GitFlow

Dois processos rodam encaixados. O **OpenSpec** governa *o que* é construído e quando a spec vira verdade. O **GitFlow** governa *onde* o código vive e quando vira release.

---

## 1. Os papéis

| Papel | Quem | O que faz |
|---|---|---|
| Product Owner / Revisor | Gabriel | Aprova proposta antes do código, revisa PR, aprova release. **Executa todas as operações de escrita no Git** — commit, push, branch, merge, tag e abertura de PR |
| Gestor / Engenheiro de Prompt | Claude (chat) | Mantém o roadmap e o `openspec/config.yaml`, escreve o enunciado de cada `/opsx:propose`, audita a entrega contra os critérios de aceite |
| Engenheiro de Software | Claude Code | Roda `/opsx:propose`, `/opsx:apply` e `/opsx:archive`, implementa e testa. **Não executa Git** — entrega os comandos prontos ao final de cada etapa |

Duas regras que não mudam:

**Nenhuma linha de código antes de uma proposta aprovada.** O OpenSpec só torna isso mecânico — a proposta agora é um artefato versionado, não uma mensagem de chat.

**O Git é do Gabriel.** O Claude Code edita arquivos, roda build e testes, e marca as tasks. Ele não executa `commit`, `push`, `branch`, `merge`, `tag` nem abre PR. Ao concluir uma etapa, entrega o bloco de comandos pronto, com as mensagens de commit já redigidas — o Gabriel revisa o diff e executa. Isso mantém o histórico sob controle humano e força uma leitura consciente de cada mudança antes dela entrar na árvore.

---

> **"Claude Code" neste documento significa o papel, não o produto.** A ferramenta que exerce o papel de engenheiro de software mudou: os changes M00 a M04 foram implementados pelo Claude Code; **a partir do M05 o papel é do Codex**. Onde se lê o nome antigo, leia "a ferramenta que implementa".
>
> Requisito para qualquer ferramenta nesse papel: os comandos do OpenSpec precisam estar instalados para ela no repositório. Trocar de ferramenta exige rodar `openspec init` de novo e selecioná-la — sem isso o ciclo `propose → apply → archive` simplesmente não existe do lado dela.
>
> **Consequência da troca, registrada de propósito.** O Codex vinha atuando como revisor automático dos PRs e foi ele quem encontrou os defeitos que a revisão humana e a da própria ferramenta implementadora deixaram passar — mutação antes de validação, double-booking sob concorrência, e uma família inteira de entradas não validadas virando 500. Com o Codex implementando e sem revisor independente no lugar dele, essa camada deixou de existir. Restam a auditoria do gestor e as três coberturas estruturais do projeto: `CoberturaDeAutorizacaoTest`, `CoberturaDoMapaDeErrosTest` e `EntradasHostisIT`. Ampliá-las no M10 deixou de ser melhoria e passou a ser compensação.

## 2. O ciclo de uma change

Na coluna da direita, **tudo é executado pelo Gabriel**. O Claude Code atua apenas nas duas colunas da esquerda e entrega os comandos.

```
   roadmap                 OpenSpec                    Git (Gabriel)
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
                       /opsx:archive                   commit: "docs(openspec): arquiva <id>"
                       move p/ changes/archive/        push na feature branch
                       promove deltas p/ specs/
                            │
                            ▼
                                                    merge --no-ff em develop
                                                    apaga a branch
```

**Quando arquivar:** na própria feature branch, **depois da aprovação do PR e antes do merge**. Assim a promoção da spec e o código que a implementa entram em `develop` no mesmo merge — `openspec/specs/` nunca fica descrevendo algo que não está integrado, nem o inverso. Como bônus, o revisor vê a mudança de spec no mesmo diff do código.

Arquivar **antes** da aprovação faz a spec mentir se o PR for rejeitado ou retrabalhado. Arquivar **depois** do merge obriga a uma branch e um PR só para isso, ou a um commit direto em `develop`, que a proteção de branch bloqueia.

> **Onde o archive coloca a change:** `openspec/changes/archive/<change-id>/`, não `openspec/archive/`. O diretório é criado pela própria skill na primeira vez.

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

Sempre `--no-ff`. O commit de merge é o que torna o histórico legível para a banca — dá para ver cada milestone como um bloco. Executado pelo Gabriel, como todo o resto do Git.

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
#    - conferir que openspec/changes/ nao tem change ativa e changes/archive/ tem as da release

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

**Sem rodapé de coautoria.** As mensagens não levam `Co-Authored-By`, `Generated with` nem qualquer assinatura de agente. Quem revisa e assina o commit é o Gabriel, e o histórico reflete isso. A metodologia assistida por IA está declarada no README, no charter e no relatório técnico — que é onde essa informação pertence, e não repetida em cada linha do `git log`.
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
2. Todo `#### Scenario:` da spec delta tem evidência. Para capabilities de **comportamento**, a evidência é um teste automatizado. Para Scenarios de **build e infraestrutura**, um comando executado com a saída registrada no corpo do PR é aceito — o `scripts/smoke-test.sh` do M10 absorve esses comandos depois. Não existe comando de verificação nesta versão do OpenSpec: a conferência é manual, na revisão do PR
3. `mvn -q clean verify` passa na raiz, sem teste ignorado
4. Cobertura do módulo tocado ≥ 80%, **a partir do M01** — o M00 não entrega comportamento e não tem código elegível; global ≥ 85% com gate de build a partir do M10
5. ArchUnit verde no `agendamento-service`
6. Documentação afetada atualizada no mesmo PR (README, ADR, Postman)
7. `/opsx:archive` executado na feature branch após a aprovação do PR, **e o commit do archive empurrado antes do merge**. O diff do PR tem de mostrar `openspec/changes/<id>/` movida para `changes/archive/` e a spec promovida em `openspec/specs/<capability>/`
8. PR mergeado em `develop` com `--no-ff` pelo Gabriel

> **Confira o archive no diff do PR antes de mergear.** Rodar `/opsx:archive` não basta: ele mexe só no working tree, e o commit é um passo separado. Se algo interromper a sequência entre o archive e o merge — uma revisão automática apontando defeito, um ciclo de correção —, o commit do archive cai fora do fluxo e o merge leva o código sem a spec. Aconteceu no M01: o PR foi mergeado com a change ainda ativa em `changes/`, e `agendamento-de-consultas` não chegou a `openspec/specs/`. O sintoma, depois do merge, é `openspec list` ainda mostrar uma change ativa.

> **Changes que mexem em build ou infraestrutura verificam a partir de um clone limpo, não da árvore de trabalho.** Diretório vazio não é versionado, artefato gerado é ignorado e configuração local não viaja — uma verificação feita só no working tree pode passar e o clone do avaliador falhar. Antes de aprovar o PR: `git clone -b <branch> <url> <tmp> && cd <tmp> && mvn clean verify`.

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
