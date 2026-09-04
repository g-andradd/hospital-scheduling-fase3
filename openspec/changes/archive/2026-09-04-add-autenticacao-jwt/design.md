# Design — Autenticação JWT e autorização por perfil

## Context

Ver `proposal.md` — Why. Os requisitos estão nos dois deltas em `specs/`.

O que os changes anteriores deixaram e condiciona este design:

- Seis endpoints abertos, com `@PreAuthorize` ausente por decisão declarada do M03.
- Seis decoradores transacionais, e os casos de uso nus **fora do contexto** — injetar o tipo errado falha na subida.
- `TratadorGlobalDeErros` herdando de `ResponseEntityExceptionHandler`, com `TipoDeErro` como fonte única do mapa e uma varredura por reflexão que exige tratador para toda exceção de domínio.
- `EntradasHostisIT`, com 291 casos, que já pegou cinco defeitos da mesma família.
- `AutenticarUsuarioUseCase`, adiado do M01 justamente para cá.

E uma lição que atravessou cinco rodadas de revisão: **proteção que depende de alguém lembrar não protege**. Os pontos 1, 2 e 5 abaixo são todos aplicações disso.

## Goals / Non-Goals

**Goals**
- Cada célula da matriz da §3 com um teste, sem depender de conferência manual
- Regra de propriedade impossível de esquecer, não apenas documentada
- Recusa de credencial que não distingue e-mail inexistente de senha errada, por nenhum canal
- Dados de demonstração comprovadamente ausentes fora do ambiente de demonstração

**Non-Goals**
- Refresh token, revogação, lista de bloqueio. Não são pedidos.
- Rate limiting e proteção contra força bruta. Fora do escopo do enunciado.
- Autorização do GraphQL. É o M09.
- Rotação de segredo, chaves assimétricas. O `§7` fixa HS256 com segredo compartilhado.

## Decisions

### D1 — A matriz do documento é a fonte dos testes, lida em tempo de execução

A matriz da §3 tem 7 linhas de endpoint por 3 perfis. Escrever 21 testes à mão e conferir na revisão é o método que falhou cinco vezes neste projeto.

O teste **lê a tabela markdown de `docs/02-especificacao-funcional.md` §3** e produz um caso por célula. Cada linha vira endpoint e método; cada coluna, um perfil; o conteúdo da célula diz o esperado — permitido, `403`, ou permitido com recorte.

A consequência é a que importa: **acrescentar uma linha à tabela do documento faz aparecer três casos de teste novos, que falham até serem implementados.** Ninguém precisa lembrar de nada.

*O risco óbvio é o parser silenciosamente parar de achar a tabela e o teste passar sem verificar nada* — o mesmo defeito que a primeira versão da varredura do M03 tinha. A mitigação é a mesma: um teste separado afirma que a leitura encontrou exatamente 7 linhas de endpoint, 3 perfis e 21 células, e falha se o formato mudar. Sem essa segunda asserção, a primeira é decorativa.

As 21 células viram 19 Scenarios na spec porque as três da linha de autenticação são idênticas — pública para todos — e um Scenario as cobre.

*Alternativa descartada:* uma tabela de casos escrita no próprio teste. Mais simples e mais robusta a mudanças de formatação, mas volta a exigir que alguém sincronize duas fontes. O documento é a fonte que a banca lê; o teste tem de derivar dele.

### D2 — A regra de propriedade entra pela assinatura, não pela anotação

`@PreAuthorize` resolve o perfil, não a propriedade: `hasRole('PACIENTE')` não sabe se *esta* consulta é *deste* paciente.

A regra vive nos casos de uso que expõem dados de consulta, e a garantia de que não pode ser esquecida é o **tipo**:

```
BuscarConsultaPorIdUseCase.executar(UUID id, SolicitanteAutenticado solicitante)
ConfirmarConsultaUseCase.executar(UUID id, SolicitanteAutenticado solicitante)
ListarConsultasUseCase.executar(ListarConsultasQuery query, SolicitanteAutenticado solicitante)
```

`SolicitanteAutenticado` é um `record` de `domain` — identificador do usuário, perfil e, quando houver, identificador de paciente. Sem tipo do Spring: o contexto de segurança é lido no controller, que o constrói.

O ponto é que **um caso de uso novo que exponha consulta não compila sem receber o solicitante**. Não é convenção, é a assinatura. Comparado a um `@PreAuthorize` esquecido, que passa aberto e silencioso, a diferença é entre erro de compilação e vazamento em produção.

Duas camadas complementares, porque nenhuma sozinha basta:

**Negar por padrão na cadeia de filtros.** `anyRequest().denyAll()` depois das exceções explícitas, em vez de `authenticated()`. Endpoint novo que ninguém liberou fica inacessível — falha visível, não brecha.

**Um teste estrutural** varre os métodos de `ConsultaController` e exige `@PreAuthorize` em cada um, no mesmo espírito da varredura de `domain.exception`. Método novo sem anotação quebra o build.

*Alternativa descartada:* checar propriedade no controller. Foi o que o roadmap chamou de "o furo mais comum", e com razão: o controller é o lugar mais fácil de esquecer e o único que não é reaproveitado quando o caso de uso é chamado de outro caminho — por exemplo, do GraphQL no M09.

### D3 — `shared-security` completo, mas ligado só onde há o que proteger

O módulo entrega: `JwtService` (emissão e validação HS256), o filtro de autenticação, a auto-configuração da cadeia stateless, e os dois tratadores de `401` e `403` em `ProblemDetail`.

**Só o `agendamento-service` o consome agora.** `notificacao-service` e `historico-service` não têm um endpoint sequer — ligá-los produziria configuração de segurança protegendo nada, testes que verificam nada, e decisões tomadas sem o consumidor na frente.

O que os torna consumíveis depois não é código extra, é a forma: o filtro lê as claims do `§7` e popula o contexto do Spring Security com o perfil como autoridade. Qualquer serviço que adicione a dependência ganha autenticação sem escrever nada; o que cada um decide é o que autorizar. O M09 vai precisar de um resolver de propriedade para GraphQL — **isso é do M09**, e construí-lo agora seria adivinhar a forma da API dele.

A validação do módulo não depende do `agendamento-service`: `shared-security` tem testes próprios de emissão, expiração, assinatura adulterada e claim ausente.

*Alternativa descartada:* ligar os três agora "para não esquecer depois". O roadmap já registra M07 e M09 como donos; e configuração de segurança sem endpoint é exatamente o consumidor imaginário que este ponto manda evitar.

### D4 — A recusa é indistinguível, inclusive no tempo

Mesma exceção, mesmo `type`, mesmo `title`, mesmo `detail`, mesmo status, para e-mail inexistente e para senha errada. Isso é o fácil.

O canal que costuma vazar é o **tempo**. Quando o e-mail não existe, a implementação ingênua não chama o BCrypt e responde em microssegundos; quando existe, gasta as dezenas de milissegundos da verificação. A diferença é medível de fora e enumera usuários com precisão.

A verificação de senha **sempre executa**, mesmo sem usuário encontrado: nesse caso ela roda contra um hash constante, descartando o resultado. As duas rotas fazem o mesmo trabalho.

Os outros canais, fechados explicitamente:

| Canal | Como fecha |
|---|---|
| Status | `401` nos dois casos |
| `type` e `title` | mesma categoria de erro |
| `detail` | texto único, sem citar e-mail nem campo |
| Cabeçalhos | nenhuma diferença |
| Log | registra a tentativa sem distinguir a causa no nível visível ao cliente |
| Tempo | verificação de senha sempre executada |

A spec tem um Scenario para o tempo. Ele é comparativo e estatístico, não uma asserção de latência absoluta: compara a distribuição das duas rotas, o que é estável mesmo numa máquina irregular.

*Limitação declarada:* isso não protege contra enumeração por outros caminhos, nem substitui rate limiting. Não há endpoint de cadastro nem de recuperação de senha neste sistema, então a superfície é só esta.

### D5 — O seed é uma migration que só existe no caminho de demonstração

`spring.flyway.locations` inclui `classpath:db/demo` **apenas** no profile `demo`. Sem o profile, o Flyway não enxerga o diretório, e as linhas não são inseridas por caminho nenhum — nem por `data.sql`, nem por `CommandLineRunner`, nem por `@PostConstruct` com `if`.

A diferença em relação a um `if (profileAtivo)` é que aqui **não há código de seed carregado** fora da demonstração. Um `if` errado insere; um diretório não listado não tem como inserir.

A verificação é por execução, não por convenção:

- Um teste de integração sobe **com** o profile `demo` e afirma que os quatro usuários existem e autenticam.
- Outro sobe **sem** o profile, contra o mesmo schema, e afirma que a tabela de usuários está vazia e que as credenciais de demonstração são recusadas.

O segundo é o que importa. Ele é a diferença entre "acreditamos que o seed não vai para produção" e "provamos que não vai".

As senhas no seed são hash BCrypt gerado previamente, não texto. O hash de `Senha@123` fica na migration; a senha em claro aparece só em `docs/02-especificacao-funcional.md` §5, que é documentação para a banca testar.

### D6 — `jjwt` para emissão e validação

O `§7` deixa a escolha aberta entre `jjwt` e `spring-security-oauth2-jose`. Escolho `jjwt`.

O segundo é a opção certa quando há OAuth2, JWKS, chaves assimétricas ou um servidor de autorização — nada disso existe aqui. Para HS256 com segredo compartilhado, ele traz uma cadeia de dependências e uma camada de abstração cujo benefício não é exercido. `jjwt` faz exatamente isto, com API pequena o bastante para caber numa classe e ser lida pela banca.

*Consequência aceita:* trocar para chaves assimétricas depois seria mudar de biblioteca. Isso está fora do escopo do projeto, e a troca ficaria contida no `JwtService`.

### D7 — Autenticação sem framework em `application`

`AutenticarUsuarioUseCase` fica em `application`, como os demais, e continua sem import de framework. Ele recebe e-mail e senha, carrega o usuário pela `UsuarioRepositoryPort` e verifica a senha por uma porta nova, `VerificadorDeSenhaPort` — cujo único adaptador é o `BCryptPasswordEncoder`, em `infrastructure`.

O caso de uso **não emite o token**: ele autentica e devolve a identidade. A emissão é do `JwtService`, no `shared-security`, chamado pelo controller. JWT é detalhe de transporte, e `application` não precisa saber que ele existe.

A `UsuarioRepositoryPort` ganha `buscarUsuarioPorEmail`. É a mesma porta do M01; a busca por e-mail não existia porque nada precisava dela.

### D8 — A superfície de autenticação entra no `EntradasHostisIT`

Autenticação acrescenta uma classe inteira de entrada hostil, e a lição das cinco rodadas é que a tabela vale o que cobre. Entram, aplicados a todos os endpoints protegidos: sem cabeçalho, cabeçalho vazio, `Bearer` sem espaço, `Bearer` sem token, esquema errado, token truncado, token com assinatura de outro segredo, token expirado, token sem `perfil`, sem `sub`, com perfil inexistente, com `pacienteId` malformado, e um token que é apenas texto aleatório.

Todas seguem a mesma regra do teste: **nenhuma resposta 5xx**, nenhum vazamento de detalhe interno.

Isto é a superfície que **este** change introduz. A varredura sistemática de todos os tipos de campo em todos os endpoints é a dívida já registrada na seção do M10, e não é reaberta aqui.

## Risks / Trade-offs

| Risco | Mitigação |
|---|---|
| Célula da matriz sem teste, por acréscimo futuro à tabela | Casos derivados da própria tabela do documento (D1), com um segundo teste afirmando que a leitura encontrou 7 linhas, 3 perfis e 21 células — sem ele, o primeiro seria decorativo |
| Endpoint novo exposto sem `@PreAuthorize`, aberto em silêncio | `denyAll` como padrão da cadeia, mais varredura estrutural dos métodos do controller (D2) |
| Caso de uso novo que exponha consulta sem checar propriedade | O solicitante é parâmetro obrigatório: não compila sem ele (D2) |
| Enumeração de usuários pelo tempo de resposta | Verificação de senha sempre executada, inclusive sem usuário (D4), com Scenario comparativo |
| Seed vazando para ambiente que não é de demonstração | Migration em diretório que o Flyway só enxerga no profile `demo`, com teste que sobe **sem** o profile e prova a ausência (D5) |
| Token malformado ou claim faltando chegando ao tratador genérico e virando 500 | Superfície acrescentada ao `EntradasHostisIT` (D8), com a mesma regra de nenhuma resposta 5xx |
| `shared-security` desenhado para consumidores que ainda não existem | Só o agendamento o consome agora; o que o torna reutilizável é a forma, não código extra (D3) |
| Segredo JWT fraco ou versionado | Vem de `JWT_SECRET`, já no `.env.example` desde o M00, com o comando de geração documentado |

## Migration Plan

A migration de schema é aditiva e não altera tabela existente. O seed é migration separada, em diretório próprio.

O impacto real é no contrato HTTP: **todos os endpoints passam a exigir token**, e um cliente que funcionava no M03 para de funcionar. Como não há cliente em produção — a release `0.1.0` é fechada por este change — não há transição a planejar. A collection do Postman, no M13, já nasce com o login em primeiro lugar.

Reverter é reverter o merge; a migration de schema pode ficar, por ser aditiva.

## Open Questions

Nenhuma. As duas ambiguidades materiais — se a listagem recortada para paciente exige `MODIFIED` em `agendamento-de-consultas`, e se o paciente que informa identificador de terceiro recebe filtro forçado ou `403` — foram decididas com o Gabriel antes da criação desta change e estão registradas no `proposal.md` e no delta de `agendamento-de-consultas`.
