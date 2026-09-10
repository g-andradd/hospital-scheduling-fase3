## Context

O M05 promoveu `mensageria-de-eventos`: envelope versão 1, snapshot autossuficiente, entrega at-least-once, retry de três tentativas e `default-requeue-rejected: false`. O `historico-service` ainda contém apenas o bootstrap; `historico_db` não possui migrations nem projeção. Ver `proposal.md` e `specs/historico-de-consultas/spec.md`.

O modelo normativo exige `consulta_historico`, `consulta_evento` e `evento_processado`. O payload do M05 contém todos os campos necessários ao M08; não há lacuna que justifique chamada ao agendamento. `EventoJson.escreverPayload` é helper interno do codec para persistência da trilha e não altera o wire contract do envelope. O M09 consumirá o read model por período, status, paciente e médico, mas definirá consultas e índices somente na change seguinte.

## Goals / Non-Goals

**Goals:**

- Projetar os cinco eventos em snapshot e trilha com PostgreSQL e RabbitMQ reais.
- Tornar idempotência e atomicidade propriedades da única entrada AMQP, inclusive sob duplicatas concorrentes.
- Preservar todos os fatos válidos sem deixar um evento antigo regredir o snapshot.
- Isolar mensagens inválidas ou incompatíveis em DLQ sem qualquer efeito persistido.
- Deixar colunas tipadas adequadas às consultas do M09, sem antecipar sua estratégia de índices.

**Non-Goals:**

- GraphQL, autorização, filtros, correção manual e índices de leitura do M09.
- Consumidores de notificação e lembretes do M06/M07.
- Chamadas HTTP ao agendamento, cache remoto ou reconstrução de payload incompleto.
- Propagação completa do MDC e regras ArchUnit do M11.
- Política automática de replay/purge das DLQs ou retenção de registros processados.

## Decisions

### D1. Uma única entrada AMQP possui a fronteira idempotente e transacional

`ConsumidorTransacionalDoHistorico` será o único bean/método com `@RabbitListener` no módulo. O mesmo método público será interceptado por `@Transactional` e executará a sequência fixa: verificar `evento_processado`; aplicar o efeito completo; inserir `evento_processado` por último; retornar apenas depois do commit. Os cinco tipos entram por um roteador interno, cujos projetores não recebem anotação Rabbit nem controlam a transação.

A proteção não depende de cada handler lembrar o protocolo. Um teste estrutural varre as classes do `historico-service` e falha se existir outro `@RabbitListener`, se o listener não for a fronteira transacional padrão ou se um projetor for exposto como listener. Um IT confirma que a transação está ativa e injeta falha entre efeito e marca para provar rollback integral. A suíte ArchUnit completa continua no M11; o M08 entrega a cobertura estrutural específica que sua garantia exige.

`evento_processado.event_id` é chave primária e `consulta_evento.id` usa o próprio `eventId`, dando defesa adicional à trilha. A consulta prévia evita trabalho na reentrega comum; sob duplicatas concorrentes, a colisão pode ocorrer na trilha ou na marca final, mas sempre reverte toda a transação perdedora. A exceção alcança o retry; numa tentativa posterior, a marca já confirmada encerra sem novo efeito. Não se grava a marca antes do efeito porque uma falha posterior perderia definitivamente o evento.

**Alternativas descartadas:** convenção em cada handler permite esquecimento; marcar antes do efeito pode confirmar perda; transações separadas criam janelas de efeito sem marca ou marca sem efeito; deduplicação apenas em memória falha após reinício e entre instâncias.

### D2. V1 materializa o modelo normativo e usa eventId como identidade da trilha

A migration V1 cria:

- `consulta_historico`: `id` igual ao `consultaId`, dimensões tipadas de paciente/médico, `data_hora timestamptz`, status/observações, `criado_em` e `atualizado_em`;
- `consulta_evento`: `id` igual ao `eventId`, `consulta_id` com FK para o snapshot, tipo, `ocorrido_em timestamptz` e `payload jsonb`;
- `evento_processado`: `event_id` PK e `processado_em`.

O payload da trilha será mapeado com `@JdbcTypeCode(SqlTypes.JSON)` do Hibernate 6, como exige o roadmap. `ddl-auto` permanece `validate`; toda criação ocorre por Flyway. O snapshot é feito antes da inserção da trilha para que qualquer dos cinco eventos possa ser o primeiro observado e satisfaça a FK. A trilha vem antes de `evento_processado`, preservando a regra de marca posterior ao efeito.

`criado_em` representa a criação local da projeção e não é reescrito; `atualizado_em` representa o `occurredAt` do último fato aplicado. O modelo não cria índice secundário para período, status, paciente ou médico no M08. PKs e unicidades necessárias à integridade não contam como otimização antecipada; o M09 medirá seus planos e definirá índices a partir das consultas reais.

**Alternativas descartadas:** guardar apenas JSON impede filtros simples do M09; duplicar todo o envelope no snapshot mistura auditoria e estado atual; criar agora índices compostos pressupõe combinações e ordenações ainda não especificadas.

### D3. Upsert atômico impede regressão do snapshot

O snapshot usa um único `INSERT ... ON CONFLICT (id) DO UPDATE ... WHERE EXCLUDED.atualizado_em >= consulta_historico.atualizado_em`. A condição é avaliada pelo PostgreSQL sob o lock da linha, eliminando o TOCTOU de consultar e depois salvar. Todo evento novo entra em `consulta_evento` e em `evento_processado`, mesmo quando o `WHERE` impede a atualização do snapshot.

O critério é exclusivamente `occurredAt`, o único marcador de ordem do contrato. Evento estritamente antigo não altera o snapshot; entre eventos com instantes diferentes, o maior vence mesmo sob concorrência. Para instantes iguais, `>=` segue a decisão obrigatória do roadmap: ambos entram na trilha e a última entrega aplicada pode definir o snapshot. O contrato não fornece sequência por agregado, portanto não há base legítima para inventar desempate por `eventId`.

**Alternativas descartadas:** horário de consumo torna replay dependente da execução; comparar status presume uma ordem que não vale para atualização de dados; `SELECT` seguido de `save` reabre corrida; ordenar por UUID cria uma ordem artificial não declarada pelo produtor.

### D4. Falha de conversão, versão desconhecida e falha de projeção terminam em DLQ

O listener reutiliza a factory do M05: acknowledge automático, conversão/validação dentro da cadeia, três tentativas com 1s/2s e `default-requeue-rejected: false`. Envelope com `version != 1` falha de forma controlada antes do roteamento; não é ignorado nem adaptado por tolerância. Depois das tentativas, a mensagem é rejeitada para a DLX e alcança a DLQ normativa com os bytes originais.

Erros de conversão não abrem efeito de negócio. Erros ocorridos dentro da projeção propagam para fora do método transacional, revertendo snapshot, trilha e marca a cada tentativa. Assim, a trilha contém somente fatos aceitos; o fato recusado fica auditável operacionalmente na DLQ. Não se grava uma linha de erro em `consulta_evento`, pois isso misturaria uma mensagem que o serviço não compreendeu com fatos válidos da consulta. Um evento válido posterior continua sendo consumido.

**Alternativas descartadas:** ACK/ignore perde incompatibilidade; requeue ilimitado monopoliza o consumidor; registrar trilha antes da validação viola atomicidade; aceitar versão futura parcialmente pode corromper a projeção.

### D5. O snapshot do M05 é a única fonte e chamadas HTTP são proibidas estruturalmente

O mapeamento lê diretamente `ConsultaPayload`: IDs e nomes do paciente/médico, especialidade, `dataHora`, status e observações estão presentes. Nenhum `RestClient`, `WebClient`, OpenFeign, URL do agendamento ou porta de cliente será introduzido. Uma cobertura estrutural falha se o módulo declarar dependência ou classe de cliente HTTP de saída, e o IT processa eventos com o agendamento indisponível.

Campo obrigatório ausente ou tipo incompatível é falha do contrato e segue D4. Não há fallback, preenchimento nulo inventado ou busca remota. Telefone, motivo de cancelamento e registradoPor continuam preservados no JSON da trilha, mas não ganham coluna no snapshot porque o modelo normativo do `historico_db` não os exige no M08.

**Alternativas descartadas:** enriquecimento síncrono acopla disponibilidade, reintroduz N+1 e pode reconstruir passado com dados atuais; replicar tabelas de pessoas amplia o read model além do necessário.

### D6. Testes usam a infraestrutura e a configuração efetivas

Os ITs sobem PostgreSQL 16 e RabbitMQ 3.13 por Testcontainers, publicam na topologia real e usam o converter, retry e listener de produção. A matriz cenário→teste inclui: sequência criada/atualizada/cancelada; os cinco tipos; duplicata sequencial e concorrente; rollback antes da marca; evento antigo e concorrência de instantes; igualdade de `occurredAt`; JSON inválido; versão desconhecida; falha persistente; mensagem válida seguinte; agendamento indisponível; schema e JSONB reais.

As coberturas demonstram sensibilidade por mutações isoladas: omitir a gravação final de `evento_processado` quebra a prova da fronteira obrigatória; trocar `>=` por atualização incondicional faz o snapshot regredir; configurar requeue ou recoverer que engole a falha impede a mensagem de chegar à DLQ. Cada mutação é restaurada antes do gate final.

## Risks / Trade-offs

- [O envelope não possui sequência por agregado; dois fatos podem compartilhar `occurredAt`] → aplicar `>=` literalmente, testar a igualdade e registrar que ordem total exigiria evolução explícita do contrato.
- [Duas duplicatas concorrentes podem fazer trabalho que depois será revertido] → manter todos os efeitos no mesmo PostgreSQL e deixar a PK final serializar a confirmação; não há efeito externo no M08.
- [Mensagem incompatível não aparece na trilha funcional] → preservar bytes e `x-death` na DLQ, sem contaminar a auditoria com fato não aceito.
- [`evento_processado` e `consulta_evento` crescem sem retenção] → manter histórico completo por requisito; política de retenção exige change própria e não será improvisada.
- [Ausência de índices secundários pode causar scan quando o M09 chegar] → o M09 define consultas, mede planos e cria apenas os índices comprovados.
- [Ativar o listener antes da migration quebra o consumo] → Flyway conclui V1 antes da inicialização JPA/listener; o IT de contexto comprova a ordem.

## Migration Plan

1. Acrescentar dependências de JPA, Flyway, PostgreSQL e testes; V1 cria as três tabelas vazias e valida o schema.
2. Implantar o serviço com a migration concluída antes de iniciar o listener de `historico.consultas`; mensagens acumuladas na fila são então projetadas.
3. Em rollback de aplicação, parar o consumidor e preservar banco, fila e DLQ. Não apagar trilha nem marcas processadas; uma versão corrigida pode retomar sem duplicar efeitos.
4. Após aprovação do PR, promover a capability e arquivar a change na mesma feature; Gabriel commita e envia o archive antes do merge.

## Open Questions

Nenhuma. O contrato M05 fornece todos os campos do modelo atual; o roadmap fixa `occurredAt >= atualizado_em`; a política de versão, DLQ, atomicidade, idempotência estrutural e fronteira com o M09 estão decididas acima.
