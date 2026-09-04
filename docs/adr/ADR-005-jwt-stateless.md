# ADR-005 — Autenticação stateless por JWT

## Contexto

O sistema precisa autenticar três perfis e autorizar cada endpoint conforme a matriz da ADR-004.
A pergunta anterior a qualquer código é onde mora o estado da sessão.

Dois fatos do projeto restringem a resposta.

O primeiro é a **arquitetura de três serviços** (ADR-002). Hoje só o agendamento expõe REST, mas
o histórico expõe GraphQL na sequência do roadmap, e ele precisa saber quem está perguntando.
Sessão em memória obrigaria sticky session ou um store de sessão compartilhado — um componente
de infraestrutura a mais, com seu próprio modo de falha, para um problema que ainda não temos.

O segundo é o **modo de avaliação**. O sistema sobe com `docker compose up -d` e é exercitado
pelo Swagger UI e por chamadas diretas. Autenticação que dependa de cookie e de origem
configurada corretamente adiciona um passo entre o avaliador e o sistema funcionando.

Há também uma restrição de domínio que não é de infraestrutura: o `domain` e o `application` do
agendamento não importam framework (ADR-007). A identidade de quem chama precisa atravessar essas
camadas sem arrastar `Authentication`, `Principal` ou qualquer tipo do Spring Security.

## Decisão

**Token JWT assinado em HS256, sem estado no servidor.**

O token carrega sujeito, e-mail, perfil e — quando aplicável — o identificador de paciente ou de
médico, conforme `docs/01-arquitetura.md` §7. Validade de 8 horas. Segredo em `JWT_SECRET`, lido
do ambiente, com o `.env` fora do controle de versão.

**Biblioteca: `jjwt` 0.12.6** (`jjwt-api`, `jjwt-impl`, `jjwt-jackson`).

**A identidade cruza a fronteira como tipo do domínio.** O filtro traduz o token em
`UsuarioAutenticado` (infraestrutura) e o controller o converte em `SolicitanteAutenticado`
(domínio) — um record com usuário, perfil e identificador de paciente, sem nenhum import de
framework. Os casos de uso que expõem dados de consulta **recebem esse parâmetro
obrigatoriamente**: um caso de uso novo que esqueça a regra de propriedade não compila, em vez de
compilar e vazar.

**A cadeia de filtros nega por padrão, mas não literalmente.** `denyAll` em tudo faria o
`@PreAuthorize` nunca ser avaliado, porque o interceptador de método só roda depois que a cadeia
deixa a requisição passar. A divisão é por nível: a cadeia libera os caminhos públicos, exige
autenticação em `/api/**` e **nega todo o resto**; dentro da API, quem decide o perfil é a
anotação no método. Cada nível tem sua própria proteção contra esquecimento — caminho novo fora
da API cai no `denyAll`, método novo sem anotação é pego pelo teste estrutural que varre o
controller.

**A recusa não conta por que recusou.** 401 e 403 saem em `ProblemDetail`, com `type` distinto e
`correlationId`, e com detalhe fixo por categoria. Responder "token expirado" informa que o token
já foi válido; "usuário não encontrado" no login transforma o endpoint em oráculo de e-mails
cadastrados. Pela mesma razão, a verificação de senha **sempre executa**, mesmo quando o e-mail
não existe: sem isso, a diferença de tempo entre "não achei o usuário" e "achei e o hash não
bate" responde a pergunta que a mensagem se recusou a responder.

## Alternativas consideradas

**Sessão HTTP com cookie, o padrão do Spring Security.**
Descartada. É a opção mais simples para um único serviço e a errada para três. Exigiria store de
sessão compartilhado ou afinidade de sessão no balanceador, e o GraphQL do histórico precisaria
participar do mesmo domínio de cookie. Troca um problema de assinatura por um de infraestrutura.

**OAuth2 / OIDC com um provedor de identidade (Keycloak).**
Descartada. É o que se faria em produção — rotação de chave, refresh token e revogação vêm de
graça. Aqui adicionaria um contêiner, um realm para configurar e um passo de setup na avaliação,
para resolver problemas que este projeto não tem. O custo de migrar depois é baixo: o filtro é o
único ponto que valida token, e o resto do sistema só conhece `SolicitanteAutenticado`.

**RS256 em vez de HS256.**
Descartada por ora. A assinatura assimétrica é a escolha certa quando os serviços que *validam* o
token não devem poder *emiti-lo* — e é para onde isso vai quando o histórico validar tokens. Com
um único emissor e um segredo compartilhado por `docker compose`, HS256 entrega a mesma garantia
com um par de chaves a menos para gerenciar. A troca fica registrada como custo assumido, não
como esquecimento.

**`java-jwt` (Auth0) ou `nimbus-jose-jwt`.**
Descartadas sem grande convicção — as três bibliotecas resolvem o problema. `jjwt` foi escolhida
pela API de parsing que exige declarar a chave antes de ler qualquer claim, o que torna
desajeitado o erro clássico de ler o payload sem verificar a assinatura. `nimbus` viria de graça
com `spring-boot-starter-oauth2-resource-server`, mas isso arrastaria o modelo de OAuth2 inteiro
para um sistema que emite os próprios tokens.

**Guardar a regra de propriedade no controller, em vez de nos casos de uso.**
Descartada, e é a alternativa que parece mais barata. O controller é a única porta *hoje*. Quando
o histórico consumir os mesmos casos de uso por GraphQL, a regra escrita no controller REST não
vai junto — e a falha é silenciosa: tudo compila, tudo passa, e o paciente lê a consulta de
outro. Com o solicitante no parâmetro, o mesmo esquecimento vira erro de compilação.

## Consequências

**Positivas**
- Nenhum estado de sessão. Qualquer instância de qualquer serviço valida qualquer token com o
  segredo compartilhado.
- A identidade atravessa as camadas sem violar a Clean Architecture; `domain` e `application`
  continuam sem import de framework.
- Um caso de uso novo que exponha dados de consulta não compila sem receber o solicitante.
- O Swagger UI autentica com um `Bearer` colado no campo de autorização — sem cookie, sem CORS,
  sem passo extra na avaliação.
- 401 e 403 seguem o mesmo contrato de erro do resto da API, com `correlationId` para correlação
  com o log da mesma requisição.

**Negativas, aceitas**
- **Token não é revogável.** Um token roubado vale até expirar; não há logout do lado do
  servidor. Mitigado pela validade de 8 horas, e é o preço direto de não ter estado. Revogação
  exigiria uma denylist — ou seja, estado — e o problema volta.
- **O segredo é um ponto único.** Vazou o `JWT_SECRET`, qualquer token pode ser forjado. Ele fica
  no ambiente, nunca no repositório, e a aplicação **não sobe** com segredo ausente ou com menos
  de 32 bytes: falha visível na partida em vez de assinatura fraca em produção.
- Sem rotação de chave. Trocar o segredo invalida todos os tokens ativos de uma vez.
- O relógio importa. Servidores com relógios divergentes recusam tokens válidos ou aceitam
  expirados; o `Clock` é injetado, o que ao menos torna isso testável.

## Status

Aceita. Implementada em `add-autenticacao-jwt` (M04), no módulo `shared-security`
(`JwtService`, `JwtAuthenticationFilter`, `SegurancaAutoConfiguration`, `RespostaDeSeguranca`) e
no `agendamento-service` (`SolicitanteAutenticado`, `AutenticarUsuarioUseCase`, `@PreAuthorize`
no `ConsultaController`). A matriz que ela aplica está na ADR-004.
