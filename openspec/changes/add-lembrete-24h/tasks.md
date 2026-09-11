# Tasks — M07

## 1. Build e configuração

- [x] 1.1 Adicionar `shared-security` às dependências do `notificacao-service` e corrigir o comentário obsoleto do POM, que ainda diz que o módulo fica de fora "até o M04"; verificar resolução com `mvn -q -pl notificacao-service -am test-compile`.
- [x] 1.2 Configurar no `application.yml`: `hospital.jwt.secret: ${JWT_SECRET}` **sem fallback**, expiração `PT8H` e emissor `hospital-agendamento`; `hospital.security.caminhos.autenticados: /internal/**`; `notificacao.lembrete.cron` (`${NOTIFICACAO_LEMBRETE_CRON:0 0 * * * *}`) e `notificacao.lembrete.agendador-habilitado` (`${NOTIFICACAO_LEMBRETE_AGENDADOR_HABILITADO:true}`); e um documento `on-profile: test` com `agendador-habilitado: false` (D6, D7). Verificar os valores efetivos em `ConfiguracaoNotificacaoIT`, e que `ProtecoesEstruturaisNotificacaoTest#nenhumValorSensivelLiteral` continua verde **sem alteração da guarda**.
- [x] 1.3 Criar `src/test/resources/application.properties` com `spring.profiles.active=test` e um segredo JWT de teste de pelo menos 32 bytes. Usar `.properties`, e não `application.yml`, para não sombrear a configuração de produção. Verificar em `ConfiguracaoNotificacaoIT` que o profile `test` está ativo no contexto da base, e que `SelecaoDeSenderIT` e as suítes do M06 sobem nele.
- [x] 1.4 Estender `NotificacaoITBase` com `@AutoConfigureMockMvc`, os `@SpyBean` do caso de uso e da operação por candidato, o gravador de SQL (5.1) e a emissão de tokens por perfil — tudo **na base**, nunca numa suíte (D9). Verificar que `ConfiguracaoNotificacaoIT#existeExatamenteUmListener` continua verde e que as suítes do M06 compartilham um único contexto.

## 2. Persistência

- [x] 2.1 Criar `V2__cria_indices_do_lembrete.sql` com `uq_notificacao_enviada_lembrete_d1` — único e parcial, em `(consulta_id) WHERE tipo = 'LEMBRETE_D1'` — e `idx_agenda_local_status_data_hora` em `(status, data_hora)`, sem tocar `V1` (D2). Verificar em `IndicesDoLembreteIT` nomes, colunas e predicado parcial lidos de `pg_indexes`, reaplicação num schema isolado sem nada executado e `ddl-auto=validate`.
- [x] 2.2 Evoluir as asserções estruturais de `SchemaNotificacaoIT` que descreviam o estado do M06, **sem afrouxá-las**: exatamente as PKs de `V1` mais os dois índices de `V2`; dois scripts aplicados; `V1` sem `CREATE INDEX` no arquivo; e exatamente o controller do lembrete como endpoint do serviço. Verificar a suíte verde.
- [x] 2.3 Provar em `IdempotenciaDoLembreteIT#armazenamentoRecusaSegundoLembreteDaMesmaConsulta` que uma segunda gravação direta de `LEMBRETE_D1` para a mesma consulta é recusada pelo PostgreSQL, e que dois registros `CONSULTA_ATUALIZADA` da mesma consulta continuam aceitos.

## 3. Caso de uso do lembrete

- [x] 3.1 Implementar o comando de candidatos de D3 como constante do código de produção, emitido por `JdbcTemplate`, com `agora` e `agora + 24h` do `Clock` injetado como parâmetros e ordenação `data_hora, consulta_id`. Verificar as bordas em `JanelaDoLembreteIT` com o `Clock` fixo da base: 23h59 recebe, exatamente 24h recebe, 24h01 não recebe, e o instante da execução ou um minuto antes não recebe.
- [x] 3.2 Restringir o comando a `AGENDADA` e `CONFIRMADA`; verificar em `JanelaDoLembreteIT` que ambas são lembradas e que `CANCELADA` e `REALIZADA` dentro da janela não recebem envio nem registro.
- [x] 3.3 Implementar a operação por candidato num bean próprio com `@Transactional(propagation = REQUIRED)`: reserva por `INSERT ... ON CONFLICT (consulta_id) WHERE tipo = 'LEMBRETE_D1' DO NOTHING`, encerramento sem envio quando nenhuma linha é afetada, e envio depois da reserva (D4). O laço que percorre os candidatos **não** é transacional. Verificar com cobertura estrutural em `ProtecoesEstruturaisNotificacaoTest`: fronteira transacional do candidato, ausência de `@Transactional` no laço, e a reserva antes do envio na sequência do código-fonte.
- [x] 3.4 Acrescentar o texto do lembrete a `TemplatesDeNotificacao` — português, assunto "Lembrete de consulta", data e hora formatadas em `America/Sao_Paulo` — sem alterar `notifica(TipoEvento)` nem o mapa de eventos (D8). Verificar em `RegistroDoLembreteIT#conteudoInformaMedicoEHorarioLocal` que 12/09/2026 14:30 UTC aparece como 12/09/2026 11:30, e que `NotificacaoReativaIT` continua verde.
- [x] 3.5 Gravar o registro com tipo `LEMBRETE_D1`, e-mail do paciente como destinatário, `sender.canal()`, `enviado_em` do `Clock` e o mesmo conteúdo entregue ao canal. Verificar em `RegistroDoLembreteIT#lembreteConfirmadoFicaRegistradoComoFoiEntregue`, comparando o registro com o argumento capturado no sender.
- [x] 3.6 Isolar a falha por candidato no laço, com `WARN` contendo apenas o `consulta_id`, e devolver a quantidade de lembretes confirmados (D5). Verificar em `IdempotenciaDoLembreteIT#falhaNoEnvioNaoDeixaLembreteEConsultaSegueElegivel` — nenhum registro após a falha e envio registrado na execução seguinte — e em `IdempotenciaDoLembreteIT#falhaEmUmaConsultaNaoImpedeAsDemais`, com o sender falhando só para um destinatário.
- [x] 3.7 Verificar em `JanelaDoLembreteIT#execucaoSemCandidatosTerminaSemEfeito` que a execução com agenda vazia devolve zero, não aciona o sender e não grava registro.
- [x] 3.8 Manter `LEMBRETE_D1` fora do contrato: cobertura estrutural exigindo os cinco valores de `TipoEvento`, nenhuma ocorrência de `LEMBRETE` em `shared-contracts` e nenhuma dependência de mensageria no caso de uso. Verificar também em `RegistroDoLembreteIT#tipoDoLembreteFicaForaDoContratoDeEventos` que, depois de uma execução com lembretes, as filas normativas e suas DLQs não recebem mensagem.

## 4. Unicidade e concorrência

- [x] 4.1 Verificar em `IdempotenciaDoLembreteIT#execucaoRepetidaNaoLembraDeNovo` que duas execuções sequenciais deixam um único registro, e que a segunda devolve zero sem acionar o sender.
- [x] 4.2 Verificar em `IdempotenciaDoLembreteIT#execucoesConcorrentesConfirmamEEntregamUmUnicoLembrete` duas execuções do caso de uso em threads reais, liberadas pela barreira da base na **entrada da operação por candidato** — depois de as duas lerem os candidatos, antes de reservarem (D9). Exigir um registro, **uma** chamada ao sender e soma das duas contagens igual a um.
- [x] 4.3 Verificar em `IdempotenciaDoLembreteIT#consultaRemarcadaDepoisDoLembreteNaoRecebeOutro`, com eventos reais no RabbitMQ: criação dentro da janela, execução com lembrete, atualização com `occurredAt` posterior e novo horário ainda na janela, nova execução sem segundo lembrete — e o aviso reativo de alteração do M06 registrado normalmente.

## 5. Evidência de SQL e de plano

- [x] 5.1 Criar na base o gravador de SQL que embrulha a `DataSource` e registra o texto de todo comando preparado ou executado, só na thread e na janela armadas pelo teste (D9). Verificar que ele captura um comando conhecido e ignora os de outra thread, e que as suítes do M06 continuam verdes com ele ativo.
- [x] 5.2 Verificar em `ConsultaDeCandidatosIT#recortesAplicadosNoComandoEnviado`, sobre o comando **capturado**: recorte de status, dois limites como parâmetros, `NOT EXISTS` sobre `LEMBRETE_D1`, e nenhum `now()`, `current_timestamp` ou equivalente.
- [x] 5.3 Verificar em `ConsultaDeCandidatosIT#maisCandidatosNaoMultiplicamLeituras` que execuções com 1 e com 20 candidatos emitem exatamente um `SELECT` cada — contagem positiva dos dois lados — e um número de gravações igual ao de lembretes confirmados.
- [x] 5.4 Verificar em `IndicesDoLembreteIT#planoUsaOIndiceDeStatusEHorario` o `EXPLAIN` do **texto capturado** em 5.2, com os parâmetros do relógio fixo, sobre massa representativa e seletiva — milhares de linhas em centenas de dias, os quatro status, lembretes prévios — e `ANALYZE`, sem `enable_seqscan=off`. Exigir `idx_agenda_local_status_data_hora` no plano. **Regra de parada:** se o planner escolher varredura sequencial, interromper o apply e reabrir D2 por `/opsx:update`.

## 6. Execução automática

- [x] 6.1 Criar `AgendadorDeLembretes` no pacote `scheduler`, removendo o `.gitkeep`, com `@EnableScheduling`, `@ConditionalOnProperty(matchIfMissing = true)` e `@Scheduled(cron = "${notificacao.lembrete.cron:0 0 * * * *}")` delegando ao caso de uso (D6). Verificar em `AgendadorDeLembretesTest`, com `ApplicationContextRunner` restrito às classes do agendador e **sem herdar o profile `test` dos recursos de teste**, que sem configuração a tarefa registrada tem cron `0 0 * * * *` e que uma expressão configurada substitui a padrão.
- [x] 6.2 Verificar a ausência no profile `test` em duas camadas. Em `AgendadorDeLembretesTest`: com o `application.yml` de produção e o profile `test`, o bean e a tarefa não existem. Em `AgendadorDesligadoIT#profileDeTesteNaoExecutaAutomaticamente`, no contexto da base: nenhum `AgendadorDeLembretes`, nenhuma tarefa agendada, e uma consulta elegível sem lembrete até o disparo explícito do caso de uso.
- [x] 6.3 Verificar em `DelegacaoDoLembreteTest#automaticoEManualAplicamAMesmaRegra` que job e endpoint chamam a mesma operação do caso de uso, e que cada um tem como única dependência o caso de uso — sem `JdbcTemplate`, `Clock`, `NotificationSenderPort` ou SQL.

## 7. Endpoint, segurança e erros HTTP

- [x] 7.1 Criar `LembreteController` com `POST /internal/lembretes/executar`, `@PreAuthorize("hasAnyRole('MEDICO','ENFERMEIRO')")` e resposta `ResultadoDaExecucao(int lembretesEnviados)`. Verificar em `DisparoManualIT#disparoSemCandidatosRespondeZero` que a execução sem candidatos recebe 200 com `lembretesEnviados` 0. Os resultados por perfil vêm do IT da matriz (8.5).
- [x] 7.2 Verificar em `DisparoManualIT`, com um método por caso e uma consulta elegível semeada, exigindo zero envio e zero registro: sem token → 401; token expirado do mesmo emissor → 401; token malformado e token assinado com outro segredo → 401 com `type` de não autenticado de `RespostaDeSeguranca`.
- [x] 7.3 Verificar em `DisparoManualIT` exatamente uma `SecurityFilterChain` no contexto e que um caminho fora de `/internal/**`, como `/api/qualquer`, cai no `denyAll`. Acrescentar cobertura estrutural que recusa `@EnableWebSecurity` ou bean de `SecurityFilterChain` declarado no módulo.
- [x] 7.4 Criar `CoberturaDeAutorizacaoNotificacaoTest`, que varre as classes compiladas e exige `@PreAuthorize` de método sem `permitAll` em todo mapeamento HTTP. Verificar que a varredura encontra exatamente o endpoint do lembrete — nunca zero —, que os mapeamentos encontrados coincidem com os endpoints da tabela do notificação em `docs/02` §3, e que um infrator nas classes de teste é **encontrado** pela mesma varredura e recusado.
- [x] 7.5 Atualizar os comentários de `SegurancaAutoConfiguration` e `SegurancaAutoConfigurationTest` que ainda descrevem dois consumidores, e acrescentar a `SegurancaAutoConfigurationTest` um caso com `hospital.security.caminhos.autenticados=/internal/**`: uma única cadeia, e `/api/**` substituído caindo no `denyAll`. Verificar com `mvn -q -pl shared-security test`.
- [x] 7.6 Fortalecer `ProtecoesEstruturaisNotificacaoTest#nenhumaClasseLeORelogioDiretamente` para recusar também `now()` isolado, `current_timestamp`, `localtimestamp`, `clock_timestamp()` e `statement_timestamp()` em `src/main`, sem distinguir caixa. Verificar a sensibilidade com textos de exemplo recusados e aceitos, como a guarda de segredo já faz.
- [x] 7.7 Criar o tratador estreito de D5: `@RestControllerAdvice(assignableTypes = LembreteController.class)` com `@ExceptionHandler(Exception.class)`. Ele relança intactas `AccessDeniedException` e `AuthenticationException` e traduz o resto em 500 `application/problem+json`, com:
  - `type` `https://hospital.fiap.br/erros/erro-interno`, título "Erro interno" e detalhe fixo e genérico em português;
  - `instance`, `timestamp` do `Clock`, e `correlationId` do atributo da requisição, do `X-Correlation-Id` ou gerado;
  - a causa registrada em `ERROR` no log.

  Verificar em `DisparoManualIT#falhaNaLeituraInicialRespondeProblemDetail500`: com uma consulta elegível semeada, renomear `agenda_local` por DDL durante a chamada e restaurá-la em `finally`. Exigir:
  - status 500, content type e `type` acima;
  - `detail` igual ao texto fixo;
  - `correlationId` igual ao `X-Correlation-Id` enviado, com `timestamp` e `instance` presentes;
  - corpo sem `agenda_local`, `select`, `Exception`, `org.` ou `\tat `;
  - nenhum envio e nenhum registro.

  A prova é comportamental, sem asserção sobre presença ou ausência de anotação.

## 8. Matriz normativa de autorização

- [x] 8.1 Acrescentar a `docs/02-especificacao-funcional.md` §3 a tabela `### notificacao-service — REST interno`, entre a tabela GraphQL e a nota `**Teste obrigatório:**`, na disposição de colunas da tabela do agendamento: `| /internal/lembretes/executar | POST | ✅ | ✅ | ❌ 403 |` — isto é, `POST /internal/lembretes/executar | MEDICO ✅ | ENFERMEIRO ✅ | PACIENTE ❌ 403`. Verificar que as tabelas do agendamento e do histórico não mudam de conteúdo.
- [x] 8.2 Mudar o marcador final de `MatrizDeAutorizacaoGraphql`, no `historico-service`, de `**Teste obrigatório:**` para o título `### notificacao-service — REST interno` — a única alteração nesse leitor e em sua suíte. Preservar, sem duplicar nem reescrever, a asserção existente `MatrizDeAutorizacaoGraphqlIT#leituraEncontrouAMatrizCompleta` (5 operações, 3 perfis e 15 células) e verificar que ela continua verde com o novo marcador: `mvn -q -pl historico-service -am -Dit.test='MatrizDeAutorizacaoGraphqlIT' -Dfailsafe.failIfNoSpecifiedTests=false verify`.
- [x] 8.3 Conferir que o leitor do agendamento continua terminando em `### historico-service`, sem mudança de código, e que a asserção existente de 7 endpoints, 3 perfis e 21 células segue verde. Verificar com `mvn -q -pl agendamento-service -am -Dit.test='MatrizDeAutorizacaoIT' -Dfailsafe.failIfNoSpecifiedTests=false verify`.
- [x] 8.4 Criar `MatrizDeAutorizacaoNotificacao` no `notificacao-service`, leitor da tabela nova do título até `**Teste obrigatório:**` no mesmo formato dos outros dois. Verificar em `MatrizDeAutorizacaoNotificacaoIT#matrizTemUmEndpointTresPerfisETresCelulas` a asserção positiva de exatamente 1 endpoint, 3 perfis e 3 células.
- [x] 8.5 Criar em `MatrizDeAutorizacaoNotificacaoIT` três métodos explícitos, um por célula, cada um com o `@DisplayName` exato de seu Scenario:
  - `medicoDisparaAExecucao`;
  - `enfermeiroDisparaAExecucao`;
  - `pacienteERecusadoSemExecutarAVarredura`.

  Cada método busca no parser a célula do seu perfil e entrega a expectativa **lida do documento** a um helper comum. O helper semeia uma consulta elegível, chama o endpoint com o token do perfil e exige, conforme a célula:
  - `✅` → 200, `lembretesEnviados` 1 e registro;
  - `❌ 403` → 403 com `type` de acesso negado de `RespostaDeSeguranca`, sem envio nem registro.

  Não há política paralela: nenhum método compara a resposta com um resultado escrito nele mesmo, e célula ausente faz a busca falhar. Verificar os três métodos e `matrizTemUmEndpointTresPerfisETresCelulas` verdes; a sensibilidade a alteração e remoção de célula é demonstrada em 10.5.

## 9. Documentação e rastreabilidade

- [x] 9.1 Acrescentar ao README a seção do lembrete D-1 (M07). Conteúdo:
  - janela e status elegíveis; cadência horária e as duas propriedades;
  - endpoint, perfis, resposta, recusas 401/403 e o 500 em Problem Detail;
  - regra de um lembrete por vida da consulta, inclusive após remarcação, e garantia de entrega;
  - modo de demonstração com `JWT_SECRET` **exportado explicitamente**, porque nem o Maven nem o Spring leem `.env`: `set -a; . ./.env; set +a` na mesma sessão que sobe agendamento e notificação; depois `mvn -pl agendamento-service -am spring-boot:run` e `mvn -pl notificacao-service -am spring-boot:run`, token por `POST /auth/login` e `curl -X POST .../internal/lembretes/executar`;
  - a observação de que o serviço não sobe sem a variável.

  Não tocar o CHANGELOG. Verificar que os comandos citados correspondem às propriedades e à rota implementadas.
- [x] 9.2 Atualizar `docs/01-arquitetura.md` §5 com janela, status, reserva antes do envio, unicidade parcial, índice, falha isolada, erro interno em Problem Detail, execução automática, endpoint e garantia externa; e acrescentar a `docs/02-especificacao-funcional.md` §4 a linha de índices do `notificacao_db`. Verificar coerência com D1–D8.
- [x] 9.3 Alinhar a release `0.2.0` ao fechamento depois do M07: `docs/00-project-charter.md` §7.1 e `docs/05-fluxo-de-trabalho.md` §4 passam de "M09 / `add-historico-graphql`" para "M07 / `add-lembrete-24h`", mantendo RF-11 a RF-20. Em `docs/04-roadmap.md`, o marcador "Ao fim deste change: abrir `release/0.2.0`" sai do M09 e vai para o M07, coerente com a sequência do topo do documento. Verificar por `grep -n "0.2.0" docs/00-project-charter.md docs/04-roadmap.md docs/05-fluxo-de-trabalho.md README.md` que nenhuma referência aponta para o M09.
- [x] 9.4 Criar `MatrizDeCenariosDoLembreteTest`, com a matriz abaixo mantida em código. A cobertura falha se:
  - a matriz estiver vazia;
  - um método não existir ou não for teste;
  - o `@DisplayName` não for `Scenario: <título>`, com comparação sem acentos e sem caixa;
  - os títulos divergirem dos Scenarios dos seis Requirements do M07, lidos do delta enquanto a change está ativa e da capability promovida depois do archive.

  Não há exceção para método parametrizado: todo Scenario aponta um método próprio. Verificar a sensibilidade com uma linha removida e com um título alterado, restaurando depois.
- [x] 9.5 Manter a matriz abaixo alinhada aos métodos reais, conferindo os 30 Scenarios por execução, e nunca por nome de classe ou task marcada.

### Matriz Scenario → método

| Requirement | Scenario | Método |
|---|---|---|
| Lembrete D-1 para consultas ativas nas próximas 24 horas | Consulta a 23h59 da execução recebe lembrete | `JanelaDoLembreteIT#consultaA23h59Recebe` |
| | Consulta exatamente a 24 horas recebe lembrete | `JanelaDoLembreteIT#consultaExatamenteA24hRecebe` |
| | Consulta a 24h01 não recebe lembrete | `JanelaDoLembreteIT#consultaA24h01NaoRecebe` |
| | Consulta no instante da execução ou no passado não recebe lembrete | `JanelaDoLembreteIT#consultaNoInstanteOuNoPassadoNaoRecebe` (2 casos) |
| | Consultas agendadas e confirmadas são lembradas | `JanelaDoLembreteIT#agendadasEConfirmadasSaoLembradas` |
| | Consultas canceladas e realizadas nunca são lembradas | `JanelaDoLembreteIT#canceladasERealizadasNuncaSaoLembradas` |
| | Execução sem candidatos termina sem efeito | `JanelaDoLembreteIT#execucaoSemCandidatosTerminaSemEfeito` |
| Lembrete entregue pelo canal configurado e registrado | Lembrete confirmado fica registrado como foi entregue | `RegistroDoLembreteIT#lembreteConfirmadoFicaRegistradoComoFoiEntregue` |
| | Conteúdo informa médico e horário local da consulta | `RegistroDoLembreteIT#conteudoInformaMedicoEHorarioLocal` |
| | Tipo do lembrete fica fora do contrato de eventos | `RegistroDoLembreteIT#tipoDoLembreteFicaForaDoContratoDeEventos` |
| No máximo um lembrete D-1 por consulta | Execução repetida não lembra de novo | `IdempotenciaDoLembreteIT#execucaoRepetidaNaoLembraDeNovo` |
| | Execuções concorrentes confirmam e entregam um único lembrete | `IdempotenciaDoLembreteIT#execucoesConcorrentesConfirmamEEntregamUmUnicoLembrete` |
| | Consulta remarcada depois do lembrete não recebe outro | `IdempotenciaDoLembreteIT#consultaRemarcadaDepoisDoLembreteNaoRecebeOutro` |
| | Armazenamento recusa segundo lembrete da mesma consulta | `IdempotenciaDoLembreteIT#armazenamentoRecusaSegundoLembreteDaMesmaConsulta` |
| | Falha no envio não deixa lembrete e a consulta segue elegível | `IdempotenciaDoLembreteIT#falhaNoEnvioNaoDeixaLembreteEConsultaSegueElegivel` |
| | Falha em uma consulta não impede as demais | `IdempotenciaDoLembreteIT#falhaEmUmaConsultaNaoImpedeAsDemais` |
| Seleção de candidatos em comando único no armazenamento | Recortes aplicados no comando enviado ao armazenamento | `ConsultaDeCandidatosIT#recortesAplicadosNoComandoEnviado` |
| | Mais candidatos não multiplicam comandos de leitura | `ConsultaDeCandidatosIT#maisCandidatosNaoMultiplicamLeituras` |
| Execução automática periódica e configurável | Sem configuração, a varredura automática é horária | `AgendadorDeLembretesTest#semConfiguracaoAVarreduraEHoraria` |
| | Expressão configurada substitui a padrão | `AgendadorDeLembretesTest#expressaoConfiguradaSubstituiAPadrao` |
| | Profile de teste não executa automaticamente | `AgendadorDesligadoIT#profileDeTesteNaoExecutaAutomaticamente` |
| | Execução automática e disparo manual aplicam a mesma regra | `DelegacaoDoLembreteTest#automaticoEManualAplicamAMesmaRegra` |
| Disparo manual protegido por perfil | Médico dispara a execução | `MatrizDeAutorizacaoNotificacaoIT#medicoDisparaAExecucao` (expectativa lida de `docs/02`) |
| | Enfermeiro dispara a execução | `MatrizDeAutorizacaoNotificacaoIT#enfermeiroDisparaAExecucao` (expectativa lida de `docs/02`) |
| | Disparo sem candidatos responde zero | `DisparoManualIT#disparoSemCandidatosRespondeZero` |
| | Paciente é recusado sem executar a varredura | `MatrizDeAutorizacaoNotificacaoIT#pacienteERecusadoSemExecutarAVarredura` (expectativa lida de `docs/02`) |
| | Sem token recebe 401 | `DisparoManualIT#semTokenRecebe401` |
| | Token expirado recebe 401 | `DisparoManualIT#tokenExpiradoRecebe401` |
| | Token inválido recebe 401 | `DisparoManualIT#tokenInvalidoRecebe401` (2 casos) |
| | Falha na leitura inicial dos candidatos responde 500 em Problem Detail | `DisparoManualIT#falhaNaLeituraInicialRespondeProblemDetail500` |

## 10. Verificação

- [x] 10.1 Executar validação e status da change; conferir a capability modificada, 6 Requirements e 30 Scenarios adicionados, e os quatro artefatos completos.

```bash
openspec validate add-lembrete-24h --strict && openspec status --change add-lembrete-24h
```

- [x] 10.2 Executar a suíte dirigida do `notificacao-service` e do `shared-security` com PostgreSQL 16 e RabbitMQ 3.13 reais, sem lista de nomes — um filtro por nome silencia a suíte que não casa.

```bash
mvn -q -pl notificacao-service -am -Dit.test='*IT' -Dfailsafe.failIfNoSpecifiedTests=false verify
```

Conferir por comparação explícita que cada suíte esperada, do M06 e do M07, produziu relatório e nada foi ignorado. O comando **falha** se faltar qualquer uma ou se houver `skipped`:

```bash
for s in ConfiguracaoNotificacaoIT SchemaNotificacaoIT PersistenciaNotificacaoIT RelogioNotificacaoIT CorrelacaoNotificacaoIT IdempotenciaNotificacaoIT AgendaLocalIT OrdenacaoAgendaIT NotificacaoReativaIT SelecaoDeSenderIT EntradasHostisNotificacaoIT ConsumoNotificacaoRabbitMqIT DesacoplamentoNotificacaoIT JanelaDoLembreteIT RegistroDoLembreteIT IdempotenciaDoLembreteIT ConsultaDeCandidatosIT IndicesDoLembreteIT AgendadorDesligadoIT DisparoManualIT MatrizDeAutorizacaoNotificacaoIT; do f=$(ls notificacao-service/target/failsafe-reports/TEST-*.$s.xml 2>/dev/null) || { echo "AUSENTE: $s"; exit 1; }; grep -q 'skipped="0"' "$f" || { echo "IGNORADOS EM: $s"; exit 1; }; done; for s in ProtecoesEstruturaisNotificacaoTest AgendadorDeLembretesTest DelegacaoDoLembreteTest CoberturaDeAutorizacaoNotificacaoTest MatrizDeCenariosDoLembreteTest; do f=$(ls notificacao-service/target/surefire-reports/TEST-*.$s.xml 2>/dev/null) || { echo "AUSENTE: $s"; exit 1; }; grep -q 'skipped="0"' "$f" || { echo "IGNORADOS EM: $s"; exit 1; }; done; f=$(ls shared-security/target/surefire-reports/TEST-*.SegurancaAutoConfigurationTest.xml 2>/dev/null) || { echo "AUSENTE: SegurancaAutoConfigurationTest"; exit 1; }; grep -q 'skipped="0"' "$f" || { echo "IGNORADOS EM: SegurancaAutoConfigurationTest"; exit 1; }; echo "todas as suites do M06 e do M07 executadas, zero ignorados"
```

- [x] 10.3 Executar o gate global na raiz, sem testes ignorados; registrar a contagem por módulo e o resultado final. Em seguida, conferir que as matrizes do agendamento e do histórico rodaram sem teste ignorado.

```bash
mvn -q clean verify
```

```bash
for r in agendamento-service/target/failsafe-reports/TEST-*.MatrizDeAutorizacaoIT.xml historico-service/target/failsafe-reports/TEST-*.MatrizDeAutorizacaoGraphqlIT.xml; do ls $r >/dev/null 2>&1 || { echo "AUSENTE: $r"; exit 1; }; grep -q 'skipped="0"' $r || { echo "IGNORADOS EM: $r"; exit 1; }; done; echo "matrizes do agendamento e do historico executadas"
```

- [x] 10.4 Conferir o JaCoCo do `notificacao-service` — mínimo de 80% de linha no módulo — a partir do relatório do gate, registrando o número real, sem antecipar o gate agregado do M10.

```bash
grep -o '<counter type="LINE"[^/]*/>' notificacao-service/target/site/jacoco/jacoco.xml | tail -1
```

- [x] 10.5 Demonstrar sensibilidade pelas mutações de D9. Cada uma é isolada, restaurada e seguida da suíte afetada verde antes da próxima:
  - (a) `<=` do limite vira `<` → borda de 24h vermelha;
  - (a') limite ampliado em um minuto → borda de 24h01 vermelha;
  - (b) recorte de status removido → canceladas e realizadas vermelho;
  - (c) envio antes da reserva → concorrência e cobertura de sequência vermelhas;
  - (c') unicidade parcial removida de `V2` → recusa do armazenamento vermelha;
  - (c'') `NOT EXISTS` removido → recortes do comando vermelho;
  - (d) `@PreAuthorize` removido → `MatrizDeAutorizacaoNotificacaoIT#pacienteERecusadoSemExecutarAVarredura` e cobertura estrutural vermelhos;
  - (e) tradução removida do tratador de erro interno → `falhaNaLeituraInicialRespondeProblemDetail500` vermelho;
  - (e') relançamento de `AccessDeniedException` removido do tratador → `pacienteERecusadoSemExecutarAVarredura` vermelho, com 500 no lugar de 403;
  - (f) célula `MEDICO` de `docs/02` §3 alterada para `❌ 403` → `MatrizDeAutorizacaoNotificacaoIT#medicoDisparaAExecucao` vermelho;
  - (f') célula `ENFERMEIRO`, e depois a linha inteira, removida de `docs/02` §3 → `enfermeiroDisparaAExecucao`, `matrizTemUmEndpointTresPerfisETresCelulas` e a cobertura estrutural de 7.4 vermelhos.

  **Evidência executada** — substituição aprovada pelo gestor; os critérios acima não mudam:
  - Cada uma das 12 mutações ficou vermelha isoladamente nos testes previstos: (a), (a'), (b), (c''), (c), (c'), (d), (e), (e'), (f), (f') célula `ENFERMEIRO` e (f') linha inteira.
  - Cada arquivo foi restaurado byte a byte, com conferência, antes da mutação seguinte.
  - A suíte afetada **não** foi executada em verde imediatamente após cada restauração. A execução da mutação seguinte exigiu verde no teste derrubado pela anterior, o que não equivale à suíte verde imediata.
  - O estado restaurado foi comprovado ao final por `mvn -q clean verify`, a partir de `clean`, com todas as suítes verdes: 1183 testes, zero ignorados.
    - shared-contracts: 134
    - shared-security: 73
    - agendamento-service: 744
    - notificacao-service: 113
    - historico-service: 119
- [ ] 10.6 Verificar a partir de clone limpo da feature branch, depois do push, em diretório temporário único, removido após registrar o resultado.

```bash
set -eu; TMP=$(mktemp -d); [ -d "$TMP" ] || { echo "mktemp falhou"; exit 1; }; TMP=$(cd "$TMP" && pwd -P); case "$TMP" in "$(cd "${TMPDIR:-/tmp}" && pwd -P)"/*) ;; *) echo "alvo fora do temporario: $TMP"; exit 1;; esac; trap 'rm -rf "$TMP"' EXIT INT TERM; git clone -b feature/m07-add-lembrete-24h https://github.com/g-andradd/hospital-scheduling-fase3.git "$TMP/repo"; cd "$TMP/repo"; mvn -q clean verify
```

- [ ] 10.7 Levar ao corpo do PR:
  - a matriz dos 30 Scenarios com seus métodos e a tabela de autorização do notificação com seus testes por célula;
  - a contagem de testes por módulo e a cobertura;
  - o SQL capturado e o plano medido;
  - as mutações e o resultado do clone limpo;
  - a declaração explícita da garantia de entrega externa.
- [ ] 10.8 **Depois da aprovação do PR e antes do merge**, executar o archive na própria feature branch, pelo workflow e não pelo CLI cru, para que a promoção seja verificada.

```
/opsx:archive add-lembrete-24h
```

- [ ] 10.9 Confirmar o resultado do archive com uma verificação que **falha** em qualquer desvio. Ela exige:
  - a change arquivada em `openspec/changes/archive/<data>-add-lembrete-24h/` e ausente de `openspec/changes/`;
  - nenhuma change ativa: nenhum diretório diretamente sob `openspec/changes/` além de `archive`;
  - `openspec/specs/notificacoes-ao-paciente/spec.md` com 15 Requirements e 56 Scenarios — 26 do M06 e 30 do M07 — e um único `## Purpose`;
  - zero linhas removidas da capability em relação ao ponto em que a branch saiu de `develop`, o que mantém os 9 Requirements e 26 Scenarios do M06 e o `## Purpose` idênticos;
  - nenhuma outra capability alterada;
  - `openspec validate --all --strict` verde.

```bash
set -eu; SPEC=openspec/specs/notificacoes-ao-paciente/spec.md; BASE=$(git merge-base HEAD develop); openspec validate --all --strict; openspec list; [ ! -d openspec/changes/add-lembrete-24h ] || { echo "change ainda ativa"; exit 1; }; ls -d openspec/changes/archive/*-add-lembrete-24h >/dev/null 2>&1 || { echo "archive ausente"; exit 1; }; ATIVAS=$(find openspec/changes -mindepth 1 -maxdepth 1 -type d ! -name archive); [ -z "$ATIVAS" ] || { echo "change ativa inesperada: $ATIVAS"; exit 1; }; [ "$(grep -c '^### Requirement:' "$SPEC")" -eq 15 ] || { echo "capability sem 15 Requirements"; exit 1; }; [ "$(grep -c '^#### Scenario:' "$SPEC")" -eq 56 ] || { echo "capability sem 56 Scenarios"; exit 1; }; [ "$(grep -c '^## Purpose' "$SPEC")" -eq 1 ] || { echo "Purpose ausente ou duplicado"; exit 1; }; DELETIONS=$(git diff --numstat "$BASE" -- "$SPEC" | awk '{s+=$2} END {print s+0}'); [ "$DELETIONS" -eq 0 ] || { echo "capability perdeu conteudo promovido: $DELETIONS linhas removidas"; exit 1; }; OUTRAS=$(git diff --name-only "$BASE" -- openspec/specs | grep -v "^$SPEC\$" || true); [ -z "$OUTRAS" ] || { echo "outra capability alterada: $OUTRAS"; exit 1; }; echo "archive conferido: 15 Requirements, 56 Scenarios, nenhuma remocao, nenhuma outra capability"
```

- [ ] 10.10 Conferir no diff do PR que código, archive e capability promovida estão na mesma feature branch; entregar ao Gabriel o comando do commit de archive e exigir o push antes do merge.

```bash
git add -A openspec && git commit -m "chore(openspec): arquiva add-lembrete-24h"
```
