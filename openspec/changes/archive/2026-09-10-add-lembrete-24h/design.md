## Context

O M06 entregou o que o lembrete precisa ler e onde ele precisa escrever: `agenda_local` materializada por qualquer um dos cinco eventos, monotônica por `occurredAt`, com o cancelamento preservado como status; `notificacao_enviada` como registro do que transações confirmadas produziram; `NotificationSenderPort` com adaptador selecionado por `notificacao.sender`; e um único `Clock` publicado em `NotificacaoConfig`. O pacote `scheduler/` existe vazio. O serviço não expõe HTTP algum e não depende de `shared-security`. Motivação em `proposal.md`; comportamento em `specs/notificacoes-ao-paciente/spec.md`.

Quatro restrições herdadas moldam o desenho:

- **A garantia do M06 é sobre o estado persistido.** D5 do M06 declarou o envio externo ao-menos-uma-vez. O lembrete não pode prometer mais que isso sem outbox próprio.
- **`notificacao_enviada` não tem unicidade por `(consulta_id, tipo)`**, de propósito: D2 do M06 deixou essa decisão para cá. As notificações reativas repetem tipo legitimamente — duas remarcações geram duas linhas `CONSULTA_ATUALIZADA`.
- **A cobertura estrutural de segredo do M06 recusa um placeholder de chave sensível cujo fallback não seja um valor local curto.** Isso colide com o padrão que agendamento e histórico usam para `JWT_SECRET` — ver D7.
- **A matriz de `docs/02` §3 é lida em tempo de teste por janelas entre marcadores.** O leitor do agendamento vai de `### agendamento-service — REST` a `### historico-service`; o do histórico vai de `### historico-service — GraphQL` a `**Teste obrigatório:**`. Uma tabela nova depois da GraphQL cai dentro da janela do histórico — ver D7.

## Goals / Non-Goals

**Goals:**

- Um caso de uso único de lembrete, chamado pelo job e pelo endpoint, com janela, status e unicidade decididos pelo PostgreSQL.
- Unicidade de lembrete por consulta que resista a execuções concorrentes sem depender de disciplina do código.
- Evidência de SQL real, de contagem de comandos e de plano de execução — não de SQL escrito pelo teste.
- Endpoint de demonstração protegido pela cadeia compartilhada, com todo erro HTTP em Problem Detail — 401 e 403 de `RespostaDeSeguranca`, e 500 do próprio serviço.
- Autorização do endpoint registrada na matriz normativa de `docs/02` §3, com um teste de integração por célula alimentado pelo documento.

**Non-Goals:**

- Outbox do serviço de notificação, lembretes em múltiplos horários (D-7, H-2), preferência de canal por paciente, retenção de `notificacao_enviada`.
- Qualquer item de M10 a M14, CHANGELOG, versão ou release.

## Decisions

### D1. Janela `(agora, agora + 24h]`, com `agora` do `Clock` e limites como parâmetros

`agora` é `clock.instant()` lido uma vez por execução; `limite` é `agora + 24h`. O comando recebe os dois como parâmetros e compara `data_hora > agora AND data_hora <= limite`. Nenhum `now()`, `CURRENT_TIMESTAMP` ou equivalente do banco: com o relógio do banco, o teste com `Clock` fixo mediria uma janela e a produção aplicaria outra, e as bordas deixariam de ser asseráveis por valor exato.

As bordas são as aprovadas. O limite inferior aberto exclui a consulta que está acontecendo agora e as passadas — lembrar uma consulta em curso não serve para nada. O limite superior fechado garante que uma execução exatamente 24 horas antes pega a consulta, em vez de adiá-la uma hora.

Com execução horária, a consulta entra na janela na primeira execução em `[X − 24h, X)` e é lembrada entre 24 e 23 horas antes. Consulta criada com menos de 24 horas de antecedência é lembrada na execução seguinte, eventualmente minutos depois da confirmação reativa — é o comportamento de "ainda não lembrada", e não um defeito.

Status elegíveis são `AGENDADA` e `CONFIRMADA`, como literais no comando. `CANCELADA` e `REALIZADA` ficam de fora pelo mesmo recorte, qualquer que seja o horário. Isso só é possível porque o M06 atualiza o status em vez de apagar a linha: a agenda distingue "cancelada" de "inexistente".

### D2. `V2` cria a unicidade parcial e o índice de status/horário; `V1` não é tocada

```sql
CREATE UNIQUE INDEX uq_notificacao_enviada_lembrete_d1
    ON notificacao_enviada (consulta_id) WHERE tipo = 'LEMBRETE_D1';
CREATE INDEX idx_agenda_local_status_data_hora
    ON agenda_local (status, data_hora);
```

**Unicidade parcial, e não por `(consulta_id, tipo)`.** Uma unicidade total recusaria a segunda remarcação de uma consulta, quebrando a notificação reativa do M06. O predicado restringe a regra ao único tipo que a exige. `V1` é imutável — reescrevê-la quebraria o checksum de quem já a aplicou.

**Índice composto `(status, data_hora)`.** Sustenta o recorte do comando: igualdade em dois valores de status e intervalo em `data_hora`, que o PostgreSQL resolve como duas faixas do mesmo índice. Alternativas descartadas:

- *Índice parcial em `data_hora WHERE status IN (...)`*: menor, mas o planner só o usa se provar que o predicado da consulta implica o do índice. Com status em parâmetro e plano genérico de prepared statement, essa prova falha, e o índice deixa de ser usado sem aviso.
- *Só `data_hora`*: resolve a janela, mas lê e descarta as canceladas e realizadas do intervalo; o pedido aprovado é um índice que sustente status **e** horário.
- *`(data_hora, status)`*: a coluna de intervalo na frente impede que o status restrinja a faixa lida.

A exclusão de consultas já lembradas é servida pela própria unicidade parcial, que é um índice em `consulta_id` restrito a `LEMBRETE_D1`.

O plano é **medido, não suposto**, como no M09: massa representativa e seletiva, `ANALYZE`, planner em condições normais — sem `enable_seqscan=off`, que só pergunta qual índice o PostgreSQL usaria se fosse obrigado. Se, mesmo assim, ele preferir varredura sequencial, a evidência contradiz esta decisão: o apply para e a reabre por `/opsx:update`, e o planner nunca é forçado até o teste ficar verde.

### D3. Um único comando traz os candidatos

```sql
SELECT a.consulta_id, a.paciente_nome, a.paciente_email, a.medico_nome, a.data_hora
FROM agenda_local a
WHERE a.status IN ('AGENDADA', 'CONFIRMADA')
  AND a.data_hora > ? AND a.data_hora <= ?
  AND NOT EXISTS (SELECT 1 FROM notificacao_enviada n
                  WHERE n.consulta_id = a.consulta_id AND n.tipo = 'LEMBRETE_D1')
ORDER BY a.data_hora, a.consulta_id
```

É um comando por execução, com a anti-junção dentro dele; o laço sobre o resultado não lê nada. A ordenação é total e determinística, o que torna asseráveis a ordem de envio e a ordem dos registros. Não há paginação: o volume é o de um dia de consultas.

O comando vive numa constante do código de produção e é emitido por `JdbcTemplate`. JPA foi descartado nos dois lados: uma query derivada esconderia o SQL que a evidência precisa ler, e gravar o registro por `JpaRepository.save` com id atribuído faz `merge()` — um `SELECT` por candidato, que é exatamente o N+1 que esta decisão proíbe, só que escondido. Além disso, `save` não expressa `ON CONFLICT`, de que D4 depende.

O `NOT EXISTS` é filtro de eficiência, e não a garantia. Ele evita reprocessar consultas já lembradas nas até 24 execuções em que elas continuam na janela; quem garante unicidade é D4.

### D4. Reserva antes do envio, uma transação por candidato

Para cada candidato, uma transação `REQUIRED` própria faz, nesta ordem:

1. `INSERT INTO notificacao_enviada (...) VALUES (...) ON CONFLICT (consulta_id) WHERE tipo = 'LEMBRETE_D1' DO NOTHING`, com destinatário, canal, conteúdo e `enviado_em = clock.instant()` — tudo conhecido antes do envio;
2. se a gravação afetou zero linhas, outra execução já tem aquela consulta: encerra sem enviar;
3. se afetou uma, chama `sender.enviar(mensagem)`;
4. confirma. Exceção em qualquer passo reverte a reserva.

**Por que isso impede duplicidade entre execuções concorrentes.** Sejam T1 e T2 duas execuções — job e endpoint, ou duas instâncias — que selecionaram a mesma consulta, porque o `NOT EXISTS` de ambas rodou antes de qualquer confirmação:

- T1 insere e passa a deter, sem confirmar, a entrada daquela chave na unicidade parcial.
- O `INSERT` de T2 na mesma chave **espera** T1 terminar: é a inserção especulativa do `ON CONFLICT` do PostgreSQL.
- T1 envia e confirma → T2 retoma, encontra o conflito, `DO NOTHING`, zero linhas, **não envia**. Uma entrega, um registro.
- T1 falha no envio e reverte → T2 retoma, insere, envia e confirma. Uma entrega — a de T1 falhou —, um registro.
- T1 envia e a confirmação falha → T2 insere e envia de novo. Duas entregas, um registro: é a janela residual da spec, a mesma do M06, e o desenho não a esconde.

Nada disso depende de o código lembrar de checar antes: a regra está no índice, e qualquer outro escritor SQL a encontra.

**A ordem difere da do M06, e isso é deliberado.** No consumidor, o envio vem antes da auditoria, e a ordem é indiferente porque a unicidade vem da marca de `evento_processado`. Aqui a gravação **é** a reserva; enviar antes dela reproduziria o defeito do M06 D5 — duas execuções concorrentes acionando o canal antes de a chave decidir. O registro continua significando o que a spec diz: a linha só confirma se o envio retornou normalmente.

**Uma transação por candidato, e não uma para a execução.** Uma transação única faria a falha do décimo candidato reverter os nove anteriores — que já foram entregues —, e a execução seguinte reenviaria nove lembretes. O laço não é transacional; a operação por candidato é outro bean, chamado pelo proxy — auto-invocação ignoraria o `@Transactional`. Uma cobertura estrutural fixa as duas fronteiras e a ordem reserva→envio no código-fonte, como o M06 fez para efeito→marca.

A leitura dos candidatos roda fora de transação e não trava a `agenda_local`. A reserva não trava linha alguma da agenda, então o consumidor do M06 nunca espera pelo lembrete.

**Alternativas descartadas:**

- *Check-then-act* — consultar a ausência e gravar depois: duas execuções passam juntas pela checagem. Recusado pela decisão aprovada.
- *Enviar e depois gravar com unicidade* — a ordem do M06: a chave decide, mas só depois de as duas execuções terem acionado o canal.
- *`SELECT ... FOR UPDATE SKIP LOCKED` na `agenda_local`* — trava as linhas que o consumidor do M06 atualiza, bloqueando o processamento de eventos durante o envio, e não dá unicidade durante a vida da consulta: a execução da hora seguinte lembraria de novo.
- *Lock consultivo por execução* (`pg_try_advisory_lock`) — serializa execuções, mas não dá unicidade durável e depende de todo caminho adquiri-lo.
- *Coluna `lembrado_em` na `agenda_local`* — acopla o lembrete à linha que o upsert do M06 reescreve, e contraria a decisão aprovada de identificar o lembrete em `notificacao_enviada`.

### D5. Falha isolada por candidato; falha da leitura inicial em Problem Detail 500

Exceção na operação de um candidato — do sender ou da gravação — já chega ao laço com a transação revertida. O laço registra `WARN` com o `consulta_id`, sem e-mail nem nome do paciente, e segue para o próximo. A consulta continua sem registro, portanto elegível na execução seguinte enquanto estiver na janela.

A execução devolve a quantidade de lembretes **confirmados**, que é o que o endpoint responde. Falhas por candidato não entram na resposta: a decisão aprovada pede a quantidade enviada, e acrescentar contadores seria contrato novo. Elas ficam no log.

Falha **da execução inteira** — o comando de candidatos não consegue ler o banco — propaga para fora do caso de uso antes de qualquer reserva ou envio. No job, o agendador do Spring registra o erro, e a hora seguinte tenta de novo. No endpoint, ela vira **500 em Problem Detail**, como todo erro HTTP do projeto. A resposta tem:

- `application/problem+json` e `type` `https://hospital.fiap.br/erros/erro-interno`, o mesmo identificador que o agendamento já usa;
- título "Erro interno" e detalhe fixo e genérico em português;
- `instance` com o caminho da requisição e `timestamp` do `Clock`;
- `correlationId` resolvido como em `RespostaDeSeguranca`: atributo da requisição, depois `X-Correlation-Id`, depois um identificador gerado.

SQL, nome de tabela, credencial, classe de exceção e stack trace não saem na resposta; a causa vai para o log em `ERROR`, com o `correlationId`.

**Tratador estreito, que não intercepta a recusa de segurança.** Um `@RestControllerAdvice(assignableTypes = LembreteController.class)` com um único `@ExceptionHandler(Exception.class)`. `AccessDeniedException` e `AuthenticationException` são **relançadas intactas** logo na entrada do método. O `ExceptionHandlerExceptionResolver` trata o relançamento da exceção original como "não resolvida" e segue o processamento padrão, que a leva ao `ExceptionTranslationFilter` e a `RespostaDeSeguranca`. Assim 401 e 403 continuam saindo do mesmo componente dos outros serviços, e todo o resto vira o 500 acima. O escopo por `assignableTypes` impede que o tratador alcance controllers futuros sem decisão própria.

Alternativas descartadas:

- *500 padrão do container* — contraria a convenção de Problem Detail com `correlationId` e `timestamp`.
- *Tratador só de `DataAccessException`* — estreito por tipo, mas qualquer outra falha inesperada voltaria ao 500 do container.
- *Tratador genérico sem relançamento* — captura o `AccessDeniedException` do `@PreAuthorize` e transforma 403 em 500, o defeito descrito em `docs/01` §8.
- *`spring.mvc.problemdetails.enabled`* — cobre exceções do próprio Spring MVC, não a falha da aplicação, e não acrescenta `correlationId` nem `timestamp`.

A prova é **comportamental**, e não a presença ou a ausência de uma anotação. O Scenario de falha inicial exige o 500 em Problem Detail, e `MatrizDeAutorizacaoNotificacaoIT#pacienteERecusadoSemExecutarAVarredura` exige o 403 de `RespostaDeSeguranca`. Remover a tradução deixa o primeiro vermelho; remover o relançamento deixa o segundo vermelho.

A falha provocada no teste é real, não simulada. O teste renomeia `agenda_local` por DDL durante a chamada e a restaura em `finally`; o PostgreSQL devolve um erro verdadeiro, com o SQL na mensagem — exatamente o que não pode vazar.

### D6. Job horário, cron configurável, ausente no profile `test`

`AgendadorDeLembretes` segue o precedente do `OutboxScheduler` do agendamento: `@Component`, `@EnableScheduling` e `@ConditionalOnProperty(name = "notificacao.lembrete.agendador-habilitado", havingValue = "true", matchIfMissing = true)`, com um método `@Scheduled(cron = "${notificacao.lembrete.cron:0 0 * * * *}")` que só chama o caso de uso. O `application.yml` expõe as duas propriedades por `NOTIFICACAO_LEMBRETE_CRON` e `NOTIFICACAO_LEMBRETE_AGENDADOR_HABILITADO`, e um documento `on-profile: test` põe `agendador-habilitado: false`.

Com a propriedade falsa, o bean não existe e, com ele, não existe `@EnableScheduling` no módulo: não há tarefa registrada, e não apenas uma tarefa que ainda não disparou. Esperar alguns segundos e ver que nada aconteceu provaria pouco com um cron horário — a evidência é a ausência de registro.

Os testes ativam o profile `test` por `src/test/resources/application.properties` (`spring.profiles.active=test`), e não por `@ActiveProfiles` em cada suíte: uma suíte nova que esquecesse a anotação subiria com o agendador ligado. O arquivo é `.properties` para não sombrear o `application.yml` de produção, que precisa continuar sendo lido. O mesmo arquivo carrega o segredo JWT de teste (D7).

A **hora em que o job dispara** vem do agendador do Spring, e não do `Clock`; o `Clock` governa a **janela**, que é a regra de negócio. Tornar o gatilho dependente do `Clock` não acrescentaria nada verificável.

Job e endpoint têm uma única dependência cada — o caso de uso — e nenhuma regra própria. Uma cobertura estrutural recusa `JdbcTemplate`, `Clock`, `NotificationSenderPort` ou SQL nas duas classes.

### D7. Endpoint na cadeia compartilhada, `/internal/**` autenticado, decisão de perfil no método

`LembreteController` expõe `POST /internal/lembretes/executar` com `@PreAuthorize("hasAnyRole('MEDICO','ENFERMEIRO')")` e devolve `ResultadoDaExecucao(int lembretesEnviados)`.

O serviço passa a depender de `shared-security` e configura `hospital.security.caminhos.autenticados: /internal/**`, **substituindo** o padrão `/api/**`: o serviço não tem `/api`, e manter o padrão tornaria essa família "autenticada sem handler" em vez de negada por omissão. Continua existindo uma única `SecurityFilterChain`, a da auto-configuração, com `denyAll` para o resto; um teste de contexto exige exatamente uma, e a cobertura estrutural recusa `@EnableWebSecurity` ou bean de cadeia no módulo. Recusas: 401 pelo `AuthenticationEntryPoint` e 403 pelo `AccessDeniedHandler`, ambos em `RespostaDeSeguranca` (ver D5 para o tratador de erro interno, que não as intercepta).

**Cobertura estrutural contra endpoint sem `@PreAuthorize`**, no formato do M09:

- varre as classes compiladas do módulo e exige anotação de método sem `permitAll` em todo mapeamento HTTP;
- exige que a varredura encontre exatamente o endpoint esperado — senão ela pode estar varrendo nada;
- compara os mapeamentos encontrados com os endpoints da tabela do notificação em `docs/02` §3: endpoint novo sem linha no documento fica vermelho;
- prova sensibilidade com um infrator nas classes de teste, **encontrado** pela mesma varredura apontada para aquela raiz.

**Célula na matriz normativa de `docs/02` §3.** O endpoint entra na documentação normativa como tabela própria, `### notificacao-service — REST interno`, entre a tabela GraphQL e a nota `**Teste obrigatório:**`. A tabela usa a mesma disposição de colunas da do agendamento — `| /internal/lembretes/executar | POST | ✅ | ✅ | ❌ 403 |`, isto é, `POST /internal/lembretes/executar | MEDICO ✅ | ENFERMEIRO ✅ | PACIENTE ❌ 403`. Os leitores são ajustados ao documento, e não o contrário:

- **Agendamento:** o leitor termina em `### historico-service` e não enxerga a tabela nova. A asserção positiva que ele já tem — 7 endpoints, 3 perfis e 21 células — continua valendo sem mudança de código.
- **Histórico:** o leitor terminava em `**Teste obrigatório:**` e passaria a ler a tabela nova como operação GraphQL. A única mudança é o fim, que passa a ser o título novo. A asserção positiva que ele já tem — `MatrizDeAutorizacaoGraphqlIT#leituraEncontrouAMatrizCompleta`, com 5 operações, 3 perfis e 15 células — é preservada sem duplicação e acusa uma janela mal ajustada.
- **Notificação:** ganha leitor próprio, do título novo até `**Teste obrigatório:**`, com asserção positiva de exatamente 1 endpoint, 3 perfis e 3 células.

O teste de integração por célula é **alimentado pelo documento**, com um método explícito por célula — `medicoDisparaAExecucao`, `enfermeiroDisparaAExecucao` e `pacienteERecusadoSemExecutarAVarredura` —, cada um com o `@DisplayName` exato do seu Scenario. Um método parametrizado único não serviria: teria um só `@DisplayName` para três Scenarios, e a rastreabilidade Scenario → método deixaria de ser verificável. Cada método busca no leitor a célula do seu perfil e entrega a expectativa lida a um helper comum, que exige, conforme a célula, 200 com `lembretesEnviados` 1 e registro, ou 403 de acesso negado sem envio nem registro. Não existe uma segunda política escrita no teste: alterar uma célula do documento muda a expectativa e deixa o método vermelho, e remover uma célula faz a busca falhar e quebra a asserção positiva de 1 endpoint, 3 perfis e 3 células.

**`JWT_SECRET` sem valor padrão — conflito resolvido.** Agendamento e histórico usam `${JWT_SECRET:troque-este-segredo-...}`. No notificação, esse fallback é recusado pela cobertura de segredo do M06: a variável contém `SECRET` e o valor não é local e curto. Relaxar a guarda enfraqueceria uma cobertura promovida. A decisão é `secret: ${JWT_SECRET}`, sem fallback: sem a variável, `JwtProperties` impede a partida — falha barulhenta em vez de assinatura fraca.

Nem o Maven nem o Spring leem `.env`. O README mostra a exportação explícita — `set -a; . ./.env; set +a` na mesma sessão de shell que sobe agendamento e notificação —, porque o token é assinado pelo agendamento e validado aqui com o mesmo segredo. Os testes recebem o segredo por `src/test/resources`, fora da varredura de `src/main`.

**`shared-security` sem mudança de comportamento.** Atualizam-se os comentários que ainda descrevem dois consumidores — `SegurancaAutoConfiguration`, `SegurancaAutoConfigurationTest` — e o comentário obsoleto do POM do notificação. Um caso novo em `SegurancaAutoConfigurationTest` prova que `/internal/**` entra por configuração sem segunda cadeia e que `/api/**`, quando substituído, cai no `denyAll`.

### D8. Reuso do M06 sem alterar o que ele garante

- **Agenda:** somente leitura. Consumidor, processador, upsert condicionado e listener não mudam.
- **Registro:** a mesma tabela e a mesma entidade `NotificacaoEnviada`; o M07 grava por JDBC por causa de D3 e D4.
- **Porta e adaptadores:** os mesmos, selecionados por `notificacao.sender`. O canal gravado é `sender.canal()`.
- **Templates:** `TemplatesDeNotificacao` ganha o texto do lembrete, em português, com assunto "Lembrete de consulta". O mapa que decide quais **eventos** notificam não muda: o lembrete não é `TipoEvento`, e colocá-lo no mapa exigiria inventar um tipo normativo.
- **Fuso:** `data_hora` é `timestamptz` e guarda o instante, não o deslocamento. O template formata em `America/Sao_Paulo`, o fuso em que o agendamento deriva a data da consulta (M05). Formatar em UTC diria ao paciente um horário três horas adiantado.
- **`LEMBRETE_D1` fora do contrato:** a cobertura estrutural exige os cinco valores de `TipoEvento` e a ausência de `LEMBRETE` em `shared-contracts`; o caso de uso não tem dependência de mensageria.
- **Guarda de relógio:** passa a recusar também `now()` isolado, `current_timestamp`, `localtimestamp`, `clock_timestamp()` e `statement_timestamp()` em `src/main`. Hoje ela só pega `now(),` e `now())`, e um `data_hora > now()` seguido de quebra de linha passaria. É fortalecimento, não afrouxamento.

### D9. Testes na infraestrutura real, num único contexto

**Um contexto.** PostgreSQL 16 e RabbitMQ 3.13 por Testcontainers, compartilhados. Tudo o que as suítes novas precisam entra em `NotificacaoITBase`: `@AutoConfigureMockMvc`, os novos `@SpyBean` do caso de uso e da operação por candidato, e o gravador de SQL. Um `@SpyBean` ou uma propriedade declarados só numa suíte criariam um segundo contexto com um segundo listener na mesma fila — foi o defeito que o M06 documentou. O `MockMvc` atravessa a cadeia real de filtros, então 401 e 403 saem dos mesmos componentes de produção. As provas do agendador que precisam de outra configuração usam `ApplicationContextRunner` apenas com as classes do agendador: sem listener, sem fila, sem concorrência com a base.

**Relógio e dados determinísticos.** O `Clock` fixo da base (`2026-09-11T12:00:00Z`). As bordas são semeadas por SQL, com `data_hora` exata. A remarcação passa pelos eventos reais no RabbitMQ, para exercitar a agenda do M06 de ponta a ponta.

**SQL real e contagem.** Um gravador na base embrulha a `DataSource` e registra o texto de todo comando preparado ou executado, só na thread e na janela armadas pelo teste. O `StatementInspector` do M09 não serve: ele vê apenas o Hibernate, e o lembrete emite por JDBC. A prova de recortes lê o comando capturado. A prova de plano roda `EXPLAIN` sobre **o texto capturado**, com os parâmetros do relógio fixo — nunca sobre SQL escrito no teste. A contagem compara uma execução com 1 candidato e outra com 20: um comando de leitura em cada, dos dois lados positivo.

**Concorrência.** Duas threads chamam o caso de uso, e a barreira da base, reaproveitada do M06, as libera na **entrada da operação por candidato**: as duas já executaram a leitura e ainda não reservaram. O teste exige um registro, uma chamada ao canal e soma de contagens igual a um. As duas threads ocupam duas das três conexões do pool de teste; o listener ocioso não ocupa nenhuma. Elas chamam o caso de uso, e não o endpoint e o job, porque o agendador não existe no profile `test` e a delegação dos dois é provada à parte (D6).

**Mutações de sensibilidade.** Cada uma altera o que é verificado, nunca a verificação, e é restaurada antes da próxima:

| Mutação | Cobertura que fica vermelha |
|---|---|
| (a) `<=` do limite superior vira `<` | borda "exatamente 24 horas" |
| (a') limite superior ampliado em um minuto | borda "24h01" |
| (b) recorte de status removido do comando | "canceladas e realizadas nunca são lembradas" |
| (c) envio antes da reserva | concorrência (duas chamadas ao canal) e cobertura de sequência |
| (c') unicidade parcial removida de `V2` | "armazenamento recusa segundo lembrete" |
| (c'') `NOT EXISTS` removido do comando | "recortes aplicados no comando enviado" |
| (d) `@PreAuthorize` removido do endpoint | `pacienteERecusadoSemExecutarAVarredura` (200 em vez de 403) e cobertura estrutural de autorização |
| (e) tradução removida do tratador de erro interno | "falha na leitura inicial responde 500 em Problem Detail" |
| (e') relançamento de `AccessDeniedException` removido do tratador | `pacienteERecusadoSemExecutarAVarredura` (500 em vez de 403) |
| (f) célula `MEDICO` do documento alterada para `❌ 403` | `medicoDisparaAExecucao` (a expectativa lida vira 403 e o endpoint responde 200) |
| (f') célula `ENFERMEIRO` ou a linha inteira removida do documento | `enfermeiroDisparaAExecucao` (célula não encontrada) e asserção positiva de 1 endpoint, 3 perfis e 3 células |

**Matriz Scenario → método.** Fica em `tasks.md` e numa cobertura estrutural que a mantém em código. Ela falha nos seguintes casos:

- a matriz está vazia;
- um método não existe ou não é teste;
- o `@DisplayName` não é `Scenario: <título>`, com comparação sem acentos e sem caixa, como os nomes do M06;
- os títulos divergem dos Scenarios dos seis Requirements do M07 lidos da spec — do delta enquanto a change está ativa, da capability promovida depois do archive.

Não há exceção para método parametrizado: cada Scenario, inclusive os três de autorização por perfil, aponta um método próprio com o `@DisplayName` do seu título.

**Testes existentes que mudam.** Nenhum Scenario do M06 é tocado. `SchemaNotificacaoIT` tinha três asserções estruturais que descreviam o estado anterior, e elas evoluem sem afrouxar: passa a exigir **exatamente** as PKs de `V1` mais os dois índices de `V2`, dois scripts aplicados, e `V1` sem `CREATE INDEX`; `servicoNaoExpoeEndpointNovo` passa a exigir exatamente o controller do lembrete. No histórico, só o marcador final do leitor da matriz muda; `leituraEncontrouAMatrizCompleta` é preservado como está, sem duplicação, e precisa continuar verde. No agendamento, nada muda.

## Risks / Trade-offs

- [Envio concluído e confirmação falhando gera reentrega na execução seguinte] → declarado na spec, no README e no PR. Um outbox local fecharia a janela, e vira change própria se algum requisito passar a exigi-lo.
- [A segunda execução concorrente espera a primeira enviar, com a chave travada] → a espera dura um envio. O adaptador padrão é local, o de correio fala com servidor local no M12, e o volume é baixo. Timeouts SMTP são configuração do M12.
- [Cancelamento aplicado durante a execução, depois da leitura e antes do envio, ainda recebe lembrete] → a janela é a duração de um envio. Na prática equivale a um cancelamento logo depois do lembrete, e o aviso reativo de cancelamento do M06 chega em seguida. Fechá-la exigiria travar a linha da agenda contra o consumidor, descartado em D4.
- [Consulta remarcada depois do lembrete não recebe outro] → é a regra aprovada de um lembrete por vida da consulta. O aviso reativo de alteração do M06 informa o novo horário. O README declara o comportamento.
- [Várias instâncias disparando no mesmo minuto] → D4 serializa por consulta; não há eleição de líder a construir.
- [`notificacao-service` não sobe sem `JWT_SECRET`] → falha na partida com mensagem explícita. O README mostra a exportação a partir do `.env`, e o Compose do M12 injeta a variável.
- [O planner pode preferir varredura sequencial mesmo com a massa representativa] → regra de parada de D2: interromper o apply e reabrir a decisão, sem forçar o planner.
- [O tratador de erro interno pode, numa refatoração, passar a capturar a recusa de segurança e transformar 403 em 500] → relançamento explícito, escopo restrito ao controller do lembrete, e `pacienteERecusadoSemExecutarAVarredura`, com expectativa lida do documento, exigindo o 403 de `RespostaDeSeguranca`; a mutação (e') prova a sensibilidade.
- [Mover o fim do leitor GraphQL muda o que ele lê] → a asserção existente `leituraEncontrouAMatrizCompleta`, de 5 operações, 3 perfis e 15 células, é preservada e falha se a janela encolher ou crescer.
- [Mudanças na base de testes alcançam as suítes do M06] → tudo na base, um contexto só. A suíte inteira do módulo roda na verificação, com os 26 Scenarios do M06 verdes e zero ignorados.
- [Gravador de SQL embrulhando a `DataSource` pode afetar componentes que esperam `HikariDataSource`] → o módulo não tem Actuator nem métrica de pool; o gravador implementa só a interface e é validado pela suíte do M06 inteira rodando com ele.
- [A DDL que provoca a falha inicial pode deixar a tabela renomeada se o teste abortar] → restauração em `finally`, e o `@BeforeEach` da suíte confere o nome da tabela antes de truncar.
- [`notificacao_enviada` cresce, agora com um lembrete por consulta] → retenção exige change própria.

## Migration Plan

1. `V2` roda por Flyway antes da validação JPA e do listener. Cria dois índices em tabelas existentes; a unicidade parcial não encontra conflito, porque nenhuma linha `LEMBRETE_D1` existe antes do M07. `CREATE INDEX` sem `CONCURRENTLY` trava escrita por instantes, o que é aceitável no ambiente de demonstração.
2. Implantar com `JWT_SECRET` igual ao do agendamento. A primeira execução depois da subida lembra todas as consultas elegíveis da janela — é o comportamento pretendido.
3. Parada operacional do lembrete: `NOTIFICACAO_LEMBRETE_AGENDADOR_HABILITADO=false` remove o job e mantém consumidor e endpoint.
4. Rollback de aplicação: `V2` pode permanecer, porque o código do M06 não grava `LEMBRETE_D1` e não depende dos índices. **Não apagar** linhas `LEMBRETE_D1`: apagá-las faria a versão corrigida reenviar lembretes já entregues.
5. Após a aprovação do PR, arquivar a change **na mesma feature branch**, commitando o archive antes do merge.
