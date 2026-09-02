# Bootstrap do projeto

Passo único, antes do M00. Executar na raiz do repositório clonado.

> **Nota sobre a versão do OpenSpec.** O OpenSpec migrou para *agent skills*: em vez de gerar um `openspec/AGENTS.md` e comandos em `.claude/commands/openspec/`, ele instala skills em `.claude/skills/` e concentra o contexto do projeto em `openspec/config.yaml`. Este repositório já está no formato novo — o antigo `openspec/project.md` não existe mais, e seu conteúdo foi dividido entre o `context:` do `config.yaml` e a seção 10 de `docs/01-arquitetura.md`.

## 1. Commitar antes de inicializar

O `openspec init` mexe na estrutura. Commitar primeiro garante que qualquer sobrescrita seja revertível com um `git checkout`.

```bash
git add .
git commit -m "docs: adiciona documentação do projeto, estrutura OpenSpec e fluxo GitFlow"
```

## 2. Instalar e inicializar o OpenSpec

```bash
npm install -g @fission-ai/openspec@latest
openspec init
```

**Responda `Yes` a "Upgrade and clean up legacy files?".**

Essa pergunta não é sobre sobrescrever nada seu. Ela remove os artefatos do formato antigo — diretórios de comandos legados e `openspec/AGENTS.md` — que neste repositório sequer existem. O que ela **preserva**: `openspec/changes/`, `openspec/specs/` e `openspec/archive/`.

Escolha **Claude Code** quando perguntar quais assistentes configurar.

### Depois do init, confira duas coisas

```bash
ls .claude/skills/          # devem existir as skills do OpenSpec
head -20 openspec/config.yaml
```

O `config.yaml` deste repositório já vem preenchido com o contexto e as regras do projeto. Se o `init` o tiver substituído por um template genérico, restaure:

```bash
git checkout openspec/config.yaml
```

E confirme o que o CLI oferece nesta versão:

```bash
openspec --help
```

## 3. Criar a estrutura GitFlow

```bash
git add .
git commit -m "chore(openspec): inicializa OpenSpec com agent skills"

git branch -M main
git push -u origin main

git checkout -b develop
git push -u origin develop
```

A partir daqui, `main` só recebe release. Todo trabalho sai de `develop`.

### Recomendado no GitHub

**Settings → General → Default branch**: definir `develop`. Os PRs passam a abrir apontando para o lugar certo sozinhos.

**Settings → Branches**: proteger `main` e `develop` — exigir PR antes do merge e bloquear force-push. Não é obrigatório para a entrega, mas evita um `push --force` acidental no meio do projeto.

## 4. Iniciar o M00

```bash
git checkout develop
git checkout -b feature/m00-bootstrap-monorepo
```

Abra o Claude Code na pasta do repositório e cole o **prompt de abertura** abaixo. A proposta do M00 já está escrita em `openspec/changes/bootstrap-monorepo/` — o Claude Code vai direto para a aplicação.

---

## Prompt de abertura da primeira sessão do Claude Code

```
Você é o engenheiro de software deste projeto. Antes de escrever qualquer código,
leia na íntegra:

- openspec/config.yaml
- docs/00-project-charter.md
- docs/01-arquitetura.md
- docs/02-especificacao-funcional.md
- docs/03-contrato-de-eventos.md
- docs/04-roadmap.md
- docs/05-fluxo-de-trabalho.md

Regras de trabalho:

1. Trabalhamos com OpenSpec. Uma change por vez, uma feature branch por change,
   com o change-id definido em docs/04-roadmap.md. Não adiante escopo de changes
   futuras.
2. O ciclo é: /opsx:propose → EU REVISO E APROVO → /opsx:apply → PR para develop
   → merge → /opsx:archive. Você nunca pula a etapa de revisão.
3. Se a spec não cobrir uma decisão que você precisa tomar, PARE e pergunte.
   Não improvise nem invente requisito.
4. GitFlow completo, conforme docs/05-fluxo-de-trabalho.md. Merges com --no-ff.
   Conventional Commits em português.
5. Uma change só é arquivada quando atende a Definition of Done da seção 6 de
   docs/05-fluxo-de-trabalho.md.
6. Ao terminar uma change, me apresente: o que foi feito, os critérios de aceite
   atendidos um a um, e o que ficou pendente ou em dúvida.

Comece confirmando que leu os sete documentos: resuma em 10 linhas o que este
sistema faz, qual é a arquitetura e como o fluxo de trabalho funciona. Em seguida,
liste quais skills do OpenSpec estão disponíveis nesta instalação e qual é a
próxima change segundo o roadmap.
```

## Depois do M00

Para cada change seguinte:

```bash
git checkout develop && git pull
git checkout -b feature/m01-add-agendamento-domain
```

E no Claude Code, o bloco **"Enunciado da proposta"** do change correspondente em `docs/04-roadmap.md`.

## Referência rápida das skills OpenSpec

Conjunto instalado neste repositório (confirmado em `.claude/commands/opsx/`):

| Comando | Uso |
|---|---|
| `/opsx:explore` | Pensar antes de se comprometer com um plano |
| `/opsx:propose` | Criar a proposta da change (proposal, design, tasks, spec delta) |
| `/opsx:update` | Revisar uma proposta já criada, quando o design não fecha na implementação |
| `/opsx:apply` | Implementar a checklist de tasks |
| `/opsx:archive` | Arquivar a change depois do merge em `develop` |
| `/opsx:sync` | Sincronizar `openspec/specs/` com as changes arquivadas |

Não existem `/opsx:verify`, `/opsx:new`, `/opsx:continue` nem `/opsx:ff` nesta versão.
