## Context

O `notificacao-service` tem apenas o bootstrap: `NotificacaoApplication`, um `application.yml` já com a configuração de consumo do M05 — três tentativas, backoff 1s/2s, `default-requeue-rejected: false` — e cinco pacotes vazios. Não há JPA, Flyway, banco nem listener. A fila `notificacao.consultas` recebe os cinco eventos desde o M05 e os acumula. Ver `proposal.md` e `specs/notificacoes-ao-paciente/spec.md`.

O M08 já resolveu o mesmo problema estrutural no histórico e deixou um precedente utilizável: uma única entrada AMQP anotada, `@Transactional` na fronteira, efeito antes da marca, e coberturas estruturais que falham quando alguém acrescenta um segundo listener ou um cliente HTTP. O M06 replica esse formato em vez de inventar outro, mas com uma diferença material — aqui o efeito inclui uma **chamada externa**, e é isso que muda o desenho.

`docs/03-contrato-de-eventos.md` §6 é normativo e traz o esqueleto: checar `evento_processado`, processar, gravar a marca na mesma transação, depois do efeito, com `correlationId` no MDC e `MDC.clear()` no `finally`. `docs/01-arquitetura.md` §5 define quais eventos notificam e qual é a porta de saída. ADR-007 mantém o serviço em camadas simples, sem Clean Architecture.

## Goals / Non-Goals

**Goals:**

- Consumir os cinco eventos e manter a agenda local coerente, com PostgreSQL e RabbitMQ reais nos testes.
- Notificar apenas os três fatos que a documentação pede, com conteúdo em português e auditoria persistida.
- Tornar idempotência e atomicidade propriedades da única entrada AMQP.
- Declarar com precisão a garantia de entrega externa, em vez de sugerir uma que o desenho não sustenta.
- Deixar a agenda local pronta para o job do M07 sem antecipá-lo.

**Non-Goals:**

- Job `@Scheduled` do lembrete D-1 e endpoint manual de reenvio: são o M07.
- Outbox próprio do serviço de notificação (ver D5).
- Actuator, logs JSON e propagação ponta a ponta de `correlationId`: M11.
- Mailpit, Compose final e containers das aplicações: M12.
- Qualquer alteração de envelope, topologia, routing key ou tipo normativo.

## Decisions

### D1. Uma única entrada AMQP, transacional, com a marca por último

`ConsumidorDeNotificacoes` será o único bean com `@RabbitListener` no módulo. O mesmo método público carrega `@Transactional` e executa a sequência fixa do contrato: consultar `evento_processado`; aplicar agenda, envio e auditoria; inserir a marca por último; retornar só depois do commit. Os cinco tipos entram por um roteador interno cujos manipuladores não recebem anotação Rabbit nem controlam transação.

Um teste estrutural varre as classes do módulo e falha se existir outro `@RabbitListener`, se o listener não for a fronteira transacional padrão, ou se um manipulador for exposto como listener. A garantia não pode depender de cada handler lembrar o protocolo — foi assim que o M08 a sustentou, e o mesmo raciocínio vale aqui.

A ordem efeito→marca é obrigatória porque `docs/03-contrato-de-eventos.md` §6 a define assim, e o projeto trata esse documento como normativo. Vale registrar o que ela **não** significa: dentro de uma única transação, com a exceção propagando, gravar a marca antes do efeito não confirmaria nada antecipadamente — o rollback desfaz a marca junto com o resto. A perda que se costuma atribuir a essa inversão só aparece quando a marca sai da transação do efeito: transação separada, `REQUIRES_NEW`, commit antecipado ou exceção engolida dentro do método. Manter a ordem do contrato é o que impede que qualquer dessas variações entre despercebida numa refatoração futura — e por isso ela é verificada **estruturalmente**, pela sequência das operações, e não por um teste de rollback: o teste de rollback passa nas duas ordens.

### D2. V1 materializa o modelo normativo do `notificacao_db`

A migration V1 cria `agenda_local` (`consulta_id` PK, paciente, contato, médico, `data_hora timestamptz`, status, `atualizado_em`), `notificacao_enviada` (id, `consulta_id`, tipo, destinatário, canal, `enviado_em`, conteúdo) e `evento_processado` (`event_id` PK, `processado_em`), exatamente como `docs/02-especificacao-funcional.md` §4 os descreve.

`ddl-auto` fica `validate`; toda criação passa por Flyway. Nenhum índice secundário é criado no M06: as consultas do lembrete D-1 pertencem ao M07, que medirá seus próprios planos — o mesmo critério que o M08 aplicou e o M09 honrou. PKs e unicidades de integridade não contam como otimização antecipada.

`notificacao_enviada` não recebe restrição de unicidade por `(consulta_id, tipo)`: o M07 precisa distinguir um lembrete enviado de uma confirmação, e uma regra de unicidade inventada agora limitaria decisões que ainda não foram tomadas. A não-duplicação no M06 vem da marca de `evento_processado`, não de constraint.

### D3. Agenda materializável por qualquer evento, monotônica por `occurredAt`, notificação para três

Os cinco tipos carregam o snapshot completo, então **qualquer um** deles materializa a agenda por upsert — não existe dependência de ter processado a criação antes. Assumir essa dependência criaria uma falha silenciosa real: entrega fora de ordem, ou reprocessamento a partir de uma DLQ, deixaria a consulta sem linha alguma e o M07 nunca a veria.

O upsert é condicionado por `occurredAt`: aplica quando o instante recebido é maior ou igual ao já aplicado à linha, e não aplica quando é estritamente anterior. A condição é avaliada pelo PostgreSQL sob o lock da linha, num único comando, eliminando o TOCTOU de consultar e depois gravar. Evento não aplicado **ainda assim recebe a marca** de processado: ele foi compreendido e decidido, e deixá-lo sem marca faria a reentrega repetir o trabalho para sempre. Para instantes iguais, a ordem de chegada decide, porque o contrato não declara sequência por agregado e inventar desempate por `eventId` criaria uma ordem que o produtor nunca afirmou.

Isso pertence ao M06, e não ao M07: o lembrete D-1 lê a agenda como fonte de verdade, e uma agenda que regride é pior do que uma agenda ausente — ela avisa o paciente sobre uma consulta que já foi cancelada. A coluna que guarda o instante do fato aplicado é o que torna a regra verificável.

Notificam apenas criação, atualização e cancelamento, que é o que `docs/01-arquitetura.md` §5 lista na coluna reativa. Confirmação e realização atualizam status e **não** notificam — inventar mensagem para eles seria criar requisito. A notificação está atrelada à **aplicação** do fato, não ao seu recebimento: evento anterior descartado pela regra de monotonicidade não envia nada, senão o paciente receberia um aviso de remarcação depois do cancelamento. As duas ausências são cenário próprio na spec, porque comportamento que não deve existir só fica protegido se for testado.

O cancelamento **atualiza** o status e nunca apaga a linha. A razão não é estética: o M07 precisa distinguir "consulta que não existe" de "consulta cancelada", e apagar a linha tornaria as duas situações idênticas.

### D4. O envio acontece dentro da transação

A porta de saída é chamada antes da gravação da marca, dentro da mesma transação. Isso satisfaz literalmente a exigência de que falha do sender não deixe marca parcial: a exceção propaga para fora do método transacional e reverte agenda, auditoria e marca juntas.

O custo é manter uma transação de banco aberta durante I/O externo. É aceitável neste serviço: o adaptador padrão escreve em log, o de correio fala com um servidor local, e o volume do projeto é baixo. Enviar **depois** do commit foi descartado porque produziria o pior caso possível — marca confirmada sem notificação enviada, ou seja, um paciente que nunca é avisado e um evento que o sistema considera processado.

### D5. A garantia externa é ao-menos-uma-vez, e isso é dito em vez de disfarçado

O efeito **persistido** é exatamente-uma-vez por `eventId`: agenda, auditoria e marca commitam juntas, e a reentrega encontra a marca e encerra sem repetir. É isso que os testes provam e é isso que a spec afirma.

Disso decorre um limite da auditoria que precisa ser dito: `notificacao_enviada` registra o que **transações confirmadas** produziram, e não tudo que saiu pelo canal. Uma tentativa cujo envio conclui e cuja transação reverte entrega a mensagem e não deixa linha. Chamar essa tabela de registro completo de envios seria descrevê-la errado, e alguém acabaria usando-a para responder "este paciente foi avisado?" — pergunta que ela não responde com certeza no caso de falha.

O **envio externo** não tem essa garantia. Existe uma janela real: o sender conclui, o commit falha, a tentativa seguinte reenvia — e o paciente recebe duas mensagens. Nenhuma ordenação entre chamada e commit elimina isso; só um outbox local, com um relay separado entregando fora da transação, moveria o problema para "ao-menos-uma-vez com deduplicação no destino".

A concorrência amplia a mesma janela, por um caminho diferente: duas entregas do mesmo `eventId` processadas ao mesmo tempo podem **ambas** chamar o sender antes de qualquer uma commitar, e só então uma perde a disputa pela chave primária de `evento_processado` e reverte. O estado persistido continua íntegro — uma agenda, uma auditoria, uma marca —, e é isso que a prova concorrente exige. Mas duas mensagens podem ter saído. Prometer uma única chamada externa nesse cenário seria falso, e por isso a spec afirma a garantia sobre o estado persistido, não sobre o número de chamadas ao canal.

**Esse outbox não é construído aqui, e a decisão não é omissão.** RF-16 pede que a mensagem seja consumida e o lembrete enviado; RF-19 pede que mensagem processada duas vezes não gere efeito duplicado; os critérios de aceite do M06 pedem que o mesmo `eventId` duas vezes produza uma única notificação. Todos falam do efeito persistido, e todos são satisfeitos. Nenhuma fonte do projeto exige entrega externa exatamente-uma-vez. Construir o outbox mesmo assim seria escopo inventado; escondê-lo atrás de uma frase como "entrega idempotente" seria pior, porque criaria confiança que o código não sustenta.

### D5b. Todos os instantes vêm do `Clock` injetado

`atualizado_em` da agenda, `enviado_em` da auditoria e `processado_em` da marca vêm do `Clock` publicado como bean, nunca de `Instant.now()`, `LocalDateTime.now()` ou `now()` no SQL. É convenção obrigatória do projeto, e com relógio fixo esses três instantes tornam-se valores exatos e asseráveis nos testes, em vez de "algo perto de agora".

O `Clock` **não** participa da monotonicidade, e vale ser preciso nisso para não criar uma falsa dependência. A regra de ordenação compara o `occurredAt` recebido com o instante do fato já aplicado à linha — dois valores que vêm dos **eventos**, não do relógio do serviço. Os testes de ordenação são determinísticos porque escolhem explicitamente o `occurredAt` de cada evento publicado, e continuariam determinísticos com relógio real.

A separação é essa: o `Clock` governa quando o serviço agiu; o envelope governa quando o fato aconteceu. Derivar a ordenação do relógio de consumo faria um replay reordenar a agenda.

### D6. Porta única de saída, adaptador por configuração, padrão log

`NotificationSenderPort` é a única saída de notificação. `LogNotificationSender` e `SmtpNotificationSender` são selecionados por `notificacao.sender` com `@ConditionalOnProperty`, `matchIfMissing` no adaptador de log — ausência de configuração resulta em log, e não em erro de contexto.

Nenhuma credencial entra no repositório: host, porta e autenticação do adaptador de correio vêm de variáveis de ambiente. A cobertura estrutural precisa ser **precisa** para valer alguma coisa — recusar "senha", "chave" ou "host" em `src/main` pegaria `spring.rabbitmq.password`, que é nome de propriedade legítimo e já presente, e o time aprenderia a ignorar a falha. A guarda recusa **valores**: literal de senha ou token atribuído, chave de API, e host fixo de provedor de correio conhecido. Ela permite nome de propriedade, referência a variável de ambiente e fallback local não secreto no formato `${SMTP_HOST:localhost}`.

Estratégia de teste do adaptador de correio, sem antecipar o Mailpit do M12: o contexto de teste seleciona o `SmtpNotificationSender` **real**, e o que é substituído é apenas o transporte de correio — PostgreSQL e RabbitMQ continuam reais. Assim se prova que o adaptador certo foi escolhido, que a porta foi chamada e que agenda e auditoria saem idênticas às do canal padrão. O que **não** se prova, e por isso não será afirmado na evidência, é entrega contra um servidor SMTP de verdade; isso é do M12.

Os templates ficam num componente próprio, em português, e o texto persistido em `notificacao_enviada` é o mesmo texto entregue — auditoria que guardasse outra coisa não serviria para auditar.

### D7. Correlação restaurada da fonte já validada

O `correlationId` vem do envelope desserializado, que o codec do M05 já validou como texto presente, e não de leitura crua do header AMQP. O header existe e o conversor confere que ele bate com o envelope; usar o campo validado evita duplicar validação e evita confiar em metadado não verificado.

`MDC.put` no início da tentativa, `MDC.clear()` em `finally` — inclusive quando a tentativa falha. Sem o `finally`, o identificador de uma mensagem vazaria para a próxima na mesma thread do container, e o log passaria a mentir exatamente no cenário em que alguém vai lê-lo. A propagação completa entre serviços continua no M11; aqui entra só a restauração dentro do consumidor.

### D8. Testes com a infraestrutura e a configuração efetivas

Os ITs sobem PostgreSQL 16 e RabbitMQ 3.13 por Testcontainers, publicam na topologia real e usam converter, retry e listener de produção. A matriz cobre: os cinco tipos e seus efeitos de agenda; as três notificações e as duas ausências; duplicata sequencial; rollback por falha de sender e por falha entre efeito e marca; JSON inválido; versão desconhecida; falha persistente com DLQ; mensagem válida seguinte; agendamento indisponível; seleção de adaptador nos dois modos.

As coberturas demonstram sensibilidade por mutações isoladas, cada uma ligada à cobertura que de fato a detecta: remover a gravação da marca quebra a prova de reentrega; trocar o `update` do cancelamento por `delete` quebra a preservação da agenda; remover a condição de `occurredAt` faz a agenda regredir e a atualização antiga ressuscitar a consulta; e inverter a ordem efeito→marca quebra a **cobertura estrutural de sequência** — não o teste de rollback, que passaria nas duas ordens. Cada mutação altera **o que é verificado**, nunca a própria verificação, e é restaurada antes da seguinte.

## Risks / Trade-offs

- [Envio externo pode duplicar na janela entre sender e commit] → declarar a garantia real na spec, na documentação e no PR; um outbox local resolveria, e vira change própria se algum requisito passar a exigi-lo.
- [Transação de banco aberta durante I/O do sender] → escopo de uma linha, volume baixo e adaptador padrão local; a alternativa move a falha para o caso pior, de marca sem envio.
- [Dois fatos podem compartilhar `occurredAt`, e o envelope não tem sequência por agregado] → aplicar `>=` literalmente, testar a igualdade e registrar que ordem total exigiria evolução explícita do contrato.
- [Entregas concorrentes do mesmo evento podem produzir duas chamadas externas] → o estado persistido é serializado pela PK da marca e provado por teste concorrente; a duplicidade externa fica declarada em D5, não escondida.
- [`notificacao_enviada` cresce sem retenção] → auditoria é requisito; política de retenção exige change própria e não será improvisada.
- [Adaptador de correio exercitado sem servidor real] → seleção do adaptador real e contrato da porta são testados com transporte controlado; a entrega contra servidor de correio pertence ao M12, e a evidência do M06 não afirmará o contrário.
- [Ativar o listener antes da migration quebraria o consumo] → Flyway conclui V1 antes da inicialização JPA/listener; o IT de contexto comprova a ordem.

## Migration Plan

1. Acrescentar ao `notificacao-service` JPA, Flyway, PostgreSQL, mail e testes; V1 cria as três tabelas vazias — incluindo em `agenda_local` a coluna do instante do fato aplicado, que sustenta a monotonicidade — e o schema é validado.
2. Implantar com a migration concluída antes de iniciar o listener de `notificacao.consultas`; as mensagens acumuladas na fila são então processadas — e produzirão notificações reais, o que é o comportamento pretendido.
3. Em rollback de aplicação, parar o consumidor e preservar banco, fila e DLQ. Não apagar agenda nem auditoria: uma versão corrigida retoma sem duplicar efeito, porque as marcas permanecem.
4. Após a aprovação do PR, promover a capability e arquivar a change **na mesma feature branch**, commitando o archive antes do merge.

## Open Questions

Nenhuma. Materialização por qualquer evento, monotonicidade por `occurredAt` com empate pela ordem de chegada, ausência de notificação para confirmação e realização e para fato não aplicado, preservação da linha cancelada, ordem efeito→marca, origem dos instantes, seleção de adaptador e os limites da garantia externa estão decididos acima, cada um ancorado em fonte do repositório.
