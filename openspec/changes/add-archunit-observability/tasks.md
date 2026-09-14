> **Condições gerais.**
>
> - **Aprovação.** Nenhuma task começa antes da aprovação explícita do Gabriel sobre proposal, design, specs e tasks.
> - **Git.** A branch é `feature/m11-add-archunit-observability`. Toda operação Git é do Gabriel; o implementador entrega os comandos prontos.
> - **Nomes.** Nomes de classe são indicativos; o comportamento está na delta de `operacao-do-ambiente`, e o desenho, em `design.md` (D1–D11).
> - **Ordem.** P0 (seções 1 a 4: ArchUnit, correlação HTTP, correlação AMQP por testes dirigidos e Actuator) fica verde no Checkpoint P0.2 antes de qualquer arquivo de P1 (seções 5 a 10: JSON, evolução do smoke, execução real, mutações, documentação e verificação). P0 não prova o fluxo nos três logs; o M11 só está concluído com P0 e P1.
> - **Contextos de teste (D11).** ITs novos reutilizam exatamente as bases e contextos existentes, que ficam intocados. Nas novas classes-folha não entra declaração local que altere a chave do cache e crie um segundo contexto ou consumidor. As anotações já existentes nas bases continuam permitidas.
> - **jq.** Continua pré-requisito só do smoke real (7.1). `mvn -q clean verify`, o `RoteiroDeSmokeTest` e o clone limpo não passam a exigi-lo (D7).
> - **Escopo.** Não entra item de M12, M13 ou M14. Contrato AMQP, topologia, schema GraphQL, migrations, regras de negócio, matrizes e garantias de entrega não mudam.

## 1. P0 — ArchUnit no agendamento (D2)

- [x] 1.1 Declarar `archunit.version` fixada em `1.4.1` e a entrada `com.tngtech.archunit:archunit` em `dependencyManagement` do POM raiz, e a dependência sem versão, em escopo `test`, só no `agendamento-service`. Verificar com `mvn -q -pl agendamento-service -am test-compile` e com o `VersaoCentralizadaTest` verde. Se `1.4.1` não resolver, **parar** e exigir `/opsx:update`, sem escolher outra versão.
- [x] 1.2 Criar a fábrica de regras parametrizada pelo pacote base e o `ArquiteturaDoAgendamentoTest` com as regras A1 a A7 do D2, como métodos `@Test` que importam o código principal com `DoNotIncludeTests`.
  - A A3 conta só os métodos declarados diretamente pela classe `*UseCase`, públicos, de instância e não sintéticos. Construtores, herdados, bridge e métodos de `Object` ficam fora. O único método esperado é `executar`.

  Verificar que todas passam sobre o código real, que o `TEST-*.xml` registra os casos e que nenhuma dependência de produção ou do `AutenticacaoController` foi alterada. Cobre "Código conforme às fronteiras é aceito".
- [x] 1.3 Criar as fontes sintéticas de teste e os negativos. Cada regra reprova identificando a classe:
  - domínio dependendo de aplicação ou infraestrutura, e aplicação dependendo de infraestrutura;
  - domínio com Spring, JPA, Jackson ou Validation;
  - caso de uso que declara diretamente dois métodos públicos de instância;
  - `@Entity` fora de persistência;
  - controller com repositório, com porta de saída e com caso de uso nu;
  - `JwtService` num controller que não é o de autenticação;
  - `System.out`.

  Criar também os positivos:
  - o controller de autenticação com `JwtService`;
  - um `*UseCase` com `executar` e ainda construtor público, método estático público, método herdado de superclasse, bridge de genérico e métodos de `Object`, aceito pela A3.

  Verificar que negativos e positivos passam. Cobre os demais sete Scenarios de "Fronteiras da Clean Architecture verificadas automaticamente".

**Checkpoint P0.1:** `mvn -q -pl agendamento-service -am test -Dtest='ArquiteturaDoAgendamentoTest' -Dsurefire.failIfNoSpecifiedTests=false` verde, com as contagens registradas.

## 2. P0 — Correlação HTTP nos três serviços (D3)

- [x] 2.1 Criar o filtro de correlação no `notificacao-service`, com a semântica do filtro do agendamento e ordem de maior precedência. Criar o `CorrelationIdFilterTest` do serviço: header honrado, header vazio ou ausente gera id, MDC durante a cadeia e MDC anterior restaurado, inclusive quando a cadeia lança exceção. Verificar que passa.
- [x] 2.2 Criar o filtro e o `CorrelationIdFilterTest` equivalentes no `historico-service`. Verificar que passa.
- [x] 2.3 Criar o `CorrelacaoHttpIT` nos três serviços, nos contextos do D11:
  - agendamento com a mesma configuração do `CadeiaDeSegurancaIT`;
  - notificação sobre a `NotificacaoITBase` exatamente como está, com o listener canônico ativo e sem propriedades nem configuração local;
  - histórico sobre a `GraphqlITBase`, cujo listener já está parado.

  Ele prova:
  - header honrado e devolvido numa resposta de sucesso;
  - id gerado quando ausente;
  - numa recusa 401 e numa 403, o header da resposta e o `correlationId` do Problem Detail iguais ao enviado.

  Casos de 403: PACIENTE criando consulta no agendamento; PACIENTE disparando o lembrete na notificação; caminho negado com token válido no histórico. Verificar que passa nos três e que o `CorrelationIdFilterTest` do agendamento continua inalterado e verde. Cobre os quatro Scenarios de "Correlação HTTP consistente nos três serviços".

## 3. P0 — Correlação assíncrona e logs de sucesso (D4)

- [x] 3.1 No consumidor do histórico, colocar o `correlationId` do envelope no MDC no início do processamento e fazer `MDC.clear()` no `finally`. Acrescentar o log `INFO` "Evento projetado", só quando o evento é projetado; ele sai antes do commit e não prova o efeito (D4). Criar o `CorrelacaoHistoricoIT` reutilizando a `HistoricoITBase` como está, com listener real e sem configuração local (D11); se precisar de um espião que a base não tem, **parar** e reportar. Ele prova:
  - correlação disponível durante a projeção;
  - limpa depois da tentativa, inclusive em falha;
  - cada tentativa do retry vê o id do próprio envelope;
  - duplicata sem log de projeção.

  Verificar que passa. Cobre "Consumidor do histórico mantém e limpa a correlação".
- [x] 3.2 No `OutboxRelay`, acrescentar o log `INFO` "Evento publicado", com `eventId`, tipo e `consultaId`, depois de marcar a publicação e dentro do escopo do MDC do envelope; ele sai antes do commit e não prova o efeito (D4). Verificar com um teste do relay que o registro sai com o `correlationId` do envelope e que o MDC anterior continua restaurado. Transação, outbox e garantia de entrega não mudam.
- [x] 3.3 Confirmar pelo diff e pela execução que o `CorrelacaoNotificacaoIT`, o `ProtecoesEstruturaisNotificacaoTest`, o `CorrelacaoEOffsetDoEventoIT`, o `OutboxRelayIT` e os testes de envelope e headers não perderam caso nem asserção e passam.

## 4. P0 — Actuator seguro nos três serviços (D5)

- [x] 4.1 Acrescentar `spring-boot-starter-actuator` e `micrometer-registry-prometheus`, sem versão, aos três serviços. Configurar a exposição exata e `show-details`/`show-components` `never` no `application.yml` de cada um. Verificar com `mvn -q -pl agendamento-service,notificacao-service,historico-service -am test-compile`.
- [x] 4.2 Acrescentar ao `SegurancaAutoConfiguration` os caminhos operacionais autenticados do D5, entre os públicos e o `denyAll`, sem curinga `/actuator/**`. Atualizar o `SegurancaAutoConfigurationTest` para provar os caminhos operacionais autenticados e `env`/`beans` negados. Verificar que passa.
- [x] 4.3 Configurar os indicadores de saúde do D5: `management.health.mail.enabled: false` no `application.yml` da notificação e `management.health.rabbit.enabled: false` no do agendamento, sem desabilitar nenhum indicador no histórico. Conferir nos três arquivos que só essas duas chaves de `management.health` existem, e que PostgreSQL e RabbitMQ seguem ativos onde o D5 manda. Criar o `EndpointsOperacionaisIT` nos três serviços, nos mesmos contextos da 2.3 (D11), com as provas do D5:
  - health público, bem-sucedido e sem detalhes;
  - 401 sem token e com token inválido;
  - 200 com token de PACIENTE e de MEDICO;
  - formato Prometheus;
  - exposição exatamente `{health, info, metrics, prometheus}`;
  - `env` e `beans` sem 2xx com token.

  Verificar que passa nos três e que o `CadeiaDeSegurancaIT` continua sem mudança de expectativa e verde. Cobre os quatro Scenarios de "Endpoints operacionais mínimos e seguros".
- [x] 4.4 Executar os inventários `EntradasHostisIT`, `SuperficieHttpNotificacaoIT` e `EntradasHostisGraphqlIT` e confirmar que as contagens de superfície são as mesmas do M10. Se algum inventário passar a enxergar endpoints do actuator, **parar** e reportar ao Gabriel.
- [x] 4.5 Verificar a proteção de contextos do D11, cujo objetivo é impedir um segundo consumidor ou contexto, não desligar todo listener:
  - pelo diff, nenhuma nova classe-folha de IT declara localmente `@MockBean`, `@SpyBean`, `@MockitoBean`, `@TestConfiguration`, `@Import`, `properties` ou `@DynamicPropertySource` que altere a chave do cache em relação à base;
  - as classes-base não mudaram, e as anotações já existentes nelas continuam como estão;
  - `CorrelacaoHistoricoIT` reutiliza a `HistoricoITBase`, com listener real;
  - os ITs HTTP e de Actuator do histórico reutilizam a `GraphqlITBase`, com listener parado;
  - os da notificação reutilizam a `NotificacaoITBase` exatamente como está, com o listener canônico ativo;
  - no Checkpoint P0.2, `ConsumoNotificacaoRabbitMqIT`, `ConsumoHistoricoRabbitMqIT` e `CorrelacaoNotificacaoIT` rodam junto com os ITs novos e ficam verdes, sem mensagem consumida por outro contexto.

**Checkpoint P0.2:**

```bash
mvn -q -pl shared-security,agendamento-service,notificacao-service,historico-service -am verify \
  -Dtest='ArquiteturaDoAgendamentoTest,CorrelationIdFilterTest,SegurancaAutoConfigurationTest' \
  -Dit.test='CorrelacaoHttpIT,CorrelacaoHistoricoIT,CorrelacaoNotificacaoIT,ConsumoNotificacaoRabbitMqIT,ConsumoHistoricoRabbitMqIT,OutboxRelayIT,EndpointsOperacionaisIT,CadeiaDeSegurancaIT,EntradasHostisIT,SuperficieHttpNotificacaoIT,EntradasHostisGraphqlIT' \
  -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false
```

Verde, com as contagens registradas. Fecha P0. A prova do fluxo nos três logs fica em P1 (seções 6 e 7).

## 5. P1 — Logs JSON no profile docker (D6)

- [x] 5.1 Criar o `application-docker.yml` nos três serviços, com `logging.structured.format.console: logstash` e `logging.structured.json.add.service` igual ao nome do serviço. Nenhum `logging.structured` no `application.yml` nem em outro profile. Verificar com `test-compile`.
- [x] 5.2 Criar o `LogsEstruturadosTest` no `quality-gates`, só com JDK, sobre os arquivos reais dos três serviços. Criar os negativos sintéticos: chave ausente no profile `docker`, chave no `application.yml` e chave em outro profile. Verificar que passa. Cobre "Profile padrão continua em texto" e "Profile docker sem formato estruturado é detectado".
- [x] 5.3 Numa execução real de um serviço com o profile `docker`, confirmar que o campo `service` aparece no JSON e que o `correlationId` do MDC é campo de primeiro nível. Se `logging.structured.json.add.*` não for honrado, **parar** e reportar ao Gabriel, sem trocar de formato.

## 6. P1 — Smoke com correlação ponta a ponta (D7)

- [x] 6.1 Evoluir o `scripts/smoke-test.sh`:
  - `CORRELATION_ID` derivado do `RUN_ID`;
  - cabeçalhos extras no `requisitar`, com `X-Correlation-Id` só na criação da consulta;
  - conferência do header devolvido;
  - agendamento com os profiles `demo,docker`, e notificação e histórico com `docker`.

  Verificar com `bash -n` e com o `RoteiroDeSmokeTest` verde.
- [x] 6.2 Implementar as três verificações de log por `jq` do D7, dentro de `aguardar`: relay do agendamento, projeção do histórico e `LogNotificationSender` da notificação.
  - A verificação fica numa função que recebe arquivo, logger, consulta, correlação e prazo, carregável por `source` sem executar o fluxo nem o preflight.
  - O roteiro define `JQ_BIN="${JQ_BIN:-jq}"` e o usa no preflight e em todas as chamadas de `jq`. Em produção o padrão é `jq`, e o preflight continua exigindo a versão 1.6 ou superior.
  - Cada registro precisa ter a consulta da execução, `correlationId` igual ao enviado e os campos `@timestamp`, `level`, `logger_name`, `message` e `service`.
  - O `grep -F` da notificação é substituído pela verificação JSON de mensagem igual.
  - Correlação divergente termina com código 1; registro ausente no prazo, com código 3.
- [x] 6.3 Acrescentar ao `RoteiroDeSmokeTest` o caso do parser de porta com a linha do Tomcat em JSON. Verificar que ele e os demais casos passam.
- [x] 6.4 Acrescentar ao `RoteiroDeSmokeTest` os negativos sintéticos da função da 6.2, **sem depender de `jq` instalado**. O teste grava no seu diretório temporário um `jq` controlado, um script bash de saída fixa que registra os argumentos recebidos, e o passa em `JQ_BIN`. Casos:
  - registro do serviço ausente → com prazo curto definido pelo teste, na ordem de segundos, a função esgota o prazo e termina com código 3, sem aguardar o timeout real do smoke;
  - registro do fluxo, da mesma consulta, com `correlationId` divergente → código 1.

  Em ambos, o teste confirma pelos argumentos registrados que a função passou o logger, a consulta e a correlação da execução. Verificar que passam numa máquina sem `jq` no `PATH`. Cobre "Registro ausente ou com correlação divergente reprova o smoke".

## 7. P1 — Smoke real

- [x] 7.1 Executar `scripts/smoke-test.sh` **uma vez**, com o Compose local no ar, e registrar para o `RUN_ID`:
  - código 0;
  - `X-Correlation-Id` devolvido igual ao enviado;
  - os três registros JSON com o mesmo `correlationId` e os campos exigidos, citando cada linha;
  - limpeza total, sem container, rede, volume, runtime ou processo;
  - Compose intacto e `healthy`.

  Cobre "Mesmo identificador nos logs dos três serviços", "Registro no profile docker é JSON completo" e "Correlação é campo estruturado".

**Checkpoint P1:** a execução única da 7.1, com a saída integral no corpo do PR.

## 8. P1 — Mutações de sensibilidade (D8)

Protocolo: SHA-256 antes, alterar só o alvo, menor gate vermelho, restaurar byte a byte, SHA-256 idêntico e o mesmo gate verde. Uma mutação por vez.

- [x] 8.1 X1: importar um tipo de `org.springframework` num tipo do `domain`. O `ArquiteturaDoAgendamentoTest` fica vermelho pela regra A2.
- [x] 8.2 X2: acrescentar a um `*UseCase` um segundo método público de instância declarado diretamente. O `ArquiteturaDoAgendamentoTest` fica vermelho pela regra A3.
- [x] 8.3 X3: retirar o MDC do consumidor do histórico. O gate é só o `CorrelacaoHistoricoIT`, que fica vermelho pela correlação ausente durante a projeção e nas tentativas do retry. O smoke mutado **não** é executado; a recusa de log ausente ou divergente pelo smoke está provada na 6.4.
- [x] 8.4 X4: acrescentar `env` à exposição de um serviço. O `EndpointsOperacionaisIT` desse serviço fica vermelho pela exposição.
- [x] 8.5 X5: remover o formato estruturado de um `application-docker.yml`. O `LogsEstruturadosTest` fica vermelho identificando o serviço.
- [x] 8.6 Consolidar no PR a tabela: mutação | alvo | SHA-256 antes | gate | vermelho observado | SHA-256 restaurado (idêntico) | gate verde.

## 9. P1 — Documentação

- [x] 9.1 Atualizar o README: Actuator, com endpoints, proteção e o health público; correlação ponta a ponta; logs JSON no profile `docker`; e a nova verificação do smoke, com `jq` 1.6 apresentado apenas como pré-requisito do smoke real — não do `mvn clean verify` nem do `RoteiroDeSmokeTest`. Verificar que os comandos citados são os executados.
- [x] 9.2 Atualizar o `docs/01-arquitetura.md`: §9 Observabilidade, com os quatro endpoints, a proteção por JWT, o formato `logstash` e o consumidor do histórico; §10 com a regra ArchUnit efetiva e a exceção nominal do `JwtService`.
- [x] 9.3 Atualizar o status do ADR-007 com a evidência do M11 (`ArquiteturaDoAgendamentoTest`) e a reconciliação da exceção nominal do `JwtService` com a nota do roadmap.
- [x] 9.4 Atualizar a rastreabilidade de RNF-05 e RNF-08 em `docs/02-especificacao-funcional.md` e a linha do M11 em `docs/04-roadmap.md`, só onde o conteúdo precisar refletir o entregue.

## 10. Verificação final

- [x] 10.1 Executar e registrar a suíte dirigida do M11, verde:

  ```bash
  mvn -q -pl shared-security,agendamento-service,notificacao-service,historico-service -am verify \
    -Dtest='ArquiteturaDoAgendamentoTest,CorrelationIdFilterTest,SegurancaAutoConfigurationTest' \
    -Dit.test='CorrelacaoHttpIT,CorrelacaoHistoricoIT,CorrelacaoNotificacaoIT,ConsumoNotificacaoRabbitMqIT,ConsumoHistoricoRabbitMqIT,OutboxRelayIT,EndpointsOperacionaisIT,CadeiaDeSegurancaIT' \
    -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false
  ```

  E também:

  ```bash
  mvn -q -pl quality-gates -am test -Dtest='LogsEstruturadosTest,RoteiroDeSmokeTest,AuditoriaDeExecucaoTest,InfraestruturaRealTest' -Dsurefire.failIfNoSpecifiedTests=false
  ```

- [x] 10.2 Executar `mvn -q clean verify` na raiz, sem filtro, uma vez. Registrar:
  - build verde, total de casos e zero ignorados;
  - marcador de sessão e auditoria APROVADA por módulo;
  - guarda de infraestrutura aprovada;
  - gate de cobertura com os pisos de 85%, 90% e 90%.
- [x] 10.3 Registrar a cobertura do código novo — filtros, MDC do histórico, logs de sucesso e caminhos operacionais — pelo relatório agregado, e confirmar que nenhuma exclusão de cobertura foi acrescentada.
- [ ] 10.4 Clone limpo: clonar a feature branch, depois do push e com autorização do Gabriel, num diretório criado com `mktemp -d` e validado sob `${TMPDIR:-/tmp}`, com repositório Maven temporário vazio e sem `install`. Executar **somente** `mvn -q -Dmaven.repo.local=<repo> clean verify`. Registrar código 0 e a ausência de artefato interno no repositório temporário. O smoke não é executado no clone; a execução real única da 7.1 na árvore da feature é a evidência runtime.
- [ ] 10.5 Conferir pelo diff:
  - nada de M12, M13 ou M14;
  - contrato, topologia, schema GraphQL, migrations, regras de negócio, matrizes e garantias inalterados;
  - o filtro do agendamento inalterado;
  - nenhuma proteção do M10 removida;
  - nas novas classes-folha de IT, nenhuma declaração local que altere a chave do cache e crie um segundo contexto ou consumidor (D11);
  - classes-base de IT inalteradas, com suas anotações já existentes preservadas;
  - `jq` sem entrar como pré-requisito do build nem do `RoteiroDeSmokeTest`;
  - `CHANGELOG.md` e versão inalterados.
- [x] 10.6 Montar no corpo do PR a matriz dos 23 Scenarios → classe e método de teste, ou comando registrado, sem Scenario sem evidência. Registrar a exceção nominal do `JwtService` e os riscos residuais do design.
- [ ] 10.7 Confirmar a prontidão para archive: todas as tasks de 1.1 a 10.6 concluídas, e `openspec status --change add-archunit-observability` e `openspec validate add-archunit-observability --strict` verdes. O archive é feito na própria feature branch, depois da aprovação do PR e antes do merge.
