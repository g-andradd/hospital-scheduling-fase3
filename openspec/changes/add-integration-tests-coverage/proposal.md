## Why

RNF-04 (cobertura de linha global ≥ 85% com JaCoCo) e RNF-06 (testes de integração com
Testcontainers reais, sem mock de infraestrutura) são os dois requisitos não funcionais que
`docs/02-especificacao-funcional.md` §2 atribui ao M10. Nenhum dos dois é hoje **garantido pelo
build**:

- o JaCoCo gera relatório por módulo, mas nada falha abaixo de piso algum. O POM pai diz
  literalmente "Gate de cobertura entra no M10";
- nada impede um `verify` verde com suíte omitida, relatório residual, teste pulado ou filtro de
  seleção ativo;
- o exemplar `shared-contracts/src/test/resources/evento-consulta.json` existe desde o M05, mas
  só os testes do próprio `shared-contracts` o leem;
- as varreduras hostis valem o que valem suas tabelas escritas à mão. O `EntradasHostisIT` cobre
  só o REST do agendamento, e a superfície GraphQL tem apenas amostras;
- não existe smoke ponta a ponta, embora `docs/05-fluxo-de-trabalho.md` §4 o exija no fechamento
  da 1.0.0.

Sem revisor automático independente desde o M05, ampliar as coberturas estruturais "deixou de
ser melhoria e passou a ser compensação" (`docs/05`).

## What Changes

- **Fixture canônica compartilhada.**
  - O exemplar existente continua sendo a única cópia física. Passa a ser publicado como
    `test-jar` (classifier `tests`) contendo **apenas** o JSON, e os três serviços dependem dele
    em escopo `test`.
  - O produtor prova compatibilidade pela mensagem recebida do broker real. Cada consumidor
    processa os bytes exatos do exemplar pelo RabbitMQ real.
  - A resolução é provada também em clone limpo com repositório Maven local isolado e vazio.
- **Superfícies hostis derivadas do registro real.**
  - REST do agendamento, inclusive `/auth/login`: o inventário vem dos handlers Spring MVC.
  - GraphQL do histórico: o inventário vem do schema servido. Só `Int` mapeia para inteiro;
    `Float` ou escalar sem dimensão normativa falha fechado.
  - `POST /internal/lembretes/executar` é inventariado e classificado como sem entrada de negócio.
  - Nas duas superfícies, o conjunto executado é comparado ao descoberto, e matrizes e
    proteções existentes são preservadas.
- **Módulo técnico `quality-gates`** — **alteração deliberada da topologia, sujeita à aprovação.**
  Hoje o reactor tem **seis projetos Maven**: o POM raiz agregador e cinco módulos filhos. Depois
  do M10 terá **sete**: a raiz, os cinco módulos de código e o `quality-gates`, que é o **sexto
  módulo filho** e o **sétimo projeto do reactor**. O módulo técnico:
  - não tem aplicação nem regra de negócio;
  - tem exatamente cinco dependências **internas do reactor**, os módulos de código, todas em
    escopo `compile`. Bibliotecas externas dos seus próprios testes ficam só em escopo `test` e
    fora dessa contagem;
  - gera o relatório JaCoCo agregado, em XML e HTML;
  - aplica 85% global e 90% em `agendamento/domain` e `agendamento/application`, por soma de
    linhas;
  - audita as famílias de relatórios `TEST-*.xml`, recusando toda forma de seleção, exclusão,
    omissão ou tolerância suportada pelas versões fixadas do Surefire e do Failsafe, seja por
    parâmetro, seja por configuração efetiva dos POMs;
  - verifica RNF-06 estruturalmente e em runtime. Cada módulo prova a infraestrutura que usa:
    PostgreSQL 16 e RabbitMQ 3.13 nos três serviços, só RabbitMQ no `shared-contracts`, e nenhuma
    prova no `shared-security`, que não tem IT de infraestrutura.

  A evidência de execução corrente vem de uma **sessão de verificação** aberta no POM raiz por uma
  execução ativa em `build/plugins`, não herdada. No mesmo commit do módulo,
  `openspec/config.yaml` passa a listá-lo.
- **Smoke `scripts/smoke-test.sh`.** Roda antes do M12, sem Dockerfile nem Compose final:
  - o preflight vem antes de qualquer recurso;
  - Postgres 16 e RabbitMQ 3.13 efêmeros, pela Docker CLI;
  - os três serviços como processos JVM, com porta dinâmica identificada sem ambiguidade;
  - fluxo login → consulta → notificação (registro persistido e log) → GraphQL, com `jq -e`;
  - saída diferente de zero em qualquer falha;
  - limpeza só do que criou, com identidade de PID conferida e pacote de diagnóstico em falha.
- **Alterações Maven mínimas.**
  - `repackage` com classifier `exec` nos três serviços, preservando o jar principal.
  - Inicialização da sessão de verificação no POM raiz.
- **`.gitattributes`** com LF para `*.sh`.
- **Documentação:** README, `docs/01` §3 e §10, `docs/03` §7, `docs/05` §4 e §6, e um adendo ao
  ADR-003.

## Capabilities

### New Capabilities

Nenhuma.

### Modified Capabilities

- `operacao-do-ambiente`:
  - MODIFIED "Build reprodutível do monorepo";
  - ADDED "Gate agregado de cobertura" (RNF-04);
  - ADDED "Auditoria da execução integral das suítes";
  - ADDED "Testes de integração sobre infraestrutura real" (RNF-06);
  - ADDED "Smoke ponta a ponta do fluxo principal".
- `mensageria-de-eventos`: ADDED "Fixture canônica do contrato compartilhada por produtor e
  consumidores".
- `agendamento-de-consultas`: ADDED "Superfície HTTP resistente a entradas hostis, verificada por
  inventário".
- `historico-de-consultas`: ADDED "Superfície GraphQL resistente a entradas hostis, verificada
  pelo schema".

Ao todo, 8 Requirements e 71 Scenarios:

| Delta | Requirements | Scenarios |
|---|---:|---:|
| `operacao-do-ambiente` | 5 | 47 |
| `mensageria-de-eventos` | 1 | 8 |
| `agendamento-de-consultas` | 1 | 8 |
| `historico-de-consultas` | 1 | 8 |

## Impact

- **Requisitos fechados:** RNF-04 e RNF-06. **Release alvo:** 1.0.0.
- **Reactor:** de seis para sete projetos Maven.
- **POM raiz:**
  - módulo `quality-gates`;
  - versões explícitas de `maven-jar-plugin`, `exec-maven-plugin` e `maven-antrun-plugin`;
  - execução `repackage` (classifier `exec`) na gestão do `spring-boot-maven-plugin`;
  - execução ativa e não herdada da sessão, em `build/plugins`.
- **POMs de módulo:** o `shared-contracts` ganha `test-jar`; os três serviços, a dependência
  `test-jar` em escopo `test`.
- **Testes:**
  - fixture no `shared-contracts` e nos três serviços;
  - `EntradasHostisIT` reestruturado, sem perda de casos;
  - varredura GraphQL nova;
  - inventário do endpoint interno;
  - evidência runtime de infraestrutura nos quatro módulos que a usam;
  - testes próprios dos verificadores.
- **Scripts e configuração:** `scripts/smoke-test.sh`, `.gitattributes` e, junto com o módulo, o
  bloco MÓDULOS de `openspec/config.yaml`.

**O que NÃO muda:**

- **Contrato e mensageria:** envelope, payload, topologia, routing keys, outbox, entrega externa
  at-least-once e idempotência dos consumidores.
- **Negócio e autorização:** regras de negócio, endpoints, schema GraphQL, a matriz normativa de
  `docs/02` §3 e suas contagens, e todas as proteções estruturais existentes.
- **Banco e serviços:** migrations, seed `V900`, `application.yml` dos serviços,
  `docker-compose.yml` e `init.sql`.
- **Versionamento:** CHANGELOG e versão ficam para o fechamento da release.

Nada do M11 ou do M12 é antecipado: nem Dockerfiles, Compose final, Mailpit, seed de consultas,
Actuator ou health; nem `correlationId` em logs ou ArchUnit.

**Riscos principais**, com mitigação em `design.md`:

- o sétimo projeto do reactor contraria o texto promovido até a aprovação;
- a atribuição Surefire de testes aninhados e herdados;
- a lista de filtros depende da versão fixada dos plugins;
- a análise estrutural por árvore sintática;
- o tempo das varreduras;
- a portabilidade do smoke entre Git Bash e Linux.

**Critérios de aceite:**

- os 71 Scenarios com evidência;
- `mvn -q clean verify` verde na raiz, sem filtro, com pisos, auditoria e guarda de infraestrutura
  aceitos;
- smoke verde duas vezes seguidas, sem recurso órfão;
- as 12 mutações registradas, M1–M7 de arquivo e M8–M12 de injeção;
- clone limpo com repositório Maven isolado;
- archive na própria feature branch antes do merge.
