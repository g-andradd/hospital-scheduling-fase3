# Tasks — M09

## 1. Build, segurança e configuração

- [x] 1.1 Adicionar ao `historico-service` as dependências de Spring for GraphQL e `shared-security`, preservando o BOM e `0.2.0-SNAPSHOT`; verificar resolução com `mvn -q test-compile` na raiz.
- [x] 1.2 Tornar configuráveis em `SegurancaAutoConfiguration` duas listas — caminhos autenticados com padrão `/api/**` e caminhos públicos adicionais com padrão **vazio** — mantendo uma única `SecurityFilterChain`; verificar em teste do `shared-security` que, com ambos os padrões, a cadeia é idêntica à atual, que caminho não listado continua caindo no `denyAll` e que o `agendamento-service` não muda de comportamento.
- [x] 1.3 Configurar o `historico-service` para acrescentar `/graphql` aos caminhos autenticados em qualquer profile, sem cadeia concorrente; verificar em `SegurancaGraphqlIT` que existe exatamente **uma** `SecurityFilterChain`, que `/graphql` exige token e que um caminho não relacionado continua negado.
- [x] 1.4 Habilitar a interface GraphiQL e acrescentar `/graphiql` e seus estáticos aos caminhos públicos adicionais **apenas** nos profiles `dev` e `demo`; verificar em `GraphiqlPorProfileIT` os quatro estados: com os valores padrão a lista pública adicional está vazia; em `dev` e em `demo` a interface responde e seus caminhos são alcançáveis; no profile padrão a interface está desabilitada **e** os caminhos caem no `denyAll`.

## 2. Schema e leitura do read model

- [x] 2.1 Criar `schema.graphqls` com o scalar `DateTime`, os enums `PeriodoFiltro` e de status compatível com `ConsultaPayload.Status`, o input `FiltroConsulta` e o tipo `ConsultaHistorico`; verificar em `SchemaGraphqlIT` que o schema carrega e que o enum de status tem exatamente os quatro valores do contrato.
- [x] 2.2 Implementar a montagem de predicados no armazenamento para período, intervalo `[de, ate)` e lista de status, com `Clock` injetado e ordenação `data_hora, id`; verificar em `FiltroHistoricoIT` os casos `TODAS`, `FUTURAS`, `PASSADAS` com dados nas três posições temporais e a fronteira exata do instante atual.
- [x] 2.3 Cobrir intervalo explícito, filtro por um e por múltiplos status, lista de status vazia, combinação de período/intervalo/status e resultado vazio em `FiltroHistoricoIT`; exigir ordenação idêntica entre execuções, inclusive com `dataHora` empatado.
- [x] 2.4 Implementar as queries `consultasDoPaciente`, `consultasDoMedico`, `minhasConsultas` e `consulta`; verificar em `ConsultaHistoricoGraphqlIT` o retorno das dimensões do snapshot, o identificador existente e o inexistente traduzido para `NOT_FOUND`.

## 3. Autorização

- [x] 3.1 Implementar `@PreAuthorize` por resolver e o componente de recorte de propriedade do paciente, resolvendo `pacienteId` e `medicoId` de `UsuarioAutenticado`; verificar que nenhum identificador de identidade vindo do cliente participa da decisão em `AutorizacaoHistoricoIT`.
- [x] 3.2 Criar `MatrizDeAutorizacaoGraphql` lendo a tabela `### historico-service — GraphQL` de `docs/02-especificacao-funcional.md`, no padrão do `agendamento-service`; verificar por asserção estrutural que a leitura encontrou 5 linhas, 3 perfis e 15 células.
- [x] 3.3 Criar `MatrizDeAutorizacaoGraphqlIT` gerando um caso por célula contra o endpoint real com token real; exigir sucesso nas células permitidas e `FORBIDDEN` nas proibidas, com 15 casos executados.
- [x] 3.4 Acrescentar cenários de propriedade em `AutorizacaoHistoricoIT`: paciente informando `pacienteId` de terceiro, paciente consultando registro alheio por `consulta(id:)` e ausência de vazamento parcial de dados.
- [x] 3.5 Criar `CoberturaDeAutorizacaoGraphqlTest` varrendo por reflexão os resolvers do histórico e falhando quando um método exposto não declara decisão de autorização; provar que a cobertura falha com um resolver infrator temporário e restaurar depois.

## 4. Correção manual e auditoria

- [x] 4.1 Definir o input da mutation com `consultaId` apenas como seletor do alvo, sem campo de autor, sem contraparte corrigível de `consultaId` e sem `pacienteId`/`medicoId`, e com presença explícita por campo permitindo nulo apenas em `observacoes`; verificar em `CorrecaoHistoricoIT` que campo ausente não corrige, que nulo em `observacoes` limpa e que nulo nos demais campos é `BAD_REQUEST`.
- [x] 4.2 Implementar `corrigirRegistroHistorico` como transação única com bloqueio pessimista do snapshot, autor obtido do token e `atualizado_em` avançado pelo `Clock`; verificar por evidência tripla em `CorrecaoHistoricoIT`: introspecção do schema comprovando que o input não expõe campo de autor, correção autenticada comprovando que a auditoria registra o `medicoId` do JWT, e documento com campo de autor desconhecido recusado como `BAD_REQUEST` sem efeito persistido.
- [x] 4.3 Gravar na trilha a linha `CORRECAO_MANUAL` com id novo, `consulta_id` preservado, `ocorrido_em` da correção e payload JSONB com justificativa e valores antes/depois; verificar o conteúdo do JSON em `CorrecaoHistoricoIT`.
- [x] 4.4 Recusar correção sem alteração efetiva, com justificativa ausente ou em branco e com status fora do domínio válido; verificar cada recusa e que o snapshot permanece intacto. Para os identificadores, verificar por introspecção que o schema não declara campo corrigível de `consultaId`, `pacienteId` ou `medicoId`, que um documento enviando qualquer um deles é recusado como `BAD_REQUEST` antes do resolver, e que após uma correção confirmada os três identificadores persistidos continuam os originais.
- [x] 4.5 Provar rollback integral com falha injetada na gravação da auditoria e ausência de lost update em duas correções concorrentes com barreira e transações reais, em `CorrecaoHistoricoIT`.
- [x] 4.6 Provar que a correção não cria linha em `evento_processado` e não publica evento, e que evento antigo posterior não desfaz a correção enquanto evento com `occurredAt` posterior volta a atualizar o snapshot, em `CorrecaoEProjecaoIT`.
- [x] 4.7 Criar cobertura estrutural que fixa os cinco tipos normativos de `TipoEvento` e recusa o vazamento de `CORRECAO_MANUAL` para o contrato, routing keys ou topologia.

## 5. Persistência e desempenho

- [x] 5.1 Criar `V2__cria_indices_de_consulta_do_historico.sql` com `consulta_historico(paciente_id, data_hora)` e `consulta_historico(medico_id, data_hora)`, sem editar `V1`; verificar em `SchemaIndicesHistoricoIT` a presença dos índices, a imutabilidade de `V1` e `ddl-auto=validate` com PostgreSQL 16.
- [x] 5.2 Medir e registrar em `SchemaIndicesHistoricoIT` os planos de execução reais das consultas por paciente e por médico com PostgreSQL real, e confirmar que a evidência sustenta D7 — os dois índices de `V2` e nenhum índice de status. Se a evidência contradisser D7, **parar o apply** e exigir `/opsx:update` com nova aprovação antes de criar qualquer índice: o design não é alterado durante a implementação.
- [x] 5.3 Provar em `DesempenhoConsultaHistoricoIT` que a quantidade de comandos de leitura de uma query raiz é constante entre um e muitos registros, e que os filtros aparecem no SQL enviado; não mascarar contagem com cache de sessão.

## 6. Erros e segurança da resposta

- [x] 6.1 Implementar o `DataFetcherExceptionResolver` para as exceções lançadas **durante** os resolvers — `FORBIDDEN`, `NOT_FOUND` e `BAD_REQUEST` de domínio em `extensions.code`; verificar em `ErrosGraphqlIT` que acesso negado nunca vira erro interno.
- [x] 6.1b Implementar na fronteira GraphQL um `WebGraphQlInterceptor`, ou instrumentação equivalente, que normalize os erros **anteriores** à execução — análise e validação do documento — para `extensions.code=BAD_REQUEST`, compartilhando com o resolver a mesma tabela de códigos e a mesma sanitização, extraídas para um ponto único; verificar em `ErrosGraphqlIT` que campo desconhecido no input e valor de enum ou argumento inválido produzem `BAD_REQUEST`, que nenhum resolver é executado nesses casos e que nada é persistido.
- [x] 6.2 Garantir que falha inesperada devolve mensagem genérica pelos dois caminhos — pré-execução e execução de resolver; verificar que a resposta não contém SQL, rastreamento de pilha nem nome de classe.
- [x] 6.3 Verificar em `ErrosGraphqlIT` token ausente e token inválido recusados na fronteira HTTP, sem execução de resolver e sem revelar qual parte do token falhou.

## 7. Documentação e rastreabilidade

- [x] 7.1 Atualizar a matriz `### historico-service — GraphQL` de `docs/02-especificacao-funcional.md` para `minhasConsultas` exclusiva de PACIENTE e revisar RF-11 a RF-14; verificar que `MatrizDeAutorizacaoGraphql` continua lendo 5 linhas, 3 perfis e 15 células.
- [x] 7.2 Atualizar `docs/01-arquitetura.md` §6 com o schema efetivo, a autorização por resolver, a correção manual auditada e a operação do GraphiQL por profile; verificar todos os links.
- [x] 7.3 Atualizar o README com exemplos de autenticação, das quatro queries e da mutation, e com o contrato da correção manual e sua trilha; não tocar o CHANGELOG, que fecha com a release `0.2.0`.
- [x] 7.4a Manter a matriz cenário → método/caso abaixo alinhada aos métodos reais; conferir os 40 Scenarios sem usar nome de classe ou task marcada como substituto de execução.
- [ ] 7.4b Levar a evidência da matriz ao corpo do PR quando Gabriel criar o PR.

### Matriz cenário → método/caso

Os 40 Scenarios de `specs/historico-de-consultas/spec.md`, cada um com o método que o executa.

| Scenario (nome exato) | Método/caso |
|---|---|
| Período TODAS não recorta pelo relógio | `FiltroHistoricoIT#periodoTodasNaoRecortaPeloRelogio` |
| Período FUTURAS devolve apenas o que ainda não passou | `FiltroHistoricoIT#periodoFuturasDevolveApenasOQueNaoPassou` |
| Período PASSADAS devolve apenas o que já ocorreu | `FiltroHistoricoIT#periodoPassadasDevolveApenasOQueOcorreu` |
| Registro exatamente no instante atual pertence ao futuro | `FiltroHistoricoIT#registroNoInstanteAtualPertenceAoFuturo` |
| Intervalo explícito inclui o início e exclui o fim | `FiltroHistoricoIT#intervaloIncluiInicioEExcluiFim` |
| Filtro por status seleciona os valores informados | `FiltroHistoricoIT#filtroPorStatusSelecionaOsValoresInformados` |
| Lista de status vazia não restringe o resultado | `FiltroHistoricoIT#listaDeStatusVaziaNaoRestringe` |
| Período, intervalo e status são combinados por conjunção | `FiltroHistoricoIT#periodoIntervaloEStatusSaoCombinadosPorConjuncao` |
| Ausência de registros devolve coleção vazia | `FiltroHistoricoIT#ausenciaDeRegistrosDevolveColecaoVazia` |
| Ordenação é total e determinística | `FiltroHistoricoIT#ordenacaoEDeterministica` |
| Identificador existente devolve o registro | `ConsultaHistoricoGraphqlIT#identificadorExistenteDevolveORegistro` |
| Identificador inexistente é recusado como não encontrado | `ConsultaHistoricoGraphqlIT#identificadorInexistenteERecusado` |
| Cada célula da matriz é honrada | `MatrizDeAutorizacaoGraphqlIT#celulaDaMatrizEHonrada` (15 casos) + `#leituraEncontrouAMatrizCompleta` |
| Paciente não alcança registro de terceiro por identificador | `AutorizacaoHistoricoIT#pacienteNaoAlcancaRegistroDeTerceiro` + `#pacienteNaoLePorIdRegistroAlheio` |
| Consultas do paciente autenticado vêm do token | `AutorizacaoHistoricoIT#consultasDoPacienteAutenticadoVemDoToken` + `#identificadorDoClienteNaoDecide` |
| Perfis clínicos não usam a operação do paciente | `AutorizacaoHistoricoIT#perfisClinicosNaoUsamAOperacaoDoPaciente` |
| Operação sem decisão de autorização é recusada pela cobertura | `CoberturaDeAutorizacaoGraphqlTest#operacaoDeclaraDecisaoDeAutorizacao` + `#resolverSemAnotacaoFalha` |
| Requisição sem token é recusada na fronteira | `ErrosGraphqlIT#requisicaoSemTokenERecusadaNaFronteira` |
| Token inválido é recusado na fronteira | `ErrosGraphqlIT#tokenInvalidoERecusadoNaFronteira` |
| Acesso negado não vira erro interno | `ErrosGraphqlIT#acessoNegadoNaoViraErroInterno` |
| Entrada inválida é recusada como requisição inválida | `ErrosGraphqlIT#enumInexistenteERecusado`, `#argumentoDeTipoIncompativelERecusado`, `#entradaInvalidaDeNegocioERecusada` |
| Falha inesperada não vaza detalhe interno | `ErrosGraphqlIT#falhaInesperadaNaoVazaDetalheInterno` |
| Interface interativa existe apenas em dev e demo | `GraphiqlPorProfileIT$ProfileDev#interfaceHabilitadaEAlcancavelEmDev`, `$ProfileDemo#interfaceHabilitadaEAlcancavelEmDemo`, `$ProfilePadrao#interfaceDesabilitadaENaoLiberada` |
| Caminhos não relacionados continuam negados | `SegurancaGraphqlIT#caminhosNaoRelacionadosContinuamNegados` |
| Correção válida altera o snapshot e registra a trilha | `CorrecaoHistoricoIT#correcaoValidaAlteraSnapshotERegistraTrilha` |
| Correção sem alteração efetiva é recusada | `CorrecaoHistoricoIT#correcaoSemAlteracaoEfetivaERecusada` |
| Justificativa ausente ou vazia é recusada | `CorrecaoHistoricoIT#justificativaAusenteOuVaziaERecusada` |
| Identificadores não existem como campos corrigíveis | `SchemaGraphqlIT#inputDaCorrecaoNaoExpoeIdentidade` + `CorrecaoHistoricoIT#identificadoresNaoSaoCorrigiveis` |
| Autor da correção vem do token | `SchemaGraphqlIT#inputDaCorrecaoNaoExpoeIdentidade` + `CorrecaoHistoricoIT#autorDaCorrecaoVemDoToken` |
| Campo desconhecido de entrada é recusado antes da execução | `ErrosGraphqlIT#campoDesconhecidoNoInputERecusado` |
| Status corrigido precisa pertencer ao domínio válido | `CorrecaoHistoricoIT#statusForaDoDominioERecusado` + `#correcaoDeStatusNaoAplicaMaquinaDeTransicoes` |
| Falha na auditoria desfaz a correção inteira | `CorrecaoHistoricoIT#falhaNaAuditoriaDesfazACorrecaoInteira` |
| Correções concorrentes não perdem alteração | `CorrecaoHistoricoIT#correcoesConcorrentesNaoPerdemAlteracao` |
| Evento antigo não desfaz a correção | `CorrecaoEProjecaoIT#eventoAntigoNaoDesfazACorrecao` |
| Evento posterior volta a atualizar o snapshot | `CorrecaoEProjecaoIT#eventoPosteriorVoltaAAtualizarOSnapshot` |
| Correção não altera o contrato de mensageria | `CorrecaoEProjecaoIT#correcaoNaoAlteraOContratoDeMensageria` + `TiposNormativosIntactosTest` |
| Correção não registra marca de evento processado | `CorrecaoEProjecaoIT#correcaoNaoRegistraMarcaDeEventoProcessado` |
| Filtro é aplicado no armazenamento | `DesempenhoConsultaHistoricoIT#filtroEAplicadoNoArmazenamento` |
| Resultado maior não multiplica comandos de leitura | `DesempenhoConsultaHistoricoIT#resultadoMaiorNaoMultiplicaComandos` |
| Índices das consultas expostas existem no esquema | `SchemaIndicesHistoricoIT#v2CriaOsDoisIndices`, `#naoExisteIndiceDeStatus`, `#migrationOriginalPermaneceInalterada`, `#planoUsaOsIndicesDeV2` |

Semântica de ausente/nulo e o carregamento do schema não têm Scenario próprio e são cobertos por
`CorrecaoHistoricoIT#ausenteNaoCorrigeENuloLimpaObservacoes`, `#nuloNosDemaisCamposERecusado` e
`SchemaGraphqlIT#schemaCarregaEExpoeAsOperacoes`, `#enumDeStatusEspelhaOContrato`,
`#filtroDeclaraOsCamposDoRecorte`.

## 8. Verificação

- [x] 8.1 Executar `openspec validate add-historico-graphql --strict` e `openspec status --change add-historico-graphql`; conferir capability, 40 Scenarios, matriz e quatro artefatos completos.

```bash
openspec validate add-historico-graphql --strict && openspec status --change add-historico-graphql
```

- [x] 8.2 Executar a suíte direcionada do `historico-service` com PostgreSQL 16 e RabbitMQ 3.13 reais; registrar contagem e zero ignorados.

```bash
mvn -q -pl historico-service -am -Dit.test='*HistoricoIT,*GraphqlIT,MatrizDeAutorizacaoGraphqlIT,SchemaIndicesHistoricoIT,CorrecaoEProjecaoIT,DesempenhoConsultaHistoricoIT' -Dfailsafe.failIfNoSpecifiedTests=false verify
```

- [x] 8.3 Executar o gate global na raiz, sem testes ignorados; registrar contagem por módulo e resultado final.

```bash
mvn -q clean verify
```

- [x] 8.4 Conferir JaCoCo do `historico-service` e a cobertura global ponderada a partir dos relatórios gerados pelo gate; registrar números reais sem antecipar o gate agregado do M10.

```bash
grep -o '<counter type="LINE"[^/]*/>' historico-service/target/site/jacoco/jacoco.xml | tail -1
```

- [x] 8.5 Executar as coberturas estruturais e demonstrar sensibilidade por duas mutações isoladas, ambas no que é verificado e nunca na própria verificação: remover temporariamente o `@PreAuthorize` de um resolver e exigir falha de `CoberturaDeAutorizacaoGraphqlTest`; alterar temporariamente a tabela da documentação ou o parser para produzir contagem diferente de 5 linhas / 3 perfis / 15 células e exigir falha da asserção estrutural. Restaurar cada mutação e repetir a suíte afetada verde; manter intacta a cobertura dos cinco tipos normativos. Remover a asserção de contagem não serve como prova — apagar uma proteção produz verde falso, não evidência.
- [ ] 8.6 Verificar a partir de clone limpo da feature branch, depois do push, em diretório temporário único criado de forma segura, e removê-lo após registrar o resultado.

```bash
set -eu; TMP=$(mktemp -d); [ -d "$TMP" ] || { echo "mktemp falhou"; exit 1; }; TMP=$(cd "$TMP" && pwd -P); case "$TMP" in "$(cd "${TMPDIR:-/tmp}" && pwd -P)"/*) ;; *) echo "alvo fora do temporario: $TMP"; exit 1;; esac; trap 'rm -rf "$TMP"' EXIT INT TERM; git clone -b feature/m09-add-historico-graphql https://github.com/g-andradd/hospital-scheduling-fase3.git "$TMP/repo"; cd "$TMP/repo"; mvn -q clean verify
```

- [ ] 8.7 Levar ao corpo do PR a matriz dos 40 Scenarios com seus métodos, a contagem de testes por módulo, a cobertura e o resultado do clone limpo.
- [ ] 8.8 **Depois da aprovação do PR e antes do merge**, executar `/opsx:archive add-historico-graphql` na própria feature branch — pelo workflow, não pelo comando CLI cru, para que a promoção da spec seja verificada e não apenas o move do diretório.

```
/opsx:archive add-historico-graphql
```

- [ ] 8.9 Confirmar o resultado do archive: `openspec/changes/add-historico-graphql` deixou de existir; a change está em `openspec/changes/archive/<data>-add-historico-graphql/`; a delta foi promovida para `openspec/specs/historico-de-consultas/spec.md` preservando os Requirements do M08; nenhuma outra capability foi alterada; e `openspec list` não mostra change ativa inesperada.

```bash
openspec validate --all --strict && openspec list
```

- [ ] 8.10 Conferir no diff do PR que código, archive e spec promovida estão na mesma feature branch, entregar ao Gabriel o comando do commit de archive e exigir o push antes do merge — diferentemente do M08, o archive não pode ficar para uma branch corretiva.

```bash
git add -A openspec && git commit -m "chore(openspec): arquiva add-historico-graphql"
```
