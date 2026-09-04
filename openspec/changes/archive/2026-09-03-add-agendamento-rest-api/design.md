# Design — API REST do agendamento

## Context

Ver `proposal.md` — Why. Os requisitos estão em `specs/agendamento-de-consultas/spec.md`.

O que os dois changes anteriores deixaram pronto e condiciona este design:

- Seis casos de uso, cada um com um método `executar`, e seis decoradores `@Transactional` em `infrastructure.transacao` que os envolvem.
- Um mapa de exceções em `docs/01-arquitetura.md` §8 com dez entradas, oito das quais alcançáveis por este change — `AccessDenied` e `Authentication` só existem a partir do M04.
- `AlteracaoConcorrenteException`, criada no M02 para que a falha de lock otimista chegasse aqui como conceito de domínio.
- `application` e `domain` sem nenhum import de framework, e a intenção declarada de manter assim.

E uma cicatriz que molda a decisão sobre `PUT`: o M01 tinha um defeito em que remarcar sem informar observações apagava a anotação clínica. Foi corrigido tornando nulo equivalente a "não mexa".

## Goals / Non-Goals

**Goals**
- Cada entrada alcançável do mapa de erros com tratador e teste
- Contrato HTTP estável, independente da forma interna do agregado
- Paginação real, com teto, resolvida no banco
- Impossibilidade estrutural de o controller escapar da transação

**Non-Goals**
- Autorização, autenticação e regra de propriedade. M04.
- HATEOAS, versionamento por content negotiation, ETag. Nada disso é pedido.
- Otimização de serialização ou de latência.

## Decisions

### D1 — O mapa de erros vira dado, e o teste percorre o dado

Oito entradas alcançáveis, oito tratadores, oito testes. O risco não é escrever os oito: é o nono aparecer num change futuro e ninguém notar que ficou sem tratador — que foi exatamente o que aconteceu três vezes na história deste projeto.

A proteção tem duas partes.

**Um enum como fonte única.** `TipoDeErro` enumera as categorias, cada constante carregando o `type` (URI), o `title` e o `status`. O tratador não escreve `409` nem monta URI: pede a constante. Um erro novo é uma constante nova, e o compilador guia o resto.

**Um teste que varre o enum.** Um teste parametrizado por `TipoDeErro` verifica que cada constante tem URI, título e status coerentes, e uma asserção separada confere que o `@RestControllerAdvice` declara `@ExceptionHandler` para toda exceção de domínio existente — a lista de exceções é obtida por varredura do pacote `domain.exception`, não escrita à mão.

É essa segunda parte que impede a divergência futura. Uma exceção nova em `domain.exception` sem tratador correspondente **quebra o teste no momento em que é criada**, e não meses depois, num 500 em produção. Escrever a lista à mão no teste não teria essa propriedade: quem cria a exceção esquece de atualizar a lista pelo mesmo motivo que esqueceu o tratador.

*Alternativa descartada:* confiar em revisão de PR. Foi o que falhou nas três vezes anteriores.

*Limitação declarada:* a varredura cobre exceções de domínio. `MethodArgumentNotValid`, `IllegalArgumentException` e o fallback genérico são do Spring e do JDK, e continuam sendo testados um a um, nominalmente.

### D2 — O controller recebe o decorador, e o caso de uso nu não é injetável

O controller depende dos tipos `*Transacional`, não dos `*UseCase`. Isso já é explícito no construtor, mas explícito não é à prova.

A proteção real é remover a alternativa: os casos de uso **deixam de ser beans**. `CasosDeUsoConfig` para de expor `AgendarConsultaUseCase` e passa a expor apenas `AgendarConsultaUseCaseTransacional`, construindo o caso de uso nu internamente e entregando-o ao decorador. Não existindo bean do caso de uso nu, injetá-lo por engano não compila em tempo de contexto: a aplicação falha na subida com `NoSuchBeanDefinitionException`, não silenciosamente sem transação.

Esse é o ponto. Uma convenção documentada seria esquecida; um bean que não existe não é injetável.

O `CasosDeUsoConfigTest` do M02 é ajustado para refletir isso: passa a afirmar que os seis decoradores resolvem **e** que nenhum caso de uso nu está registrado como bean.

*Alternativa descartada:* anotar os casos de uso com `@Transactional` e eliminar os decoradores. Resolveria o risco de injeção errada de uma vez, mas encerraria a propriedade "`application` sem framework" que o M01 estabeleceu e o M02 preservou — e que o M11 pode transformar em regra ArchUnit.

*Alternativa descartada:* marcar os beans de caso de uso como `@Primary` nos decoradores. Não impede nada: o tipo do caso de uso nu continua injetável.

### D3 — Dois `type` distintos para o mesmo 409

`ConflitoDeAgenda` e `AlteracaoConcorrente` são ambos 409, e é correto que sejam: as duas são conflito com o estado atual do recurso. Mas o que o cliente deve fazer é oposto.

| | `type` | O que o cliente faz |
|---|---|---|
| Conflito de agenda | `.../erros/conflito-de-agenda` | Nada. Repetir dá o mesmo resultado. Escolher outro horário |
| Alteração concorrente | `.../erros/alteracao-concorrente` | Reler o recurso e repetir a operação. Provavelmente funciona |

O status HTTP não carrega essa diferença, e é para isso que o `type` da RFC 7807 existe: ele é o identificador estável da **categoria**, e é onde o cliente programa sua reação. Colapsar os dois no mesmo `type` obrigaria o cliente a interpretar o `detail`, que é prosa em português destinada a humanos.

O `detail` da alteração concorrente diz explicitamente para recarregar e tentar de novo, e é herdado da mensagem que a exceção já carrega desde o M02.

*Nota para o M13:* a collection precisa de um cenário para cada um dos dois, senão a distinção não aparece na avaliação.

### D4 — Teto de página de 100, com o pedido excessivo sendo aparado

`spring.data.web.pageable.max-page-size = 100`, com padrão de 20.

Um pedido de `size=100000` é **aparado para 100**, não recusado. Recusar seria defensável, mas transforma em erro do cliente algo que o servidor sabe resolver — e, na prática, produz um 400 que o consumidor não entende, porque ele pediu algo sintaticamente válido. Aparar responde a intenção ("me dê o máximo que você puder") com o máximo que o serviço aguenta.

O teto existe porque sem ele um único `size=100000` materializa cem mil linhas mapeadas para objetos de domínio e depois para DTOs, três vezes o volume em memória, e derruba o serviço com um parâmetro de query. Não é hipótese: é o modo de falha mais fácil de acionar numa API paginada sem teto.

100 é escolhido por ser confortavelmente acima de qualquer uso legítimo de tela — a agenda de um médico num mês não passa disso — e ordens de grandeza abaixo do que causa problema.

**Onde a paginação vive.** Descendo até a porta, como decidido:

```
FiltroDeConsultas  ganha  pagina (int) e tamanho (int)
ConsultaRepositoryPort.listar(FiltroDeConsultas) -> Pagina<Consulta>
```

`Pagina<T>` é um `record` de `domain`: conteúdo, número da página, tamanho e total. Não é `org.springframework.data.domain.Page` — esse tipo não entra em `domain` nem em `application`. O controller traduz `Pageable` em página e tamanho na entrada, e `Pagina<T>` em corpo de resposta na saída.

Isso altera a porta definida no M01 e implementada no M02, e é a única forma de o teto significar alguma coisa: paginar no controller sobre a lista completa deixaria o banco devolvendo a tabela inteira, que é precisamente o erro que a query de conflito do M02 existe para não cometer. O fake pagina em memória, o adaptador pagina em SQL, e a suíte de contrato compartilhada — que já existe desde o M02 — passa a cobrir os cenários de página nas duas implementações.

### D5 — DTOs de web separados dos records de aplicação

`ConsultaResponse` em `infrastructure.web`, distinto de `ConsultaResumo` em `application`.

Parece duplicação, e em parte é: hoje os campos coincidem. A diferença é o que cada um significa. `ConsultaResumo` é a saída de um caso de uso e pode mudar porque o caso de uso mudou; `ConsultaResponse` é contrato publicado, e mudá-lo quebra cliente. Fundi-los faria uma alteração interna virar quebra de contrato sem ninguém decidir isso.

O corpo expõe `registradoPorId`: a matriz da §3 não o restringe, e ele é informação legítima de auditoria para médico e enfermeiro. Se o M04 concluir que a visão do paciente não deve incluí-lo, a filtragem tem lugar próprio justamente por existir um DTO de web separado.

### D6 — `PUT` com semântica de preservação, documentada

Campo ausente no corpo preserva o valor atual, espelhando o `AtualizarConsultaCommand`.

É semântica de `PATCH` sob o verbo `PUT`, e a divergência é consciente. O motivo é concreto: exigir corpo completo faz uma remarcação que não reenvia `observacoes` apagar registro clínico — o defeito que o Codex encontrou no M01, reaberto pela porta do HTTP. Trocar o verbo para `PATCH` alinharia a semântica, mas contraria a matriz da §3, que o M04 usa para aplicar `@PreAuthorize` e o M13 para montar a collection.

A descrição do endpoint no OpenAPI declara a semântica em uma frase, e a spec tem um Scenario dedicado a ela.

### D7 — `correlationId` gerado aqui, sem esperar o M11

Todo `ProblemDetail` precisa de `correlationId`, e o requisito é deste change. O M11 é dono da propagação ponta a ponta — MDC, header AMQP, restauração no consumidor.

A divisão: aqui nasce um filtro que lê `X-Correlation-Id` ou gera um, guarda no contexto da requisição e o entrega ao advice. O M11 estende esse mesmo filtro para o MDC e para as bordas de mensageria. Adiantar só a parte que o `ProblemDetail` exige evita tanto um campo vazio agora quanto reescrever o filtro depois.

### D8 — Como `AlteracaoConcorrente` é exercitada pela API

Ela é a única das oito entradas que a API não consegue provocar por uma requisição só: exige que outra transação altere a mesma linha entre a leitura e a gravação, dentro do intervalo de uma requisição HTTP. Orquestrar isso por HTTP daria um teste caro e intermitente, que falharia por temporização e seria desativado na primeira vez que atrapalhasse.

A verificação é dividida entre dois níveis, e nenhum deles é "por construção":

**A produção da exceção** já é provada contra Postgres real pelo `TransacaoDeAlteracaoIT` do M02, que abre duas transações e demonstra que a segunda é recusada com `AlteracaoConcorrenteException`. Esse teste continua valendo e não é reescrito aqui.

**O mapeamento para HTTP** é provado por um teste de camada web em que o decorador transacional é substituído por um dublê que lança a exceção. A asserção é sobre a resposta: status 409, `type` de alteração concorrente — distinto do de conflito de agenda —, e `detail` orientando a recarregar.

A divisão é honesta porque as duas metades cobrem coisas diferentes: uma prova que o banco realmente produz a falha, a outra que a falha vira a resposta certa. É a mesma estratégia usada para as demais entradas do mapa; a diferença é que nas outras a produção é trivial de provocar, e aqui ela mora num teste de outro change.

*Alternativa descartada:* um teste de integração que dispara duas requisições HTTP concorrentes e espera que uma perca. Depende de a segunda cair exatamente na janela entre a leitura e o flush da primeira; sem um ponto de sincronização dentro do caso de uso — que existiria só para o teste — é intermitente por construção.

## Risks / Trade-offs

| Risco | Mitigação |
|---|---|
| Exceção nova em change futuro sem `@ExceptionHandler`, virando 500 silencioso — já aconteceu três vezes | Teste que varre `domain.exception` por reflexão e exige tratador para cada tipo encontrado (D1). Falha no momento em que a exceção é criada |
| Controller injetar o caso de uso nu e perder a transação, quebrando o outbox do M05 | Casos de uso deixam de ser beans (D2). Injeção errada falha na subida do contexto, não em silêncio |
| Cliente tratar alteração concorrente como definitiva e não repetir a operação | `type` distinto por categoria (D3), com o `detail` orientando a recarregar |
| Mudança na porta de listagem quebrar o fake ou o adaptador de forma assimétrica | A suíte de contrato do M02 já exercita as duas implementações; os cenários de página entram nela |
| Teto de página aparar silenciosamente e confundir quem pediu mais | O corpo da resposta devolve o `tamanho` efetivamente aplicado, então o cliente vê o que recebeu |
| `PUT` com semântica de preservação surpreender quem espera substituição | Declarado no OpenAPI, com Scenario próprio na spec e nota no `proposal.md` |
| Endpoints abertos irem para `develop` e alguém supor que a segurança existe | Declarado no `proposal.md`; o M04 é o change seguinte e não há release entre os dois |

## Migration Plan

Não se aplica: a API não existe hoje, e não há cliente a quebrar. A alteração da porta de listagem é interna e coberta pela suíte de contrato.

Reverter é reverter o merge.

## Open Questions

Nenhuma. As duas ambiguidades materiais — onde a paginação vive e a semântica do `PUT` — foram decididas com o Gabriel antes da criação desta change e estão registradas em D4 e D6.
