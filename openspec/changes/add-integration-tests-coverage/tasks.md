> **Condições gerais.**
>
> - **Aprovação antes de tudo.** Nenhuma task começa antes da aprovação explícita do Gabriel
>   sobre proposal, design, specs e tasks. A aprovação inclui a delta MODIFIED de
>   `operacao-do-ambiente`: raiz, cinco módulos de código e um módulo técnico — sete projetos
>   Maven.
> - **Git.** A branch é `feature/m10-add-integration-tests-coverage`. Toda operação Git é do
>   Gabriel; o implementador entrega os comandos prontos.
> - **Nomes.** Nomes de classe são indicativos. O comportamento está nas specs, e o desenho, em
>   `design.md` (D1–D16).
> - **Checkpoints.** Em cada checkpoint, comando e saída são registrados no corpo do PR. O `-q` só
>   é usado quando o Reactor Summary não é evidência.

## 1. Fixture canônica publicada por test-jar (D1)

- [ ] 1.1 Fixar no POM raiz a versão explícita do `maven-jar-plugin` e acrescentar ao `shared-contracts` a execução `test-jar` com `<includes>` restrito a `evento-consulta.json`. Verificar com `mvn -q -pl shared-contracts package` e `jar tf shared-contracts/target/shared-contracts-*-tests.jar`: apenas `evento-consulta.json` e `META-INF/`, sem nenhuma `.class`.
- [ ] 1.2 Acrescentar aos três serviços a dependência `br.com.fiap.hospital:shared-contracts:${revision}`, com `type` `test-jar`, `classifier` `tests` e `scope` `test`. Verificar com `mvn -q -pl agendamento-service,notificacao-service,historico-service -am test-compile`.
- [ ] 1.3 Criar no `shared-contracts` o `FixtureCanonicaTest` (G1). Verificar que passa. Cobre "Exemplar canônico é envelope válido do contrato" e "Existe uma única cópia física do exemplar".
- [ ] 1.4 Criar em cada serviço o `FixtureCompartilhadaTest` (G2 e G4). Ele registra a origem resolvida. Verificar que passa. Cobre "Existe uma única cópia física do exemplar" e "Cópia ou sombreamento do exemplar em um serviço é recusado".
- [ ] 1.5 Executar `mvn -q test` na raiz e registrar que passa, que só `*Test` rodaram, que nenhum container foi iniciado e que a origem registrada pela G2 é o diretório de testes do `shared-contracts`.

**Checkpoint 1:** `mvn -q -pl shared-contracts,agendamento-service,notificacao-service,historico-service -am verify -Dtest='FixtureCanonicaTest,FixtureCompartilhadaTest' -Dsurefire.failIfNoSpecifiedTests=false -DskipITs` verde.

## 2. Compatibilidade do produtor com a fixture (D2)

- [ ] 2.1 Criar o comparador de árvores JSON com a tabela de normalização do D2, junto com o `ComparacaoComFixtureTest`. Ele prova que cada caso abaixo **falha**:
  - chave a mais e chave a menos;
  - tipo divergente;
  - valor divergente, inclusive o texto de `dataHora`;
  - `aggregateId` diferente de `consultaId`;
  - `occurredAt` sem `Z`;
  - UUID não canônico;
  - correlação vazia;
  - `alteracoes` presente.

  Verificar que passa. Cobre "Campos gerados são validados, e não ignorados".
- [ ] 2.2 Criar `CompatibilidadeDoProdutorComFixtureIT` sobre `M05RabbitBase`, com a G3, conforme o D2. Verificar que passa. Cobre "Produtor emite envelope compatível com o exemplar".

## 3. Consumo da fixture pelos dois consumidores (D2)

- [ ] 3.1 Criar `ConsumoDaFixtureNotificacaoIT` sobre `NotificacaoITBase`, com a G3: bytes exatos, headers do exemplar e espera por marca **ou** DLQ, sendo a DLQ falha explícita. Verificar que passa. Cobre "Notificação processa os bytes do exemplar pelo broker real".
- [ ] 3.2 Criar `ConsumoDaFixtureHistoricoIT` sobre `HistoricoITBase`, com a G3 e a comparação do payload como `jsonb`. Verificar que passa. Cobre "Histórico processa os bytes do exemplar pelo broker real".
- [ ] 3.3 Confirmar pelo diff que `ConsumoNotificacaoRabbitMqIT`, `ConsumoHistoricoRabbitMqIT` e as bases não perderam caso nem asserção.

**Checkpoint 2:** `mvn -q -pl shared-contracts,agendamento-service,notificacao-service,historico-service -am verify -Dtest=ComparacaoComFixtureTest -Dit.test='CompatibilidadeDoProdutorComFixtureIT,ConsumoDaFixture*IT' -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false` verde.

## 4. Superfícies hostis: REST, GraphQL e endpoint interno (D7, D8 e D9)

- [ ] 4.1 Antes de alterar o `EntradasHostisIT`, registrar no PR a linha de base: todos os valores hostis, casos e métodos atuais.
- [ ] 4.2 REST: implementar a descoberta por `RequestMappingHandlerMapping` e a tabela de classificação bidirecional (D7). Verificar por teste que um endpoint sem classificação e uma classificação sem endpoint reprovam.
- [ ] 4.3 REST: implementar a extração de entradas e o mapeamento tipo → dimensão, e criar o `InventarioDaSuperficieTest`, unitário, com negativos automatizados:
  - tipo sem mapeamento;
  - entrada sem ataque;
  - ataque órfão;
  - dimensão vazia;
  - inventário vazio;
  - dimensão divergente da declarada.

  Verificar que passa.
- [ ] 4.4 REST: implementar o catálogo com a asserção dos mínimos do D7, e um teste que prova que retirar um mínimo reprova. Verificar que os valores da linha de base de 4.1 estão contidos no catálogo.
- [ ] 4.5 REST: implementar o plano de ataques, com controles, alvo novo por combinação que muda estado, horários únicos e cliente de bytes exatos. Verificar que cada controle responde com o sucesso da operação. Cobre "Controle válido prova que o ataque alcança a camada pretendida".
- [ ] 4.6 REST: implementar as asserções por ataque — sem 5xx, sem vazamento, Problem Detail na recusa da aplicação e recusa do conector só nas variantes marcadas. Verificar que passa. Cobre "Nenhuma entrada hostil produz 5xx nem vaza detalhe interno" e "Recusa de entrada hostil segue Problem Detail".
- [ ] 4.7 REST: implementar a varredura como **cinco métodos `@Test` ordinários e ordenados**, sem fábrica dinâmica (D7): controles, passagem 1, passagem 2, controles finais e comparação `E = D × V`, com falhas agregadas e contagens por combinação. Verificar que passa e que o `TEST-*.xml` da suíte registra os cinco casos. Cobre:
  - "Cada entrada compatível é atacada em todas as variantes da sua dimensão";
  - "Endpoint ou entrada nova sem ataque é recusada";
  - "Ataque órfão ou combinação não executada é recusada";
  - "Inventário vazio ou dimensão sem combinação é recusado";
  - "Repetição da varredura não degrada o serviço".
- [ ] 4.8 REST: preservar sem alterar expectativa `credenciaisHostis` (19 casos), `aVarreduraAlcancaODominio` e `corridaHttpProduzUmaConsultaUmEventoE409Sem5xx`. Verificar que existem e passam.
- [ ] 4.9 GraphQL: implementar a descoberta a partir de `GraphQlSource.schema()` — operações, argumentos e campos de entrada, com obrigatoriedade — e o mapeamento do D8, em que **somente `Int`** vai para INTEIRO. Criar o `InventarioGraphqlTest`, unitário, com negativos automatizados sobre schemas sintéticos:
  - argumento `Float` reprova por falta de dimensão normativa;
  - argumento `Boolean` reprova pelo mesmo motivo;
  - escalar customizado sem dimensão reprova;
  - operação, argumento ou campo sem ataque;
  - ataque órfão;
  - dimensão presente sem combinação;
  - inventário vazio;
  - um argumento `Int` sintético passa a exigir ataques de INTEIRO.

  Verificar que passa.
- [ ] 4.10 GraphQL: implementar o `EntradasHostisGraphqlIT` sobre `GraphqlITBase`, com:
  - catálogo e mínimos do D8: variáveis, documento, corpo HTTP e objetos de entrada;
  - plano com controles por perfil autorizado — MEDICO, e PACIENTE em `minhasConsultas`;
  - alvo novo por combinação na mutação;
  - asserções: sem 5xx, sem `INTERNAL_ERROR`, só `BAD_REQUEST`, `NOT_FOUND` ou `FORBIDDEN`, fronteira HTTP em 4xx, sem vazamento, e entrada recusada sem efeito em snapshot ou trilha;
  - cinco métodos `@Test` ordinários e ordenados, sem fábrica dinâmica (D8): controles, passagem 1, passagem 2, controles finais e `E = D × V`, com falhas agregadas e contagens por combinação.

  Verificar que passa, registrar as contagens e confirmar que o `TEST-*.xml` da suíte registra os cinco casos. Cobre os 8 Scenarios de `historico-de-consultas`:
  - "Nenhuma entrada hostil GraphQL produz 5xx, erro interno ou vazamento";
  - "Recusa de entrada hostil GraphQL usa código estável e não persiste efeito";
  - "Cada argumento e campo de entrada é atacado em todas as variantes da dimensão";
  - "Operação, argumento ou campo de entrada novo sem ataque é recusado";
  - "Ataque órfão ou combinação não executada é recusada na superfície GraphQL";
  - "Inventário GraphQL vazio ou dimensão sem combinação é recusado";
  - "Controle válido alcança o resolver";
  - "Repetição da varredura GraphQL não degrada o serviço".
- [ ] 4.11 GraphQL: confirmar pelo diff e pela execução que `MatrizDeAutorizacaoGraphqlIT` (15 células), `CoberturaDeAutorizacaoGraphqlTest`, `ErrosGraphqlIT`, `SegurancaGraphqlIT` e `EntradasHostisHistoricoIT` estão intactos e verdes.
- [ ] 4.12 Notificação: criar o `SuperficieHttpNotificacaoIT` sobre `NotificacaoITBase` (D9). Ele exige exatamente `POST /internal/lembretes/executar`, classificado como sem entrada de negócio, e reprova parâmetro ou handler novo. Criar também um negativo com handler sintético. Confirmar que `MatrizDeAutorizacaoNotificacaoIT` (3 células) e `DisparoManualIT` estão intactos e verdes.
- [ ] 4.13 Medir e registrar o tempo do `EntradasHostisIT` e do `EntradasHostisGraphqlIT`. Se algum passar de 2 minutos, **parar** e reportar ao Gabriel, sem cortar variantes.
- [ ] 4.14 Registrar no PR a conferência determinística dos sete achados do M03:
  - PR7 (`discussion_r3928689668`): coberto por CORPO;
  - PR8 (`discussion_r3929008553`): coberto por CORPO e TEXTO de `motivo`;
  - PR8 (`discussion_r3929008559`): coberto pela asserção de vazamento, mais UUID, ENUM e DATA de consulta;
  - PR9 (`discussion_r3929248930`): coberto por UUID inexistente no corpo;
  - PR9 (`discussion_r3929248934`): coberto por INTEIRO `pagina` × `tamanho`;
  - PR10 (`discussion_r3929432417`): coberto por DATA;
  - PR11 (`discussion_r3929719540`): fora da varredura hostil, preservado por `ApiDocumentadaIT`.

  Para cada um, citar a variante ou o teste que o cobre.

**Checkpoint 3:**

```bash
mvn -q -pl agendamento-service,notificacao-service,historico-service -am verify \
  -Dtest='InventarioDaSuperficieTest,InventarioGraphqlTest' \
  -Dit.test='EntradasHostisIT,EntradasHostisGraphqlIT,SuperficieHttpNotificacaoIT,MatrizDeAutorizacao*IT,ErrosGraphqlIT' \
  -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false
```

Verde, com as contagens registradas.

## 5. Módulo técnico, sessão de verificação e gate de cobertura (D3 e D4)

- [ ] 5.1 Confirmar no PR a aprovação do Gabriel para a delta MODIFIED "Build reprodutível do monorepo". Sem ela, parar.
- [ ] 5.2 Criar `quality-gates/pom.xml` com parent no POM raiz e `packaging jar`, com:
  - exatamente **cinco dependências internas do reactor** — `shared-contracts`, `shared-security` e os três serviços —, todas em escopo `compile` padrão;
  - bibliotecas externas dos próprios testes (JUnit, AssertJ) somente em escopo `test`, fora dessa contagem.

  Acrescentar o módulo por último em `<modules>` e fixar a versão do `exec-maven-plugin`. **No mesmo commit**, o implementador (Claude Code) atualiza o bloco MÓDULOS de `openspec/config.yaml` para cinco módulos de código mais `quality-gates` (técnico), e o Gabriel revisa e commita. Verificar:
  - com `mvn validate` **sem `-q`**, que o Reactor Summary lista **sete projetos** — raiz, cinco módulos de código e `quality-gates` —, com o `quality-gates` por último;
  - pelo diff, que `config.yaml` e POMs mudaram no mesmo conjunto.
- [ ] 5.3 Executar `mvn -Drevision=0.0.0-SENTINELA-M10 -DskipTests package` na raiz, **sem `-q`**. Verificar:
  - **sete entradas** no Reactor Summary;
  - **sete `.flattened-pom.xml`** — o da raiz e o de cada um dos seis módulos —, todos com `0.0.0-SENTINELA-M10` e sem o literal `${revision}`.

  Depois, executar `mvn -q clean` e confirmar que não restou artefato sentinela. Cobre "Alteração de versão em um único ponto".
- [ ] 5.4 Acrescentar à gestão do `spring-boot-maven-plugin` a execução `repackage` com classifier `exec`. Verificar que existe exatamente um `*-exec.jar` por serviço e que `jar tf` do artefato principal não contém `BOOT-INF/`. Cobre "Serviços empacotados como aplicações executáveis"; o `java -jar` é provado em 7.12.
- [ ] 5.5 Acrescentar a sessão de verificação (D4): o `maven-antrun-plugin`, com versão fixada, declarado em **`<build><plugins>` do POM raiz** — não apenas em `<pluginManagement>` —, com uma `<execution>` ativa em `initialize` e `<inherited>false</inherited>`. A execução remove somente os quatro alvos do D4 e grava o marcador com `${maven.build.timestamp}` em milissegundos. Criar o `SessaoDeVerificacaoTest` estrutural, com negativos automatizados sobre POMs sintéticos:
  - execução **presente só em `pluginManagement`**;
  - `inherited` ausente ou `true`;
  - fase errada;
  - alvo de remoção faltando;
  - execução sem goal.

  Verificar:
  - o `mvn -q verify` **sem** `clean`, rodado duas vezes, registra no log a remoção das evidências anteriores e marcadores distintos;
  - o teste passa.

  Cobre "Evidência residual é descartada antes dos testes".
- [ ] 5.6 Criar o `AgregacaoDoReactorTest` e o `ExclusoesDeCoberturaTest`, com negativos automatizados sobre POMs sintéticos. O `AgregacaoDoReactorTest` distingue dependências **internas** — `groupId` do projeto e `artifactId` entre os `<modules>` — de **externas**. Ele exige as cinco internas em `compile`; recusa `test`, `runtime` e `provided` **apenas nelas**; recusa dependência interna faltando, sobrando ou apontando para o próprio módulo; aceita bibliotecas externas em `test` e recusa as externas fora de `test`; e recusa módulo de código dependendo do `quality-gates` e `@SpringBootApplication`. O `ExclusoesDeCoberturaTest` admite como única exclusão `**/*Application.class`. Verificar que passam. Cobre "Módulo de código fora da agregação é recusado", "Módulo técnico permanece estritamente técnico" e "Exclusão adicional de cobertura é recusada".
- [ ] 5.7 Implementar o `VerificadorDeCobertura`, só com JDK. Ele confere o marcador de sessão, `jacoco.exec` não vazio nos cinco módulos, LINE, escopos, comparação inteira, soma contra total e parser seguro offline, e **não usa data de modificação**. Criar o `VerificadorDeCoberturaTest` com todos os negativos do D4, inclusive datas arredondadas dois segundos para antes do início. Verificar que passa. Cobre:
  - "Cobertura acima dos pisos é aceita";
  - "Escopo exatamente no piso é aceito";
  - "Cobertura global abaixo do piso falha o build";
  - "Subárvore domain abaixo do piso falha mesmo com cobertura global suficiente";
  - "Subárvore application abaixo do piso falha mesmo com cobertura global suficiente";
  - "Percentual é calculado por soma, e não por média";
  - "Relatório ausente, vazio ou ilegível falha fechado";
  - "Módulo, pacote, contador ou dados de execução ausentes falham fechado";
  - "Evidência de outra sessão é recusada";
  - "Aceitação não depende da resolução do relógio do sistema de arquivos".
- [ ] 5.8 Ligar na fase `verify` do `quality-gates` o `relatorio-agregado` e o `gate-de-cobertura`. Verificar que `mvn -q clean verify` produz `jacoco.xml` e `index.html` agregados, com os cinco grupos, e imprime a tabela de escopos.

## 6. Auditoria por famílias e infraestrutura real (D5 e D6)

- [ ] 6.1 Implementar a `AuditoriaDeExecucao`, só com JDK e a Compiler Tree API, conforme o D5:
  - inventário de **todos** os fontes de teste, com teste fora da convenção reprovando;
  - **somente `TEST-*.xml`** nas famílias; `failsafe-summary.xml` conferido à parte e **nunca** tratado como órfão; demais arquivos dos plugins ignorados; `TEST-*.xml` ilegível, órfão ou ambíguo reprovando;
  - famílias com externo zero legítimo e total positivo;
  - **proteção contra omissão:** evidência por suíte e família e recusa dos mecanismos enumerados, **sem** afirmar execução individual por método;
  - sessão; desabilitação;
  - recusa de **todas** as propriedades de seleção, exclusão, omissão e tolerância da tabela do D5, com a ampliação por dependência pela user property `dependenciesToScan` e pelo elemento `<dependenciesToScan>` na inspeção dos plugins.

  Antes de concluir, conferir essa tabela contra a documentação dos goals `surefire:test`, `failsafe:integration-test` e `failsafe:verify` da versão 3.2.5 fixada, acrescentando toda outra propriedade capaz de selecionar ou excluir casos, e registrar a conferência no PR. Implementar a verificação da **configuração efetiva** em todos os POMs e perfis:
  - includes normativos exatos;
  - nenhum `<excludes>` nem elemento de seleção, omissão ou tolerância;
  - nenhuma propriedade de filtro em `<properties>`;
  - nenhum `junit-platform.properties` nem `configurationParameters` de filtro.

  Criar o `AuditoriaDeExecucaoTest` com as árvores sintéticas modeladas em `ConsultaRepositoryAdapterIT` e `ConsultaRepositoryContractTest` com três `@Nested`, `ConsultaRepositoryFakeTest`, `GraphiqlPorProfileIT`, `JwtServiceTest`, uma classe parametrizada e uma `@TestFactory`, com `failsafe-summary.xml` e `*.txt` presentes. Ele inclui:
  - todos os negativos do D5;
  - **uma classe de filtro por vez, com todos os relatórios presentes**, inclusive `-DdependenciesToScan=...`;
  - um negativo por configuração efetiva;
  - o negativo de **omissão mascarada pela soma**: parametrizado com cinco casos e outro método omitido por `-Dtest=Classe#metodo` e por `@Disabled`.

  Verificar que passa. Cobre:
  - "Suíte omitida é recusada";
  - "Filtro de seleção, omissão ou tolerância é recusado";
  - "Módulo sem relatórios é recusado";
  - "Teste falho, com erro ou pulado é recusado";
  - "Família sem casos executados é recusada";
  - "Relatório de outra sessão é recusado pela auditoria";
  - "Relatório ilegível é recusado";
  - "Abstratas, bases, contratos herdados e auxiliares não contam como omissões";
  - "Desabilitação declarada no código de teste é recusada";
  - "Teste concreto fora da convenção é recusado";
  - "Relatório órfão ou atribuição ambígua é recusada".
- [ ] 6.2 Ligar a `auditoria-de-execucao` **entre** o `relatorio-agregado` e o `gate-de-cobertura`. Verificar com `mvn -q clean verify` que a auditoria aceita a árvore real — inclusive as famílias de `ConsultaRepositoryAdapterIT`, `GraphiqlPorProfileIT` e `JwtServiceTest`, e o `failsafe-summary.xml` de cada módulo — e imprime suítes, relatórios e casos por módulo. Cobre "Execução integral e verde é aceita".
- [ ] 6.3 Criar o `InfraestruturaRealTest` (D6) com a Compiler Tree API e resolução por imports, com negativos e aceitos sintéticos:
  - recusados: `@Mock`, `@MockBean`, `@MockitoBean`, `@TestBean`, `Mockito.mock` com import estático, `@Bean DataSource` em configuração de teste, H2, `replace = ANY`, import sob demanda;
  - aceitos: `@SpyBean`, decorador de porta, `@MockBean MailSender`.

  Verificar que passa sobre o reactor real. Cobre "Dublê de infraestrutura em teste de integração é recusado", "Banco em memória, broker embarcado ou substituição automática é recusado" e "Espião, decorador e dublê de porta externa continuam permitidos".
- [ ] 6.4 Criar a evidência runtime **somente da infraestrutura que cada módulo usa**, nas bases existentes:

  | IT | Base | Prova |
  |---|---|---|
  | `InfraestruturaRealAgendamentoIT` | `M05RabbitBase` | PostgreSQL 16 e RabbitMQ 3.13 |
  | `InfraestruturaRealNotificacaoIT` | `NotificacaoITBase` | PostgreSQL 16 e RabbitMQ 3.13 |
  | `InfraestruturaRealHistoricoIT` | `HistoricoITBase` | PostgreSQL 16 e RabbitMQ 3.13 |
  | `InfraestruturaRealContratosIT` | `RabbitITBase` | somente RabbitMQ 3.13 |

  O `shared-security` não ganha IT de evidência, porque não tem IT de infraestrutura. O `InfraestruturaRealTest` passa a derivar, pelos containers declarados em cada módulo, a infraestrutura exigida, com negativos sintéticos:
  - módulo com `PostgreSQLContainer` sem prova de PostgreSQL;
  - prova de infraestrutura não usada;
  - módulo só com RabbitMQ e prova só de RabbitMQ, aceito.

  Criar também o negativo do comparador de evidência, com endereço divergente. Verificar que passam. Cobre "Cada módulo comprova a infraestrutura real que usa" e "Container declarado sem uso efetivo não satisfaz a verificação".

**Checkpoint 4:** `mvn -q clean verify` verde na raiz, sem filtro, com a tabela de cobertura, as contagens da auditoria e a saída da guarda registradas.

## 7. Smoke e ciclo de vida (D10 a D13)

- [ ] 7.1 Criar `.gitattributes` com `*.sh text eol=lf`. O Gabriel confere com `git check-attr eol scripts/smoke-test.sh`.
- [ ] 7.2 Criar `scripts/smoke-test.sh` na ordem do D10: modo estrito; variáveis vazias e traps; **preflight completo antes** de `RUN_ID`, `mktemp`, rede, containers e processos; códigos 0 a 5, 130 e 143. Verificar com `PATH` sem `jq`:
  - código 2;
  - nenhum diretório `hospital-smoke.*` novo;
  - nenhum container ou rede com o label.

  Cobre "Pré-requisito ausente é recusado antes de criar recursos".
- [ ] 7.3 Implementar o build único e a localização de exatamente um `*-exec.jar` por serviço.
- [ ] 7.4 Implementar a infraestrutura efêmera do D10, com o registro dos volumes e a readiness de Postgres e RabbitMQ.
- [ ] 7.5 Implementar a subida dos serviços com as variáveis do D10 e o parser de porta: LF e CRLF; exatamente uma porta válida por processo; falha imediata na morte do processo; log exibido no prazo; confirmação pela readiness. Verificar com logs sintéticos em LF, CRLF, sem a linha e com duas portas distintas. Cobre "Porta efetiva é identificada sem ambiguidade".
- [ ] 7.6 Implementar a função `aguardar` e a readiness dos três serviços (D11).
- [ ] 7.7 Implementar o fluxo: login com claims do `V900`; criação da consulta com `dataHora` calculada por `jq` e `observacoes = smoke-<RUN_ID>`; 201, `jq -e`, UUID validado e `Location`.
- [ ] 7.8 Implementar a evidência da notificação (D12), com persistência e linha exata do log. Cobre "Notificação é comprovada pela execução corrente".
- [ ] 7.9 Implementar a consulta GraphQL por espera condicional, tolerando só `NOT_FOUND`. Verificar alimentando a função de validação do script, carregado por `source` sem executar o fluxo, com uma resposta HTTP 200 com `errors` `BAD_REQUEST`: código 1. Cobre "Erro GraphQL com status de sucesso é falha".
- [ ] 7.10 Implementar a limpeza do D13:
  - pacote de diagnóstico mascarado;
  - runtime sempre removido, com o caminho validado;
  - conferência de PID por `jobs -p` e pela linha de comando;
  - containers e rede por nome exato, após conferir o valor completo do label;
  - verificação de órfãos com código 5.

  Verificar com um PID alheio injetado na lista, que não é sinalizado. Cobre "Somente processos da própria execução são encerrados".
- [ ] 7.11 Criar no `quality-gates` o `RoteiroDeSmokeTest`, com negativos sobre scripts sintéticos: sem modo estrito, `sleep` fora de `aguardar`, `docker compose`, `pkill` ou `killall`, `mktemp` antes do preflight. Verificar que passa sobre o script real. Cobre "Espera assíncrona é condicional".
- [ ] 7.12 Executar o smoke duas vezes seguidas, com o Compose local no ar. Registrar:
  - códigos 0;
  - para cada `RUN_ID`, `docker ps -a --filter label=br.com.fiap.hospital.smoke=<RUN_ID>` vazio, e o mesmo para rede e volumes;
  - ausência de diretório de runtime;
  - `hospital-postgres` e `hospital-rabbitmq` intactos e `healthy`.

  Cobre "Fluxo completo bem-sucedido termina com código zero", "Execuções consecutivas são independentes", "Recursos alheios permanecem intactos" e "Recursos de runtime são removidos em sucesso, falha e interrupção" (sucesso).

**Checkpoint 5:** as duas execuções de 7.12, com as saídas integrais anexadas ao PR.

## 8. Mutações manuais de sensibilidade (D15, parte B)

Os negativos automatizados da parte A estão nas tasks 2.1, 4.3, 4.4, 4.9, 4.12, 5.5, 5.6, 5.7, 6.1, 6.3, 6.4, 7.5 e 7.11, e rodam em todo gate global. As 12 mutações abaixo têm **uma única causa** cada, e seguem dois protocolos.

**Protocolo B1 — mutações de arquivo (8.1 a 8.7):**

1. SHA-256 do alvo antes;
2. alterar somente o alvo;
3. rodar o menor gate;
4. registrar o vermelho;
5. restaurar byte a byte e conferir o SHA-256 idêntico;
6. rodar o gate de novo, agora verde.

**Protocolo B2 — injeções de parâmetro ou falha operacional (8.8 a 8.12):**

1. registrar o estado antes: SHA-256 dos arquivos versionados envolvidos, e a listagem de containers, redes e volumes com o label e dos containers do Compose;
2. injetar;
3. registrar o vermelho, com código e mensagem;
4. comprovar a limpeza total: runtime removido, zero órfãos, e cópias e pacotes de diagnóstico removidos depois de registrados;
5. confirmar que nenhum arquivo versionado nem recurso persistente foi alterado.

- [ ] 8.1 M1 (B1): remover do plano REST a entrada `status` do GET de listagem. O `EntradasHostisIT` falha por combinação não executada.
- [ ] 8.2 M2 (B1): acrescentar `@RequestParam String sintetico` opcional a um handler REST. O `EntradasHostisIT` falha por entrada sem ataque.
- [ ] 8.3 M3 (B1): acrescentar um argumento opcional `String` a `consultasDoMedico` no schema, e o parâmetro no resolver se o mapeamento exigir, restaurados juntos. O `EntradasHostisGraphqlIT` falha por argumento sem ataque.
- [ ] 8.4 M4 (B1): renomear `payload.medico.crm` no exemplar canônico. O `CompatibilidadeDoProdutorComFixtureIT` falha por divergência de chaves, e os `ConsumoDaFixture*IT`, com o exemplar na DLQ reportado como falha. Cobre "Divergência entre exemplar e produtor ou consumidor é detectada".
- [ ] 8.5 M5 (B1): copiar o exemplar para `notificacao-service/src/test/resources`. O `FixtureCanonicaTest` falha por segunda cópia, e o `FixtureCompartilhadaTest` da notificação, por recurso duplicado.
- [ ] 8.6 M6 (B1): retirar a dependência interna `shared-security` do `quality-gates`. O `AgregacaoDoReactorTest` falha por módulo não agregado.
- [ ] 8.7 M7 (B1): acrescentar `@MockBean RabbitTemplate` a um IT. O `InfraestruturaRealTest` falha.
- [ ] 8.8 M8 (B2): informar `test=CpfTest` ao verificador de auditoria, sobre cópia completa dos relatórios verdes fora de `target`, com marcador válido. Ele falha **somente** pelo filtro.
- [ ] 8.9 M9 (B2): rodar uma cópia do smoke, fora da árvore, com a expectativa de status `CONFIRMADA`. O código é 1 e o pacote de diagnóstico é informado. Cobre "Verificação divergente termina com código não zero".
- [ ] 8.10 M10 (B2): matar o PID do histórico durante a espera GraphQL. O código é 4, com o log do histórico. Cobre "Processo encerrado durante a execução é falha imediata".
- [ ] 8.11 M11 (B2): SIGINT durante a subida dos serviços. O código é 130, com limpeza completa. Cobre "Recursos de runtime são removidos em sucesso, falha e interrupção" (interrupção).
- [ ] 8.12 M12 (B2): `docker pause` do RabbitMQ da execução durante a espera da notificação, com os processos vivos. O código é 3, com etapa, última resposta e logs. Cobre "Prazo esgotado é falha" e, junto com 8.9, a remoção em falha.
- [ ] 8.13 Consolidar no PR duas tabelas, com colunas aplicáveis ao tipo:
  - **B1 (M1–M7):** mutação | alvo | SHA-256 antes | gate | vermelho observado | SHA-256 restaurado (idêntico) | gate verde;
  - **B2 (M8–M12):** injeção | alvo | vermelho observado (código e mensagem) | limpeza total (runtime, órfãos, cópias e pacotes removidos) | arquivos versionados inalterados (SHA-256 antes e depois) | recursos persistentes inalterados (listagens antes e depois).

## 9. Suíte dirigida do M10

- [ ] 9.1 Executar e registrar o comando abaixo, verde:

  ```bash
  mvn -q -pl shared-contracts,agendamento-service,notificacao-service,historico-service -am verify \
    -Dtest='FixtureCanonicaTest,FixtureCompartilhadaTest,ComparacaoComFixtureTest,InventarioDaSuperficieTest,InventarioGraphqlTest' \
    -Dit.test='CompatibilidadeDoProdutorComFixtureIT,ConsumoDaFixture*IT,EntradasHostisIT,EntradasHostisGraphqlIT,SuperficieHttpNotificacaoIT,InfraestruturaReal*IT' \
    -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false
  ```
- [ ] 9.2 Executar e registrar o comando abaixo, verde. Ele para em `test` e não aciona os gates globais:

  ```bash
  mvn -q -pl quality-gates -am test \
    -Dtest='SessaoDeVerificacaoTest,AgregacaoDoReactorTest,ExclusoesDeCoberturaTest,VerificadorDeCoberturaTest,AuditoriaDeExecucaoTest,InfraestruturaRealTest,RoteiroDeSmokeTest' \
    -Dsurefire.failIfNoSpecifiedTests=false
  ```

## 10. Gate global

- [ ] 10.1 Executar `mvn -q clean verify` na raiz, **sem** `-Dtest`, `-Dit.test` ou filtro equivalente. Registrar:
  - build verde;
  - total de casos e zero ignorados;
  - marcador de sessão;
  - auditoria por módulo;
  - guarda de infraestrutura;
  - tabela do gate de cobertura.

  Cobre "Build completo a partir de um clone limpo" na árvore de trabalho e "Execução integral e verde é aceita".

## 11. Cobertura agregada e pisos

- [ ] 11.1 Registrar as linhas cobertas, as perdidas e o percentual dos escopos global, `domain` e `application`, com os pisos de 85%, 90% e 90%, a partir da tabela e do `index.html` agregado.
- [ ] 11.2 Revisar cada teste criado e registrar que todos verificam comportamento ou estrutura: nenhum teste de getter, construtor trivial ou `toString`, e nenhuma exclusão nova. Se algum piso não for atendido, **parar** e reportar ao Gabriel com proposta de testes comportamentais. O piso não é rebaixado e não se exclui código.

## 12. Clone limpo com repositório Maven isolado (D14)

- [ ] 12.1 O Gabriel, ou o implementador com autorização explícita dele para clonagem somente leitura, clona a feature branch num diretório temporário e executa, com `-Dmaven.repo.local=<repo-temporario-vazio>` e **sem `install`**, nesta ordem: `mvn -q test`, `mvn -q package` e `mvn -q clean verify`. Registrar:
  - a origem da G2 em `test` (diretório de testes) e em `package`/`verify` (jar anexado);
  - a execução verde;
  - a ausência de `<repo-temporario>/br/com/fiap/hospital`.

  Cobre "Build completo a partir de um clone limpo".
- [ ] 12.2 No mesmo clone, executar `MAVEN_ARGS=-Dmaven.repo.local=<repo-temporario> scripts/smoke-test.sh` e registrar código 0 e zero órfãos.

## 13. Documentação pertinente (D16)

- [ ] 13.1 Atualizar o README com:
  - gate, pisos e relatório agregado;
  - sessão de verificação, auditoria e recusa de filtros;
  - smoke: pré-requisitos, uso, códigos, pacote de diagnóstico, o que cria e remove, e execuções simultâneas não suportadas;
  - fixture por test-jar.

  Verificar que os comandos citados são os executados em 9, 10 e 7.12.
- [ ] 13.2 Atualizar `docs/01-arquitetura.md`: §3 com o `quality-gates` e o smoke sem Compose; §10 com o gate como regra efetiva.
- [ ] 13.3 Acrescentar a `docs/03-contrato-de-eventos.md` §7 a linha "Fixture canônica exercitada por produtor e consumidores | M10".
- [ ] 13.4 Atualizar `docs/05-fluxo-de-trabalho.md` §4 e §6 com `mvn -q clean verify` e `scripts/smoke-test.sh` na release e na DoD.
- [ ] 13.5 Acrescentar ao ADR-003 um adendo datado registrando o módulo técnico — sexto módulo filho e sétimo projeto do reactor — e a razão da escolha.
- [ ] 13.6 Registrar no corpo do PR que `CHANGELOG.md` e versão ficam para o fechamento da release 1.0.0. O `openspec/config.yaml` já foi atualizado na task 5.2.

## 14. Verificação final dos artefatos

- [ ] 14.1 Montar no PR a matriz Scenario → evidência para **todos os 71 Scenarios**: 47 em `operacao-do-ambiente`, 8 em `mensageria-de-eventos`, 8 em `agendamento-de-consultas` e 8 em `historico-de-consultas`. A evidência é a classe e o método de teste, ou, no smoke, o comando registrado. Verificar que nenhum Scenario fica sem evidência.
- [ ] 14.2 Conferir pelo diff:
  - nada de M11 ou M12: nem Dockerfile, Compose das aplicações, Mailpit, seed de consultas, Actuator ou health; nem `correlationId` em log, ArchUnit ou Makefile;
  - contrato, topologia de mensageria, schema GraphQL, garantias de mensageria e matrizes inalterados;
  - nenhuma proteção estrutural existente removida;
  - o classifier `exec` preservando o jar principal;
  - os pisos de 85% e 90% intactos.
- [ ] 14.3 Confirmar a prontidão para archive:
  - todas as tasks implementáveis anteriores, de 1.1 a 14.2, concluídas;
  - `openspec status --change add-integration-tests-coverage` e `openspec validate add-integration-tests-coverage --strict` verdes.

## 15. Procedimento pós-DoD obrigatório

Esta seção não tem checkbox: é executada depois da DoD e não é contabilizada pelo status.

1. O Gabriel aprova o PR.
2. O implementador (Claude Code) executa `/opsx:archive add-integration-tests-coverage` na própria feature branch, antes do merge.
3. O implementador valida as specs promovidas com `openspec validate --specs --strict` e confere que `openspec list` não mostra change ativa.
4. O Gabriel revisa o diff — change movida para `openspec/changes/archive/` e specs promovidas das quatro capabilities —, commita e faz push do archive.
5. Somente depois, o Gabriel faz o merge `--no-ff` em `develop`.
