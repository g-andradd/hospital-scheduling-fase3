## Context

Motivação e escopo estão em `proposal.md`, e o comportamento, nas quatro deltas em `specs/`. Aqui
fica apenas o estado atual que condiciona o desenho.

**Build**

- Hoje o reactor tem **seis projetos Maven**: o POM raiz agregador (`packaging pom`) e cinco
  módulos filhos.
- O Surefire e o Failsafe estão fixados em 3.2.5 no POM raiz. O surefire roda `**/*Test.java` e o
  failsafe, `**/*IT.java`, sem `<excludes>`.
- O JaCoCo roda `prepare-agent` e `report` por módulo, excluindo `**/*Application.class`. A
  propriedade `argLine` é composta pelo surefire e herdada pelo failsafe, e o `jacoco.exec`
  acumula as duas fases com `append`.
- Não existe gate.
- O `spring-boot-maven-plugin` está declarado sem execução. Sem o starter-parent, o `package` gera
  jars de biblioteca, não executáveis.
- O `flatten-maven-plugin` gera `.flattened-pom.xml` em todos os projetos, inclusive na raiz.
- Não há CI em `.github` nem `.gitattributes`.

**Relatórios reais observados** — a auditoria precisa aceitá-los.

| Suíte | Relatório externo | Onde estão os casos |
|---|---|---|
| `ConsultaRepositoryAdapterIT` (failsafe) e `ConsultaRepositoryFakeTest` (surefire) | `tests="0"` | `TEST-…ConsultaRepositoryContractTest$Listagem.xml`, `$DeteccaoDeConflito` e `$GravacaoERecuperacao`, com o nome da classe **declarante** do `@Nested`, no diretório do plugin de cada subclasse |
| `GraphiqlPorProfileIT` | `tests="0"` | `$ProfileDemo`, `$ProfileDev`, `$ProfilePadrao` |
| `JwtServiceTest` | `tests="0"` | `$Configuracao`, `$Recusa`, `$Emissao` |

O `failsafe-reports` contém também o `failsafe-summary.xml`, que é resumo do plugin e não
relatório de suíte. Há `@Nested` em dez arquivos de teste. `ConsultaRepositoryContractTest` é
abstrata e termina em `Test`. As bases `*ITBase` e `M05*Base` são abstratas e sem o sufixo. Não há
`@Disabled`, `@Ignore` nem assumptions.

**Dublês existentes, todos legítimos**

- `@MockBean MailSender` em `SelecaoDeSenderIT`: porta externa.
- Decoradores `@MockitoBean` em `ConsultaControllerTest`: fatia web, unitário.
- `Mockito.mock(ServicoDeLembretes)` em dois `*Test`.
- Barreiras e decoradores de porta de domínio em `M05JpaBase`.
- O `BeanPostProcessor` que embrulha a `DataSource` real em `NotificacaoITBase`.

**Uso de infraestrutura nos ITs**

| Módulo | Infraestrutura usada | Base |
|---|---|---|
| `agendamento-service` | PostgreSQL e RabbitMQ | `ContainerPostgres` e `M05RabbitBase` |
| `notificacao-service` | PostgreSQL e RabbitMQ | `ContainersDeNotificacao` |
| `historico-service` | PostgreSQL e RabbitMQ | `ContainersDoHistorico` |
| `shared-contracts` | só RabbitMQ | `RabbitITBase` |
| `shared-security` | nenhuma | só testes unitários |

**Fixture**

- `evento-consulta.json` existe desde o M05 e é lido só pelo `EntradasAmqp` do `shared-contracts`.
- Os serviços montam envelopes por construtores nas próprias bases.
- `M05RabbitBase` tem Postgres, RabbitMQ e relay reais, com relógio fixo.
- O converter recusa `content-type`, headers `x-event-*` e routing key divergentes.

**Superfícies**

- **REST do agendamento:** seis handlers de consulta e `POST /auth/login`, além de springdoc e
  `/error`.
- **Notificação:** um único handler, `POST /internal/lembretes/executar`, sem parâmetro de negócio.
- **GraphQL do histórico:** quatro queries e uma mutation, sobre `FiltroConsulta`,
  `CorrigirRegistroHistoricoInput`, `ID`, `String`, dois enums e o escalar `DateTime`. Não há
  `Int`, `Float` nem `Boolean`.
- O `GraphqlITBase` já sobe a aplicação real, com o listener parado e POST cru.
- As varreduras atuais: `EntradasHostisIT` (REST) e `EntradasHostisHistoricoIT` (AMQP). O GraphQL
  tem só amostras em `ErrosGraphqlIT`.

**Notificação e ambiente do smoke**

- O `LogNotificationSender` registra em INFO
  `Notificacao para {destinatario}: {assunto} | {corpo}`, e o conteúdo persistido é o corpo
  entregue.
- O `notificacao-service` não sobe sem `JWT_SECRET`.
- O seed `V900` (`demo`) tem quatro usuários, um médico e dois pacientes, sem consultas.

**Débitos do M03**, consultados somente leitura pela revisão, nos PRs 7 a 11:

| PR | Thread | Achado |
|---|---|---|
| 7 | `discussion_r3928689668` | exceções HTTP malformadas |
| 8 | `discussion_r3929008553` | cancelamento sem motivo |
| 8 | `discussion_r3929008559` | sanitização de conversão |
| 9 | `discussion_r3929248930` | `registradoPorId` inexistente e FK |
| 9 | `discussion_r3929248934` | overflow de offset da paginação |
| 10 | `discussion_r3929432417` | extremos de `OffsetDateTime` |
| 11 | `discussion_r3929719540` | OpenAPI 201 e `Location` |

### Hierarquia das fontes

| Camada | O que determina aqui |
|---|---|
| **1. Enunciado (PDF)** | Autenticação e níveis de acesso dos três perfis; agendamento e alteração de consultas; histórico via GraphQL; separação em serviços; mensageria agendamento→notificação; processamento e envio do lembrete; documentação, execução e endpoints funcionais. **O PDF não exige** percentual de cobertura, JaCoCo, test-jar, Mailpit, Dockerfiles de aplicação nem Compose final. |
| **2. Regras promovidas** | JWT stateless e matrizes de autorização; contrato canônico de eventos; transactional outbox; entrega externa at-least-once; idempotência dos consumidores; Testcontainers; proteções estruturais existentes. Nenhuma é alterada. A única delta MODIFIED é a da topologia do reactor (D3). |
| **3. Específico do M10** (roadmap e enunciado desta change) | Pisos JaCoCo de 85% e 90%; fixture compartilhada; smoke em shell com `jq`; inventários das varreduras hostis — o roadmap declara capability "todas"; auditoria contra falso verde; provas de sensibilidade. RNF-04 e RNF-06 vêm de `docs/02` §2. |

## Goals / Non-Goals

**Goals:**

- Gates que falham fechado: sem evidência da sessão corrente, sem aprovação.
- Recusa exaustiva dos mecanismos de seleção e omissão das versões fixadas dos plugins.
- Fixture única, exercitada pelos três serviços e provada com repositório Maven isolado.
- Varreduras hostis cujo conjunto exigido deriva do registro real e é comparado ao executado.
- Smoke reprodutível, que remove seu runtime e encerra só seus processos.
- Negativos automatizados nos verificadores, mais 12 mutações com causa única.

**Non-Goals:**

- Nada de M11 (ArchUnit, `correlationId` em logs, Actuator, health, logs JSON), M12
  (Dockerfiles, Compose final, Mailpit, seed de consultas, Makefile), M13 ou M14.
- Garantia exactly-once, retry automático e mudança de contrato ou de schema.
- Testes sem comportamento para inflar cobertura, ou exclusão de código da medição.
- Afirmar execução por método a partir da soma de casos (D5).

## Decisions

### D1. Fixture publicada por `test-jar`, com uma única cópia física

**Mecanismo Maven**

- O `shared-contracts` declara `maven-jar-plugin`, com versão explícita no POM pai, e uma
  execução `test-jar` com `<includes><include>evento-consulta.json</include></includes>`.
- O artefato contém só o JSON e `META-INF`: nenhuma `.class`, nenhum helper.
- Os três serviços declaram:

```xml
<dependency>
  <groupId>br.com.fiap.hospital</groupId>
  <artifactId>shared-contracts</artifactId>
  <version>${project.version}</version>
  <type>test-jar</type>
  <classifier>tests</classifier>
  <scope>test</scope>
</dependency>
```

**Consequências assumidas**

- As dependências `test` do produtor não são transitivas pelo test-jar, então os serviços mantêm
  as próprias.
- Em `test`, o Maven 3.9 resolve o test-jar para `shared-contracts/target/test-classes`, o que foi
  corrigido em MNG-3043. Em `package` e `verify`, resolve para o jar anexado.
- A prova é feita em **clone limpo com `maven.repo.local` temporário e vazio**, sem `install`,
  exercitando `test`, `package` e `verify` (D14).

**Guardas estruturais**

| Guarda | Onde | O que prova |
|---|---|---|
| G1 | `shared-contracts`, unitário | Uma única cópia física no repositório, por nome e por SHA-256. `EventoJson.ler` aceita o exemplar como `CONSULTA_CRIADA` versão 1. |
| G2 | cada serviço, unitário | Exatamente um recurso no classpath, originado do `shared-contracts`, nunca do próprio módulo; SHA-256 igual ao do canônico; origem registrada. |
| G3 | cada serviço, no IT comportamental | Origem em URL `jar:` do `-tests.jar`, sem `.class`. |
| G4 | cada serviço, unitário | Nenhuma referência a tipo de `shared-contracts/src/test/java`. |

**Conteúdo.** Mantido. Uma divergência legítima com o produtor é discussão de contrato: o apply
para e pede `/opsx:update`.

### D2. Usos comportamentais: produtor pelo broker real, consumidores com os bytes exatos

**Produtor** — IT sobre `M05RabbitBase`.

1. Semeia usuário, paciente e médico com os identificadores e valores do exemplar.
2. Agenda pelo caso de uso real.
3. Aciona o relay e recebe a mensagem de `notificacao.consultas`.
4. Compara as árvores JSON. O conjunto de chaves deve ser idêntico em todos os níveis, com o mesmo
   tipo de nó e o mesmo valor, exceto nos caminhos normalizados.

| Caminho normalizado | Validação obrigatória no lugar da igualdade |
|---|---|
| `eventId` | UUID canônico; igual a `x-event-id` |
| `aggregateId` | UUID canônico; igual a `payload.consultaId` |
| `payload.consultaId` | igual ao id devolvido pelo caso de uso |
| `occurredAt` | ISO-8601 com `Z`; igual ao `Clock` |
| `correlationId` | não vazio; igual a `x-correlation-id` |

Também são exigidos `x-event-type`, `content-type` JSON, routing key `consulta.criada` e ausência
de `alteracoes`.

**Consumidores** — um IT por serviço.

- Publicam os bytes do recurso, após a G3, com headers extraídos do exemplar.
- A espera termina por marca de processamento **ou** por DLQ, e a DLQ é falha explícita.
- **Notificação:** `agenda_local`; exatamente um `notificacao_enviada` `CONSULTA_CRIADA` com
  canal `LOG` e conteúdo igual ao entregue; a marca.
- **Histórico:** o snapshot, com `atualizado_em = occurredAt`; a trilha com `id = eventId` e
  payload igual como `jsonb`; a marca.
- Estes ITs complementam os `Consumo*RabbitMqIT`; não os substituem.

### D3. Módulo técnico `quality-gates` — alteração deliberada da topologia

> **Sujeita à aprovação do Gabriel.** O reactor passa de seis para **sete projetos Maven**: o POM
> raiz agregador, os cinco módulos de código e o `quality-gates`. O `quality-gates` é o **sexto
> módulo filho** e o **sétimo projeto do reactor**. A spec promovida fala em "cinco módulos", e a
> delta MODIFIED "Build reprodutível do monorepo" reescreve o Requirement inteiro: além da raiz,
> continuam **cinco módulos de código**, e o sexto módulo filho é estritamente técnico. Nada dele
> é implementado antes da aprovação.

**Dependências**

- **Internas do reactor:** exatamente cinco, uma por módulo de código — `groupId`
  `br.com.fiap.hospital`, `artifactId` entre os `<modules>` de código. Todas em escopo `compile`
  padrão, sem `<scope>` ou com `compile`, e com o tipo `jar` padrão.
- Segundo a documentação do `report-aggregate`, `compile`, `runtime` e `provided` fornecem classes,
  fontes e dados de execução, enquanto `test` forneceria só dados de execução. Por isso `test` é
  proibido **nessas cinco**.
- **Externas:** bibliotecas necessárias aos testes do próprio módulo, como JUnit e AssertJ, são
  permitidas **somente em escopo `test`** e não entram na contagem. O código principal usa só o
  JDK, então biblioteca externa fora de `test` é recusada.
- **Versão das dependências internas e POM publicado.**
  - Os POMs-fonte continuam centralizados: `${revision}` fica restrito à versão do projeto raiz e
    à versão do `<parent>` de cada um dos seis módulos — sete ocorrências —, e toda dependência
    entre módulos do reactor usa `${project.version}`.
  - O `flatten-maven-plugin` 1.6.0, em `resolveCiFriendliesOnly`, resolve os placeholders
    CI-friendly só na versão do projeto e do parent: sozinho, deixava `${revision}`, e depois
    `${project.version}`, literais nas `<dependency>` dos `.flattened-pom.xml`.
  - Por isso a configuração central do plugin, no POM raiz, declara
    `<pomElements><dependencies>resolve</dependencies></pomElements>`. É essa resolução que
    materializa as versões no POM publicado: as dependências internas saem com a versão do
    reactor.
  - Versões e escopos externos explicitados no `.flattened-pom.xml` — por exemplo, versões vindas
    do BOM e `<scope>compile</scope>` — são resultado esperado da resolução: é o POM resolvido para
    consumidores. Os POMs-fonte **não** recebem versões externas duplicadas.
  - A versão do plugin não muda.
  - A prova estrutural (`VersaoCentralizadaTest`) exige, no POM-fonte, exatamente uma ocorrência
    de `${revision}` na raiz e uma no parent de cada módulo, nenhuma em `<dependency>`, dependência
    interna com `${project.version}`, e, no plugin central, `flattenMode=resolveCiFriendliesOnly` e
    `pomElements/dependencies=resolve`.
- O `AgregacaoDoReactorTest` distingue internas de externas:
  - exige as cinco internas em `compile`;
  - recusa `test`, `runtime` e `provided` apenas nelas;
  - recusa dependência interna do próprio módulo ou de artefato fora de `<modules>`;
  - recusa biblioteca externa fora de `test`.

**O que o módulo contém e o que não contém**

- A dependência, e não a posição em `<modules>`, põe o módulo por último. Ele é listado por
  último só para leitura.
- Contém apenas verificadores só com JDK (`br.com.fiap.hospital.qualidade`) e seus testes.
- Não tem Spring, `@SpringBootApplication` nem domínio, e nenhum módulo de código depende dele.

**Execuções na fase `verify`**, em ordem:

1. `jacoco:report-aggregate` (`relatorio-agregado`), sem o próprio módulo;
2. `exec` da auditoria (`auditoria-de-execucao`, D5);
3. `exec` do gate (`gate-de-cobertura`, D4).

São gates distintos. Uma inversão de ordem é fail-closed.

**Invocação.** `exec-maven-plugin`, goal `exec`, com versão explícita, usando a JVM do Maven
(`${java.home}/bin/java -cp ${project.build.outputDirectory}`), sem Python. O código de saída
diferente de zero reprova o lifecycle.

**`openspec/config.yaml`.** No **mesmo commit** que acrescentar o módulo, após a aprovação da
topologia, o implementador (Claude Code) atualiza o bloco MÓDULOS, e o Gabriel revisa e commita
(task 5.2).

**Guardas estruturais** (unitárias, com POMs sintéticos negativos):

- as regras de dependência internas e externas acima;
- nenhum POM de código dependendo do módulo;
- nenhum `@SpringBootApplication`;
- exclusões JaCoCo restritas a `**/*Application.class`.

### D4. Sessão de verificação e verificador de cobertura

**Sessão de verificação, em vez de data de modificação**

- O `maven-antrun-plugin`, com versão explícita, é declarado em **`<build><plugins>` do POM raiz**
  — e **não apenas** em `<pluginManagement>`, onde a execução nunca rodaria. Ele tem uma
  `<execution>` **ativa**, ligada à fase `initialize` e com `<inherited>false</inherited>`.
- O projeto raiz é o primeiro do reactor, então a execução roda uma única vez, antes de qualquer
  módulo:
  1. remove **somente** `*/target/jacoco.exec`, `*/target/surefire-reports/**`,
     `*/target/failsafe-reports/**` e `quality-gates/target/site/jacoco-aggregate/**`;
  2. grava `target/sessao-de-verificacao/sessao.txt` com o identificador da sessão.
- O identificador é o `${maven.build.timestamp}` com formato `yyyy-MM-dd'T'HH:mm:ss.SSS'Z'`: o
  início da sessão Maven, idêntico em todos os projetos.
- O verificador recebe o mesmo valor e exige **igualdade textual** com o marcador. Marcador
  ausente ou de outra sessão reprova.
- Como toda evidência anterior foi apagada, a presença de uma evidência prova que ela foi produzida
  depois. Nenhuma decisão usa data de modificação.
- A remoção também impede que o `append` do JaCoCo some execuções anteriores quando o gate roda
  sem `clean`.

**`SessaoDeVerificacaoTest`**, estrutural. Ele exige, no POM raiz:

- plugin em `build/plugins`;
- execução ativa;
- fase `initialize`;
- `inherited=false`;
- os quatro alvos de remoção;
- o marcador.

Negativos sintéticos, cada um reprovado:

- execução **presente só em `pluginManagement`**, que não seria executada;
- `inherited` ausente ou `true`;
- fase diferente;
- alvo de remoção faltando;
- execução sem goal.

**Entrada do gate**

- XML e `index.html` do mesmo `report-aggregate`.
- Um `target/jacoco.exec` não vazio em cada um dos cinco módulos de código.

**Contador normativo: LINE.** INSTRUCTION e BRANCH são impressos só para transparência.

**Escopos**

| Escopo | Como é somado |
|---|---|
| Global | Soma de LINE dos cinco `<group>`, conferida com o total do `<report>`. |
| `domain` | Os pacotes `br/com/fiap/hospital/agendamento/domain` e seus subpacotes. |
| `application` | O mesmo critério, com o prefixo `…/application`. |

**Comparação.** Inteira: `cobertas × 100 ≥ piso × (cobertas + perdidas)`. Um escopo exatamente no
piso passa.

**Fail-closed.** Reprovam:

- relatório ausente, ilegível ou vazio;
- marcador inválido;
- `jacoco.exec` ausente;
- bundle, LINE, total ou pacotes ausentes;
- soma divergente do total.

**Validação de todos os packages (opção A+).** O JaCoCo não escreve contador cujo total é zero:
um pacote só de interfaces sai com `<class>` e `<sourcefile>` e nenhum `<counter>`. O relatório
real da primeira execução completa tinha três pacotes assim (`agendamento/domain/port`,
`notificacao/repository` e `historico/infrastructure/persistence/repository`). A regra:

- `<group>` e `<report>` têm **exatamente um** `<counter type="LINE">` direto e válido;
- **todo** `<package>` dos cinco groups é validado antes de qualquer escopo — não só os de
  `domain` e `application` — e produz uma única contagem validada:
  - com um LINE direto válido, contribui com ele;
  - **pacote sem linhas executáveis:** contribui 0/0 **somente** se tem ao menos um `<class>` e ao
    menos um `<sourcefile>` e não tem, em toda a subárvore, nenhum `<counter>`, `<method>` ou
    `<line>`;
  - qualquer outro contador ou evidência executável sem LINE direto reprova;
  - package vazio (sem `<class>` ou sem `<sourcefile>`), LINE duplicado ou ilegível reprova;
- a mesma contagem validada alimenta a reconciliação e os escopos, sem caminho paralelo:
  - soma dos packages de cada group, incluindo os 0/0, **igual** ao LINE direto do group;
  - soma dos groups **igual** ao LINE do report;
  - `domain` e `application` somados dessas contagens, com pacotes existentes e total positivo.

**Garantia observável.** Ausência acidental de LINE, relatório sem informação de linha sobre
código executável e inconsistência entre package, group e report são recusados. Não se afirma que
qualquer adulteração deliberada do relatório seja distinguível: um relatório reescrito com
contadores coerentes entre si não é detectável por este gate.

**Parser seguro e offline.** DTD externa desligada, sem entidades externas.

**Testes automatizados:**

- acima do piso; no piso; global abaixo; `domain` abaixo; `application` abaixo;
- média contra soma, nos dois sentidos;
- pacote, bundle ou LINE ausente; total zero;
- pacote só de interfaces no formato real do JaCoCo, aceito como 0/0, e os três pacotes 0/0 do
  relatório real reproduzidos;
- INSTRUCTION presente e LINE ausente; `<method>`, `<line>` ou counter descendente com LINE
  direto ausente; pacote fora de `domain`/`application` sem LINE; package vazio; soma dos
  packages divergente do LINE do group — todos reprovados;
- `<report/>`; XML inválido; arquivo ausente; soma divergente;
- DTD externa;
- marcador ausente ou de outra sessão;
- `jacoco.exec` ausente;
- datas de modificação arredondadas dois segundos antes, aceitas.

### D5. Auditoria por famílias de relatórios

**Inventário de fontes**, independente dos relatórios

- Parte de **todos** os `.java` de `src/test/java` dos módulos de `<modules>`. Os padrões de nome
  vêm dos `<include>` do POM raiz.
- A classificação usa a JDK Compiler Tree API:
  - interface, enum, record ou anotação → não é suíte;
  - classe abstrata → base ou contrato;
  - classe concreta que declara ou herda método de teste (`@Test`, `@ParameterizedTest`,
    `@RepeatedTest`, `@TestFactory`, `@TestTemplate`), inclusive por `@Nested` herdado → **suíte
    executável**;
  - classe concreta sem teste → auxiliar.
- Uma suíte executável fora dos includes é **teste fora da convenção** e reprova.
- Uma declaração não interpretável ou superclasse não resolvida reprova.

**Só `TEST-*.xml` participa das famílias**

- Nos diretórios `surefire-reports` e `failsafe-reports`, apenas os arquivos `TEST-*.xml` são
  relatórios de suíte.
- O `failsafe-summary.xml` é resumo do plugin: **não** participa de família e **não** é órfão.
  Ele passa por uma conferência à parte — resultado sem falhas —, sem efeito nas famílias.
- Os demais arquivos dos plugins (`*.txt`, `*-output.txt`, `*.dumpstream`) não são relatórios e
  são ignorados pelas famílias.
- Um `TEST-*.xml` ilegível, órfão ou ambíguo reprova.

**Família de uma suíte S, executada pelo plugin P**

- O relatório externo `TEST-<fqcn(S)>.xml`.
- Para cada classe `@Nested` N declarada em S ou em suas superclasses, transitivamente, o
  relatório `TEST-<fqcn(classe declarante de N)>$<caminho de N>.xml` no diretório de P. Por
  exemplo, `ConsultaRepositoryAdapterIT` → `ConsultaRepositoryContractTest$Listagem.xml` em
  `failsafe-reports`.
- Todos os `TEST-*.xml` da família precisam ser válidos, sem failure, error ou skipped, nem caso
  com `<skipped>`, `<failure>` ou `<error>`.
- O externo pode ter `tests="0"`, desde que a soma da família seja **positiva**.
- Toda classe aninhada com testes precisa ter seu relatório.

**Atribuição**

- Todo `TEST-*.xml` do diretório de P é atribuído a **exatamente uma** família. Um relatório sem
  família é órfão; um que caberia em duas reprova por ambiguidade — é o caso de duas subclasses do
  mesmo contrato no mesmo plugin.
- Hoje `ConsultaRepositoryFakeTest` (surefire) e `ConsultaRepositoryAdapterIT` (failsafe) não são
  ambíguos.

**Proteção contra omissão: evidência por suíte e família, sem prova por método**

- A auditoria **não prova** a execução individual de cada método de teste. Correlacionar método
  e caso dependeria do formato de nome dos casos no relatório, que varia entre parametrizados,
  dinâmicos, aninhados e herdados. E a soma de casos não substitui essa correlação: um
  parametrizado com cinco casos esconderia um método omitido.
- O que a auditoria exige:
  1. **evidência por suíte e por família:** toda suíte executável e toda classe aninhada com
     testes produziram `TEST-*.xml` na sessão, sem falha, erro ou pulado, e com total positivo
     por família;
  2. **recusa dos mecanismos enumerados de omissão**, por parâmetro ou por configuração:
     seleção e exclusão por nome, tags, motores, suítes declaradas, ampliação por dependência,
     skip, desabilitação, suposição condicional, caso pulado e tolerância a falhas.
- A proteção é contra esses mecanismos conhecidos de omissão, e não uma prova por método. Um
  parametrizado sem argumentos falha por padrão no JUnit.
- **Nenhum gate ou inventário obrigatório do M10 usa `@TestFactory`.** As varreduras de D7 e D8
  são métodos `@Test` ordinários, e a execução verde sempre deixa casos no `TEST-*.xml` da suíte.
- **Limite residual declarado, para testes futuros:** uma fábrica dinâmica que emite zero testes
  não é detectável por família.

**Seleção, exclusão, omissão e tolerância — por parâmetro**

O verificador recebe o valor de cada propriedade das versões fixadas (Surefire e Failsafe 3.2.5)
capaz de selecionar, excluir, pular ou tolerar casos. Qualquer uma definida reprova; um literal
`${...}` conta como indefinido.

| Classe | Propriedades |
|---|---|
| Seleção por nome | `test`, `it.test`, `surefire.includes`, `failsafe.includes`, `surefire.includesFile`, `failsafe.includesFile` |
| Exclusão por nome | `surefire.excludes`, `failsafe.excludes`, `surefire.excludesFile`, `failsafe.excludesFile` |
| Tags | `groups`, `excludedGroups` |
| Motores | `surefire.includeJUnit5Engines`, `surefire.excludeJUnit5Engines`, `failsafe.includeJUnit5Engines`, `failsafe.excludeJUnit5Engines` |
| Suítes declaradas | `surefire.suiteXmlFiles`, `failsafe.suiteXmlFiles` |
| Ampliação por dependência | `dependenciesToScan` — a mesma user property no Surefire e no Failsafe 3.2.5 |
| Omissão | `skipTests`, `skipITs`, `maven.test.skip`, `maven.test.skip.exec`, `surefire.skipAfterFailureCount`, `failsafe.skipAfterFailureCount` |
| Tolerância | `maven.test.failure.ignore`, `surefire.rerunFailingTestsCount`, `failsafe.rerunFailingTestsCount` |
| Tolerância à seleção vazia | `surefire.failIfNoSpecifiedTests`, `failsafe.failIfNoSpecifiedTests`, `it.failIfNoSpecifiedTests` |

**Conferência contra a versão fixada (task 6.1).** Fontes: os descritores oficiais
`META-INF/maven/plugin.xml` de `maven-surefire-plugin-3.2.5.jar` (goal `test`) e de
`maven-failsafe-plugin-3.2.5.jar` (goals `integration-test` e `verify`), que geram as páginas
`surefire:test`, `failsafe:integration-test` e `failsafe:verify` da documentação Apache Maven. Todo
parâmetro com user property dos três goals foi examinado:

- **Incluídos:** toda propriedade da tabela acima está nos descritores com exatamente esse nome. A
  única lacuna era `failIfNoSpecifiedTests` (`surefire.`, `failsafe.` e o legado `it.`), que tolera
  uma seleção que não casou com nenhuma classe — tolerância associada à seleção.
- **Examinados e não incluídos, por não selecionarem, omitirem ou tolerarem casos:**
  - `failIfNoTests` e `*.failOnFlakeCount` só tornam o build mais estrito;
  - `disableXmlReport` e `surefire.reportNameSuffix` mudam só a evidência, e a ausência ou o nome
    inesperado dos relatórios já reprova por família ausente e relatório órfão;
  - `runOrder`, `parallel*`, `threadCount*`, `forkCount`, `reuseForks`, `forkNode` e
    `*.timeout`/`*.exitTimeout` mudam só ordem, paralelismo e processo; um timeout é falha;
  - `argLine`, `jvm`, `enableAssertions`, `childDelegation`, `useSystemClassLoader`,
    `useManifestOnlyJar`, `useModulePath`, `maven.test.additionalClasspath*`,
    `maven.test.dependency.excludes`, `junitArtifactName`, `testNGArtifactName`, `objectFactory`,
    `tempDir`, `encoding`, `printSummary`, `reportFormat`, `useFile`, `trimStackTrace`,
    `redirectTestOutputToFile`, `excludedEnvironmentVariables`,
    `enableProcessChecker`, `shutdown` e `debugForkedProcess` configuram ambiente, classpath e
    saída. Uma troca de provedor que não execute as classes reprova por família ausente;
  - `argLine` e `systemPropertiesFile` não selecionam por si, mas são canais de parâmetros do
    JUnit Platform e são inspecionados como tal (abaixo).
- **Parâmetros só de configuração** (sem user property), também examinados: `testClassesDirectory`
  muda o diretório varrido — seleção — e `summaryFile` do `failsafe:verify` troca o resumo lido —
  tolerância —; os dois entram na inspeção dos POMs. `reportsDirectory` só move a evidência, o que
  já reprova por módulo sem relatórios; `summaryFiles` só acrescenta resumos; `properties`,
  `systemPropertyVariables` e `systemProperties` são canais de parâmetros do JUnit Platform
  (abaixo).

**Por configuração efetiva dos POMs**

Propriedades e configurações declaradas no POM de um módulo não aparecem para o `quality-gates`.
Por isso, uma verificação estrutural lê o POM raiz e todos os POMs de módulo, inclusive perfis, e
exige:

- surefire com `<includes>` exatamente `**/*Test.java`, e failsafe com exatamente `**/*IT.java`,
  sem `<excludes>`;
- nenhum `<test>`, `<includesFile>`, `<excludesFile>`, `<groups>`, `<excludedGroups>`,
  `<includeJUnit5Engines>`, `<excludeJUnit5Engines>`, `<suiteXmlFiles>`, `<dependenciesToScan>`,
  `<skip>`, `<skipTests>`, `<skipITs>`, `<skipExec>`, `<skipAfterFailureCount>`,
  `<rerunFailingTestsCount>`, `<testFailureIgnore>`, `<failIfNoSpecifiedTests>`,
  `<testClassesDirectory>` ou `<summaryFile>` na configuração dos dois plugins, no nível do plugin
  ou de execução, em `pluginManagement` ou `build/plugins`, em qualquer projeto ou perfil;
- os `<includes>` normativos declarados uma única vez, no POM raiz; nenhum módulo, perfil ou
  execução os redefine;
- nenhuma propriedade da tabela acima em `<properties>` de projeto ou perfil;
- nenhuma das chaves internas lidas pelo provider JUnit Platform do Surefire 3.2.5 dentro do
  elemento `<properties>` da configuração do Surefire ou do Failsafe — `groups`, `excludegroups`,
  `includejunit5engines` e `excludejunit5engines` —, no plugin da raiz, em módulos, perfis,
  `pluginManagement` e execuções (abaixo);
- nenhum parâmetro do JUnit Platform que omita a execução ou tolere falha, em nenhum canal
  (abaixo).

**Parâmetros do JUnit Platform.** Conferidos nas versões efetivas do build — JUnit Jupiter 5.12.2 e
JUnit Platform 1.12.2. A recusa é por chave oficial e pelo valor que muda a semântica, nunca por
palavra contida no nome. Fontes, separadas pela natureza da evidência:

- **Documentação oficial:** o *User Guide* na tag `r5.12.2` do repositório `junit-team/junit5` —
  `user-guide/running-tests.adoc` (fontes e precedência dos parâmetros de configuração;
  `configurationParameters` e filtros por nome, `groups` e `excludedGroups` no Surefire) e
  `user-guide/advanced-topics/launcher-api.adoc` (modo dry-run; listener de descoberta padrão que
  aborta na primeira falha; `junit.platform.execution.listeners.deactivate` só alcança listeners
  registrados por `ServiceLoader`).
- **Bytecode dos jars efetivos:** as chaves existentes, pelas constantes de
  `org.junit.jupiter.engine.Constants` e `org.junit.platform.launcher.LauncherConstants`; o dry-run,
  que em `EngineExecutionOrchestrator` notifica cada teste com `executionSkipped`; os valores aceitos
  do listener de descoberta, `logging` e `abortOnFailure`, no enum de `LauncherDiscoveryListeners`, e
  o padrão `abortOnFailure`, em `LauncherDiscoveryRequestBuilder`.

- **Filtram descoberta ou execução — recusados:**
  - `junit.platform.execution.dryRun.enabled` com valor verdadeiro: pelo guia, o Launcher pula a
    execução real e notifica os testes como se tivessem sido ignorados;
  - `junit.platform.discovery.listener.default` com valor diferente de `abortOnFailure`: pelo guia, o
    listener padrão aborta a descoberta na primeira falha; `logging`, o outro valor aceito pelo
    bytecode, só registra a falha e deixa a execução seguir sem as classes afetadas.
- **Examinados e legítimos — aceitos:** `junit.jupiter.execution.parallel.*` e
  `junit.jupiter.execution.timeout.*` (paralelismo e tempo; timeout é falha),
  `junit.jupiter.testinstance.lifecycle.default`, `junit.jupiter.testmethod.order.default`,
  `junit.jupiter.testclass.order.default`, `junit.jupiter.displayname.generator.default`,
  `junit.jupiter.tempdir.*`, `junit.jupiter.params.*`, `junit.platform.output.capture.*`,
  `junit.platform.stacktrace.pruning.enabled`, `junit.platform.reporting.output.dir`,
  `junit.platform.launcher.interceptors.enabled`, `junit.jupiter.extensions.*`,
  `junit.platform.execution.listeners.deactivate` — que só desativa listeners registrados por
  `ServiceLoader`, e não o do Surefire — e `junit.jupiter.conditions.deactivate`, que desativa
  condições e faz executar mais, nunca menos.
- **Seleção por tags, motores, nome ou pacote** não existe como parâmetro de configuração nesses
  canais: é feita por filtros da requisição do Launcher, que o Surefire e o Failsafe montam a partir
  de `groups`, `excludedGroups`, `includeJUnit5Engines`, `excludeJUnit5Engines`, includes e
  excludes — já cobertos pela tabela e pela configuração efetiva.
- **Chaves internas do provider, confirmadas por bytecode** (`maven-surefire-common` e
  `surefire-junit-platform` 3.2.5): `AbstractSurefireMojo.convertGroupParameters` e
  `convertJunitEngineParameters` gravam os elementos `<groups>`, `<excludedGroups>`,
  `<includeJUnit5Engines>` e `<excludeJUnit5Engines>` no mesmo `Properties` do elemento
  `<properties>` do plugin, sob as chaves `groups`, `excludegroups`, `includejunit5engines` e
  `excludejunit5engines`; o `JUnitPlatformProvider` lê exatamente essas quatro chaves desse mapa para
  montar os filtros de tag e de motor. Por isso, declará-las diretamente em
  `<configuration><properties>` seleciona testes sem passar pelos elementos de primeiro nível. As
  quatro são recusadas pelo nome exato, em `<properties>` do Surefire e do Failsafe, no plugin da
  raiz, em módulos, perfis, `pluginManagement` e execuções. Nenhuma outra chave desse mapa é recusada:
  `configurationParameters` é conferido pelos parâmetros do JUnit Platform, e as demais não selecionam.
- **Duas representações de um parâmetro `Properties`, confirmadas por bytecode** (`PropertiesConverter`
  do `org.eclipse.sisu.plexus` 0.3.5 embarcado no Maven 3.9.3): um filho `<property>` vale pelos seus
  `<name>` e `<value>`; qualquer outro filho vale pelo próprio nome, com o texto como valor. Assim,
  `<groups>x</groups>` e `<property><name>groups</name><value>x</value></property>` produzem a mesma
  entrada. A inspeção lê as duas formas, por igualdade exata de chave, em `<properties>` —
  `groups`, `excludegroups`, `includejunit5engines`, `excludejunit5engines` e `configurationParameters`
  — e em `<systemProperties>`.
- **Configuração escrita contra configuração recebida: interpolação Maven e precedência da linha de
  comando.** O Maven interpola `${...}` antes de entregar a configuração ao Surefire e ao Failsafe, e os
  dois ainda resolvem `@{...}` no `argLine`, na execução. Na interpolação, uma user property da linha de
  comando prevalece sobre a propriedade de perfil, módulo ou raiz: com `<m10.dry-run>false</m10.dry-run>`
  e `junit.platform.execution.dryRun.enabled=${m10.dry-run}` no `configurationParameters`,
  `mvn verify -Dm10.dry-run=true` entrega `true` ao JUnit. A auditoria não recebe propriedades
  arbitrárias da execução, então nem o texto cru nem o valor do POM provam o valor recebido. Estratégia
  **A**:
  - **alias customizado é recusado** — mesmo quando o valor atual do POM parece seguro — em toda posição
    capaz de ocultar configuração de teste: qualquer ponto do `configurationParameters`; o nome de uma
    propriedade em `<properties>` do plugin, em `<systemProperties>` ou de uma `systemPropertyVariable`;
    o valor de um parâmetro relevante do JUnit Platform; o `argLine`, do plugin ou de `<properties>` do
    projeto; e o caminho de um `systemPropertiesFile`. Alias é toda expressão `${nome}` ou `@{nome}` fora
    das exceções abaixo; a mensagem mostra o valor que o POM lhe dá, quando há;
  - **exceção 1, `@{argLine}` e `${argLine}` no `argLine`:** o valor efetivo de `argLine` — já com a
    precedência da linha de comando — é repassado à auditoria pelo `exec` e analisado; por isso
    `-DargLine=-Djunit.platform.execution.dryRun.enabled=true` reprova;
  - **exceção 2, expressões próprias do modelo e não sobrescrevíveis**, nos canais de `argLine` e de
    caminho: `${project.basedir}`, `${basedir}` e `${project.build.directory}`. O interpolador de modelo
    as resolve pelo próprio projeto antes das user properties — conferido com `help:evaluate` sob
    `-Dproject.build.directory=HOSTIL` e `-Dproject.basedir=HOSTIL`, que mantiveram os valores reais,
    enquanto `-Dm10.dry-run=true` mudou um alias `false`. A auditoria as substitui pelos valores reais do
    módulo e analisa os `-D` do texto resultante, de modo que um diretório cujo caminho contenha
    ` -Djunit.platform.execution.dryRun.enabled=true` reprova. `${project.build.directory}` só vale
    enquanto nenhum POM redefinir `<build><directory>`;
  - **agente do Mockito por caminho determinístico:** `${org.mockito:mockito-core:jar}`, definida pelo goal
    `dependency:properties`, também é uma propriedade Maven sobrescrevível por `-D`, e poderia trazer
    `caminho -Djunit.platform.execution.dryRun.enabled=true`. O POM raiz deixa de usá-la: o goal
    `dependency:copy-dependencies` copia o `mockito-core` para `${project.build.directory}/agentes`, sem
    versão no nome, e o `argLine` normativo passa a `@{argLine} -javaagent:${project.build.directory}/agentes/mockito-core.jar`.
    O Mockito continua como java agent composto com o JaCoCo, como o roadmap exige, e a coordenada, se
    voltar ao `argLine`, é recusada como alias;
  - **fora dessas posições, `${...}` não reprova só por existir** — uma propriedade comum ou o valor de
    uma propriedade de sistema que não é parâmetro relevante;
  - as propriedades de `<properties>` visíveis no escopo — raiz, módulo e perfil — ainda são resolvidas,
    só para o diagnóstico e para acusar também um valor inseguro já presente no POM; nunca para aceitar um
    alias.
- **Canais**, na ordem de precedência do guia, todos inspecionados:
  1. requisição do Launcher: `<properties><configurationParameters>` do Surefire e do Failsafe, em
     qualquer POM, perfil, `pluginManagement` ou execução;
  2. propriedades de sistema da JVM de teste: `<systemPropertyVariables>`, `<systemProperties>`,
     `-D` em `<argLine>`, o arquivo de `<systemPropertiesFile>`, e, pela linha de comando, as duas
     chaves, `argLine`, `surefire.systemPropertiesFile` e `failsafe.systemPropertiesFile`, repassados
     à auditoria como as demais propriedades;
  3. `junit-platform.properties` na raiz do classpath de teste: `src/test/resources` e
     `src/main/resources` de cada módulo.
- Um `systemPropertiesFile` declarado que não possa ser lido — inexistente, diretório, caminho
  inválido ou conteúdo que não é um arquivo de propriedades válido — reprova com diagnóstico: o
  canal não pode ser conferido. O mesmo vale para `configurationParameters` e
  `junit-platform.properties` ilegíveis. Nenhum desses casos interrompe a auditoria com exceção.
- **Limite residual declarado:** um `junit-platform.properties` dentro de um jar de dependência e
  extensões autodetectadas não são inspecionados. Uma condição autodetectada que desabilite testes
  aparece como caso pulado e reprova; um tratador de exceção que engula falhas não é detectável.

A recusa de suíte sem família complementa essa verificação: ela pega qualquer seleção não
enumerada que tenha efeito sobre classes inteiras.

**Desabilitação.** Anotações e herança são resolvidas pela árvore sintática e pelos imports —
escopos, mesmo arquivo, declaração simples, mesmo pacote, imports sob demanda e `java.lang` —, nunca
por expressão regular nem por semelhança de nome. **Depois** da resolução, a varredura reprova só:

- `org.junit.jupiter.api.Disabled` e `org.junit.Ignore`;
- as anotações condicionais do pacote `org.junit.jupiter.api.condition` do JUnit 5.12.2 —
  `EnabledOnOs`, `DisabledOnOs`, `EnabledOnJre`, `DisabledOnJre`, `EnabledForJreRange`,
  `DisabledForJreRange`, `EnabledInNativeImage`, `DisabledInNativeImage`, `EnabledIf`, `DisabledIf`,
  `EnabledIfSystemProperty`, `DisabledIfSystemProperty`, `EnabledIfSystemProperties`,
  `DisabledIfSystemProperties`, `EnabledIfEnvironmentVariable`, `DisabledIfEnvironmentVariable`,
  `EnabledIfEnvironmentVariables` e `DisabledIfEnvironmentVariables`;
- `org.springframework.test.context.junit.jupiter.EnabledIf` e `DisabledIf`;
- `org.testcontainers.junit.jupiter.Testcontainers` com `disabledWithoutDocker = true`;
- anotação declarada nas fontes de teste que seja meta-anotada, transitivamente, com uma das
  anteriores.

Uma anotação própria de nome parecido — `@EnabledIfAuditoria`, por exemplo — sem meta-anotação
condicional é aceita: nome semelhante não prova semântica. Um nome dessa lista que não possa ser
resolvido reprova identificando o arquivo e o símbolo.

**Suposição condicional.** A varredura reprova só chamadas resolvidas às APIs de suposição do
classpath de teste:

- `org.junit.jupiter.api.Assumptions` (`assumeTrue`, `assumeFalse`, `assumingThat`, `abort`);
- `org.junit.Assume` (`assumeTrue`, `assumeFalse`, `assumeNotNull`, `assumeThat`,
  `assumeNoException`);
- `org.assertj.core.api.Assumptions` e `org.assertj.core.api.BDDAssumptions` do AssertJ 3.27.6, com
  seus métodos estáticos públicos de suposição (`assumeThat*` e `given*`). Fica de fora
  `setPreferredAssumptionException`, que só configura qual exceção o AssertJ lançará numa suposição
  posterior: chamá-lo não aborta, não pula nem condiciona o teste.

Resolve-se a chamada qualificada pelo tipo — nome simples importado, import sob demanda ou nome
qualificado —, a referência de método pelo mesmo critério, e a chamada não qualificada por import
estático simples ou sob demanda da API, salvo se um método de mesmo nome declarado na própria
classe, nas envolventes ou nos supertipos das fontes de teste a sombrear. Um método local como
`assumeFormatoValido()` é aceito.

**Classe aninhada com testes sem `@Nested`.** Uma classe aninhada que declara métodos de teste sem
ser `@Nested` nunca é executada: os includes excluem classes internas e o JUnit só descobre as
aninhadas marcadas. Ela reprova como teste que nunca rodaria, do mesmo modo que a suíte fora da
convenção.

**Sessão.** O marcador de D4 precisa ser igual ao identificador recebido. Nada usa data de
modificação.

**Saída.** Por módulo: suítes, relatórios e casos. Com violação, código 1 e a lista.

**Testes automatizados.** Árvores sintéticas modeladas nas estruturas reais:

- `ConsultaRepositoryAdapterIT`, com o contrato de três `@Nested` e externo zero;
- `ConsultaRepositoryFakeTest`;
- `GraphiqlPorProfileIT`;
- `JwtServiceTest`;
- uma parametrizada e uma `@TestFactory`;
- com `failsafe-summary.xml` e arquivos `*.txt` presentes, que **não** reprovam.

Casos cobertos:

- aceita;
- aninhada sem relatório; família com total zero;
- falha, erro, pulado no atributo e no caso;
- `TEST-*.xml` inválido; órfão; ambíguo;
- fora da convenção;
- abstrata e auxiliar não exigidas;
- módulo sem diretório;
- marcador ausente ou de outra sessão;
- datas arredondadas;
- `@Disabled`;
- mudança do include acompanhada;
- **uma classe de filtro por vez, sempre com todos os relatórios presentes**, para que o filtro
  seja a única causa: seleção por nome, exclusão por nome, arquivo de inclusão, arquivo de
  exclusão, tags, motores, suítes declaradas, ampliação por dependência
  (`-DdependenciesToScan=...`), skip, `skipAfterFailureCount`, `maven.test.failure.ignore` e
  `rerunFailingTestsCount`;
- **configuração efetiva, uma por vez:** include alterado, `<excludes>` acrescentado,
  `<dependenciesToScan>` acrescentado, propriedade de filtro em `<properties>` de módulo, filtro
  só num perfil, cada chave interna do provider (`groups`, `excludegroups`, `includejunit5engines`,
  `excludejunit5engines`) em `<properties>` do plugin, uma por vez e nas localizações da raiz, perfil,
  `pluginManagement` e execução, e cada parâmetro oficial do JUnit Platform que omite ou tolera, em
  cada canal, inclusive `systemPropertiesFile` inexistente, diretório e com conteúdo inválido; cada
  chave interna do provider e o `configurationParameters` nas duas representações de `Properties`;
  e, por alias, o alias com valor `false` no POM em `configurationParameters` — a simulação da user
  property que o sobrescreve —, o conteúdo inteiro por alias, aliases em `systemPropertyVariables` e
  `systemProperties`, `${m10.test.args}` e `@{m10.test.args}` no `argLine`, o listener de descoberta
  escondido por alias, a coordenada do Mockito sobrescrevível e um caminho de projeto que introduza `-D`
  pelo `${project.build.directory}`; com os positivos de `dryRun=false` literal, `configurationParameters`
  literais legítimos, o `argLine` normativo com o agente em `${project.build.directory}/agentes`, o
  `@{argLine}` com valor efetivo seguro e propriedade comum fora dos canais de teste;
- **ausência de falso positivo:** configuração legítima do JUnit Platform aceita em todos os canais
  (`junit.jupiter.execution.parallel.enabled=true`,
  `junit.jupiter.execution.parallel.mode.default=concurrent`,
  `junit.jupiter.testinstance.lifecycle.default=per_class`,
  `junit.jupiter.extensions.autodetection.enabled=true`,
  `junit.platform.execution.listeners.deactivate=algum.Listener`); anotação própria de nome
  `@EnabledIf...` sem meta-anotação condicional aceita; método local `assumeFormatoValido()`
  aceito; `Assumptions.setPreferredAssumptionException(...)` do AssertJ aceito; e, para as condições e suposições reais, import simples, sob demanda, estático e chamada
  ou anotação qualificada, inclusive por meta-anotação local;
- **omissão mascarada pela soma:** uma classe com um método parametrizado gerando cinco casos e
  outro método omitido, todos os relatórios presentes e total positivo. Com a omissão por
  `-Dtest=Classe#metodoParametrizado`, a auditoria reprova pelo filtro; com a mesma omissão por
  `@Disabled`, reprova pela varredura de fonte. O teste também demonstra que, sem esses
  mecanismos, a soma sozinha não reprovaria, o que justifica não afirmar garantia por método.

### D6. Infraestrutura real (RNF-06): árvore sintática mais evidência runtime da infraestrutura usada

**Análise estrutural**

- Teste unitário do `quality-gates` com a JDK Compiler Tree API (`com.sun.source.tree`, via
  `JavacTask.parse`). Os nomes são resolvidos por imports simples, estáticos e sob demanda, e pelo
  mesmo pacote, contra a lista de tipos de infraestrutura:
  - `javax.sql.DataSource`;
  - `JdbcTemplate`, `NamedParameterJdbcTemplate`;
  - `EntityManager`, `EntityManagerFactory`;
  - as interfaces de repositório Spring Data dos fontes `main`, descobertas por herança de
    `Repository`, `CrudRepository` ou `JpaRepository`;
  - `ConnectionFactory` do Rabbit, `RabbitTemplate`, `AmqpTemplate`, `AmqpAdmin`, `RabbitAdmin`.
- Um nome que não se resolve, mas pode coincidir com tipo de infraestrutura, reprova.

**Recusa, em `*IT` e em suas bases e configurações:**

- `@Mock`, `@MockBean`, `@MockitoBean` ou `@TestBean` sobre tipo de infraestrutura;
- `Mockito.mock(...)` ou `mock(...)` com import estático;
- `@Bean` de teste com retorno de infraestrutura;
- `@DataJpaTest` ou `@JdbcTest` sem `@AutoConfigureTestDatabase(replace = Replace.NONE)`;
- propriedade de banco embarcado;
- dependência de H2, HSQLDB, Derby ou broker embarcado;
- imagem diferente de `postgres:16` ou `rabbitmq:3.13-management`.

**Permitido explicitamente:**

- `@SpyBean` e `@MockitoSpyBean`;
- `doThrow` sobre espião;
- barreiras e decoradores que delegam ao real, inclusive o `BeanPostProcessor` da `DataSource`;
- dublês de `MailSender`, `JavaMailSender`, `NotificationSenderPort` e outras portas externas.

**A infraestrutura exigida é derivada, não presumida.** A análise identifica, por módulo, os
containers que os ITs efetivamente declaram e usam (`PostgreSQLContainer`, `RabbitMQContainer`).
Cada infraestrutura assim identificada exige a prova runtime correspondente, e **somente ela**:

| Módulo | IT de evidência | Prova |
|---|---|---|
| `agendamento-service` | `InfraestruturaRealAgendamentoIT` sobre `M05RabbitBase` | PostgreSQL 16 e RabbitMQ 3.13 |
| `notificacao-service` | `InfraestruturaRealNotificacaoIT` sobre `NotificacaoITBase` | PostgreSQL 16 e RabbitMQ 3.13 |
| `historico-service` | `InfraestruturaRealHistoricoIT` sobre `HistoricoITBase` | PostgreSQL 16 e RabbitMQ 3.13 |
| `shared-contracts` | `InfraestruturaRealContratosIT` sobre `RabbitITBase` | somente RabbitMQ 3.13 |
| `shared-security` | nenhum | nenhuma: não há IT de infraestrutura, e prova artificial é recusada |

**Conteúdo das provas**

- **PostgreSQL:** `DataSource.getConnection().getMetaData().getURL()` com host, porta e banco do
  container, e `server_version_num` em `[160000, 170000)` por consulta real.
- **RabbitMQ:**
  - `CachingConnectionFactory` com host e porta do container;
  - `version` do servidor, obtida por conexão real, iniciando com `3.13`;
  - uma operação real: declaração passiva da fila normativa, ou envio e recebimento numa fila
    temporária.

Uma `DataSource` embrulhada que delega ao real passa. O `InfraestruturaRealTest` exige o IT de
evidência exatamente onde houver infraestrutura usada, e recusa exigir ou aceitar prova de
infraestrutura que o módulo não usa.

**Negativos automatizados** com fontes sintéticas:

- recusados: `@Mock`, `@MockBean`, `@MockitoBean`, `@TestBean`, `Mockito.mock` com import
  estático, `@Bean DataSource`, H2, `replace = ANY`, import sob demanda, e módulo com
  `PostgreSQLContainer` sem prova de PostgreSQL;
- aceitos: `@SpyBean`, decorador de porta, `@MockBean MailSender`, e módulo só com
  `RabbitMQContainer` e prova só de RabbitMQ;
- o comparador de evidência reprova fonte de dados ou fábrica apontando para outro endereço.

### D7. Superfície REST do agendamento

A descoberta é feita no contexto real do `EntradasHostisIT`, a partir de
`RequestMappingHandlerMapping.getHandlerMethods()`.

**Classificação**, bidirecional contra a descoberta:

- incluídos: os seis endpoints de consulta e `POST /auth/login`;
- excluídos, com motivo: springdoc, swagger e `/error`.

**Entradas.** `@PathVariable`, `@RequestParam` e `@RequestBody`, com os componentes de record.
Parâmetro sem classificação reprova.

**Tipo → dimensão**

| Tipo | Dimensão |
|---|---|
| `UUID` | UUID |
| `Enum` ou coleção de enum | ENUM |
| temporais | DATA |
| inteiros | INTEIRO |
| `String` | TEXTO |
| `@RequestBody` | CORPO, mais uma dimensão por campo |

Tipo sem mapeamento reprova.

**Catálogo, com mínimos nominais obrigatórios**

| Dimensão | Variantes mínimas |
|---|---|
| UUID | malformado; inexistente; nil; vazio; caminho estranho. No corpo, nulo e tipo incompatível. |
| ENUM | inexistente; vazio; caixa divergente; ordinal. No corpo, nulo e tipo incompatível. |
| DATA | as 12 bordas de `DATAS_HOSTIS`; sem offset; formato local; no corpo, timestamp numérico e nulo. |
| INTEIRO | zero; negativo; `MAX`; `MAX+1`; muito grande; fração; texto; vazio. No corpo, nulo e tipo incompatível. |
| TEXTO | vazio; espaços; acima do limite; NUL e controle, nas bordas **e no meio** do texto; unicode de 4 bytes; SQL; HTML. No corpo, nulo e tipo incompatível. |
| CORPO | JSON malformado; ausente; `null`; array; escalar; todos os campos com tipo incompatível; `Content-Type` `text/plain` e `application/xml`; campo desconhecido; chave duplicada; conteúdo após o JSON. |

Todos os valores atuais do `EntradasHostisIT` estão contidos no catálogo.

**Plano de ataques**, declarado à parte da descoberta: controles por endpoint, pontos de injeção,
alvo novo quando a operação muda estado e cliente de bytes exatos.

**Execução por métodos `@Test` ordinários, e não por fábrica dinâmica.** A classe usa ciclo de
vida `PER_CLASS` e `@TestMethodOrder(OrderAnnotation.class)`, com cinco métodos `@Test` ordenados:

1. `controles` — o controle válido de cada endpoint e entrada;
2. `passagem1` — todas as combinações de D × V;
3. `passagem2` — a repetição completa;
4. `controlesFinais` — os controles depois das duas passagens;
5. `comparacao` — `E = D × V`, dimensão descoberta igual à declarada, as seis dimensões presentes
   e inventário não vazio.

Cada método percorre suas combinações e acumula as falhas com asserções agregadas (soft). A
mensagem identifica cada `(método, rota, entrada, dimensão, variante)` reprovada, e as contagens
por combinação são impressas. A execução verde produz necessariamente os cinco casos no
`TEST-*.xml` da suíte, auditáveis pela família (D5). A `comparacao` roda mesmo que uma passagem
falhe, e um E vazio — por exemplo, se as passagens não rodaram — reprova.

**Asserções por ataque:** nenhum 5xx; nenhum vazamento; Problem Detail na recusa da aplicação;
recusa do conector só nas variantes marcadas.

**Isolamento entre combinações.** Depois de verificar a resposta, a varredura descarta as consultas
que a combinação criou. Um valor hostil **aceito** muda a agenda e pode impedir as combinações
seguintes — uma duração no teto do inteiro ocupa a agenda do médico por milênios —, e sem o
descarte a varredura pararia por efeito do ataque anterior, não por defeito do endpoint sob ataque.
O descarte é sempre posterior às asserções (D17).

**Preservado:** `credenciaisHostis` (19 casos), `aVarreduraAlcancaODominio`, a corrida HTTP do M05,
as matrizes e as proteções.

**Débitos do M03** — conferência determinística:

| Achado | Coberto por |
|---|---|
| Exceções HTTP malformadas (PR7) | CORPO |
| Cancelamento sem motivo (PR8) | CORPO e TEXTO de `motivo` |
| Sanitização de conversão (PR8) | vazamento, mais UUID, ENUM e DATA de consulta |
| `registradoPorId` e FK (PR9) | UUID inexistente no corpo |
| Overflow de offset (PR9) | INTEIRO `pagina` × `tamanho` |
| Extremos de `OffsetDateTime` (PR10) | DATA |

O PR11 (OpenAPI 201 e `Location`) fica fora da varredura e é preservado por `ApiDocumentadaIT`.

### D8. Superfície GraphQL do histórico

A varredura é o `EntradasHostisGraphqlIT`, sobre o `GraphqlITBase`.

**Descoberta.** Vem de `GraphQlSource.schema()`, o schema **servido**: campos de `Query` e
`Mutation`, argumentos e campos de entrada, recursivamente, com a obrigatoriedade. A classificação
das operações é bidirecional.

**Tipo → dimensão**

| Tipo GraphQL | Dimensão |
|---|---|
| `ID` | UUID — todos os ids do schema são UUID |
| enums | ENUM |
| `DateTime` | DATA |
| `String` | TEXTO |
| **somente `Int`** | INTEIRO — nenhum hoje; se surgir, passa a exigir ataques |
| `Float`, `Boolean` ou qualquer escalar sem dimensão normativa | **falha fechada** até a spec classificá-lo |
| tipo de entrada | CORPO de objeto, mais uma dimensão por campo |

**Três naturezas de combinação, e por que elas não se misturam**

A primeira versão desta varredura tratava tudo como uma tupla só, e isso a tornou
semanticamente falsa: as variantes de documento eram textos fixos — `consulta(id: 42)`, por
exemplo — e o laço registrava como executada a operação da vez. Uma combinação rotulada
`Query.consultasDoPaciente` executava, na verdade, `consulta`. Cinco rótulos diferentes, um
único ataque. O mesmo valia para o corpo HTTP: recusar um corpo `null` é uma propriedade da
fronteira de transporte, não de cada operação, e contá-la cinco vezes inflava `E` sem
exercitar nada a mais.

| Natureza | Tupla | Cardinalidade |
|---|---|---|
| Vinculada ao schema | (operação, entrada, dimensão, variante) | uma entrada por argumento ou campo de objeto de entrada descoberto |
| Documento da operação | (operação, `<documento>`, variante) | uma por operação, com o documento **gerado** a partir dela |
| Transporte global | (`<graphql-http>`, `<corpo>`, variante) | **uma só**, nunca multiplicada pelas operações |

**Catálogo, com mínimos obrigatórios**

| Dimensão | Natureza | Variantes mínimas |
|---|---|---|
| UUID | vinculada | malformado; inexistente; nil; vazio; número JSON; lista no lugar de escalar; nulo em obrigatório. |
| ENUM | vinculada | inexistente; caixa divergente; vazio; número; elemento nulo em lista não nula. |
| DATA | vinculada | as bordas de `DATAS_HOSTIS`; sem offset; formato local; número JSON. |
| TEXTO | vinculada | vazio; espaços; 10 000 caracteres; NUL e controle, nas bordas e no meio; unicode de 4 bytes; SQL; HTML; número ou objeto no lugar. |
| INTEIRO | vinculada | o catálogo REST, aplicado quando `Int` existir no schema. |
| OBJETO | vinculada | escalar; lista; campo desconhecido, inclusive autor e ids; obrigatório ausente; objeto vazio; nulo. |
| DOCUMENTO | por operação | sintaxe inválida; vazio; campo inexistente; argumento desconhecido; literal de tipo incompatível; variável não declarada; variável com tipo divergente; duas operações sem `operationName`. |
| CORPO_HTTP | global | JSON malformado; ausente; `null`; array; escalar; `text/plain`; `query` ausente; `query` não string; `variables` não objeto; `operationName` inexistente. |

**Documentos gerados a partir da operação-alvo.** Nenhuma variante de documento carrega o nome
de uma operação fixa. O gerador recebe a raiz (`query` ou `mutation`), o nome da operação e **um
argumento real dela**, e produz os oito documentos. Quando a variante precisa de um valor
incompatível, o argumento usado é o da própria operação — `filtro: 42` em `minhasConsultas`,
`input: 42` em `corrigirRegistroHistorico` —, e nunca o argumento de outra.

**Guardas do modelo**, unitárias, que reprovam:

- documento rotulado para uma operação que executa outra;
- gerador que devolve documento fixo, com nome de operação embutido;
- variante de transporte contabilizada uma vez por operação, em vez de uma vez ao todo;
- combinação exigida e não executada;
- ataque órfão, sem entrada, documento ou transporte correspondente.

**Cardinalidade.** `E` é a soma das três naturezas: entradas descobertas × variantes da dimensão,
mais operações × variantes de documento, mais as variantes de transporte. Com o schema servido
hoje — 27 entradas em 5 operações — são 242 vinculadas (UUID 28, ENUM 35, DATA 105, TEXTO 50,
OBJETO 24), 40 de documento e 10 de transporte: **292**. As contagens são impressas pela suíte, e
o número normativo é o que ela imprime, não o que está escrito aqui.

**Plano e controles.** Valores por variáveis, com os nomes dos próprios argumentos. MEDICO nas
operações clínicas; PACIENTE em `minhasConsultas`, a única em que esse perfil é o controle
correto. A mutação usa alvo novo por combinação. O controle exige ausência de `errors` e dados
produzidos pelo resolver.

**Asserções.** Nenhum 5xx; nenhum `INTERNAL_ERROR`; apenas `BAD_REQUEST`, `NOT_FOUND` e
`FORBIDDEN`; fronteira HTTP em 4xx; nenhum vazamento; entrada recusada sem alteração no snapshot
nem na trilha.

**Execução.** A mesma estrutura de D7, sem fábrica dinâmica: cinco métodos `@Test` ordinários e
ordenados — controles, passagem 1, passagem 2, controles finais e comparação `E = D × V` nas três
naturezas, com as dimensões presentes no schema. As falhas são agregadas e as contagens impressas
por combinação, e a execução verde produz os cinco casos no `TEST-*.xml` da suíte.

**Preservado:** `MatrizDeAutorizacaoGraphqlIT` (15 células), `CoberturaDeAutorizacaoGraphqlTest`,
`ErrosGraphqlIT`, `SegurancaGraphqlIT`, `EntradasHostisHistoricoIT` e as proteções do M09.

### D9. Endpoint interno da notificação

O `SuperficieHttpNotificacaoIT`, sobre `NotificacaoITBase`, exige exatamente
`POST /internal/lembretes/executar`, classificado como **inventariado, sem entrada de negócio**:
sem combinações hostis de valor. Um parâmetro ou handler novo reprova.

Preservados: `MatrizDeAutorizacaoNotificacaoIT` (3 células) e `DisparoManualIT`.

### D10. Smoke: ciclo de vida

1. **Guarda inicial.** Modo estrito, variáveis de recurso vazias e traps instaladas.
2. **Preflight completo antes de qualquer recurso.** Bash 4, Docker e daemon, Java 21, Maven,
   curl e jq 1.6. Qualquer ausência termina com código 2, sem diretório, rede, container nem
   processo.
3. **Recursos da execução.** `RUN_ID`, `mktemp -d` com prefixo `hospital-smoke.`, rede e
   containers `hospital-smoke-<RUN_ID>…`, com label `br.com.fiap.hospital.smoke=<RUN_ID>`.
4. **Build único.**
   `mvn -q -DskipTests package -pl agendamento-service,notificacao-service,historico-service -am`,
   seguido de exatamente um `*-exec.jar` por serviço.
5. **JARs executáveis.** O `repackage` com classifier `exec` preserva o jar principal. Não há
   Dockerfile.
6. **Infraestrutura.**
   - Postgres: `docker create` do `postgres:16`, `docker cp` do `init.sql` e `docker start`.
   - RabbitMQ: `rabbitmq:3.13-management`.
   - Portas `127.0.0.1::` dinâmicas, credenciais aleatórias, volumes registrados e
     `MSYS_NO_PATHCONV=1`.
   - Só `init.sql` e o seed `V900`, sem consulta pré-carregada.
7. **Segredo.** `JWT_SECRET` com 48 bytes aleatórios, nunca impresso.
8. **Serviços.** `java -jar`, com as variáveis abaixo, logs no runtime e PIDs capturados.

| Variável | Agendamento | Notificação | Histórico |
|---|---|---|---|
| Profile | `SPRING_PROFILES_ACTIVE=demo` | padrão | padrão |
| Banco | `AGENDAMENTO_DB_URL`, `POSTGRES_USER`, `POSTGRES_PASSWORD` | `NOTIFICACAO_DB_URL`, `NOTIFICACAO_DB_USER`, `NOTIFICACAO_DB_PASSWORD` | `HISTORICO_DB_URL`, `HISTORICO_DB_USER`, `HISTORICO_DB_PASSWORD` |
| Broker | `RABBITMQ_HOST`, `RABBITMQ_PORT`, `RABBITMQ_USER`, `RABBITMQ_PASSWORD` | as mesmas quatro | as mesmas quatro |
| Segredo | `JWT_SECRET` | `JWT_SECRET` | `JWT_SECRET` |
| Porta | `SERVER_PORT=0` | `SERVER_PORT=0` | `SERVER_PORT=0` |
| Outras | — | `NOTIFICACAO_SENDER=log` | — |

9. **Parser de porta.** Remove `\r`. Procura a linha de início do Tomcat e exige **exatamente uma**
   porta distinta em `[1, 65535]` por processo. Roda dentro de `aguardar`: falha imediata na morte
   do processo e log exibido no prazo. A porta é confirmada pela readiness HTTP.
10. **Readiness e fluxo** (D11 e D12): login com claims do `V900`; consulta a 30 dias com
    `observacoes = smoke-<RUN_ID>`; 201, `jq -e`, UUID validado e `Location`; notificação;
    GraphQL.
11. **Códigos de saída:**

| Código | Situação |
|---|---|
| 0 | sucesso |
| 1 | verificação divergente |
| 2 | preflight |
| 3 | prazo esgotado |
| 4 | processo encerrado |
| 5 | limpeza incompleta |
| 130 e 143 | interrupção |

### D11. Readiness e espera assíncrona

**Função `aguardar`:** prazo absoluto, backoff de 0,25 s a 2 s, `kill -0` em todos os PIDs a cada
iteração (código 4) e, no prazo, código 3 com diagnóstico. Um teste estrutural reprova `sleep`
fora dela.

**Readiness:**

| Serviço | Condição |
|---|---|
| Agendamento | login 200 com token não vazio |
| Histórico | `query { __typename }` autenticada, sem `errors` |
| Notificação | disparo do lembrete com `lembretesEnviados == 0`, antes de a consulta existir |

A escolha da notificação se justifica: é o único endpoint existente, protegido, lê o banco, e a
asserção de zero torna a inocuidade verificável.

**GraphQL.** Só `NOT_FOUND` significa "ainda não"; qualquer outro código reprova. O `jq -e` final
confere id, paciente, médico, `AGENDADA`, observação e o mesmo instante.

### D12. Evidência da notificação: persistência mais log

Com `NOTIFICACAO_SENDER=log`:

1. **Persistência.** `json_agg` de `notificacao_enviada` pelo UUID validado, com exatamente um
   `CONSULTA_CRIADA`, canal `LOG`, destinatário do seed e conteúdo não vazio, além da
   `agenda_local` `AGENDADA`.
2. **Log.** `grep -F` da linha exata do `LogNotificationSender`, com o conteúdo lido do banco.

**Semântica.** O registro prova processamento confirmado; o log prova a invocação do adapter. A
garantia continua at-least-once: o log exige pelo menos uma ocorrência e o banco, exatamente uma.

### D13. Segurança, repetibilidade e limpeza do smoke

**Traps.** EXIT, INT (130) e TERM (143), com `on_exit` idempotente, tolerante a variáveis vazias.

**Pacote de diagnóstico.** Em falha, ou com `SMOKE_MANTER_LOGS=1`, os logs, as caudas dos
containers, as últimas respostas e as contagens são promovidos para
`${TMPDIR:-/tmp}/hospital-smoke-diagnostico-<RUN_ID>/`, com tokens e segredo mascarados e caminho
impresso. O pacote não é recurso de runtime.

**Runtime.** Sempre removido, com o caminho validado — prefixo `hospital-smoke.` e caminho
devolvido pelo `mktemp`.

**Processos.** Antes de cada sinal, o PID precisa constar de `jobs -p` e, onde houver `ps`, a
linha de comando precisa conter o `*-exec.jar` da execução. Encerramento: TERM, espera e KILL.
Nunca por nome.

**Containers e rede.** Remoção por nome exato, após conferir o **valor completo** do label, com
`docker rm -f -v` e `docker network rm`. Nunca `docker compose`.

**Órfãos.** Verificados por label com valor completo, rede, volumes, PIDs e diretório de runtime.
Qualquer resíduo leva ao código 5.

**Repetibilidade.** Execuções consecutivas são suportadas; simultâneas no mesmo checkout, não.

### D14. Execuções separadas

| Execução | Comando | Papel |
|---|---|---|
| Suíte dirigida | `-pl` dos módulos de código com `verify` e filtros, mais `-pl quality-gates -am test` | Ciclo curto que nunca aciona os gates globais. |
| Topologia | `mvn validate`, **sem `-q`** | O Reactor Summary é a evidência: sete projetos — raiz, cinco módulos de código e `quality-gates` —, com o `quality-gates` por último. |
| Versão sentinela | `mvn -Drevision=0.0.0-SENTINELA-M10 -DskipTests package`, **sem `-q`**, seguido de `mvn -q clean` | Scenario "Alteração de versão em um único ponto": **sete entradas** no Reactor Summary e **sete `.flattened-pom.xml`** — o da raiz e os de cada um dos seis módulos —, todos com a versão sentinela, sem `${revision}` em parte alguma, inclusive em `<dependencies>`, e com as dependências internas apontando para a versão sentinela. |
| Gate global | `mvn -q clean verify`, na raiz, sem filtro | Sessão, suítes, auditoria e cobertura. |
| Clone limpo isolado | `mvn -Dmaven.repo.local=<tmp>` com `test`, `package` e `verify`; smoke com `MAVEN_ARGS=-Dmaven.repo.local=<tmp>` | Origens da G2 registradas e `<tmp>/br/com/fiap/hospital` ausente. |
| Smoke | `scripts/smoke-test.sh`, duas vezes | Fluxo ponta a ponta e ausência de órfãos. |

O `-q` só é usado quando o Reactor Summary não é evidência. O clone é feito pelo Gabriel, ou por
ele autorizado. `mvn test` continua executando só `*Test`, sem container.

### D15. Provas de sensibilidade

**A. Negativos automatizados**, que rodam em todo gate global:

- XMLs de cobertura (D4);
- sessão, inclusive a execução só em `pluginManagement` (D4);
- árvores de relatórios, com cada classe de filtro isolada, configuração efetiva, omissão
  mascarada pela soma, `failsafe-summary.xml` e datas arredondadas (D5);
- inventários REST e GraphQL, inclusive `Float` e `Boolean` sem dimensão (D7 e D8);
- scripts sintéticos (D11 e D13);
- fontes de dublê e infraestrutura por módulo (D6);
- POMs sintéticos: dependências internas e externas, exclusões e sessão (D3 e D4).

**B. Mutações manuais: 12, cada uma com causa única, em dois protocolos.**

**B1. Mutações de arquivo (M1–M7).** Protocolo:

1. SHA-256 do alvo antes;
2. alterar somente o alvo;
3. rodar o menor gate;
4. registrar o vermelho;
5. restaurar byte a byte e conferir o SHA-256 idêntico;
6. rodar o gate de novo, agora verde.

| # | Mutação | Gate | Vermelho esperado |
|---|---|---|---|
| M1 | Remover do plano REST a entrada `status` do GET de listagem | `EntradasHostisIT` | combinação não executada |
| M2 | Acrescentar `@RequestParam String sintetico` opcional a um handler REST | `EntradasHostisIT` | entrada sem ataque |
| M3 | Acrescentar argumento opcional `String` a `consultasDoMedico` no schema e, se preciso, no resolver, restaurados juntos | `EntradasHostisGraphqlIT` | argumento sem ataque |
| M4 | Renomear `payload.medico.crm` no exemplar canônico | IT do produtor e ITs de consumo | divergência; exemplar na DLQ reportado como falha |
| M5 | Copiar o exemplar para `notificacao-service/src/test/resources` | G1 e G2 | segunda cópia; recurso duplicado |
| M6 | Retirar a dependência interna `shared-security` do `quality-gates` | `AgregacaoDoReactorTest` | módulo não agregado |
| M7 | Acrescentar `@MockBean RabbitTemplate` a um IT | `InfraestruturaRealTest` | dublê de infraestrutura |

**B2. Injeções de parâmetro ou de falha operacional (M8–M12).** Nenhum arquivo versionado é
alterado. Protocolo:

1. registrar o estado antes: SHA-256 dos arquivos versionados envolvidos, e a listagem de
   containers, redes e volumes com o label e dos containers do Compose;
2. injetar o parâmetro ou a falha;
3. registrar o vermelho, com código e mensagem;
4. comprovar a limpeza total: runtime removido, zero órfãos, e cópias e pacotes de diagnóstico
   removidos depois de registrados;
5. confirmar que nenhum arquivo versionado nem recurso persistente foi alterado: SHA-256
   idênticos e listagens iguais às de antes.

| # | Injeção | Alvo | Vermelho esperado |
|---|---|---|---|
| M8 | `test=CpfTest` informado ao verificador, sobre cópia completa dos relatórios verdes fora de `target`, com marcador válido | auditoria | só o filtro |
| M9 | Cópia do smoke, fora da árvore, com a expectativa `CONFIRMADA` | smoke | código 1, com pacote de diagnóstico |
| M10 | Matar o PID do histórico durante a espera GraphQL | smoke | código 4 |
| M11 | SIGINT durante a subida | smoke | código 130 |
| M12 | `docker pause` do RabbitMQ da execução durante a espera da notificação | smoke | código 3 |

As mutações manuais já provadas por negativos automatizados foram removidas.

### D16. Documentação

- **README:** gate e relatório; sessão e auditoria; recusa de filtros; smoke; fixture.
- **`docs/01` §3 e §10:** `quality-gates` e smoke sem Compose; o gate como regra efetiva.
- **`docs/03` §7:** a linha da fixture canônica.
- **`docs/05` §4 e §6:** gate global e smoke na release e na DoD.
- **ADR-003:** adendo datado. Não se cria ADR-008.
- **`openspec/config.yaml`:** atualizado no commit do módulo (task 5.2).
- **Sem alteração:** CHANGELOG e versão, que ficam para a release.

### D17. Hardening mínimo de produção descoberto pela varredura

A primeira execução completa da varredura de D7 produziu **5xx reproduzíveis nas duas passagens**.
São defeitos de produção, não do teste: o Requirement aprovado diz que nenhuma entrada hostil
produz 5xx. Tolerá-los como exceção conhecida foi **reprovado**, e abrir change separado também —
o defeito impede o fechamento legítimo do M10 e foi revelado pelo gate que o próprio M10 introduz.
O que segue é o escopo **mínimo** que torna verdadeira uma garantia já aprovada.

| # | Sintoma observado | Causa | Correção | Resposta |
|---|---|---|---|---|
| 1 | `GET /consultas?de=` ou `?ate=` com `OffsetDateTime.MAX`/`MIN` → `DataIntegrityViolationException`, `timestamp out of range` | O limite do intervalo chega cru ao SQL, e o `timestamptz` do PostgreSQL tem faixa menor que a de `OffsetDateTime` | Faixa temporal persistível definida em **um único ponto**, aplicada a `de` e `ate` antes do repositório | 400 |
| 2 | `PATCH /{id}/cancelar` com NUL no `motivo` → `MensagemInvalidaException: CAMPO em motivoCancelamento` | Dois defeitos juntos. O NUL é incompatível com os três destinos: a coluna `text` o recusa (`invalid byte sequence for encoding "UTF8": 0x00`), o `jsonb` do outbox também (`unsupported Unicode escape sequence`), e como parâmetro da consulta de credencial ele derruba o comando. E `Consulta.cancelar` validava `isBlank()` mas gravava `motivo.trim()`: como `trim()` remove tudo ≤ U+0020, um motivo só de controles passava na validação e ficava vazio — que o contrato recusa, já com a consulta cancelada | NUL recusado antes de mutar, persistir, publicar ou consultar credencial; e motivo vazio **depois de aparado** tratado como motivo vazio, pela regra preexistente. Controles diferentes de NUL não são recusados: a caracterização mostrou U+0001 e U+001F no meio do texto atravessando coluna, outbox e evento sem perda | 422 |
| 3 | `PATCH /{id}/cancelar` com chave JSON duplicada → `HttpMessageConversionException` | O tratador cobre `HttpMessageNotReadableException`; a detecção estrita de duplicata chega na outra forma e escapa para o catch-all | Tratar a falha de **leitura** da requisição também nessa forma concreta, para todos os corpos | 400 |
| 4 | `POST /consultas` com `duracaoMinutos = 2147483647` → 201, agenda do médico ocupada até o ano 6109 | O horizonte é verificado sobre o **início** do período; o fim não é verificado nem protegido de estouro | O horizonte de `Consulta.HORIZONTE_MAXIMO_MESES` passa a valer para o fim exclusivo, na criação e na alteração | 422 |

**Limites da correção**, para que ela não vire outra coisa:

- nenhum limite clínico arbitrário de duração é inventado: a regra reaproveita o horizonte
  existente, e nenhum número mágico novo é espalhado por controllers ou testes;
- `DataIntegrityViolationException` **não** é convertida genericamente em erro do cliente: violação
  de integridade não relacionada continua sendo falha real;
- **leitura e escrita são separadas explicitamente.** Não existe exceção concreta que cubra
  apenas a leitura nesta forma — a duplicata chega como o próprio `HttpMessageConversionException`,
  e não como `HttpMessageNotReadableException` —, então a opção adotada é o **handler do supertipo
  com guarda obrigatória**: `HttpMessageNotWritableException` é **relançada**, nunca convertida.
  Falha ao escrever a resposta é defeito do serviço, e respondê-la como 400 esconderia o bug atrás
  de um erro do cliente. A guarda é verificada por teste próprio, que fica vermelho se ela sair;
- o domínio de agendamento **não** passa a depender de `shared-contracts`: a política de caracteres
  é reproduzida explicitamente, com a razão registrada no código, para que uma entrada aceita pelo
  domínio não seja recusada depois na serialização do evento;
- a normalização de espaços é a preexistente — nulo ou em branco vira nulo, o resto é aparado —,
  com uma guarda: texto que fica vazio depois de aparado é tratado como branco. Controles nas bordas
  continuam aparados como os espaços sempre foram; isso fica caracterizado por regressão, e não é
  regra nova.

**Regressões dirigidas**, além das duas passagens da varredura: uma por correção, provando a
resposta, a ausência de efeito — consulta inalterada, nada em `outbox_evento`, nada publicado — e um
controle válido imediatamente dentro da fronteira adotada, para que a correção não recuse o
legítimo.

### D18. Hardening do histórico descoberto pela varredura GraphQL

A varredura de D8, com o modelo de prova corrigido, produziu **41 ocorrências por passagem**,
reproduzidas nas duas. São defeitos de produção do `historico-service`, da mesma natureza dos de
D17, e seguem o mesmo protocolo: artefato normativo primeiro, hardening mínimo depois, regressão
dirigida por família, e controle válido dentro de cada fronteira.

| # | Família | Onde | Sintoma | Ocorrências |
|---|---|---|---|---:|
| 1 | Identificador não-UUID | `consulta(id)`, `consultasDoPaciente(pacienteId)`, `consultasDoMedico(medicoId)` — malformado, vazio, número JSON, lista | `INTERNAL_ERROR` | 10 |
| 2 | Instante fora da faixa persistível | `filtro.de`, `filtro.ate` nas três consultas e `input.dataHora` na correção — bordas máxima e mínima | `INTERNAL_ERROR` (`timestamp out of range`) | 14 |
| 3 | Texto com caractere de controle | `input.justificativa`, `pacienteNome`, `medicoNome`, `especialidade`, `observacoes` — NUL nas bordas e no meio | `INTERNAL_ERROR` | 10 |
| 4 | Texto acima do limite da coluna | `input.pacienteNome`, `medicoNome`, `especialidade` com 10 000 caracteres | `INTERNAL_ERROR` | 3 |
| 5 | Literal incompatível como `ID` | documento de `consulta`, `consultasDoPaciente`, `consultasDoMedico` com `42` | `INTERNAL_ERROR` | 3 |
| 6 | Corpo HTTP `null` | fronteira `/graphql` | **HTTP 500** sem corpo GraphQL | 1 |

A família 5 tem a mesma causa da 1: o literal numérico é coagido a `ID`, e o identificador chega
ao resolver sem validação. As famílias 3 e 4 só aparecem na mutação porque é a única operação que
escreve. `justificativa` e `observacoes` não estouram por tamanho — são colunas de texto livre —,
mas estouram pelo NUL. A caracterização contra o PostgreSQL real mostrou que só o U+0000 é incompatível — `invalid byte sequence` na coluna e `unsupported Unicode escape sequence` no `jsonb` —, enquanto U+0001 e U+001F isolados atravessam correção, snapshot e trilha; a política recusa, portanto, exatamente o NUL.

**Correções, com o limite de cada uma**

- **Identificador:** validar o formato UUID **antes** do repositório, com `BAD_REQUEST`. O
  `ID` do GraphQL não distingue formato; quem distingue é o serviço.
- **Instante:** faixa temporal persistível definida num único ponto do histórico, aplicada aos
  limites do filtro e à data corrigida, com `BAD_REQUEST` antes de qualquer consulta ou escrita.
- **Texto:** política de caracteres representáveis aplicada aos cinco campos de texto da
  correção, antes de mutar o snapshot ou escrever a trilha.
- **Tamanho:** os limites vêm da V1 — `paciente_nome`, `medico_nome` e `especialidade` são
  `VARCHAR(255)`, contados em caracteres, como o PostgreSQL conta — por code point, e não por
  unidade UTF-16. Nenhum número novo é inventado, e `justificativa` e `observacoes`, que são
  texto livre, não ganham teto arbitrário.
- **Corpo HTTP:** corpo nulo ou estruturalmente inválido é recusado na fronteira, com 4xx
  sanitizado, antes de qualquer execução GraphQL.

**O que a correção não é.** `DataIntegrityViolationException` **não** vira `BAD_REQUEST`, e
nenhum catch-all classifica causa de banco como erro do cliente: o que se corrige é a validação
das causas previsíveis antes da persistência. Onde a exceção legítima chegar embrulhada pelo
GraphQL ou pelo Spring, o desembrulho é restrito, nominal e testado — nunca uma varredura de
causas que rebaixe falha real de servidor a erro de entrada.

**Semântica preservada.** Campo ausente continua significando "não corrigir"; nulo explícito
continua sendo aceito apenas em `observacoes`, onde limpa o registro. Nenhuma recusa altera
snapshot ou trilha, e cada regressão traz um controle válido imediatamente dentro da fronteira
adotada — sem ele, uma correção que recusasse tudo passaria igual.

## Alternativas rejeitadas

| Alternativa | Por que foi rejeitada |
|---|---|
| Copiar o JSON; helper Java pelo test-jar; `build-helper`; fixture em `src/main` | Divergência silenciosa; dependências não transitivas; origem invisível; dado de teste em produção. |
| Provar o test-jar só com o `.m2` do desenvolvedor | Artefato interno antigo mascara erro do reactor. |
| Somar percentuais de log ou média de módulos | Matemática errada. |
| `jacoco:check` no agregador ou por módulo | Verifica nada ou não mede o global. |
| Dependências internas do agregador em escopo `test` | Forneceriam só dados de execução. |
| Contar bibliotecas externas de teste como dependências agregadas | Confunde a topologia com o ferramental do próprio módulo. |
| Gate no último serviço funcional; ordem de `<modules>` | Dependências falsas; ordem textual não garante nada. |
| Sessão declarada só em `pluginManagement` | Não executa; o gate ficaria sem limpeza e sem marcador. |
| Frescor por data de modificação | Aceita `jacoco.exec` residual e reprova arquivo novo em sistema de arquivos de baixa resolução. |
| `tests > 0` em cada XML isolado | Reprova as famílias reais. |
| "Um caso por método" por soma de casos | Um parametrizado mascara um método omitido. |
| Correlação método → caso por nome | Depende de formato de nome não verificável nesta etapa. |
| Tratar `failsafe-summary.xml` como relatório de suíte | Falso vermelho por órfão. |
| Recusar só `test` e `it.test` | Deixa includes, excludes, arquivos, tags, motores, skip e tolerância abertos. |
| Checar filtros só por parâmetro | Propriedades e configuração em POM de módulo não chegam ao `quality-gates`. |
| Inventário só pelos includes | Não vê teste fora da convenção. |
| Guarda de dublê por texto; container declarado como prova | Não vê anotações, imports nem `@Bean`; declaração morta. |
| Exigir PostgreSQL de todos os módulos | Prova artificial no `shared-contracts` e no `shared-security`. |
| Mapear `Float` para INTEIRO | Dimensão não normativa; perde a falha fechada. |
| Tabelas manuais; varrer só o REST | Repete o defeito do M03; ignora o GraphQL. |
| Property-based testing agora | Sem ganho mensurável; E não determinístico. |
| Smoke com Compose e `spring-boot:run`, ou em JUnit | Maven concorrente, M12, ou não é o smoke em shell. |
| Actuator; Mailpit | M11; M12. |
| `sleep` fixo; `mktemp` antes do preflight; matar processo por nome | Falso verde; contradiz a spec; atinge processo alheio. |
| `tmpfs`; `repackage` sem classifier; `java -cp` | Permissões variam; troca o artefato do reactor; separador frágil. |
| Mesmo protocolo para mutação de arquivo e injeção operacional | Injeção não tem SHA a restaurar; exige outra prova de ausência de efeito. |
| Varreduras obrigatórias por `@TestFactory` | Uma fábrica que emite zero testes não deixa caso no `TEST-*.xml`: um falso verde que a auditoria por família não detecta. |
| Tolerar os 5xx de D17 como exceção conhecida no catálogo | Contraria o Requirement aprovado e esvazia justamente a garantia que a varredura existe para dar. |
| Corrigir os 5xx de D17 em change separado | O defeito impede o fechamento legítimo do M10 e foi revelado pelo gate que o M10 introduz; a varredura ficaria vermelha até lá. |
| Converter `DataIntegrityViolationException` em 4xx | Esconderia violação de integridade real, que é falha de servidor, atrás de uma resposta de erro do cliente. |
| Capturar o supertipo `HttpMessageConversionException` sem discriminar leitura de escrita | Transformaria falha de escrita da resposta e defeito de configuração em 400. O supertipo **é** tratado, mas com guarda que relança `HttpMessageNotWritableException` (D17). |
| Limite clínico arbitrário de duração em minutos | Número novo sem fonte normativa; o horizonte de agendamento já é a regra existente e suficiente. |

## Risks / Trade-offs

| Risco | Mitigação |
|---|---|
| Sétimo projeto do reactor contraria o texto promovido até a aprovação | Delta MODIFIED explícita; a task 5.1 bloqueia; `config.yaml` no mesmo commit. |
| Lista de filtros incompleta para a versão fixada | Conferência obrigatória contra a documentação dos goals na task 6.1; a recusa de suíte sem família cobre seleções por classe não enumeradas. |
| Fábrica dinâmica vazia não detectável por família | Limite declarado para testes futuros; nenhuma varredura, gate ou inventário obrigatório do M10 usa `@TestFactory`. |
| Atribuição Surefire de `@Nested` mudar | Testes modelados nas estruturas reais; versão fixada; o gate em árvore real acusa. |
| Análise sem atribuição completa de tipos | Fail-closed na ambiguidade, mais evidência runtime. |
| Remoção da sessão apagar além das evidências | Alvos restritos e verificados. |
| Varreduras lentas | Tempo registrado; acima de 2 minutos, o apply para, sem cortar variantes. |
| Correção de produção dentro de uma change de testes (D17) | Escopo fechado nas quatro causas observadas, autorizado explicitamente; Scenarios normativos próprios; regressões dirigidas com controle dentro da fronteira; nenhuma regra existente afrouxada. |
| Git Bash, CRLF e sinais | `MSYS_NO_PATHCONV`, `docker cp`, `.gitattributes`, `jobs -p`, evidência em Windows e Linux. |
| IDE resolver o test-jar de outro modo | O gate normativo é o Maven CLI. |
| Log do sender mudar no M11 | O smoke falha, em vez de passar. |
| Docker, rede e pull de imagens | Declarados no preflight e no README. |

## Migration Plan

1. A implementação segue `tasks.md`, na branch `feature/m10-add-integration-tests-coverage`. Todo
   o Git é do Gabriel.
2. Não há migration, dado persistente nem mudança de contrato ou schema.
3. **Rollback:** reverter o merge.
4. **Após o archive:** a spec de `operacao-do-ambiente` descreve a raiz, cinco módulos de código e
   um módulo técnico, e a release 1.0.0 exige gate global e smoke.
