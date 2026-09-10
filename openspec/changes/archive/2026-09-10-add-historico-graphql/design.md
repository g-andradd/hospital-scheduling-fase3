## Context

O M08 deixou o `historico-service` com um read model completo e uma única entrada AMQP: `consulta_historico` como snapshot, `consulta_evento` como trilha e `evento_processado` como marca de idempotência, sem índice secundário algum e sem nenhuma porta HTTP. O serviço não depende de `shared-security` hoje e não expõe controller. Ver `proposal.md` e `specs/historico-de-consultas/spec.md`.

Três restrições do repositório moldam o desenho. A primeira: `SegurancaAutoConfiguration`, em `shared-security`, publica **uma** `SecurityFilterChain` que libera os caminhos públicos, exige autenticação em `/api/**` e aplica `anyRequest().denyAll()` — `/graphql` cairia no `denyAll` e o `@PreAuthorize` do resolver nunca seria avaliado. A segunda: a identidade já chega pronta em `UsuarioAutenticado`, com `pacienteId` e `medicoId` preenchidos quando aplicáveis, então nada precisa ser reconstruído a partir do corpo da requisição. A terceira: o M08 fixou `ddl-auto: validate` e migrations Flyway como único caminho de schema, e ADR-007 mantém o histórico em camadas simples, sem Clean Architecture.

O padrão executável da matriz de autorização já existe em `agendamento-service`: `MatrizDeAutorizacao` lê a tabela de `docs/02-especificacao-funcional.md` em tempo de execução, `MatrizDeAutorizacaoIT` gera um caso por célula e `CoberturaDeAutorizacaoTest` varre os controllers exigindo anotação. O M09 replica os três papéis no histórico em vez de inventar um mecanismo novo.

## Goals / Non-Goals

**Goals:**

- Expor a leitura filtrada e a correção do read model por GraphQL, com PostgreSQL real nos testes.
- Fazer da matriz da documentação a fonte executável da autorização do histórico, com falha visível quando um resolver novo não declara decisão.
- Admitir `/graphql` mantendo o `denyAll` como padrão da cadeia compartilhada e sem cadeia concorrente.
- Traduzir toda recusa para código GraphQL estável, sem 500 e sem vazamento de detalhe interno.
- Tornar a correção manual atômica com sua auditoria e compatível com a regra de monotonicidade já promovida.
- Criar apenas os índices que as consultas desta change realmente exercitam, com plano medido.

**Non-Goals:**

- Paginação, `Connection`/cursor, subscriptions, federation e `DataLoader`: o tipo devolvido é plano e não tem campo que dispare carregamento por item, então não há N+1 a resolver com batching. Introduzi-los agora seria maquinário sem problema correspondente.
- Exposição da trilha completa de eventos por GraphQL — nenhum documento do projeto a exige.
- Emissão de token, endpoint de login ou store de usuários no histórico.
- Qualquer alteração de `TipoEvento`, routing key, topologia ou envelope.
- Reescrita do consumidor AMQP do M08.

## Decisions

### D1. `/graphql` entra na cadeia compartilhada por lista configurável, não por cadeia nova

`SegurancaAutoConfiguration` passa a ler os caminhos autenticados de uma propriedade com valor padrão `/api/**`; o `historico-service` declara `/api/**,/graphql`. A cadeia continua única, o `anyRequest().denyAll()` continua sendo o padrão e o agendamento não muda de comportamento porque o padrão preserva exatamente a lista atual.

A alternativa de declarar uma segunda `SecurityFilterChain` no histórico foi descartada: o bean da auto-configuração não é `@ConditionalOnMissingBean`, então o serviço passaria a ter duas cadeias ordenadas, e qual delas atende `/graphql` viraria detalhe de ordenação — exatamente a ambiguidade que se quer evitar. Acrescentar `/graphql` fixo na cadeia compartilhada também foi descartado: abriria o caminho nos três serviços, inclusive onde ele não existe.

Autenticar `/graphql` não resolve o GraphiQL: a interface é uma página estática servida antes de qualquer token existir, e no `denyAll` ela sequer carrega. Por isso a cadeia passa a ler **duas** listas configuráveis, com papéis distintos:

- **caminhos autenticados**, padrão `/api/**` — exigem token e deixam o perfil para o `@PreAuthorize`;
- **caminhos públicos adicionais**, padrão **vazio** — somam-se aos públicos fixos já existentes.

O `historico-service` acrescenta `/graphql` à primeira lista em qualquer profile. Somente os profiles `dev` e `demo` habilitam a interface e acrescentam `/graphiql` e seus estáticos à segunda; no profile padrão a interface está desabilitada **e** os caminhos não entram na lista pública, então caem no `denyAll`. As duas condições são independentes de propósito: desabilitar sem negar deixaria o caminho respondendo algo, e negar sem desabilitar deixaria a interface montada esperando um caminho aberto.

O agendamento não muda: ambos os padrões — `/api/**` autenticado e lista pública adicional vazia — reproduzem exatamente a cadeia atual. Continua existindo **uma única** `SecurityFilterChain`; as listas variam por configuração, não por bean novo. Um IT sobe o contexto em cada profile e comprova os dois lados — a ausência precisa ser testada, senão "desabilitado" é só intenção.

### D2. Autorização por resolver, com a matriz da documentação como fonte executável

Cada resolver declara `@PreAuthorize` com o perfil admitido, e o recorte de propriedade do paciente é decidido em um componente de autorização dedicado, não no corpo do resolver — mesma separação que ADR-004 impôs ao agendamento. `minhasConsultas` não recebe argumento de identidade: o `pacienteId` sai de `UsuarioAutenticado`. Em `consultasDoPaciente` e `consulta`, o perfil PACIENTE só passa quando o alvo coincide com o `pacienteId` do token.

Três coberturas sustentam a garantia, com papéis distintos. Um leitor da tabela de `docs/02-especificacao-funcional.md` §3 produz as 15 células; um IT gera um caso por célula contra o endpoint real com token real; e uma asserção estrutural exige que a leitura tenha encontrado **5 linhas, 3 perfis e 15 células** — sem ela, um parser que deixa de achar a tabela faria a suíte passar verificando nada, que foi a falha registrada no M03. Uma quarta cobertura varre os resolvers por reflexão e falha se algum método exposto não declarar decisão de autorização, porque a cadeia de filtros só exige autenticação e não distingue perfil.

A alternativa de escrever as 15 células no próprio teste foi descartada pelo mesmo motivo do agendamento: a tabela do documento e o teste divergiriam sem que nada falhasse.

### D3. `minhasConsultas` é exclusiva de PACIENTE

Decisão do Product Owner. MEDICO e ENFERMEIRO recebem acesso negado nessa operação e continuam lendo o histórico por `consultasDoPaciente`, `consultasDoMedico` e `consulta`, o que preserva o que o enunciado exige desses perfis. A matriz atual do documento marca a operação como permitida aos três; ela será corrigida no apply, e a correção da tabela é o que faz o IT gerado passar a exigir o novo comportamento.

O ganho é semântico: a operação significa "as minhas", e um perfil clínico não tem consultas próprias como paciente no modelo. Mantê-la aberta a todos criaria um segundo caminho de leitura com regra diferente da matriz para o mesmo dado.

### D4. Semântica temporal explícita, decidida no armazenamento e com `Clock` injetado

`TODAS` não acrescenta predicado; `FUTURAS` aplica `data_hora >= :agora`; `PASSADAS` aplica `data_hora < :agora`. O intervalo usa `data_hora >= :de` e `data_hora < :ate`, seguindo a mesma convenção semiaberta já adotada no agendamento para períodos. Período, intervalo e status entram por conjunção; a lista de status casa por pertencimento e, quando vazia ou ausente, não acrescenta predicado. A ordenação é `data_hora, id` — o segundo campo existe para tornar a ordem total, não por relevância.

`agora` vem do `Clock` já publicado no serviço, o mesmo que o M08 usa. Sem isso, o teste de fronteira exata do instante atual seria não determinístico e a suíte oscilaria.

Todos os predicados são montados na query enviada ao PostgreSQL. Filtrar em memória foi descartado por razão de correção, não só de desempenho: um recorte aplicado depois da paginação futura ou de um limite devolveria resultado errado, e a spec exige que o filtro seja do armazenamento.

### D5. A correção manual é uma transação única com bloqueio pessimista da linha

O serviço de correção carrega o snapshot com bloqueio de escrita, valida autorização e input, captura o estado anterior, aplica os campos permitidos, avança `atualizado_em` com o `Clock` e insere na trilha uma linha com identificador novo, `tipo_evento = CORRECAO_MANUAL`, `consulta_id` preservado, `ocorrido_em` igual ao instante da correção e payload JSONB com autor, justificativa e valores antes/depois. Tudo na mesma transação; falha em qualquer ponto reverte snapshot e auditoria juntos.

O bloqueio é pessimista porque a leitura-modificação-escrita é inevitável aqui: diferente do upsert do M08, a correção precisa do estado anterior para auditar. Sem bloqueio, duas correções concorrentes leriam o mesmo "antes" e uma sobrescreveria a outra com auditoria mentindo sobre o valor anterior — perda silenciosa dentro do próprio registro de auditoria. Lock otimista com `@Version` foi descartado por exigir coluna nova no snapshot e transformar concorrência em erro para o cliente, quando serializar as duas correções é comportamento melhor.

Forma do input, decidida pelo que o schema **não** declara. `consultaId` entra como seletor do registro alvo e não tem contraparte corrigível; não existe campo de autor, de `pacienteId` nem de `medicoId`. Isso importa para o desenho da prova: GraphQL valida o documento contra o schema antes de executar o resolver, então um `autorId` enviado pelo cliente não é "ignorado pela aplicação" — ele é recusado como requisição inválida e o resolver nunca roda. Descrever esses campos como ignorados sugeriria que existe código de defesa em tempo de execução onde a defesa é o próprio contrato.

A evidência correspondente é, portanto, tripla: introspecção do schema mostrando que o input não expõe autor nem identificadores corrigíveis; uma correção autenticada mostrando que a trilha registra o `medicoId` do token; e um documento com campo desconhecido recusado como requisição inválida, sem efeito persistido. Nenhuma das três sozinha prova o contrato — a primeira sem a segunda não diz de onde veio o autor, e a segunda sem a terceira não exclui um campo aceito e silenciosamente descartado.

Semântica de campo no input, fixada explicitamente para não virar improviso: **campo ausente** significa "não corrigir"; **valor nulo explícito** é aceito somente em `observacoes` e significa limpar o registro clínico; para os demais campos, nulo explícito é entrada inválida. Essa distinção exige que o input carregue presença, e não apenas valor. Justificativa em branco ou só com espaços é inválida, e uma correção que não altera nada é recusada como requisição inválida — gravar auditoria de não-mudança poluiria a trilha com ruído indistinguível de correção real.

O status corrigido precisa pertencer ao conjunto válido, mas a máquina de transições do agendamento **não** se aplica: corrigir um registro que ficou com status errado é justamente o caso de uso, e recusá-lo por transição inválida tornaria a mutation inútil onde ela mais importa. Isso é decisão consciente, registrada aqui porque contraria a intuição de reaproveitar a regra do M01.

A correção não escreve em `evento_processado` — a marca é do protocolo AMQP, e criá-la aqui faria um `eventId` inexistente parecer processado — e não publica evento, o que manteria o histórico como produtor e violaria a direção do fluxo definida em ADR-002.

### D6. `CORRECAO_MANUAL` é tipo local da trilha

O valor entra apenas na coluna `tipo_evento` do histórico, que já é `VARCHAR`. `TipoEvento` do `shared-contracts`, as routing keys e a topologia não mudam. Uma cobertura estrutural afirma que o conjunto de tipos normativos permanece com os cinco valores e que o tipo local não vazou para o contrato.

Convivência com a projeção: como a correção avança `atualizado_em` para o instante da correção, a regra `EXCLUDED.atualizado_em >= consulta_historico.atualizado_em` já promovida faz um evento antigo não desfazer a correção e um evento posterior legitimamente sobrescrevê-la. Isso é consequência da regra existente, não exceção — nenhum Requirement do M08 precisa ser modificado.

### D7. Índices criados por migration nova, a partir das consultas reais

`V2` cria `consulta_historico(paciente_id, data_hora)` e `consulta_historico(medico_id, data_hora)` — as duas dimensões pelas quais as queries expostas recortam, com o horário como segunda coluna porque todo filtro temporal e a ordenação usam `data_hora`. Índice por status **não** é criado nesta change: o status é filtro de baixa seletividade sobre quatro valores e sempre aparece combinado com paciente ou médico. A decisão está fechada aqui, não delegada ao apply. O apply mede e registra os planos reais para confirmar esta decisão; se a evidência a contradisser, o apply **para** e exige nova aprovação por `/opsx:update`, em vez de criar o índice e ajustar o design em silêncio. Design alterado durante a implementação, sem aprovação, é a forma mais barata de a spec deixar de descrever o sistema.

`V1` não é editada — migration aplicada é imutável, e reescrevê-la quebraria o checksum de qualquer ambiente que já a rodou.

### D8. Códigos de erro estáveis por dois caminhos distintos, sob a mesma política

Uma requisição GraphQL pode falhar em dois momentos, e um só mecanismo não alcança os dois. **Antes da execução**, o motor faz parsing e validação do documento contra o schema: campo desconhecido no input, valor de enum inexistente, argumento com tipo errado e documento sintaticamente inválido morrem aí, e nenhum data fetcher chega a rodar. **Durante a execução**, o resolver lança exceções de negócio: acesso negado, registro inexistente, entrada semanticamente inválida e falhas inesperadas.

O `DataFetcherExceptionResolver` cobre apenas o segundo caso — é isso que ele significa. Ele traduz acesso negado para `FORBIDDEN`, registro inexistente para `NOT_FOUND`, violação de regra de entrada para `BAD_REQUEST` e qualquer outra exceção para mensagem genérica, sem SQL, sem rastreamento de pilha e sem nome de classe. Sem ele, `AccessDeniedException` lançada no resolver sairia como erro interno — o defeito que a spec proíbe nominalmente.

Os erros pré-execução precisam de um mecanismo na fronteira: um `WebGraphQlInterceptor` (ou instrumentação equivalente) transforma a resposta antes de ela sair, normalizando os erros de `ValidationError` e de parsing para `extensions.code = BAD_REQUEST` e aplicando a mesma sanitização de mensagem. Sem isso, a classificação desses erros ficaria no padrão do motor e a spec passaria a depender de um detalhe da biblioteca.

Os dois caminhos compartilham **política**, não implementação: a mesma tabela de códigos e a mesma regra de sanitização, extraídas para um ponto único que ambos consultam. Descrevê-los como "um único tradutor de exceções" seria descrição falsa e levaria alguém a procurar no `DataFetcherExceptionResolver` um caso que ele nunca vê. A distinção também tem consequência testável: nos erros pré-execução, nenhum resolver executa e nada é persistido — é exatamente o que prova que a recusa aconteceu antes, e não depois com rollback.

A recusa por falta de token continua na fronteira HTTP, antes dos dois: quem não se autenticou não deve alcançar nem o parsing da operação.

## Risks / Trade-offs

- [Tornar configurável a lista de caminhos autenticados mexe num módulo que o agendamento usa] → manter o padrão idêntico à lista atual, para que o agendamento não mude, e cobrir por teste que o padrão continua sendo `/api/**` e que o `denyAll` segue valendo para caminho não listado.
- [A matriz do documento será editada nesta change, e o IT lê o documento] → a asserção estrutural de 5 linhas, 3 perfis e 15 células é o que impede a edição de quebrar o parser em silêncio; ela falha antes de a suíte passar vazia.
- [Bloqueio pessimista serializa correções do mesmo registro] → o escopo é uma linha e a operação é rara e manual; o custo é irrelevante perto de uma auditoria que mente sobre o valor anterior.
- [Correção manual e evento AMQP disputam o mesmo snapshot] → ambos passam pela mesma linha do PostgreSQL, um por bloqueio explícito e outro pelo lock do upsert; a regra de `occurredAt` decide o vencedor de forma determinística e há teste para as duas direções.
- [Sem índice por status, um filtro só por status varre mais linhas] → o volume do read model do projeto não justifica um índice especulativo; a decisão fica registrada com o plano medido, e o M10 mede novamente com dados de demonstração.
- [O tipo local `CORRECAO_MANUAL` pode ser confundido com evento normativo em análise futura] → a cobertura estrutural fixa os cinco tipos do contrato, e a documentação descreve o tipo local como pertencente apenas à trilha do histórico.
- [Ausência de paginação deixa uma consulta muito ampla devolver tudo] → o read model do projeto é pequeno e a paginação pertence a uma change própria; registrar aqui evita que a omissão pareça esquecimento.

## Migration Plan

1. Acrescentar ao `historico-service` as dependências de GraphQL e de `shared-security`; `V2` cria os dois índices e o esquema continua validado por `ddl-auto: validate`.
2. Implantar com a migration concluída antes de o endpoint receber tráfego. O consumidor AMQP do M08 continua ativo durante todo o processo e não é afetado.
3. Em rollback de aplicação, remover a versão nova e manter banco, filas e trilha intactos: os índices de `V2` são compatíveis com a versão anterior do serviço, que simplesmente não os usa. Nenhuma correção já confirmada é desfeita — a trilha é auditoria e não se apaga.
4. Após a aprovação do PR, promover a capability e arquivar a change **na própria feature branch**, commitando o archive antes do merge, para que código e spec entrem em `develop` no mesmo merge `--no-ff`.

## Open Questions

Nenhuma. A exclusividade de `minhasConsultas`, os campos corrigíveis, a imutabilidade dos identificadores, a semântica de ausente/nulo, a não aplicação da máquina de estados na correção e a admissão de `/graphql` estão decididas acima; a estratégia de índices fica registrada com critério de evidência em vez de ficar em aberto.
