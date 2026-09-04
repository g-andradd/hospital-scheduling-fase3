# Design — M05

## Context

Ver `proposal.md` para motivação e limites. A referência normativa é `docs/03-contrato-de-eventos.md` §§1–7, combinada com as notas do M05 no roadmap. O banco tem V1 e o seed demo V900; V2 fica para outbox e V3 para exclusão. `EventoDeConsulta` contém apenas id, tipo e instante; `EventPublisherLogAdapter` ainda é o adaptador efetivo. `ConsultaRepositoryAdapter.salvar` já faz `saveAndFlush`, traduzindo lock otimista. Os quatro casos de escrita são envolvidos pelos decoradores de `infrastructure/transacao`, construídos por `CasosDeUsoConfig`; os casos de uso nus não são beans.

## Goals / Non-Goals

**Goals:** tornar inseparáveis o commit do fato e sua intenção de publicação; impor corretude concorrente no banco; tornar determinísticos snapshot, correlação e serialização; testar as fronteiras reais de falha, inclusive antes do método de consumo.

**Non-Goals:** transação distribuída PostgreSQL/RabbitMQ, entrega exactly-once, ordenação global, efeitos de consumidores M06/M08, endpoint de realização e correções gerais do M10. O produtor não implementará tabelas de idempotência dos satélites.

## Decisions

### D1. Atomicidade nos decoradores existentes

Manter `@Transactional` com propagação REQUIRED nos decoradores de escrita do M02. O mesmo `JpaTransactionManager`, datasource e contexto envolvem validação, `consultas.salvar`, composição do envelope e inserção do outbox. `OutboxEventPublisher` implementa `EventPublisherPort` em infrastructure, exige transação ativa (MANDATORY) e substitui o adaptador de log. Não usa REQUIRES_NEW, chamada assíncrona nem callback after-commit. O relay é outro bean, com fronteira transacional própria invocada pelo scheduler, evitando self-invocation de proxy.

O `saveAndFlush` existente detecta conflitos antes de produzir o evento, mas não faz commit. Erro de snapshot, serialização ou gravação do outbox propaga e desfaz também a consulta; erro após a inserção do outbox desfaz ambos. Nenhuma exceção de persistência é engolida para continuar uma transação marcada rollback-only. Casos de uso seguem sem Spring/JPA/Jackson/AMQP; `RabbitTemplate` existe somente no relay.

**Alternativas:** publicação síncrona no caso de uso perde atomicidade entre recursos; after-commit pode perder o evento se o processo cair; transação separada do outbox pode produzir evento sem consulta. O corte opcional do contrato não será usado. Esta decisão origina ADR-006.

### D2. Snapshot imutável e alterações anteriores

Ampliar o evento de domínio com valores imutáveis do estado posterior e, para atualização, valores anteriores dos campos alteráveis, em records Java puros. Capturar o anterior antes de `consulta.atualizar`, preservando a ordem atual de validação antes de qualquer mutação. O adaptador completa o snapshot com paciente, médico e usuário registrante na mesma transação; não leva entidades JPA vivas ao relay nem consulta o cadastro no momento da publicação.

`payload` da linha `outbox_evento` guarda o **envelope inteiro**, não apenas seu objeto interno `payload`. `id = eventId` (UUID criado uma vez), `agregado_id = aggregateId`, `tipo_evento = eventType`, `routing_key` vem de tabela fechada, `criado_em = occurredAt`, `publicado_em = NULL`, `tentativas = 0`. `occurredAt` é o instante do fato fornecido pelo evento/Clock, serializado em UTC; `publicado_em` é o instante da confirmação registrada pelo relay. A migration V2 cria PK, campos obrigatórios, `payload jsonb`, contador `numeric` inteiro não negativo (Java `BigInteger`, sem teto de retentativas) e o índice exigido `outbox_evento(publicado_em) WHERE publicado_em IS NULL`.

O snapshot contém todos os campos de §4, com `telefone`, `observacoes` e `motivoCancelamento` presentes mesmo quando nulos conforme o modelo atual. Em cancelamento, motivo não pode ser vazio. `alteracoes` é omitido fora de CONSULTA_ATUALIZADA; nesta, contém apenas campos efetivamente alterados: `dataHoraAnterior`, `duracaoMinutosAnterior`, `medicoIdAnterior`, `observacoesAnterior`. Comparar datas por instante; mudança apenas da representação do offset não é mudança de horário. Em atualização sem diferença, preservar o comportamento atual de emitir ATUALIZADA com `alteracoes: {}`. Não enviar senha, hash ou CPF. Validar `aggregateId == payload.consultaId` e correspondência tipo/routing key.

Os cinco tipos do contrato serão serializáveis e roteáveis. Criar, atualizar, confirmar e cancelar usam os casos existentes. REALIZADA será exercitado com a transição de domínio existente em fixture transacional de teste, sem acrescentar caso de uso ou endpoint público.

**Alternativas:** remontar o snapshot no relay mistura fatos passados com cadastro/consulta atuais; guardar apenas IDs impede reconstruir os valores anteriores; expor entidades JPA ou o contrato Jackson no domínio quebra as fronteiras existentes.

### D3. Relay, confirmação e falha repetida

`@Scheduled(fixedDelay = 1000)` chama um bean transacional de lote. A seleção é `WHERE publicado_em IS NULL ORDER BY tentativas, criado_em, id LIMIT 50 FOR UPDATE SKIP LOCKED`. Locks ficam retidos até o resultado do lote ser commitado, para outra instância pular linhas em processamento. A ordenação por menor número de falhas impede que cinquenta eventos defeituosos monopolizem todo lote enquanto há eventos novos aptos. O índice parcial normativo é obrigatório; não é apresentado como índice que também resolve essa ordenação.

Publicar no exchange `hospital.consultas` com mensagem persistente, `content_type=application/json`, headers normativos, `publisher-confirm-type: correlated`, `publisher-returns: true` e `mandatory: true`. Cada tentativa usa seu próprio `CorrelationData`; sua identificação técnica pode incluir um UUID da tentativa, mas o `eventId` do envelope/header não muda. Marcar publicado somente após ACK positivo **e ausência de return**. Timeout de confirmação de 5 segundos, NACK, return ou erro de conexão mantêm pendente. ACK não comprova consumo: comprova aceitação pelo broker. A espera não ocorre na thread HTTP nem em callback que escreve no banco fora da transação.

Processar sequencialmente até cinquenta registros; cada falha de publicação incrementa `tentativas` uma vez e é tratada dentro do lote sem abortar sua transação JDBC. Persistir tanto sucessos como incrementos ao encerrar o lote. Erro de banco aborta o lote inteiro, inclusive incrementos; será possível reenviar eventos já aceitos. Falha antes de tentar uma linha não conta como tentativa daquela linha. Limitar também espera de conexão/aquisição de canal; documentar que lote com cinquenta timeouts pode reter locks por cerca de 250 segundos mais overhead. `fixedDelay` conta após a conclusão; não promete publicação em até 1 segundo.

**Sem teto de falhas de publicação.** O contrato exige manter pendente e retentar: nenhuma linha vai para DLQ, recebe `publicado_em` artificial ou é apagada por exceder tentativas. Erro inclui id, tipo e contador em log estruturado, sem payload de paciente. Diagnóstico operacional consulta pendências/contador/idade, corrige broker/configuração e deixa o relay retomar. Limite de três tentativas pertence ao **consumo**, não ao outbox. Limpeza e console de reprocessamento ficam fora deste M05.

**Alternativas:** marcar ao retornar de `convertAndSend` perde mensagens não confirmadas; soltar locks antes do envio permite dois relays enviarem a mesma linha simultaneamente; teto com descarte viola o contrato; prioridade somente por antiguidade permite bloqueio permanente dos lotes por eventos defeituosos. O custo de locks durante I/O é aceito para manter o desenho simples e verificável.

### D4. At-least-once como contrato de integração

Depois do ACK do RabbitMQ e antes do commit de `publicado_em`, uma queda pode deixar o evento entregue e ainda pendente. O próximo relay envia novamente **o mesmo envelope, eventId, occurredAt e correlationId**. Não gerar identidade nova nem compensar desfazendo a consulta. O IT deve abrir precisamente essa janela, não apenas duplicar manualmente um JSON.

Entrega at-least-once é consequência aceita. O evento único por operação significa uma linha no outbox; uma execução sem falha entrega uma cópia por fila, mas não há garantia de uma única entrega física. Confirmação do publisher não é ACK do consumidor. M06 e M08 deverão registrar `eventId` processado junto do efeito na mesma transação e tolerar disputa/duplicação; M08 também não poderá regredir snapshot por evento fora de ordem. Isso é dependência documentada, não implementação antecipada.

### D5. Correlação sobrevive ao request

O adaptador lê a correlação estabelecida pelo filtro HTTP no instante da gravação, inclui-a no envelope e persiste-o em JSONB. Quando invocado legitimamente sem request/MDC, gera correlação única naquele momento; nunca na retentativa. O relay extrai `correlationId` do JSON persistido e o copia para `x-correlation-id`. Para seu próprio log, abre um escopo MDC por evento e restaura o contexto anterior em `finally`. Processar dois eventos no mesmo worker não mistura correlações. Não buscar a correlação no MDC vazio do scheduler. Envelope e headers de identidade devem concordar; os consumidores futuros recebem essa fronteira pronta, mas seus logs ponta a ponta são M11.

### D6. Instante no banco, offset explícito no payload

Persistência garante o instante; o delta remove a promessa de guardar o offset original. O snapshot converte o instante da consulta por `instant.atZone(ZoneId.of("America/Sao_Paulo")).toOffsetDateTime()` no adaptador. Usar a zona já adotada pelo Clock/configuração do projeto, aplicada **à data da consulta**, não o offset atual do relógio nem um `-03:00` constante. O mesmo vale para `dataHoraAnterior`; `occurredAt` usa UTC. Jackson/JavaTimeModule escreve ISO-8601 textual, sem timestamps numéricos, e não ajusta o offset recebido ao fuso implícito do desserializador.

A representação é congelada no JSONB junto ao fato. Mesmo que o relay rode com JVM/sessão em outra zona, não recalcula horário ou snapshot. Correção documental também alcança o Javadoc de `PeriodoConsulta`, que hoje sugere preservar o offset no banco. Testes com offsets diferentes e datas com regras históricas distintas provam igualdade de instante e derivação pela zona. **Alternativas:** preservar o offset de entrada exigiria nova coluna e contrato; usar offset devolvido pelo JDBC depende da sessão; fixar -03:00 erra datas com outra regra da zona.

### D7. Topologia literal e retenção de dead letters

Beans em auto-configuração de `shared-contracts` declaram exchanges topic duráveis `hospital.consultas` e `hospital.consultas.dlx`; filas duráveis `notificacao.consultas` e `historico.consultas` com `x-dead-letter-exchange: hospital.consultas.dlx`; DLQs duráveis `notificacao.consultas.dlq` e `historico.consultas.dlq`. Os quatro bindings usam literalmente `consulta.#`, os dois primeiros no principal e os dois últimos na DLX. Não definir `x-dead-letter-routing-key`, TTL ou binding de volta às filas principais. A consequência é que uma rejeição de qualquer origem chega **a ambas as DLQs**; `x-death` identifica a origem. Não tentar “corrigir” a topologia isolando as DLQs.

Escolher filas quorum para as quatro filas, sem alterar nomes, exchanges ou bindings. Nas duas filas de origem configurar `x-dead-letter-strategy=at-least-once` e `x-overflow=reject-publish`, com feature flag `stream_queue` habilitada no RabbitMQ 3.13. São escolhas para sustentar RF-20 durante indisponibilidade temporária de destino: dead-lettering padrão pode perder mensagens. Confirmar os argumentos efetivos via Management API no IT, além de um teste suspendendo a disponibilidade da DLX e restaurando-a. As DLQs não têm consumidor/reenvio automático. Sem expiração/purge automático. O ambiente de um nó não oferece tolerância à perda do disco/nó; não se promete HA.

Auto-configuração compartilhada é importada pelos três serviços; credenciais/conexão vêm do ambiente. Os serviços satélites ganham somente o contrato/configuração neste M05, sem listener de negócio. RabbitMQ foi escolhido pelo contrato de tópicos e filas do projeto, integração Spring AMQP e operação local; Kafka não acrescenta uma necessidade deste escopo. Registrar em ADR-001. Filas clássicas com DLX padrão foram descartadas pela possibilidade de perder mensagens na transferência à DLQ.

### D8. Retry inclui conversão e validação

Configurar exatamente `spring.rabbitmq.listener.simple`: `acknowledge-mode: auto`, `default-requeue-rejected: false`, retry habilitado, `max-attempts: 3`, `initial-interval: 1000ms`, `multiplier: 2.0`, `max-interval: 10000ms`. São três tentativas totais (inicial mais duas), com pausas de 1s e 2s. Há uma única cadeia de retry, evitando multiplicação 3×3. Usar interceptor stateless envolvendo a chamada ao adaptador de listener **incluindo a conversão**, política explícita para exceções encapsuladas (`traverseCauses`) e `RejectAndDontRequeueRecoverer` após esgotar. Não usar recoverer que apenas registra e retorna ACK, nem `ImmediateRequeueAmqpException` para entrada inválida. O handler fatal do container não pode antecipar o descarte na primeira falha de conversão nem apagar mensagem com `x-death` antes do fluxo de retry; configurar e provar essa ordem no IT.

Converter dedicado usa tipo local fechado `EventoEnvelope<ConsultaPayload>`, JavaTimeModule e validação recursiva antes de qualquer efeito. Não confiar em `__TypeId__` recebido nem habilitar desserialização polimórfica arbitrária. Validar JSON objeto, tipos estritos (sem coagir número/string), UUID, enums, versão 1, presença/não nulidade de campos obrigatórios, UTC em occurredAt, offset explícito em dataHora, duração positiva, identificação da consulta, conteúdo de `alteracoes` e consistência dos headers normativos. Campos nulos admitidos em D2 continuam válidos. Headers de identidade ausentes/divergentes, content-type errado e tipos impossíveis são inválidos. Converter tanto JSON malformado como erro semântico para exceção nominal de mensagem inválida, reconhecida pela política de retry; não deixar NPE/IllegalArgumentException escapar sem classificação.

Testar ambos os nomes de fila com listeners exclusivos de teste usando a **mesma factory de produção**, sem efeito de negócio. Contar tentativas no interceptor/converter (JSON inválido nunca entra no método tipado); contar efeitos separadamente. Uma poison message sofre três tentativas, é rejeitada sem requeue e aparece nas DLQs com os bytes originais e `x-death`; uma mensagem válida seguinte é consumida, comprovando que o container continua vivo. Falha transitória que cessa na terceira tentativa produz um único sucesso e não vai à DLQ. Falha repetida de negócio do listener de teste tem a mesma política. RabbitMQ não devolve HTTP 500: a evidência aqui é rejeição controlada, ausência de efeito e ausência de loop. O equivalente HTTP para a constraint é `409`, coberto por `EntradasHostisIT`.

**Alternativas:** só validar no método do listener ignora falhas anteriores de conversão; confiar nos defaults de fatal/recoverer pode mudar o número de tentativas ou descartar sem DLQ; retry no broker por requeue sem limite produz loop. Não exigir idempotência real dos consumidores neste marco.

### D9. Exclusão concorrente no PostgreSQL

V3 instala `btree_gist` e cria `periodo_ocupado tstzrange NOT NULL`, calculado pelo banco, mais duas constraints **NOT DEFERRABLE**: `ex_consulta_medico_periodo` com `medico_id WITH =, periodo_ocupado WITH &&` e `ex_consulta_paciente_periodo` com `paciente_id WITH =, periodo_ocupado WITH &&`, ambas `EXCLUDE USING gist ... WHERE (status IN ('AGENDADA', 'CONFIRMADA'))`. O range é `[data_hora, data_hora + duração em minutos)`. Uma única constraint com médico e paciente juntos seria incorreta: exigiria que ambos coincidissem.

A coluna é derivada por trigger BEFORE INSERT OR UPDATE, que sempre sobrescreve o range, inclusive quando alguém tenta alterá-lo diretamente. Backfill precede NOT NULL e exclusões. Calcular limites em UTC com a duração em minutos; não usar função falsamente marcada IMMUTABLE para indexar `timestamptz + interval`. JPA não escreve a coluna derivada. O trigger mantém a invariável também para SQL direto; as queries existentes de pré-verificação permanecem para resposta antecipada, mas não são a autoridade concorrente. Adjacência é permitida, consultas terminais não ocupam agenda, alterações de horário/duração/médico atualizam o range.

No `ConsultaRepositoryAdapter.salvar`, aproveitar `saveAndFlush` para capturar a violação imediata. Percorrer a cadeia de causas de `DataIntegrityViolationException` até SQLSTATE **23P01** e conferir **nome exato** de uma das duas constraints; traduzir para `ConflitoDeAgendaException.doMedico/doPaciente` com mensagem controlada. Não interpretar texto localizado do PostgreSQL nem converter toda violação de integridade para conflito. Não consultar o banco depois da violação: a transação está abortada. Propagar ao decorador para rollback; o handler existente retorna `TipoDeErro.CONFLITO_DE_AGENDA`, status 409, correlationId e timestamp, sem SQL/constraint/stack trace. Lock otimista continua com sua categoria própria.

**Lacuna encontrada e decidida no apply:** PostgreSQL pode abortar a perdedora por `40P01` (deadlock) ou `40001` (serialization_failure). Ambos são transitórios e viram `AlteracaoConcorrenteException`, com `TipoDeErro.ALTERACAO_CONCORRENTE` e 409, jamais `ConflitoDeAgendaException`: não provam que o horário está ocupado nem que a outra transação fez commit. Inspecionar SQLSTATE estruturado na cadeia de causas do erro de persistência; não classificar pelo texto ou por toda a família DataAccessException. Para `23P01`, o Hibernate pode fornecer constraintName nulo: ler o nome estruturado do driver PostgreSQL e manter a lista exata de constraints. Nenhuma consulta após o erro; rollback integral de consulta/outbox.

**Sem retry automático, por decisão do Gabriel:** neste ambiente de demonstração, o deadlock foi reproduzido pela barreira do IT. Preferimos devolver a categoria existente de “releia e tente novamente”, preservando a diferença entre conflito definitivo e falha transitória. A retentativa limitada da transação inteira é alternativa apropriada para uso concorrente real, mas ampliaria o mecanismo transacional deste marco. Reavaliar antes de operar mais de uma instância do serviço ou tráfego concorrente real; nunca retentar só o statement dentro de uma transação abortada.

IT de concorrência usa PostgreSQL real, duas conexões/transações e barreira **depois das duas pré-verificações e antes da escrita**, impondo a janela TOCTOU. Liberar ambas e esperar commits fora da barreira, com timeout finito: uma grava e outra recebe conflito definitivo ou falha transitória de concorrência. Casos isolam mesmo médico/pacientes diferentes e mesmo paciente/médicos diferentes; incluir remarcação concorrente, adjacência, status terminais e escrita direta. Provar uma consulta nova e um outbox, nunca dois. Provar também via HTTP `201 + 409` para criação, uma consulta e um evento, sem 5xx; aceitar type de conflito de agenda ou alteração concorrente, conforme o SQLSTATE. Não exigir determinismo entre `23P01` e `40P01`, que depende do timing. Provar `40001` separadamente em PostgreSQL real. O ponto de sincronização de teste apenas coordena a execução, sem simular consulta SQL ou infraestrutura.

**Alternativas:** lock otimista não protege inserções de IDs distintos; pré-query tem TOCTOU; SERIALIZABLE exigiria política de retentativa e tradução adicionais; locks por pessoa serializam tráfego sem necessidade e dependem de todos os escritores obedecerem. GiST impõe a regra inclusive a outro escritor SQL. Revoga-se a justificativa do M02 de preferir legibilidade à corretude, mantendo o arquivo histórico intacto.

### D9.1. Verificação do seed antes da migration

Verificado em 2026-09-04: `agendamento-service/src/main/resources/db/demo/V900__seed_demo.sql` insere quatro usuários, um médico e dois pacientes; não insere consultas. A busca nos fontes de produção não encontrou outro seed de consultas. As cinco consultas (duas realizadas, uma cancelada e duas futuras) estão descritas em `docs/02-especificacao-funcional.md` §5, mas não materializadas no seed atual. Portanto, o seed versionado não produz colisão no backfill. Isso não prova ausência de colisões em um banco demo já utilizado.

Manter V900 intacta e não acrescentar consultas neste ajuste. A V3 verificará pares de consultas ativas sobrepostas antes de criar as exclusões e falhará com mensagem explícita em português, identificando os IDs conflitantes e se o recurso comum é médico ou paciente. Não apagará, deslocará horários nem excluirá registros da regra. O IT de upgrade executará o seed real e provará tanto a migração sem colisão quanto a recusa após introduzir sobreposição de teste. A ausência das cinco consultas no seed é uma lacuna preexistente da demonstração, não uma justificativa para enfraquecer a constraint.

## Open Questions

Na aprovação da proposta: nenhuma. As decisões D1–D9 resolvem atomicidade, relay, retentativas, reentrega, correlação, offset, topologia, validação e exclusão concorrente. A inspeção do seed registrada em D9.1 resolve o risco de colisão introduzida pelo seed versionado; dados inconsistentes de um ambiente já utilizado serão recusados explicitamente. Não restava decisão de escopo ou de comportamento necessária para iniciar a implementação aprovada.

**Resolvida durante o apply:** Gabriel decidiu mapear `40P01` e `40001` para alteração concorrente, sem retry automático, conforme D9. Nenhuma dúvida em aberto.

**Cobertura estrutural no M05:** `docs/04-roadmap.md` reserva a introdução da suíte ArchUnit ao M11. Este apply mantém essa fronteira e verifica os imports proibidos e os decoradores transacionais com inspeção automatizada e as coberturas estruturais já existentes; antecipar a dependência e as regras completas alteraria o escopo aprovado.

## Risks / Trade-offs

| Risco | Mitigação / limite aceito |
|---|---|
| Broker confirma, commit local falha | Mesmo eventId no reenvio; idempotência obrigatória em M06/M08; IT da janela exata. |
| Pendências crescem durante falha prolongada | Sem descarte; contador/idade para diagnóstico; prioridades por tentativas e timeout finito; operador corrige a causa. |
| Outbox segura locks durante I/O | Até 50 registros, waits limitados e SKIP LOCKED; scheduler fora da requisição. |
| Mensagem inválida morre antes do retry | IT instrumenta a conversão real/factory de produção, além de falhas do listener. |
| DLQs recebem cópias das duas origens | Topologia normativa preservada e x-death inspecionado; não prometer exclusividade. |
| RF-20 exceder a garantia da DLX padrão | Quorum com dead-lettering at-least-once e IT de indisponibilidade temporária; sem promessa de sobreviver à perda física do único nó. |
| Range derivado divergir dos campos | Trigger em toda escrita e IT com SQL direto, UTC, adjacência e mudança de duração. |
| Constraints falharem em dados antigos | Diagnóstico prévio, falha de migration sem correção destrutiva automática. |
| Ausência de revisor independente | Evidência cenário→teste, três coberturas existentes mantidas e cobertura estrutural nova para entradas AMQP; auditoria humana antes do apply/archive. |

## Migration Plan

1. V2 cria outbox e índice parcial; V3 cria extensão, coluna/trigger, backfill e exclusões. Testar banco vazio, upgrade de V1 com dados válidos, e upgrade recusado com sobreposição ativa. Não editar V1/V900 nem apagar dados para passar V3. Como demo já aplica V900, configurar `out-of-order` apenas no profile demo para permitir V2/V3 pendentes nesse banco; testar upgrade demo e reaplicação. Não habilitar globalmente nem usar baseline/repair para esconder migration.
2. Aplicar as quatro declarações de fila novas e políticas/argumentos; confirmar feature flag, tipos e bindings no broker. Se já existirem filas incompatíveis, falhar a declaração e documentar migração assistida preservando mensagens; nunca purgar/recriar automaticamente.
3. Ativar publisher outbox e relay. Não converter logs antigos em eventos nem reconstruir eventos de consultas preexistentes. Fixture de integração publica a partir de operações novas.
4. Rollback de aplicação mantém V2/V3 e dados, desliga relay e preserva pendências para retomada; versão antiga que só loga eventos não mantém RF-15, portanto não é rollback funcional transparente. Recuperação preferida é corrigir e avançar. Remover constraints/extensão/outbox exigiria migration posterior revisada, não rollback automático.
5. Após implementação aprovada e verificada, Gabriel revisa PR; só então archive na feature, commit e push do archive pelo Gabriel, conferência de promoção/movimentação no diff, e finalmente merge --no-ff. Nesta proposta nenhuma dessas ações é executada.

## Referências técnicas consultadas

Estas fontes sustentam as escolhas de implementação; não substituem o contrato local.

- [PostgreSQL 16 — ranges e exclusões](https://www.postgresql.org/docs/16/rangetypes.html) e [btree_gist](https://www.postgresql.org/docs/16/btree-gist.html): exclusão de sobreposição e igualdade UUID.
- [PostgreSQL 16 — tipos temporais](https://www.postgresql.org/docs/16/datatype-datetime.html): timestamptz preserva o instante, não a zona/offset de entrada.
- [Spring AMQP 3.2 — confirms e returns](https://docs.spring.io/spring-amqp/reference/3.2/amqp/template.html): confirmação correlacionada e retorno consultado antes de marcar sucesso.
- [Spring AMQP — recuperação e retry](https://docs.spring.io/spring-amqp/reference/3.1/amqp/resilience-recovering-from-errors-and-broker-failures.html): rejeição sem requeue e classificação da cadeia de causas; verificar na versão resolvida pelo BOM 3.5.7 durante apply.
- [RabbitMQ 3.13 — quorum queues](https://www.rabbitmq.com/docs/3.13/quorum-queues): dead-lettering at-least-once exige configuração explícita e pode duplicar entregas.
