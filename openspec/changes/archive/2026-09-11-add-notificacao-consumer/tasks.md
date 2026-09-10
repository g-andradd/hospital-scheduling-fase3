# Tasks — M06

## 1. Build e configuração

- [x] 1.1 Adicionar ao `notificacao-service` JPA, Flyway, PostgreSQL, starter de mail e Testcontainers PostgreSQL/RabbitMQ, preservando o BOM e `0.2.0-SNAPSHOT`; verificar resolução com `mvn -q test-compile` na raiz.
- [x] 1.2 Configurar datasource/Flyway do `notificacao_db` e perfil de teste, mantendo a factory compartilhada do M05 com três tentativas e `default-requeue-rejected: false`; verificar propriedades e objetos efetivos em `ConfiguracaoNotificacaoIT`.
- [x] 1.3 Garantir que Flyway conclui antes de JPA e do listener e que o serviço não expõe endpoint novo; verificar subida do contexto e ordem real em `SchemaNotificacaoIT`.

## 2. Persistência

- [x] 2.1 Criar `V1__cria_modelo_de_notificacao.sql` com `agenda_local`, `notificacao_enviada` e `evento_processado`, PKs e timestamps do modelo normativo, incluindo em `agenda_local` a coluna do instante do fato aplicado que sustenta a monotonicidade, sem índices secundários do M07; verificar schema, reaplicação e `ddl-auto=validate` em `SchemaNotificacaoIT` com PostgreSQL 16.
- [x] 2.2 Mapear as três entidades e seus repositórios, com `agenda_local` chaveada por `consulta_id`; verificar round-trip de instantes, contato e campos nulos em `PersistenciaNotificacaoIT`.
- [x] 2.3 Publicar o bean `Clock` e derivar dele `atualizado_em`, `enviado_em` e `processado_em`, sem `Instant.now()`, `LocalDateTime.now()` ou `now()` no SQL; verificar com relógio fixo em `RelogioNotificacaoIT` que os três instantes correspondem ao instante fixado, e por cobertura estrutural que nenhuma classe de `src/main` lê o relógio diretamente.

## 3. Consumo idempotente

- [x] 3.1 Criar `ConsumidorDeNotificacoes` como única entrada `@RabbitListener` na fila `notificacao.consultas`, com `@Transactional`, consulta de duplicata, efeito e marca por último; verificar fronteira anotada e ordem das operações em `ProtecoesEstruturaisNotificacaoTest`.
- [x] 3.2 Restaurar o `correlationId` validado do envelope no MDC durante a tentativa e limpar em `finally`; verificar em `CorrelacaoNotificacaoIT` que o valor vale durante o processamento e que não vaza para a mensagem seguinte, inclusive após falha.
- [x] 3.3 Provar reentrega sequencial do mesmo `eventId` exigindo uma única notificação registrada, agenda inalterada e marca única, em `IdempotenciaNotificacaoIT`.
- [x] 3.4 Provar rollback com falha injetada entre efeito e marca, relendo por outra transação para exigir ausência de agenda, auditoria e marca, em `IdempotenciaNotificacaoIT`.
- [x] 3.5 Provar duas entregas concorrentes do mesmo `eventId` com duas transações reais, em `IdempotenciaNotificacaoIT`: exigir **um** registro de auditoria, **uma** marca e **uma** agenda confirmados, com a tentativa perdedora integralmente revertida. Não afirmar chamada única ao sender — D5 registra que ambas podem chamá-lo antes de a PK decidir.
  - A barreira fica **depois da consulta de ausência do `eventId` e antes do upsert**, liberando as duas transações só quando ambas já concluíram a checagem inicial. É essa a corrida que interessa: as duas se julgam a primeira e seguem para o efeito.
  - A barreira **não** pode ficar no sender: a primeira transação já teria o lock da linha da agenda, a segunda não chegaria ao envio, e o teste ou trava em deadlock ou passa sem exercitar a disputa que diz exercitar.

## 4. Agenda local e notificações reativas

- [x] 4.1 Implementar o roteador dos cinco tipos a partir de `EventoEnvelope<ConsultaPayload>`, com upsert da agenda por qualquer um deles; verificar cada tipo isolado em `AgendaLocalIT` e que um evento de atualização, confirmação, cancelamento ou realização recebido **sem** criação prévia materializa a linha a partir do próprio snapshot.
- [x] 4.2 Garantir que o cancelamento atualiza o status e nunca remove a linha; verificar em `AgendaLocalIT` que a linha persiste com status cancelado após o evento.
- [x] 4.2b Implementar o upsert condicionado por `occurredAt >= instante do fato aplicado`, num único comando, sem pré-consulta TOCTOU; verificar em `OrdenacaoAgendaIT` que evento anterior é marcado como processado sem regredir a agenda, que empate de `occurredAt` segue a ordem de chegada e que o SQL real carrega a condição.
- [x] 4.2c Cobrir o caso hostil em `OrdenacaoAgendaIT`: cancelamento aplicado e, em seguida, atualização com `occurredAt` anterior — exigir status cancelado preservado, nenhuma notificação de alteração enviada nem registrada, e o evento antigo ainda assim marcado como processado.
- [x] 4.3 Implementar as notificações reativas de criação, atualização e cancelamento, com templates em português e registro em `notificacao_enviada` dentro da transação; verificar conteúdo, destinatário, canal e tipo em `NotificacaoReativaIT`, e que uma transação confirmada deixa exatamente um registro. Não afirmar em teste nem em documentação que a tabela registra todo envio externo — D5 descreve a janela em que isso não vale.
- [x] 4.4 Garantir que confirmação e realização atualizam a agenda e **não** registram notificação; verificar a ausência explicitamente em `NotificacaoReativaIT`.
- [x] 4.4b Atrelar o envio à **aplicação** do fato, e não ao recebimento; verificar em `NotificacaoReativaIT` que evento de criação, atualização ou cancelamento descartado pela regra de monotonicidade não envia nem registra notificação, mas é marcado como processado.
- [x] 4.5 Verificar em `NotificacaoReativaIT` que o cancelamento envia mensagem de cancelamento e não o texto de confirmação.

## 5. Porta de saída e adaptadores

- [x] 5.1 Criar `NotificationSenderPort` como única saída e o adaptador de log como padrão via `@ConditionalOnProperty(matchIfMissing = true)`; verificar em `SelecaoDeSenderIT` que sem configuração o adaptador de log é o ativo e nenhum provedor externo é contactado.
- [x] 5.2 Criar o adaptador SMTP selecionado por `notificacao.sender=smtp`, sem credencial no repositório; verificar em `SelecaoDeSenderIT` que o contexto seleciona o `SmtpNotificationSender` **real** e que apenas ele fica ativo nesse modo, com o transporte de correio controlado por teste enquanto PostgreSQL e RabbitMQ permanecem reais. Exigir que agenda e auditoria sejam idênticas às do canal padrão. A evidência **não** pode afirmar entrega contra servidor SMTP real — isso é do M12.
- [x] 5.3 Criar cobertura estrutural que recusa **valores** sensíveis em `src/main` — literal de senha ou token atribuído, chave de API e host fixo de provedor de correio — e que permite nome de propriedade, referência a variável de ambiente e fallback local não secreto no formato `${SMTP_HOST:localhost}`; provar que ela **não** acusa `spring.rabbitmq.password` nem os placeholders existentes, e que acusa um segredo literal temporário. Exigir também uma única porta de saída de notificação, com exatamente os dois adaptadores previstos.
- [x] 5.4 Provar rollback quando o sender falha: exigir ausência de agenda alterada, de registro de envio e de marca, em `IdempotenciaNotificacaoIT`.

## 6. Retry, DLQ e desacoplamento

- [x] 6.1 Ligar o consumidor à fila normativa usando converter e topologia compartilhados, sem duplicar cadeia de retry; verificar os cinco tipos publicados no RabbitMQ real em `ConsumoNotificacaoRabbitMqIT`.
- [x] 6.2 Criar `EntradasHostisNotificacaoIT` com JSON/campo/metadado inválido e `version != 1`; exigir três tentativas, bytes originais e `x-death` na DLQ, zero linhas nas três tabelas e ausência de requeue infinito.
- [x] 6.3 Injetar falha persistente no processamento, exigir rollback em cada tentativa e DLQ; publicar mensagem válida em seguida e comprovar que o mesmo consumidor permanece ativo.
- [x] 6.4 Criar cobertura estrutural que recusa `RestClient`, `WebClient`, OpenFeign, URL ou porta de cliente para o agendamento; verificar por IT que o processamento funciona com o agendamento indisponível e que campo ausente segue para DLQ sem chamada remota.

## 7. Documentação e rastreabilidade

- [x] 7.1 Atualizar README e `docs/01-arquitetura.md` §5 com o consumidor, a agenda local, a política de notificação por tipo, a seleção de adaptador, a operação e o rollback; **declarar explicitamente** que o efeito persistido é exatamente-uma-vez e o envio externo é ao-menos-uma-vez. Não tocar o CHANGELOG, que fecha com a release `0.2.0`.
- [x] 7.2a Manter a matriz cenário → método/caso alinhada aos métodos reais; conferir os 26 Scenarios sem usar nome de classe ou task marcada como substituto de execução.
- [x] 7.2b Levar a evidência da matriz ao corpo do PR quando Gabriel criar o PR.

### Matriz cenário → método/caso

Os 26 Scenarios da spec, cada um com o método que o executa. Todos verdes na suíte dirigida.

| Requirement | Scenario | Método |
|---|---|---|
| Agenda local materializável por qualquer evento | Criação materializa a agenda local | `AgendaLocalIT#criacaoMaterializaAAgendaLocal` |
| | Evento posterior sem criação prévia materializa a agenda | `AgendaLocalIT#qualquerTipoMaterializaAAgenda` (5 casos, um por tipo) |
| | Atualização altera a agenda sem duplicá-la | `AgendaLocalIT#atualizacaoAlteraSemDuplicar` |
| | Cancelamento preserva a linha e marca o status | `AgendaLocalIT#cancelamentoPreservaALinha` |
| Agenda monotônica por instante do fato | Evento antigo é processado sem regredir a agenda | `OrdenacaoAgendaIT#eventoAntigoNaoRegrideAAgenda` |
| | Atualização antiga não ressuscita consulta cancelada | `OrdenacaoAgendaIT#atualizacaoAntigaNaoRessuscitaConsultaCancelada` |
| | Fatos no mesmo instante seguem a ordem de chegada | `OrdenacaoAgendaIT#fatosNoMesmoInstanteSeguemAOrdemDeChegada` |
| Notificações reativas ao paciente | Criação notifica o agendamento da consulta | `NotificacaoReativaIT#criacaoNotificaOAgendamento` |
| | Cancelamento notifica cancelamento, não confirmação | `NotificacaoReativaIT#cancelamentoNotificaCancelamento` |
| | Atualização notifica a alteração | `NotificacaoReativaIT#atualizacaoNotificaAAlteracao` |
| | Confirmação e realização não geram notificação | `NotificacaoReativaIT#confirmacaoERealizacaoNaoNotificam` |
| | Evento não aplicado não gera notificação obsoleta | `NotificacaoReativaIT#eventoNaoAplicadoNaoNotifica` |
| Efeito único por eventId | Reentrega do mesmo evento não notifica duas vezes | `IdempotenciaNotificacaoIT#reentregaNaoNotificaDuasVezes` |
| | Entregas concorrentes confirmam um único efeito | `IdempotenciaNotificacaoIT#entregasConcorrentesConfirmamUmUnicoEfeito` |
| | Falha no envio desfaz o efeito inteiro | `IdempotenciaNotificacaoIT#falhaNoEnvioDesfazOEfeitoInteiro` |
| | Falha entre o efeito e a marca desfaz a projeção | `IdempotenciaNotificacaoIT#falhaEntreEfeitoEMarcaDesfazAProjecao` |
| Rejeição segura de entradas não processáveis | Mensagem malformada não deixa efeito e chega à DLQ | `EntradasHostisNotificacaoIT#mensagemMalformadaNaoDeixaEfeitoEChegaADlq` |
| | Versão desconhecida é rejeitada | `EntradasHostisNotificacaoIT#versaoDesconhecidaERejeitada` |
| | Falha persistente reverte cada tentativa | `EntradasHostisNotificacaoIT#falhaPersistenteReverteCadaTentativaEConsumidorSegueAtivo` |
| | Mensagem válida posterior continua sendo processada | mesma execução, segunda metade do método acima — a continuidade do consumidor só é observável depois da rejeição |
| Notificação desacoplada do agendamento | Evento completo notifica sem consulta remota | `DesacoplamentoNotificacaoIT#eventoCompletoNotificaSemConsultaRemota` |
| | Campo obrigatório ausente não dispara busca no produtor | `EntradasHostisNotificacaoIT#campoObrigatorioAusenteERejeitado` |
| Canal de envio selecionável e sem segredo | Sem configuração, o canal padrão é o de log | `ConfiguracaoNotificacaoIT#canalPadraoEODeLog` |
| | Canal selecionado por configuração substitui o padrão | `SelecaoDeSenderIT#canalSelecionadoSubstituiOPadrao` |
| Instantes derivados de fonte de tempo injetada | Instantes registrados são governados pela fonte de tempo | `RelogioNotificacaoIT#instantesRegistradosVemDoClock` |
| Correlação preservada durante o processamento | Correlação vale durante a tentativa e não vaza depois | `CorrelacaoNotificacaoIT#correlacaoValeDuranteOProcessamento` |

## 8. Verificação

- [x] 8.1 Executar validação e status da change; conferir capability, 26 Scenarios, matriz e quatro artefatos completos.

```bash
openspec validate add-notificacao-consumer --strict && openspec status --change add-notificacao-consumer
```

- [x] 8.2 Executar a suíte direcionada do `notificacao-service` com PostgreSQL 16 e RabbitMQ 3.13 reais; registrar contagem e zero ignorados.

Rodar **todas** as suítes de integração do módulo, sem lista de nomes: um filtro por nomes silencia a suíte que não casa com o padrão, e o build fica verde sem tê-la executado.

```bash
mvn -q -pl notificacao-service -am -Dit.test='*IT' -Dfailsafe.failIfNoSpecifiedTests=false verify
```

Conferir em seguida, por comparação explícita, que cada suíte esperada produziu relatório e que nada foi ignorado — o comando **falha** se faltar qualquer uma ou se houver `skipped`:

```bash
for s in ConfiguracaoNotificacaoIT SchemaNotificacaoIT PersistenciaNotificacaoIT RelogioNotificacaoIT CorrelacaoNotificacaoIT IdempotenciaNotificacaoIT AgendaLocalIT OrdenacaoAgendaIT NotificacaoReativaIT SelecaoDeSenderIT EntradasHostisNotificacaoIT ConsumoNotificacaoRabbitMqIT DesacoplamentoNotificacaoIT; do f=$(ls notificacao-service/target/failsafe-reports/TEST-*.$s.xml 2>/dev/null) || { echo "AUSENTE: $s"; exit 1; }; grep -q 'skipped="0"' "$f" || { echo "IGNORADOS EM: $s"; exit 1; }; done; echo "todas as suites do M06 executadas, zero ignorados"
```

- [x] 8.3 Executar o gate global na raiz, sem testes ignorados; registrar contagem por módulo e resultado final.

```bash
mvn -q clean verify
```

- [x] 8.4 Conferir JaCoCo do `notificacao-service` — mínimo de 80% do módulo — e a cobertura global ponderada, a partir dos relatórios gerados pelo gate; registrar números reais sem antecipar o gate agregado do M10.

```bash
grep -o '<counter type="LINE"[^/]*/>' notificacao-service/target/site/jacoco/jacoco.xml | tail -1
```

- [x] 8.5 Executar as coberturas estruturais — listener único, ausência de cliente HTTP, guarda de segredo, relógio injetado — e demonstrar sensibilidade por quatro mutações isoladas, sempre no que é verificado e nunca na própria verificação. Restaurar cada mutação e repetir a suíte afetada verde antes da seguinte:
  - remover a gravação da marca final → `IdempotenciaNotificacaoIT` (reentrega) fica vermelho;
  - trocar o `update` do cancelamento por `delete` → `AgendaLocalIT` fica vermelho;
  - remover a condição de `occurredAt` do upsert → `OrdenacaoAgendaIT` fica vermelho, com a agenda regredindo e a atualização antiga ressuscitando a consulta;
  - inverter a ordem, gravando a marca antes do efeito → a **cobertura estrutural de sequência** fica vermelha. Esta mutação **não** é verificada pelo teste de rollback, que passa nas duas ordens; ligá-la ao teste errado daria falso conforto.
- [x] 8.6 Verificar a partir de clone limpo da feature branch, depois do push, em diretório temporário único, e removê-lo após registrar o resultado.

```bash
set -eu; TMP=$(mktemp -d); [ -d "$TMP" ] || { echo "mktemp falhou"; exit 1; }; TMP=$(cd "$TMP" && pwd -P); case "$TMP" in "$(cd "${TMPDIR:-/tmp}" && pwd -P)"/*) ;; *) echo "alvo fora do temporario: $TMP"; exit 1;; esac; trap 'rm -rf "$TMP"' EXIT INT TERM; git clone -b feature/m06-add-notificacao-consumer https://github.com/g-andradd/hospital-scheduling-fase3.git "$TMP/repo"; cd "$TMP/repo"; mvn -q clean verify
```

- [x] 8.7 Levar ao corpo do PR a matriz dos 26 Scenarios com seus métodos, a contagem de testes por módulo, a cobertura, as mutações e o resultado do clone limpo — incluindo a declaração explícita da garantia de entrega externa.
- [x] 8.8 **Depois da aprovação do PR e antes do merge**, executar `/opsx:archive add-notificacao-consumer` na própria feature branch — pelo workflow, não pelo comando CLI cru, para que a promoção da capability nova seja verificada e não apenas o move do diretório.

```
/opsx:archive add-notificacao-consumer
```

- [x] 8.9 Confirmar o resultado do archive: `openspec/changes/add-notificacao-consumer` deixou de existir; a change está em `openspec/changes/archive/<data>-add-notificacao-consumer/`; a capability foi criada em `openspec/specs/notificacoes-ao-paciente/spec.md` com o `## Purpose` preenchido, sem placeholder; nenhuma outra capability foi alterada; e `openspec list` não mostra change ativa inesperada.

```bash
openspec validate --all --strict && openspec list
```

- [x] 8.10 Conferir no diff do PR que código, archive e capability promovida estão na mesma feature branch, entregar ao Gabriel o comando do commit de archive e exigir o push antes do merge.

```bash
git add -A openspec && git commit -m "chore(openspec): arquiva add-notificacao-consumer"
```
