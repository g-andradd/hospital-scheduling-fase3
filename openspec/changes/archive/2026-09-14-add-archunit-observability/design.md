## Context

A motivação está em `proposal.md` (Why). Estado atual que orienta o desenho:

- **Agendamento.**
  - Pacotes `domain`, `application` e `infrastructure`, com `config`, `messaging`, `persistence/{entity,mapper,repository}`, `security`, `transacao` e `web`.
  - Os sete `*UseCase` expõem só o construtor e `executar`.
  - Os controllers injetam apenas os decoradores `*UseCaseTransacional`. A exceção é o `AutenticacaoController`, que também injeta o `JwtService` do `shared-security` (`docs/01` §7).
  - As quatro entidades `@Entity` estão em `infrastructure.persistence.entity`.
  - Não há `System.out`, `System.err` nem `printStackTrace` no código principal.
- **Correlação existente.**
  - O `CorrelationIdFilter` do agendamento roda com `HIGHEST_PRECEDENCE`, antes da segurança, e restaura o MDC anterior.
  - O `OutboxEventPublisher` grava no envelope o `correlationId` do MDC. O `OutboxRelay` restaura o valor do envelope no MDC durante a publicação e devolve o anterior.
  - O `EventoEnvelopeConverter` escreve e confere `x-correlation-id`.
  - O `ConsumidorDeNotificacoes` põe o id no MDC e faz `MDC.clear()` no `finally`, como `docs/03` §6. O `ProtecoesEstruturaisNotificacaoTest` e o `CorrelacaoNotificacaoIT` provam isso.
  - O `ConsumidorTransacionalDoHistorico` não usa MDC.
  - Notificação e histórico não têm filtro HTTP; o `RespostaDeSeguranca` busca o id no atributo, depois no header, e por último gera um.
- **Segurança.**
  - Uma única `SecurityFilterChain` em `SegurancaAutoConfiguration`.
  - `PUBLICOS` já inclui `/actuator/health` e `/actuator/health/**`. Os caminhos autenticados vêm de `hospital.security.caminhos.autenticados`: `/api/**` por padrão, `/internal/**` na notificação e `/api/**,/graphql` no histórico. O resto cai no `denyAll`.
  - O `CadeiaDeSegurancaIT` exige que `/actuator/env` e `/actuator/beans` sejam 401 ou 403 e que `/actuator/health` não seja 401 nem 403.
- **Sem Actuator nem configuração de logging.** Nenhum serviço tem Actuator ou logging configurado. Há `application-demo.yml` só no agendamento; o histórico declara `dev` e `demo` dentro do `application.yml`.
- **Proteções do M10:**
  - a auditoria (`InventarioDeTestes`) reconhece como teste apenas `@Test`, `@RepeatedTest`, `@TestFactory`, `@TestTemplate`, `@ParameterizedTest` e `org.junit.Test`;
  - os inventários de superfície (`EntradasHostisIT` e `SuperficieHttpNotificacaoIT`) leem somente o bean `requestMappingHandlerMapping`;
  - o smoke valida a notificação por `grep -F` da linha do `LogNotificationSender` e descobre a porta pela regex `Tomcat started on port N`;
  - o `RoteiroDeSmokeTest` aplica as regras estruturais do roteiro.
- **Contrato.** O `correlationId` do envelope é `string` não vazia, sem formato (`docs/03` §3).
- **Contextos de teste.**
  - A `GraphqlITBase` do histórico sobe com `spring.rabbitmq.listener.simple.auto-startup=false`, para não competir pela fila com as suítes do M08.
  - A `HistoricoITBase` e a `NotificacaoITBase` mantêm o listener real e concentram espiões e configurações de teste na base, com `@Import`, `@TestConfiguration` e `@SpyBean` legítimos. Uma declaração local numa suíte que alterasse a chave do cache criaria um segundo contexto com listener na mesma fila; o M06 e o M09 já sofreram com isso.
  - O agendamento não tem listener AMQP. O `CadeiaDeSegurancaIT` declara só `@SpringBootTest(RANDOM_PORT)` e `@DynamicPropertySource` do Postgres.

## Goals / Non-Goals

**Goals:**
- Tornar RNF-05 e RNF-08 verificáveis no build e no smoke, com a menor mudança em código de produção.
- Entregar P0 antes de P1, com checkpoints próprios.
- Manter intactas todas as proteções e contagens do M10.

**Non-Goals:**
- Unificar o filtro de correlação em `shared-security`.
- Validar ou normalizar o formato de `X-Correlation-Id`.
- Métricas de negócio, tracing distribuído, OpenTelemetry ou dashboards.
- Separar a porta de gerenciamento ou criar papel dedicado a métricas.
- Arquitetura verificada nos serviços satélites (ADR-007).
- Qualquer item de M12, M13 ou M14.

## Decisions

### D1. Ordem de implementação: P0 antes de P1

- **P0 — verificável por testes dirigidos:**
  - ArchUnit;
  - correlação HTTP nos três serviços;
  - correlação AMQP: MDC no consumidor do histórico e logs de sucesso, provados por `CorrelacaoHistoricoIT`, pelo teste do relay e pelas suítes existentes;
  - Actuator seguro.

  P0 fecha com o Checkpoint P0.2 e **não** prova o fluxo nos três logs.
- **P1 — prova ponta a ponta e fechamento:**
  - logs JSON no profile `docker` (D6);
  - evolução do smoke (D7) e a execução real única que fecha a prova ponta a ponta do RNF-08;
  - mutações, documentação e verificações finais.

O M11 só está concluído com P0 e P1. A ordem garante que ArchUnit, correlação e Actuator estejam verdes antes de qualquer arquivo de P1.

**Alternativa descartada:** smoke textual em P0 e troca por JSON em P1. Seria implementar e testar duas vezes a mesma verificação.

### D2. ArchUnit como biblioteca, com regras em métodos `@Test`

**Dependência.**
- `com.tngtech.archunit:archunit` (core), em escopo `test`, só no `agendamento-service`.
- A versão fica fixada em `1.4.1`, na propriedade `archunit.version` do POM raiz, com entrada em `dependencyManagement`. Se `1.4.1` não resolver durante a apply, o implementador **para** e exige `/opsx:update`; não escolhe outra versão durante a implementação.

**Onde e como as regras rodam.**
- As regras vivem em `ArquiteturaDoAgendamentoTest` (surefire), como métodos `@Test` que chamam `regra.check(classes)`.
- As classes vêm de `ClassFileImporter` sobre `br.com.fiap.hospital.agendamento`, com `ImportOption.DoNotIncludeTests`.
- Uma fábrica de regras recebe o pacote base. Assim as mesmas regras rodam contra o código real e contra fontes sintéticas de teste, que servem de negativos.

**Por que não `archunit-junit5`.** Com o engine próprio e `@ArchTest`, a classe não teria nenhuma anotação que a auditoria do M10 reconhece. Ela seria classificada como auxiliar, e o `TEST-*.xml` gerado reprovaria como relatório órfão. O engine novo também ampliaria a superfície de filtros por motor que a auditoria recusa.

**Alternativa descartada:** reflexão manual sobre as classes, que reinventa o que a biblioteca faz.

**Regras** — notas obrigatórias do roadmap, decididas:

| # | Regra | Forma |
|---|---|---|
| A1 | Direção das camadas | Nenhuma classe de `..domain..` depende de `..application..` ou `..infrastructure..`; nenhuma de `..application..` depende de `..infrastructure..`. |
| A2 | Domínio sem framework | Nenhuma classe de `..domain..` depende de `org.springframework..`, `jakarta.persistence..`, `javax.persistence..`, `org.hibernate..`, `com.fasterxml.jackson..`, `jakarta.validation..` ou `javax.validation..`. |
| A3 | Um método público por caso de uso | Classes de `..application..` com nome terminado em `UseCase` declaram **diretamente** exatamente um método público, de instância e não sintético. Não contam construtores, métodos herdados, bridge methods e métodos de `Object`. Na prática a condição usa só os métodos declarados pela própria classe e descarta estáticos, sintéticos e bridge. O único método esperado é `executar`. |
| A4 | Entidades JPA | Classes anotadas com `jakarta.persistence.Entity` residem em `..infrastructure.persistence..`. |
| A5 | Controllers passam pelo caso de uso transacional | Classes anotadas com `@RestController` ou `@Controller` não dependem de `..infrastructure.persistence..`, `..infrastructure.messaging..`, `..domain.port..`, subtipos de `org.springframework.data.repository.Repository` nem de classes de `..application..` terminadas em `UseCase` — o caso de uso nu, sem demarcação transacional. |
| A6 | Exceção nominal do token | Entre os controllers, só `infrastructure.web.AutenticacaoController` pode depender de `br.com.fiap.hospital.security.JwtService`. |
| A7 | Saída padrão | `GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS`, que cobre `System.out`, `System.err` e `printStackTrace`. |

**Reconciliação do A5/A6 com roadmap e ADR-007.**
- A nota "controllers não injetam repositório, só caso de uso" é mantida.
- O `JwtService` não é repositório nem caso de uso: é o componente de emissão de token que `docs/01` §7 põe na fronteira HTTP. Refatorar a autenticação para escondê-lo atrás de um caso de uso mudaria a responsabilidade documentada só para agradar a regra.
- A exceção é **nominal**, com uma classe e um tipo; não existe exceção genérica por pacote ou anotação. O ADR-007 ganha registro desta reconciliação e da evidência (D10).

A1 inclui "aplicação não depende de infraestrutura", que já é o texto do ADR-007 (`infrastructure → application → domain`). O código atual já cumpre.

**Negativos.** Fontes sintéticas em `src/test`, sob um pacote base sintético com subpacotes que imitam as camadas, provam que cada regra reprova e identifica a classe:
- domínio que importa aplicação;
- domínio com Spring;
- caso de uso que declara dois métodos públicos de instância;
- `@Entity` fora de persistência;
- controller com repositório;
- controller com caso de uso nu;
- `JwtService` em outro controller;
- `System.out`.

Positivos sintéticos provam:
- a exceção nominal do controller de autenticação;
- que não contam para a A3 um caso de uso com `executar` mais construtor público, método estático público, método herdado de uma superclasse sintética, bridge method gerado por genérico e métodos de `Object`.

### D3. Correlação HTTP nos satélites por cópia do filtro existente

- `notificacao-service` e `historico-service` recebem cada um uma cópia do `CorrelationIdFilter`, no pacote web de cada serviço, com a mesma semântica:
  - honra header não vazio e gera UUID na ausência;
  - grava o atributo `correlationId`, o header de resposta e o MDC;
  - restaura o MDC anterior no `finally`;
  - `@Order(HIGHEST_PRECEDENCE)`, antes do filtro da cadeia de segurança.
- O `RespostaDeSeguranca` já lê o atributo, então 401 e 403 passam a trazer o mesmo id.
- O filtro do agendamento não é tocado.

**Alternativas descartadas:**
- Mover o filtro para `shared-security` como auto-configuração: é a refatoração que a aprovação pediu para evitar e alteraria o agendamento sem ganho funcional.
- Registrar o filtro dentro da `SecurityFilterChain`: não roda antes da cadeia, então as recusas não herdariam o id.

**Custo aceito:** três cópias de cerca de 40 linhas, cada uma com teste próprio.

### D4. Correlação AMQP: completar o histórico e dois logs de sucesso

- **Consumidor do histórico.** `MDC.put("correlationId", evento.correlationId())` no início do método do listener e `MDC.clear()` no `finally`.
  - É exatamente o esqueleto normativo de `docs/03` §6 e o que o consumidor da notificação faz, provado por `notificacoes-ao-paciente`.
  - **Alternativa descartada:** restaurar o valor anterior. O contrato normativo prescreve `clear`, e a thread do container de listener não carrega contexto anterior legítimo.
- **Log de projeção.** `INFO` depois do `saveAndFlush` da marca, só quando o evento foi projetado (nunca no descarte por duplicata): `Evento projetado eventId=... tipo=... consultaId=...`.
- **Log de publicação.** `INFO` no `OutboxRelay`, depois de marcar o evento como publicado, ainda dentro do escopo em que o MDC tem o id do envelope: `Evento publicado eventId=... tipo=... consultaId=...`.
- **Semântica dos dois logs de sucesso.** Os dois são emitidos **dentro da transação**, antes do commit que o proxy transacional faz no retorno do método.
  - Cada um registra uma tentativa bem-sucedida até aquele ponto; nenhum é auditoria nem prova de commit.
  - Uma falha de commit desfaz o efeito local. O relay deixa o evento pendente e o republica; o histórico recebe a reentrega at-least-once e projeta de novo. Nos dois casos, um novo log é emitido.
  - A prova do efeito confirmado continua sendo o estado persistido: a marca de publicação no outbox, os registros de projeção e de notificação e a leitura por GraphQL.
- **Notificação.** A linha existente do `LogNotificationSender` já roda sob o MDC do consumidor.
- **O que não muda:** transações, ordem de efeitos, contrato, headers, topologia, retry, DLQ e idempotência.

### D5. Actuator seguro na cadeia compartilhada

**Dependências.** `spring-boot-starter-actuator` e `micrometer-registry-prometheus` nos três serviços, com versões do BOM do Spring Boot.

**Configuração**, no `application.yml` de cada serviço, sem profile:

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus
  endpoint:
    health:
      show-details: never
      show-components: never
```

**Indicadores de saúde por serviço.** O `health` agrega só as dependências operacionais ativas de cada serviço, também no `application.yml`, sem profile:
- **notificacao-service:** `management.health.mail.enabled: false`.
  - O sender padrão é `log`. A presença do starter de mail não torna o SMTP uma dependência operacional ativa, e sem este ajuste o indicador `mail` derrubaria o `health` para 503 sem servidor SMTP.
  - PostgreSQL e RabbitMQ continuam contribuindo normalmente.
- **agendamento-service:** `management.health.rabbit.enabled: false`.
  - O ADR-006 estabelece que a indisponibilidade do broker não torna a API indisponível: a consulta e o outbox continuam sendo confirmados no PostgreSQL.
  - O PostgreSQL continua contribuindo normalmente.
- **historico-service:** nenhum indicador desabilitado; PostgreSQL e RabbitMQ continuam contribuindo.
- **Consequência para o M12:** quando `notificacao.sender=smtp` for ativado com o Mailpit, o M12 decide explicitamente se reabilita `management.health.mail.enabled=true` no ambiente Compose. Essa decisão não é implementada no M11.

**Segurança.** O `SegurancaAutoConfiguration` ganha a constante `OPERACIONAIS_AUTENTICADOS`, com `/actuator`, `/actuator/info`, `/actuator/metrics`, `/actuator/metrics/**` e `/actuator/prometheus`.
- O matcher `.authenticated()` fica depois dos públicos e antes do `denyAll`.
- `health` continua em `PUBLICOS`.
- Qualquer outro caminho de actuator continua negado, mesmo que a exposição seja ampliada por engano: defesa em profundidade.

**Alternativas descartadas:**
- `/actuator/**` autenticado: um `env` exposto por descuido ficaria alcançável com qualquer token.
- Declarar os caminhos em `hospital.security.caminhos.autenticados` de cada serviço: repete a mesma lista três vezes, e o agendamento hoje depende do padrão.
- Porta de gerenciamento separada ou segunda cadeia: contraria a cadeia única aprovada.
- Papel dedicado: não há matriz de papéis para operação.

**Evidência — `EndpointsOperacionaisIT` por serviço.**
- **Onde:** nos contextos existentes, conforme D11 — agendamento com a configuração idêntica à do `CadeiaDeSegurancaIT`; notificação sobre a `NotificacaoITBase`; histórico sobre a `GraphqlITBase`, com listener parado.
- **Health:** sem token responde 200, e o corpo tem só `status`. A resposta bem-sucedida não depende de SMTP na notificação nem de broker no agendamento, conforme os indicadores de saúde acima.
- **Recusa:** `/actuator`, `info`, `metrics` e `prometheus` respondem 401 em Problem Detail sem token e com token inválido.
- **Acesso:** os mesmos respondem 200 com token válido de perfil PACIENTE e de perfil MEDICO.
- **Prometheus:** o content-type é de exposição textual Prometheus, e o corpo contém `# TYPE jvm_`.
- **Exposição exata:** os ids expostos, lidos do bean de endpoints web mapeados, são exatamente `{health, info, metrics, prometheus}`.
- **Proibidos:** `/actuator/env` e `/actuator/beans`, com token válido, não respondem 2xx.

O `CadeiaDeSegurancaIT` existente é preservado sem mudança de expectativa.

**Interação com o M10.** O Actuator registra endpoints em `webEndpointServletHandlerMapping`, fora do `requestMappingHandlerMapping` que os inventários de superfície leem. A apply confirma com `EntradasHostisIT`, `SuperficieHttpNotificacaoIT` e `EntradasHostisGraphqlIT` verdes. Se algum inventário passar a enxergar endpoints do actuator, a apply **para** e consulta, porque isso tocaria uma proteção do M10.

### D6. Logs JSON nativos do Spring Boot 3.5 no profile `docker`

Cada serviço ganha `src/main/resources/application-docker.yml` com:

```yaml
logging:
  structured:
    format:
      console: logstash
    json:
      add:
        service: <nome do serviço>
```

- **Campos do formato `logstash`:** `@timestamp`, `level`, `logger_name`, `message` e `thread_name`, mais cada chave do MDC como campo de primeiro nível — portanto `correlationId`. O campo `service` identifica o serviço.
- **Sem o profile `docker`,** nenhuma configuração ativa o formato estruturado.
- **Alternativas descartadas:**
  - `logstash-logback-encoder` com `logback-spring.xml`: dependência e arquivo que o suporte nativo tornou desnecessários.
  - Formato `ecs`: traz `service.name` nativo, mas foi aprovado o `logstash`, e o smoke fica mais simples com MDC no primeiro nível.
- **Evidência estrutural.** `LogsEstruturadosTest`, no `quality-gates`, lê os arquivos de configuração dos três serviços com leitura linha a linha, só JDK. Ele exige:
  - `application-docker.yml` com `logging.structured.format.console: logstash` e `logging.structured.json.add.service`;
  - nenhum `logging.structured` no `application.yml` nem em outro profile.

  Negativos sintéticos cobrem a chave ausente, a chave no `application.yml` e a chave em outro profile.
- **Evidência runtime:** o smoke (D7).

### D7. Smoke: correlação ponta a ponta com JSON, no roteiro existente

- **Início.** `CORRELATION_ID="smoke-${RUN_ID}"`, único por execução e aceito pelo contrato.
- **Criação da consulta.** O `requisitar` aceita cabeçalhos extras, e só a criação envia `X-Correlation-Id`. Depois do 201, o header devolvido, lido dos cabeçalhos da resposta sem diferenciar maiúsculas, precisa ser igual ao enviado.
- **Profiles.** Agendamento com `demo,docker`, notificação e histórico com `docker`.
- **Porta.** A descoberta não muda, porque a regex continua casando dentro da mensagem JSON. O `RoteiroDeSmokeTest` ganha um caso de parser com a linha do Tomcat em JSON.
- **Leitura dos logs.** Cada verificação usa `jq -R 'fromjson? | select(...)'` sobre o log do serviço na execução corrente, dentro de `aguardar`, e exige:
  - **agendamento:** `logger_name` do relay, `message` contendo o `CONSULTA_ID` e `correlationId == CORRELATION_ID`;
  - **histórico:** `logger_name` do consumidor, `message` contendo o `CONSULTA_ID` e `correlationId == CORRELATION_ID`;
  - **notificação:** `logger_name` do `LogNotificationSender`, `message` igual à linha montada com o conteúdo lido do banco e `correlationId == CORRELATION_ID`. Substitui o `grep -F` atual, com verificação mais forte.
  - **em todos:** `@timestamp`, `level`, `logger_name`, `message` e `service` presentes.
- **Resultado.**
  - Registro do fluxo com outro `correlationId` → código 1 imediato.
  - Registro ausente até o prazo → código 3.
  - Outra consulta ou outra execução não casa, porque o filtro exige `CONSULTA_ID` e `CORRELATION_ID` da execução, e os logs vivem no runtime dela.
- **Testabilidade.** A verificação de log fica numa função do roteiro, carregável por `source` sem executar o fluxo nem o preflight, como o parser de porta do M10. Ela recebe arquivo, logger, consulta, correlação e prazo.
- **Executável do jq injetável.**
  - O roteiro define `JQ_BIN="${JQ_BIN:-jq}"` e o usa no preflight e em todas as chamadas de `jq`.
  - Em produção o padrão continua `jq`, e o smoke real segue exigindo `jq` 1.6 ou superior no preflight.
  - A escolha é `JQ_BIN`, e não um prefixo no `PATH`: fica determinística no bash do Windows, onde a resolução por `PATH` já causou o desvio para o WSL no M10.
- **Negativos no `RoteiroDeSmokeTest`, sem `jq` instalado.**
  - O teste grava no seu diretório temporário um `jq` controlado, um script bash de saída fixa, e o passa em `JQ_BIN`.
  - O script simula o resultado da consulta ao log e registra os argumentos recebidos. O teste confirma que a função passou o logger, a consulta e a correlação da execução.
  - Casos:
    - registro do serviço ausente → a função chamada com prazo curto definido pelo teste, na ordem de segundos, esgota o prazo e termina com código 3, sem aguardar o timeout real do smoke;
    - registro do fluxo, da mesma consulta, com `correlationId` divergente → código 1 imediato.

  Esses negativos provam que o próprio smoke recusa log ausente ou divergente; a mutação X3 não roda o smoke completo. O filtro `jq` real é exercido pela execução real da 7.1.
- **Pré-requisitos inalterados em relação ao M10.** `jq` continua pré-requisito só do smoke real. `mvn -q clean verify`, o `RoteiroDeSmokeTest` e o clone limpo não passam a exigir `jq`.
- **Execução real:** uma única execução fecha a prova ponta a ponta. As duas execuções consecutivas e a independência entre elas já foram provadas no M10 e não mudam aqui.
- **Preservado:** preflight, códigos 0–5/130/143, isolamento por `RUN_ID`, limpeza, pacote de diagnóstico e regras estruturais do roteiro.

### D8. Sensibilidade: cinco mutações de arquivo

Protocolo B1 do M10: SHA-256 antes, alterar só o alvo, menor gate vermelho, restaurar byte a byte, SHA-256 idêntico, mesmo gate verde. Uma de cada vez.

| # | Mutação | Gate | Vermelho esperado |
|---|---|---|---|
| X1 | Importar tipo de `org.springframework` num tipo de `domain` | `ArquiteturaDoAgendamentoTest` | A2 |
| X2 | Acrescentar segundo método público de instância declarado por um `*UseCase` | `ArquiteturaDoAgendamentoTest` | A3 |
| X3 | Retirar o MDC do consumidor do histórico | `CorrelacaoHistoricoIT`, o menor gate dirigido; o smoke mutado não é executado | Correlação ausente durante a projeção e nas tentativas do retry |
| X4 | Acrescentar `env` à exposição de um serviço | `EndpointsOperacionaisIT` desse serviço | Exposição diferente da permitida |
| X5 | Remover o formato estruturado de um `application-docker.yml` | `LogsEstruturadosTest` | Serviço sem formato no profile `docker` |

### D9. Evidência por Scenario

Cada Scenario da delta tem evidência planejada. A matriz final, com classe e método ou comando, vai no corpo do PR.

| Requirement | Evidência |
|---|---|
| Fronteiras verificadas | `ArquiteturaDoAgendamentoTest` (código real e negativos sintéticos); X1 e X2 |
| Correlação HTTP | `CorrelationIdFilterTest` existente no agendamento; `CorrelationIdFilterTest` e `CorrelacaoHttpIT` na notificação e no histórico; `CorrelacaoHttpIT` no agendamento para 401/403 |
| Correlação assíncrona | Mesmo identificador nos três logs: execução real única do smoke. Consumidor do histórico: `CorrelacaoHistoricoIT` e X3. Registro ausente ou divergente: negativos sintéticos do `RoteiroDeSmokeTest` (códigos 3 e 1). Preservados: `CorrelacaoNotificacaoIT` e os testes do relay |
| Endpoints operacionais | `EndpointsOperacionaisIT` ×3; `CadeiaDeSegurancaIT` preservado; teste de `SegurancaAutoConfiguration`; X4 |
| Logs estruturados | smoke real (JSON e campos); `LogsEstruturadosTest`; X5 |

### D10. Documentação, verificação e fechamento

- **README:** Actuator, correlação ponta a ponta, logs JSON e smoke com a nova verificação. `jq` aparece só como pré-requisito do smoke real, e não do build.
- **`docs/01`:** §9 Observabilidade com `prometheus`, a proteção e o formato; §10 com a regra ArchUnit efetiva.
- **ADR-007:** status com a evidência do M11 e a reconciliação do `JwtService`.
- **`docs/02` e `docs/04`:** rastreabilidade de RNF-05 e RNF-08 e linha do M11, na apply.
- **Verificação:**
  - suítes dirigidas;
  - `mvn -q clean verify` sem filtro, com auditoria e pisos 85/90/90;
  - cobertura do código novo;
  - uma execução real do smoke na árvore da feature;
  - clone limpo, em temporário validado, só com `mvn -q -Dmaven.repo.local=<repo> clean verify`. O clone prova build e testes sem dependência local omitida, e o smoke não é repetido nele;
  - conferência do escopo no diff.
- **Fechamento:** PR com a matriz dos 23 Scenarios; archive na feature branch depois da aprovação e antes do merge.

### D11. Reuso de contextos de teste e listeners concorrentes

O objetivo é **impedir um segundo consumidor ou contexto** na mesma fila, não desligar todo listener em testes HTTP. Os novos ITs reutilizam **exatamente** as bases e os contextos existentes.

- **Onde vale a proibição.** Nas novas classes-folha de IT, nenhuma declaração local — `@MockBean`, `@SpyBean`, `@MockitoBean`, `@TestConfiguration`, `@Import`, `properties` ou `@DynamicPropertySource` próprios — que altere a chave do cache de contexto e, com isso, crie um segundo contexto.
- **Bases existentes intocadas.** As anotações das classes-base, como `@Import`, `@TestConfiguration` e `@SpyBean`, são legítimas, continuam permitidas e não são alteradas. Nenhuma dessas anotações cria contexto concorrente por si só; o problema é a declaração local que diverge da base.
- **Espiões:** qualquer espião necessário à nova suíte usa o que a base já declara; se faltar, a apply para e reporta, em vez de declará-lo na folha.
- **Por serviço:**
  - **Histórico:** `CorrelacaoHistoricoIT` prova o listener real e reutiliza a `HistoricoITBase`. `CorrelacaoHttpIT` e `EndpointsOperacionaisIT` reutilizam a `GraphqlITBase`, cujo listener já está parado.
  - **Notificação:** `CorrelacaoHttpIT` e `EndpointsOperacionaisIT` reutilizam a `NotificacaoITBase` exatamente como está, inclusive com o listener canônico ativo, sem propriedades nem configuração local. Compartilham o contexto das suítes de consumo e não criam um segundo consumidor.
  - **Agendamento:** sem listener AMQP. Os novos ITs repetem a configuração do `CadeiaDeSegurancaIT` — `@SpringBootTest(RANDOM_PORT)` e o mesmo `@DynamicPropertySource` — para reaproveitar o contexto em cache.
- **Verificação no gate conjunto (Checkpoint P0.2):**
  - as suítes de consumo que contam mensagens — `ConsumoNotificacaoRabbitMqIT`, `ConsumoHistoricoRabbitMqIT` e `CorrelacaoNotificacaoIT` — rodam no mesmo `verify` que os ITs novos e continuam verdes;
  - o diff confirma que as novas classes-folha não declaram configuração local que altere a chave do cache e que as classes-base não mudaram.

**Alternativa descartada:** contextos próprios por suíte, com espiões locais. O M06 e o M09 mostraram que um segundo contexto com listener ativo consome mensagens de outra suíte, e o sintoma aparece longe da causa.

## Risks / Trade-offs

- **[Suíte ArchUnit invisível para a auditoria do M10]** → regras em `@Test` com o ArchUnit core, sem engine novo (D2). O gate global prova que o relatório é reconhecido.
- **[Actuator entrar nos inventários de superfície do M10]** → mapeamento separado (D5). A apply roda os três inventários e para se algum mudar.
- **[Propriedades `logging.structured.json.add.*` não honradas na versão fixada]** → a apply verifica numa execução real, pelo campo `service` no JSON. Se não funcionar, para e consulta, sem trocar de formato por conta própria.
- **[Linhas não JSON no log com o profile `docker`, como banner e avisos da JVM]** → o `jq` usa `fromjson?` e só os registros relevantes são exigidos.
- **[Parser de porta com JSON]** → novo caso do `RoteiroDeSmokeTest` e a execução real única.
- **[`X-Correlation-Id` arbitrário é ecoado em header e log]** → comportamento existente desde o M03, estendido aos satélites por decisão aprovada. O Tomcat recusa CR/LF em header, e o JSON escapa caracteres de controle. Não há validação nova no M11, porque isso mudaria o comportamento já promovido do agendamento. Fica registrado como risco residual para o relatório do M14.
- **[Logs de publicação e de projeção emitidos antes do commit]** → os dois registram uma tentativa bem-sucedida até aquele ponto, não auditoria nem prova de commit. Falha de commit causa republicação do relay ou reentrega ao histórico, com novo log. A prova do efeito confirmado continua sendo o estado persistido e a leitura por GraphQL (D4). Transação, outbox e garantia de entrega não mudam.
- **[Segundo contexto Spring com listener ativo roubando mensagens]** → reuso exato das bases, intocadas, e proibição de configuração local que altere a chave do cache nas novas classes-folha (D11), verificados no gate conjunto com as suítes de consumo.
- **[Negativos do smoke tornarem `jq` requisito do gate Maven]** → evitado. O `RoteiroDeSmokeTest` injeta um `jq` controlado por `JQ_BIN` (D7), e `jq` continua pré-requisito só do smoke real. Trade-off: o texto do filtro `jq` não é exercido no gate; ele é provado na execução real da 7.1, e os negativos provam a decisão de código 3 ou 1 e os argumentos da execução.
- **[Versão do ArchUnit indisponível]** → versão fixada em `1.4.1`; a apply para e exige `/opsx:update`.
- **[Métricas exigem JWT]** → um scraper externo precisará de token. Aceito: o M12 usa só `health`, que é público.
- **[Health sem os indicadores `mail` na notificação e `rabbit` no agendamento]** → SMTP indisponível e broker indisponível no agendamento não aparecem no `health`. É deliberado: o sender padrão é `log`, e o ADR-006 mantém a API disponível sem broker (D5). Quando o SMTP for ativado com o Mailpit, o M12 decide explicitamente se reabilita `management.health.mail.enabled` no Compose.
- **[`MDC.clear()` apaga outras chaves do MDC na thread do listener]** → não há outras chaves nessa thread, e é o contrato normativo.
- **[Três cópias do filtro divergirem no futuro]** → testes equivalentes nos três serviços; a unificação fica para quando houver motivo funcional.
- **[Cobertura do código novo]** → filtros, MDC, logs e configuração de segurança são exercitados por testes próprios; os pisos são conferidos no gate.

## Migration Plan

- **Implantação:** sem migração de dados nem de contrato. O Actuator e os logs de sucesso são aditivos; o formato JSON só vale com o profile `docker`, que nenhum ambiente atual ativa.
- **Rollback:** reverter o merge. Nada persiste fora do código.
