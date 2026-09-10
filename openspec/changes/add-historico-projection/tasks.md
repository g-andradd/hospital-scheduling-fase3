# Tasks — M08

## 1. Build e configuração

- [x] 1.1 Adicionar ao `historico-service` JPA, Flyway, PostgreSQL e Testcontainers PostgreSQL/RabbitMQ, preservando versões do BOM e `0.2.0-SNAPSHOT`; verificar resolução com `mvn -q test-compile` na raiz.
- [x] 1.2 Configurar datasource/Flyway do `historico_db` e perfil de teste, mantendo a factory compartilhada do M05 com três tentativas e `default-requeue-rejected: false`; verificar propriedades e objetos efetivos em `ConfiguracaoHistoricoIT` e `ConfiguracaoDeConsumoTest`.
- [x] 1.3 Configurar inicialização para Flyway concluir antes de JPA/listener e manter o serviço sem endpoint novo; verificar subida do contexto e ordem real em `SchemaHistoricoIT`.

## 2. Persistência do read model

- [x] 2.1 Criar `V1__cria_read_model_do_historico.sql` com `consulta_historico`, `consulta_evento` e `evento_processado`, PKs/FK e timestamps do modelo normativo, sem índices secundários de consulta do M09; verificar schema vazio, reaplicação e `ddl-auto=validate` em `SchemaHistoricoIT` com PostgreSQL 16.
- [x] 2.2 Mapear snapshot, trilha e marca processada; usar `eventId` como `consulta_evento.id` e `@JdbcTypeCode(SqlTypes.JSON)` no payload; verificar round-trip do JSON completo, instantes e campos nulos em `PersistenciaHistoricoIT`.
- [x] 2.3 Implementar o upsert nativo atômico com condição `EXCLUDED.atualizado_em >= consulta_historico.atualizado_em`, preservando `criado_em` local e usando `occurredAt` como `atualizado_em`; verificar o SQL real e que não há pré-query TOCTOU em `OrdenacaoHistoricoIT`.

## 3. Projeção e idempotência transacional

- [x] 3.1 Implementar o projetor dos cinco tipos a partir de `EventoEnvelope<ConsultaPayload>`, fazendo upsert do snapshot e inserção da trilha antes da marca final; verificar sequência criada→atualizada→cancelada e cada tipo isolado em `ProjecaoHistoricoIT`.
- [x] 3.2 Criar `ConsumidorTransacionalDoHistorico` como única entrada `@RabbitListener`, com `@Transactional`, consulta de duplicata, efeito e inserção de `evento_processado` por último; verificar fronteira anotada e ordem das operações em `ProtecoesEstruturaisTest`.
- [x] 3.3 Provar reentrega sequencial e falha injetada entre projeção e marca, relendo por outra transação para exigir efeito único ou rollback de snapshot/trilha/marca em `IdempotenciaHistoricoIT`.
- [x] 3.4 Provar duas entregas concorrentes do mesmo `eventId` com barreira e duas transações reais; exigir uma trilha, um snapshot e uma marca confirmados, com a tentativa perdedora integralmente revertida em `IdempotenciaHistoricoIT`.

## 4. Eventos fora de ordem

- [x] 4.1 Projetar todo evento novo na trilha mesmo quando o upsert do snapshot não se aplica; verificar evento antigo após novo com trilha ampliada, marca processada e snapshot intacto em `OrdenacaoHistoricoIT`.
- [x] 4.2 Cobrir eventos de instantes diferentes processados concorrentemente e exigir snapshot final do maior `occurredAt`, independentemente de quem adquire o lock primeiro, em `OrdenacaoHistoricoIT`.
- [x] 4.3 Cobrir dois eventos distintos com `occurredAt` igual e exigir que ambos entrem na trilha e o segundo recebido determine o snapshot, documentando o limite de ordenação do envelope atual.

## 5. Retry, DLQ e versão

- [x] 5.1 Ligar o consumidor à fila normativa `historico.consultas` usando converter e topologia compartilhados, sem duplicar cadeia de retry; verificar os cinco tipos publicados no RabbitMQ real em `ConsumoHistoricoRabbitMqIT`.
- [x] 5.2 Criar `EntradasHostisHistoricoIT` com JSON/campo/metadado inválido e `version != 1`; exigir três tentativas, bytes originais e `x-death` na DLQ, zero linhas nas três tabelas e ausência de requeue infinito.
- [x] 5.3 Injetar falha persistente dentro da projeção, exigir rollback em cada tentativa e DLQ; publicar mensagem válida em seguida e comprovar que o mesmo consumidor permanece ativo.

## 6. Proteções estruturais

- [x] 6.1 Criar `ProtecoesEstruturaisTest` que descobre todos os `@RabbitListener` do `historico-service` e exige a única fronteira transacional padrão, rejeitando listener adicional ou projetor anotado; provar que a cobertura falha com uma classe infratora temporária.
- [x] 6.2 Criar `ProtecoesEstruturaisTest` que recusa uso/configuração de `RestClient`, `WebClient`, OpenFeign, URL ou porta de cliente para agendamento; verificar por IT que a projeção funciona com o agendamento indisponível e que campo ausente segue para DLQ sem chamada remota.
- [x] 6.3 Provar que a marca final não é opcional: omitir temporariamente a gravação de `evento_processado` deve fazer a cobertura da fronteira e o IT de reentrega falharem, ainda que a PK da trilha ofereça defesa adicional; restaurar a implementação após a prova.

## 7. Documentação e rastreabilidade

- [x] 7.1 Atualizar README e documentação de arquitetura/modelo físico com projeção, idempotência, regra de ordenação, DLQ, ausência de HTTP, operação e rollback; preservar a alteração de sequência já feita pelo Gabriel em `docs/04-roadmap.md` e verificar todos os links.
- [x] 7.2a Manter a matriz abaixo alinhada aos métodos e casos parametrizados reais; conferir os 15 Scenarios sem usar apenas nome de classe ou task marcada como substituto de execução.
- [ ] 7.2b Levar a evidência da matriz ao corpo do PR quando Gabriel criar o PR.

### Matriz cenário → método/caso

| Scenario (nome exato) | Método/caso |
|---|---|
| Sequência completa atualiza snapshot e trilha | `ProjecaoHistoricoIT#sequenciaCompletaAtualizaSnapshotETrilha` |
| Todos os tipos normativos são projetáveis | `ProjecaoHistoricoIT#todosOsTiposNormativosSaoProjetaveis` |
| Reentrega sequencial não duplica efeito | `IdempotenciaHistoricoIT#reentregaSequencialNaoDuplicaEfeito` |
| Reentregas concorrentes não confirmam dois efeitos | `IdempotenciaHistoricoIT#reentregasConcorrentesNaoConfirmamDoisEfeitos` |
| Falha entre o efeito e a marca desfaz a projeção | `IdempotenciaHistoricoIT#falhaEntreEfeitoEMarcaDesfazProjecao` |
| Evento antigo amplia a trilha sem regredir o snapshot | `OrdenacaoHistoricoIT#eventoAntigoAmpliaTrilhaSemRegredirSnapshot` |
| Eventos fora de ordem processados concorrentemente preservam o mais novo | `OrdenacaoHistoricoIT#eventosConcorrentesPreservamOMaisNovo` |
| Fatos no mesmo instante seguem a ordem de chegada | `OrdenacaoHistoricoIT#fatosNoMesmoInstanteSeguemOrdemDeChegada` |
| Mensagem malformada não deixa efeito e chega à DLQ | `EntradasHostisHistoricoIT#mensagemMalformadaNaoDeixaEfeitoEChegaADlq` |
| Versão desconhecida é rejeitada | `EntradasHostisHistoricoIT#versaoDesconhecidaERejeitadaPeloConsumidorReal` |
| Falha persistente da projeção reverte cada tentativa | `EntradasHostisHistoricoIT#falhaPersistenteReverteCadaTentativa` |
| Mensagem válida posterior continua sendo processada | `EntradasHostisHistoricoIT#mensagemValidaPosteriorContinuaSendoProcessada` |
| Evento completo materializa o histórico sem consulta remota | `DesacoplamentoHistoricoIT#eventoCompletoMaterializaSemConsultaRemota` |
| Campo obrigatório ausente não dispara busca no produtor | `EntradasHostisHistoricoIT#campoObrigatorioAusenteERejeitadoPeloConsumidorReal` |
| Snapshot mantém as dimensões do evento aplicado | `ProjecaoHistoricoIT#snapshotMantemDimensoesDoEventoAplicado` |

## 8. Verificação

- [x] 8.1 Executar `openspec validate add-historico-projection --strict` e `openspec status --change add-historico-projection`; conferir capability, 15 Scenarios, matriz e quatro artefatos completos.
- [x] 8.2 Executar `mvn -q clean verify` na raiz com PostgreSQL 16 e RabbitMQ 3.13 reais, sem testes ignorados; registrar contagem por módulo e resultado final.
- [x] 8.3 Conferir JaCoCo do `historico-service` e cobertura global mínima de 85%, registrando números reais sem antecipar agregação do M10.
- [x] 8.4 Executar as coberturas estruturais de listener idempotente e ausência de cliente HTTP; conferir schema/JSONB, configuração efetiva de retry e inexistência de índices secundários antecipados.
- [x] 8.5 Demonstrar as três mutações de D6 — omitir a marca final, tornar o upsert incondicional e engolir a rejeição — e repetir as suítes afetadas após restaurar cada alteração.
- [ ] 8.6 Entregar ao Gabriel comandos para commit/push e clone limpo da feature; registrar `mvn -q clean verify` do clone antes da aprovação. Após aprovação, promover e arquivar a change nesta mesma branch, entregar o commit do archive e exigir push antes do merge do único PR.
