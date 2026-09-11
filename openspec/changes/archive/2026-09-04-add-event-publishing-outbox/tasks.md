# Tasks — M05

Implementação concluída e verificada no working tree e em clone limpo. A matriz identifica métodos reais e cada cenário pelo nome exato no teste/DisplayName ou nos casos parametrizados.

## 1. Build e configuração

- [x] 1.1 Adicionar dependências AMQP, Jackson JavaTime, Spring Retry e suporte de teste ao shared-contracts e aos serviços consumidores da configuração; verificar resolução com `mvn -q test-compile` na raiz, mantendo as versões do BOM e 0.2.0-SNAPSHOT.
- [x] 1.2 Registrar auto-configuração compartilhada e conexão RabbitMQ por ambiente nos três serviços, com factory/converter próprios; verificar os três contextos e ausência de listeners de negócio antecipados.
- [x] 1.3 Configurar perfil de teste com scheduler desabilitado por padrão e acionamento explícito do relay nos ITs, preservando teste dedicado do fixedDelay de 1000ms e do proxy transacional; verificar por `ConfiguracaoDoRelayTest`.

## 2. Contratos e topologia

- [x] 2.1 Criar records de envelope/payload e tabela fechada dos cinco pares tipo/routing key; verificar por `ContratoDeEventosTest` e `ContratoDeEventosRabbitMqIT`, incluindo REALIZADA sem endpoint novo.
- [x] 2.2 Criar serialização ISO-8601 com JavaTimeModule e validação recursiva estrita de envelope, snapshot e headers; verificar JSON inválido, campos ausentes/nulos, tipos incorretos, enum/UUID/data, versão, IDs divergentes e __TypeId__ hostil em `EventoEnvelopeConverterTest`.
- [x] 2.3 Declarar dois exchanges, quatro filas quorum, quatro bindings consulta.#, DLX e argumentos de dead-lettering at-least-once de D7; verificar definições efetivas, x-death e fanout para as duas DLQs em `TopologiaRabbitMqIT`.
- [x] 2.4 Provar retenção com broker reiniciado no mesmo volume e DLX temporariamente indisponível/restaurada, sem retorno à fila principal, usando `TopologiaRabbitMqIT` com RabbitMQ 3.13 real.
- [x] 2.5 Criar fixture JSON normativo em shared-contracts/src/test/resources e teste que compara chaves/tipos com o contrato; verificar snapshot completo, campos opcionais nulos e ausência de senha/hash/CPF. Preparar reutilização posterior, sem implementar os testes cruzados de M10.

## 3. Domínio e produção do fato

- [x] 3.1 Ampliar EventoDeConsulta com dados imutáveis do fato e capturar valores anteriores antes da mutação; verificar `PublicacaoDeEventosUseCaseTest`, `AtualizarConsultaUseCaseTest` e a inspeção estrutural de imports/decoradores, preservando validação antes da escrita. A suíte ArchUnit permanece no M11, conforme o roadmap.
- [x] 3.2 Compor snapshot no adaptador transacional e alteracoes apenas em ATUALIZADA, com campos realmente modificados, nulos anteriores e mapa vazio em atualização sem diferença; verificar `SnapshotDoEventoIT` para os cinco tipos e mudança posterior dos cadastros.
- [x] 3.3 Fixar occurredAt em UTC, normalizar dataHora/dataHoraAnterior pela zona America/Sao_Paulo no instante do fato e persistir a correlação do HTTP ou gerada sem request; verificar `CorrelacaoEOffsetDoEventoIT` com MDC encerrado, duas correlações, outra zona de sessão/JVM e regras históricas distintas.

## 4. Persistência e transações

- [x] 4.1 Criar V2__cria_outbox_evento.sql com envelope JSONB, identidades, timestamps, contador inteiro não negativo sem overflow de 32 bits e índice parcial publicado_em IS NULL; verificar schema/índice real e contador em `OutboxRepositoryIT`.
- [x] 4.2 Implementar OutboxEventPublisher MANDATORY no mesmo datasource/transaction manager dos decoradores e substituir EventPublisherLogAdapter; verificar commit conjunto dos quatro casos de escrita e recusa de chamada sem transação.
- [x] 4.3 Criar `AtomicidadeDoOutboxIT` com @DataJpaTest/PostgreSQL real, decoradores e adaptador reais, sem transação externa automática mascarando commits: injetar falha após salvar consulta, erro real de inserção do outbox e falha após inserir outbox; reler em nova transação e exigir rollback de ambos, também em alteração.
- [x] 4.4 Criar V3__impede_sobreposicao_de_consultas.sql com btree_gist, range derivado por trigger, backfill e duas exclusões NOT DEFERRABLE parciais para médico/paciente; verificar UTC, intervalo [início,fim), estados ativos/terminais, duração e tentativa de adulterar o range por SQL em `ExclusaoDeAgendaIT`.
- [x] 4.5 Validar migrations em banco vazio, upgrade V1 com dados, bloqueio explícito em português com IDs/recurso de sobreposição preexistente e upgrade com o seed real V900 (sem consultas) e out-of-order só no perfil demo; verificar preservação de dados/reaplicação e recusa de colisão introduzida no teste em `MigrationsDoOutboxIT` sem editar V1 ou V900, conforme D9.1.

## 5. Relay e garantias de entrega

- [x] 5.1 Implementar seleção transacional FOR UPDATE SKIP LOCKED de até 50 pendentes, priorizando tentativas/criado_em/id; verificar duas conexões/instâncias sem envio simultâneo da mesma linha em `ConcorrenciaDoRelayIT`.
- [x] 5.2 Publicar com mensagem persistente, confirms correlacionados, returns, mandatory e espera finita, marcando sucesso somente por ACK sem return; verificar `OutboxRelayIT` com broker real e sem escrita no banco em callback fora da transação.
- [x] 5.3 Tratar falhas de conexão, ausência de exchange/rota, NACK e timeout, incrementando uma vez por falha persistida, sem teto/descarte; verificar os quatro resultados, contador acima de 2147483647 e cinquenta falhas sem bloquear evento novo em `OutboxRelayIT`.
- [x] 5.4 Provocar rollback local após ACK real do broker e verificar reenvio idêntico, mesma linha de outbox e consulta preservada em `OutboxReentregaIT`; não substituir por teste que apenas publica duas cópias manualmente.
- [x] 5.5 Provar operação HTTP confirmada com broker indisponível e posterior entrega após restabelecimento em `PublicacaoComBrokerIndisponivelIT`; verificar que snapshot, occurredAt e correlação persistidos não são reconstruídos pelo relay.

## 6. Retry e superfície AMQP hostil

- [x] 6.1 Aplicar nos três serviços a configuração normativa de retry e rejeição sem requeue, com uma única cadeia, conversão dentro do retry e recoverer que rejeita para DLX; verificar propriedades e objetos efetivos em `ConfiguracaoDeConsumoTest`.
- [x] 6.2 Criar `EntradasHostisAmqpIT` parametrizado pelas duas filas e pela tabela de JSON/estrutura inválida, campos obrigatórios ausentes/nulos, tipos/UUID/enum/data/duração inválidos, versão desconhecida e metadados incompatíveis; exigir três tentativas totais, zero efeito, bytes originais nas DLQs e processamento de mensagem válida seguinte.
- [x] 6.3 Testar recuperação no terceiro processamento e falha persistente do listener de fixture em `EntradasHostisAmqpIT`, com factory real; instrumentar conversão/interceptor para contar tentativas anteriores ao método tipado e verificar pausas de 1s/2s com tolerância explícita, sem sleeps arbitrários.
- [x] 6.4 Criar `CoberturaDeEntradasAmqpTest` que deriva campos e tipos do contrato e exige casos ausente/nulo/tipo incompatível para cada campo obrigatório, controla exceções dos campos opcionais, descobre os cinco tipos e verifica que toda família de erro de mensagem é classificada; provar que retirar um caso obrigatório ou uma classificação faz o teste falhar.

## 7. Concorrência, API e regressões

- [x] 7.1 Traduzir SQLSTATE 23P01 das duas constraints nomeadas para ConflitoDeAgendaException e 40P01/40001 para AlteracaoConcorrenteException no saveAndFlush, sem retry automático, propagando rollback sem consultar transação abortada; verificar tipos médico/paciente e preservação das categorias de outros erros e lock otimista em testes do adaptador.
- [x] 7.2 Criar ITs TOCTOU com barreira após ambas as pré-queries reais e antes da escrita: médico comum, paciente comum e duas remarcações; exigir um vencedor, rollback do perdedor e apenas seu evento ausente em `ExclusaoDeAgendaIT`. Cobrir também adjacência, status terminais e recursos distintos.
- [x] 7.3 Acrescentar corrida HTTP autorizada ao `EntradasHostisIT`, exigir 201+409 com type de conflito de agenda ou alteração concorrente, correlationId/timestamp, uma consulta e um outbox, nenhum 5xx e sem SQL/stack; manter `CoberturaDoMapaDeErrosTest` e `CoberturaDeAutorizacaoTest` verdes, sem alterar a matriz.
- [x] 7.4 Ajustar durabilidade para igualdade de instante e acrescentar sessão com fuso distinto em `ConsultaRepositoryAdapterIT`; preservar todos os cenários herdados dos dois Requirements modificados.

## 8. Documentação e rastreabilidade

- [x] 8.1 Escrever ADR-001 (RabbitMQ) e ADR-006 (Transactional Outbox) em Contexto/Decisão/Alternativas/Consequências/Status; verificar correspondência com D1–D9, inclusive at-least-once, custo de locks e obrigação futura de idempotência.
- [x] 8.2 Atualizar README, documentação de arquitetura e modelo físico para outbox, range derivado, configuração por ambiente, offset, diagnóstico de pendências/DLQs, upgrade demo e limites de rollback; corrigir o Javadoc de PeriodoConsulta. Verificar links e exemplos contra os testes; não reescrever o contrato normativo nem os designs arquivados. Não há novo endpoint para Postman neste M05.
- [x] 8.3 Completar a matriz abaixo com métodos/casos parametrizados reais e levar sua evidência ao corpo do PR para auditoria do Gabriel; conferir cada Scenario, sem usar nome de classe ou task marcada como substituto de execução.

### Matriz cenário → método/caso

Mensageria corresponde a mensageria-de-eventos; Agendamento a agendamento-de-consultas. Cada linha exige evidência individual, ainda que vários casos estejam na mesma classe.

| Capability | Scenario (nome exato) | Método/caso |
|---|---|---|
| Mensageria | Todos os destinos recebem os eventos | `TopologiaRabbitMqIT#definicoesEfetivasEFanout` |
| Mensageria | Recursos e mensagens sobrevivem ao reinício do broker | `TopologiaRabbitMqIT#reinicioDoBrokerNoMesmoVolume` |
| Mensageria | Dead letter alcança as duas DLQs | `TopologiaRabbitMqIT#rejeicaoPreservaCorpoChaveEMarcaXDeath` |
| Mensageria | Destino de dead letter temporariamente indisponível | `TopologiaRabbitMqIT#dlxIndisponivelRetemEEntregaAoRestaurar` |
| Mensageria | Cinco tipos usam envelope e routing key correspondentes | `ContratoDeEventosRabbitMqIT#envelopeTardioImutavel` |
| Mensageria | Momento do fato não muda com a publicação tardia | `ContratoDeEventosRabbitMqIT#envelopeTardioImutavel` |
| Mensageria | Snapshot de criação é autossuficiente | `SnapshotDoEventoIT#criacaoCompletaSemDadosPrivados` |
| Mensageria | Atualização inclui apenas valores anteriores alterados | `SnapshotDoEventoIT#alteracoesCapturamAntesDaMutacao` |
| Mensageria | Atualização sem diferença preserva o contrato | `SnapshotDoEventoIT#semDiferencaEOffsetEquivalente` |
| Mensageria | Mudanças de status têm snapshot posterior | `SnapshotDoEventoIT#statusConfirmadaCanceladaERealizada` |
| Mensageria | Mudança posterior não reescreve o passado | `SnapshotDoEventoIT#snapshotPersistidoIndependeDosCadastros` |
| Mensageria | Operação aceita confirma consulta e evento juntos | `AtomicidadeDoOutboxIT#commitsDosQuatroCasosDeEscrita` |
| Mensageria | Falha depois de salvar consulta desfaz a escrita | `AtomicidadeDoOutboxIT#falhaDesfazCriacaoEAlteracao` |
| Mensageria | Falha depois de salvar outbox desfaz ambas as escritas | `AtomicidadeDoOutboxIT#falhaDesfazCriacaoEAlteracao` |
| Mensageria | Regra de negócio recusada não produz evento | `AtomicidadeDoOutboxIT#conflitoNaoCriaEvento` |
| Mensageria | Broker indisponível não desfaz fato confirmado | `PublicacaoComBrokerIndisponivelIT#httpConfirmaSemBrokerEEntregaDepoisComMesmoFato` |
| Mensageria | Pendente confirmado passa a publicado | `OutboxRelayIT#ackSemReturnPublicaPersistente` |
| Mensageria | Falha mantém pendente e incrementa tentativas | `OutboxRelayIT#falhasReaisRetem` |
| Mensageria | Falha repetida não descarta nem bloqueia eventos novos | `OutboxRelayIT#cinquentaDefeituososNaoMonopolizamLoteEContadorNaoTransborda` |
| Mensageria | Contador não transborda em inteiro de 32 bits | `OutboxRelayIT#cinquentaDefeituososNaoMonopolizamLoteEContadorNaoTransborda` |
| Mensageria | Lote e concorrência do relay respeitam o limite | `ConcorrenciaDoRelayIT#doisRelaysNaoEnviamAMesmaLinhaSimultaneamente` |
| Mensageria | Queda entre confirmação do broker e commit local | `OutboxReentregaIT#ackRealSeguidoDeRollbackReenviaMesmoEnvelope` |
| Mensageria | Correlação HTTP reaparece no header após o fim do request | `PublicacaoComBrokerIndisponivelIT#httpConfirmaSemBrokerEEntregaDepoisComMesmoFato` |
| Mensageria | Eventos sucessivos não compartilham correlação por acidente | `OutboxRelayIT#correlacaoPersistidaRestauraMdcEHeaderSemReconstituirSnapshot` |
| Mensageria | Fato sem request recebe correlação estável | `CorrelacaoEOffsetDoEventoIT#duasCorrelacoesEOrigemSemRequestSaoEstaveis` |
| Mensageria | Offset deriva da zona no instante do agendamento | `CorrelacaoEOffsetDoEventoIT#regrasHistoricasIndependemDaSessaoEDaJvm` |
| Mensageria | Configuração efetiva exige rejeição sem requeue | `ConfiguracaoDeConsumoTest#configuracaoRealDosTresServicos` |
| Mensageria | Payload malformado chega à DLQ após três tentativas | `EntradasHostisAmqpIT#catalogoCompletoAteDlq` |
| Mensageria | Campo obrigatório ausente ou inválido é isolado | `EntradasHostisAmqpIT#catalogoCompletoAteDlq` |
| Mensageria | Versão desconhecida não alcança o processamento | `EntradasHostisAmqpIT#catalogoCompletoAteDlq` |
| Mensageria | Metadados incompatíveis não alteram a desserialização | `EntradasHostisAmqpIT#catalogoCompletoAteDlq` |
| Mensageria | Falha transitória recupera dentro do limite | `EntradasHostisAmqpIT#falhasDoListener` |
| Mensageria | Falha persistente não derruba o consumidor | `EntradasHostisAmqpIT#falhasDoListener` |
| Agendamento | Consulta registrada sobrevive ao reinício | `ConsultaRepositoryContractTest#consultaGravadaERecuperadaIntegralmente (herdado por ConsultaRepositoryAdapterIT)` |
| Agendamento | Mudança de estado é persistida | `ConsultaRepositoryContractTest#mudancaDeEstadoEPersistida (herdado por ConsultaRepositoryAdapterIT)` |
| Agendamento | Operação recusada não deixa registro | `ConsultaRepositoryContractTest#operacaoRecusadaNaoDeixaRegistro (herdado por ConsultaRepositoryAdapterIT)` |
| Agendamento | Consulta gravada no passado é recuperável | `ConsultaRepositoryContractTest#consultaNoPassadoERecuperavel (herdado por ConsultaRepositoryAdapterIT)` |
| Agendamento | Leitura com outro deslocamento preserva o instante | `ConsultaRepositoryAdapterIT#fusoDeSessaoNaoAlteraInstante` |
| Agendamento | Conflito é detectado contra dados persistidos | `AtomicidadeDoOutboxIT#conflitoNaoCriaEvento` |
| Agendamento | Períodos adjacentes persistidos não são conflito | `ExclusaoDeAgendaIT#adjacenciaEEstadosTerminais` |
| Agendamento | Consulta encerrada persistida não bloqueia a agenda | `ExclusaoDeAgendaIT#adjacenciaEEstadosTerminais` |
| Agendamento | Busca de conflito é delimitada na origem | `ConsultaRepositoryContractTest#buscaEDelimitadaNaOrigem (herdado por ConsultaRepositoryAdapterIT)` |
| Agendamento | Inserções concorrentes do mesmo médico não confirmam juntas | `ExclusaoDeAgendaIT#insercoesConcorrentes` |
| Agendamento | Inserções concorrentes do mesmo paciente não confirmam juntas | `ExclusaoDeAgendaIT#insercoesConcorrentes` |
| Agendamento | Remarcações concorrentes não criam sobreposição | `ExclusaoDeAgendaIT#remarcacoesConcorrentes` |
| Agendamento | Conflito concorrente pela API retorna Problem Detail | `EntradasHostisIT#corridaHttpProduzUmaConsultaUmEventoE409Sem5xx` |
| Agendamento | Escrita direta não contorna a exclusão | `ExclusaoDeAgendaIT#escritaDiretaEAtualizacaoDerivamRange` |
| Agendamento | Alteração de período mantém a exclusão coerente | `ExclusaoDeAgendaIT#escritaDiretaEAtualizacaoDerivamRange` |
| Agendamento | Consultas concorrentes sem recurso em comum são aceitas | `ExclusaoDeAgendaIT#concorrentesIndependentes` |

## 9. Verificação

- [x] 9.1 Executar `openspec validate add-event-publishing-outbox --strict` e `openspec status --change add-event-publishing-outbox`; conferir deltas completos, matriz cenário→teste e todos os artefatos.
- [x] 9.2 Executar `mvn -q clean verify` na raiz com PostgreSQL/RabbitMQ reais, sem testes ignorados, registrando contagens e resultados de todos os módulos.
- [x] 9.3 Conferir os relatórios `target/site/jacoco/index.html` dos módulos tocados: mínimo 80% por módulo e 90% em domain/application do agendamento; registrar números reais sem antecipar o gate global do M10.
- [x] 9.4 Executar `rg -n '^import (org\.springframework|jakarta\.(persistence|validation)|com\.fasterxml|org\.springframework\.amqp)' agendamento-service/src/main/java/br/com/fiap/hospital/agendamento/domain agendamento-service/src/main/java/br/com/fiap/hospital/agendamento/application` e exigir nenhuma ocorrência; verificar os decoradores transacionais e exigir as três coberturas estruturais existentes mais a cobertura AMQP verdes. A suíte ArchUnit continua reservada ao M11 por `docs/04-roadmap.md`.
- [x] 9.5 Demonstrar que as verificações detectam o defeito: retirar temporariamente a exclusão no banco isolado deve quebrar o IT concorrente; retirar um campo do catálogo hostil deve quebrar a cobertura; fazer o recoverer engolir erro deve quebrar a prova de DLQ. Restaurar as alterações experimentais e repetir apenas as suítes afetadas.
- [x] 9.6 Entregar ao Gabriel instruções de clone limpo da feature e executar `mvn -q clean verify` no clone que ele preparar; registrar a saída antes da aprovação do PR, pois o M05 altera build/infra. Todas as operações Git e o commit/push do archive antes do merge continuam exclusivos dele.
