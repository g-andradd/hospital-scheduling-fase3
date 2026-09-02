# ADR-003 — Monorepo Maven multi-módulo

## Contexto

A decisão de ter três serviços (ADR-002) mais dois módulos compartilhados levanta imediatamente
a pergunta de como organizá-los no controle de versão: cinco repositórios independentes, como se
faria numa organização com times separados, ou um repositório único com módulos Maven.

Dois fatores do projeto pesam mais que a prática de mercado aqui.

O primeiro é o **contrato de eventos**. Produtor e dois consumidores precisam concordar
literalmente sobre o envelope, os tipos de evento e a topologia descritos em
`docs/03-contrato-de-eventos.md`. Em multi-repo, essa concordância exige publicar um artefato
versionado num registry e coordenar bumps de versão em três repositórios a cada mudança de
contrato — num projeto que ainda está descobrindo o contrato.

O segundo é o **modo de avaliação**. A banca clona o repositório e roda o projeto. Cada
repositório extra é um passo a mais entre o avaliador e o sistema funcionando.

## Decisão

Um repositório, um POM pai agregador e cinco módulos:

```
hospital-scheduling-fase3/
├── pom.xml                 # packaging pom — dependencyManagement, plugins, ${revision}
├── shared-contracts/       # envelope de eventos, payloads, topologia
├── shared-security/        # filtro JWT, JwtService, config base
├── agendamento-service/
├── notificacao-service/
└── historico-service/
```

Três mecanismos sustentam a decisão:

**`dependencyManagement` no POM pai**, importando o BOM `spring-boot-dependencies`. Os módulos
declaram dependências sem versão. É impossível dois serviços divergirem na versão do Spring
Boot, do Jackson ou do driver do Postgres.

**Versão única via `${revision}`.** O POM pai declara `<revision>0.1.0-SNAPSHOT</revision>` e
todos os módulos usam `<version>${revision}</version>`. Fechar uma release é alterar uma linha.
Isso exige o `flatten-maven-plugin` com `flattenMode=resolveCiFriendliesOnly`: sem ele, o POM
instalado carrega o placeholder literal e quebra qualquer consumidor do artefato.

**Fases de teste separadas por convenção de nome.** `surefire` roda `*Test.java` — unitários,
sem container. `failsafe` roda `*IT.java` na fase `verify` — integração com Testcontainers.
`mvn test` fica rápido para o ciclo curto; `mvn verify` roda tudo antes do PR.

## Alternativas consideradas

**Cinco repositórios independentes.**
Descartada. É a organização correta quando times diferentes têm ciclos de release diferentes —
não é o caso aqui. O custo seria: publicar `shared-contracts` num registry, versionar o contrato
de eventos externamente, abrir três PRs coordenados a cada evolução do envelope, e entregar à
banca cinco URLs em vez de uma. Todo o benefício do multi-repo é autonomia de deploy, que este
projeto não exerce — os cinco artefatos sobem juntos num único `docker compose up`.

**Monorepo sem módulos Maven, com cada serviço como projeto Maven solto.**
Descartada. Resolve o problema do clone único, mas não o da consistência de versões: cada
serviço voltaria a declarar suas próprias versões de dependência, e o `shared-contracts` teria
de ser instalado no repositório local antes de os serviços compilarem. Perde-se o build de um
comando na raiz.

**Gradle com composite build.**
Descartada. Tecnicamente equivalente para este fim, mas o curso e o restante da avaliação são
Maven. Trocar a ferramenta de build adicionaria risco sem adicionar argumento técnico.

**BOM próprio em módulo separado, em vez de `dependencyManagement` no POM pai.**
Descartada. Um BOM se paga quando módulos são publicados e consumidos por projetos externos.
Com cinco módulos no mesmo reactor, seria uma indireção sem consumidor.

## Consequências

**Positivas**
- `git clone` + `mvn clean verify` + `docker compose up -d` — três comandos entre o avaliador e o sistema.
- O contrato de eventos existe em um lugar só. Produtor e consumidores compilam contra a mesma classe; divergência vira erro de compilação, não bug de produção.
- Versões de dependência impossíveis de divergir entre serviços.
- Uma alteração que atravessa serviços — evoluir o envelope de eventos, por exemplo — cabe num único PR atômico, com o build inteiro validando a mudança.
- Histórico Git legível como uma narrativa só, o que importa para a avaliação do GitFlow.

**Negativas, aceitas**
- Acoplamento de release: os cinco módulos compartilham uma versão. Não há como publicar `shared-contracts` 1.2.0 mantendo os serviços em 1.1.0. Irrelevante aqui, onde tudo sobe junto.
- O build da raiz compila tudo, mesmo quando a mudança tocou um módulo só. Com cinco módulos pequenos, é questão de segundos.
- O `flatten-maven-plugin` é obrigatório e gera um `.flattened-pom.xml` por módulo — arquivo transitório que precisa estar no `.gitignore` para não sujar o repositório.
- Em uma organização real com times independentes, esta decisão seria a errada. Ela é correta para *este* projeto, e o critério que a sustenta — um time, um ciclo de release, um ambiente de entrega — deve ser reavaliado se algum desses deixar de valer.

## Status

Aceita. Materializada em `bootstrap-monorepo` (M00), que cria o POM pai e os cinco módulos.
