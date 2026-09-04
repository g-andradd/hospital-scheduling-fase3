# Autenticação JWT e autorização por perfil

## Why

O M03 deixou seis endpoints abertos, e isso está declarado em três lugares: no `proposal.md` daquele change, no README com aviso destacado e na descrição do OpenAPI. Qualquer um que tenha o endereço do serviço remarca a consulta de qualquer paciente.

Este change fecha isso. É também o item que o enunciado da Fase 3 mais cobra: segurança com Spring Security e níveis de acesso é um dos três eixos avaliados, e a matriz de `docs/02-especificacao-funcional.md` §3 é a parte da entrega que a banca consegue verificar objetivamente, célula por célula.

Há uma segunda razão para ele vir agora, e não depois: o M05 vai publicar eventos com `correlationId` e identidade do usuário que originou a mudança. Sem autenticação, essa identidade não existe.

## What Changes

**`shared-security`**
- `JwtService` para emissão e validação de token HS256, com as claims de `docs/01-arquitetura.md` §7
- Filtro de autenticação que lê o token, valida e popula o contexto do Spring Security
- Auto-configuração da cadeia de filtros, stateless, reaproveitável pelos três serviços
- `AuthenticationEntryPoint` e `AccessDeniedHandler` devolvendo `ProblemDetail` no formato do §8

**Autenticação** (`agendamento-service`)
- `POST /auth/login` recebendo e-mail e senha, devolvendo `accessToken`, `expiresIn` e `perfil`
- `AutenticarUsuarioUseCase`, adiado do M01, com porta de verificação de senha
- Adaptador BCrypt da porta

**Autorização** (`agendamento-service`)
- `@PreAuthorize` em todos os endpoints, conforme a matriz da §3
- Regra de propriedade: paciente só alcança os próprios recursos
- Filtro forçado ao próprio identificador na listagem, para o perfil paciente

**Seed** (`agendamento-service`)
- Usuários de demonstração da §5 de `docs/02-especificacao-funcional.md`, restritos ao profile `demo`

**Testes**
- Um teste de integração por célula da matriz, com garantia estrutural de que nenhuma célula fica sem teste
- Superfície de ataque de autenticação acrescentada ao `EntradasHostisIT`

**Documentação**
- ADR-004, sobre a resolução da ambiguidade do enunciado
- ADR-005, sobre JWT stateless em vez de sessão

## Capabilities

### New Capabilities
- `autenticacao-e-autorizacao`: quem é o usuário, o que cada perfil pode fazer, e o que acontece quando a credencial falta, é inválida ou não basta

### Modified Capabilities
- `agendamento-de-consultas`: o Requirement de listagem passa a descrever o recorte por perfil. Para o paciente, a listagem deixa de devolver todas as consultas registradas e passa a devolver apenas as próprias — comportamento observável diferente do que a spec afirma hoje, e declará-lo aqui evita que ela passe a mentir

## Impact

- **Capabilities:** `autenticacao-e-autorizacao` (nova), `agendamento-de-consultas` (modificada)
- **Requisitos fechados:** RF-01, RF-02, RF-03, RF-04, RNF-01, RNF-02
- **Release alvo:** `0.1.0` — **este change fecha a release**
- **Branch:** `feature/m04-add-autenticacao-jwt`
- **Módulos tocados:** `shared-security` (todo), `agendamento-service` (`domain`, `application`, `infrastructure`, migrations, `src/test`)

## O que NÃO muda nesta change

- **As regras de agendamento.** Nenhuma regra de negócio do M01 é alterada. A autorização decide *quem pode pedir*; o que acontece depois continua igual.
- **`notificacao-service` e `historico-service`.** Eles ganham a dependência de `shared-security` e a validação de token funcionando, mas **não têm endpoint algum** — não há o que proteger ainda. O primeiro é o M07, o segundo é o M09.
- **Autorização no GraphQL.** A tabela do `historico-service` na §3 é do M09.
- **Cadastro de usuários.** Não há endpoint para criar usuário, paciente ou médico. O seed do profile `demo` é a única forma de existirem, e é deliberado: o enunciado não pede cadastro.
- **Refresh token, revogação, expiração configurável por perfil.** Nada disso é pedido. O token expira e o cliente faz login de novo.
- **Mensageria.** O `EventPublisherLogAdapter` provisório do M02 permanece. É o M05.
- **A dívida do `EntradasHostisIT`** registrada na seção do M10. Aqui a tabela ganha apenas a superfície nova que este change introduz — autenticação —, não a varredura sistemática que é trabalho daquele change.
