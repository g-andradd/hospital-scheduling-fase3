# Tasks — Autenticação JWT e autorização por perfil

## 1. Build

- [x] 1.1 Adicionar `jjwt-api`, `jjwt-impl` e `jjwt-jackson` ao `shared-security`, com versão no POM pai (D6), e verificar que `mvn -q test-compile` resolve
- [x] 1.2 Adicionar ao `shared-security` as dependências de teste e ao `agendamento-service` a dependência de `shared-security` e de `spring-boot-starter-security`, e verificar que resolvem
- [x] 1.3 Configurar `JWT_SECRET` e o prazo de validade do token no `application.yml`, lendo do ambiente com o padrão do `.env.example`

## 2. shared-security

- [x] 2.1 Criar `JwtService` emitindo HS256 com as claims de `docs/01-arquitetura.md` §7, e verificar pelo cenário "Token carrega a identidade do usuário"
- [x] 2.2 Fazer o `JwtService` recusar token expirado, com assinatura de outro segredo, malformado e sem claim de perfil, e verificar por teste próprio do módulo, sem depender do agendamento (D3)
- [x] 2.3 Criar o filtro de autenticação que lê o token do cabeçalho, valida e popula o contexto com o perfil como autoridade, e verificar que ausência de cabeçalho não autentica ninguém
- [x] 2.4 Criar a auto-configuração da cadeia stateless, com CSRF desabilitado, liberando autenticação, saúde e especificação da API, e **`denyAll` como padrão** (D2), e verificar pelo cenário "Recursos públicos permanecem acessíveis"
- [x] 2.5 Criar `AuthenticationEntryPoint` e `AccessDeniedHandler` devolvendo `ProblemDetail` com `correlationId` e `timestamp`, com `type` distinto entre 401 e 403, e verificar pelos cenários do Requirement "Erros de autenticação e autorização em Problem Detail"
- [x] 2.6 Verificar que nenhuma resposta de 401 ou 403 expõe stack trace, nome de classe ou pacote

## 3. Autenticação

- [x] 3.1 Criar `SolicitanteAutenticado` em `domain` como record com usuário, perfil e identificador de paciente, sem tipo do Spring (D2), e verificar que continua sem import de framework
- [x] 3.2 Criar `VerificadorDeSenhaPort` em `domain` e o adaptador BCrypt em `infrastructure` (D7), e verificar que a senha nunca é devolvida nem registrada
- [x] 3.3 Acrescentar `buscarUsuarioPorEmail` à `UsuarioRepositoryPort` e implementar no fake e no adaptador, e verificar pela suíte de contrato
- [x] 3.4 Implementar `AutenticarUsuarioUseCase` em `application`, sem emitir token (D7), e verificar pelos cenários de autenticação bem-sucedida, senha incorreta, e-mail inexistente e usuário inativo
- [x] 3.5 Fazer a verificação de senha **sempre executar**, mesmo sem usuário encontrado (D4), e verificar pelo cenário "A recusa não vaza pelo tempo de resposta"
- [x] 3.6 Criar `POST /auth/login` devolvendo token, prazo e perfil, e verificar pelo cenário "Autenticação bem-sucedida"
- [x] 3.7 Verificar que a resposta e o log do login não contêm a senha nem o hash

## 4. Autorização

- [x] 4.1 Anotar cada endpoint de `ConsultaController` com `@PreAuthorize` conforme a matriz da §3
- [x] 4.2 Passar `SolicitanteAutenticado` aos casos de uso que expõem dados de consulta, tornando a omissão um erro de compilação (D2), e verificar que o contexto de segurança é lido no controller
- [x] 4.3 Implementar a regra de propriedade em buscar e confirmar, e verificar pelos cenários "Paciente não recupera consulta de terceiro" e "Paciente não confirma consulta de terceiro"
- [x] 4.4 Implementar o filtro forçado ao próprio identificador na listagem, e verificar pelos cenários "Filtro de paciente é forçado à própria identidade" e "Listagem sem filtro também é recortada"
- [x] 4.5 Verificar pelo cenário "Operação nova sem a regra de propriedade é recusada" que o padrão da cadeia é negar
- [x] 4.6 Criar o teste estrutural que varre os métodos de `ConsultaController` e exige `@PreAuthorize` em cada um (D2), e verificar que ele falha ao se acrescentar um método sem anotação

## 5. Matriz da autorização

- [x] 5.1 Criar o leitor da tabela da §3 de `docs/02-especificacao-funcional.md`, produzindo uma célula por endpoint e perfil (D1)
- [x] 5.2 Criar o teste que afirma que a leitura encontrou 7 linhas de endpoint, 3 perfis e 21 células, e verificar que ele falha se o formato da tabela mudar — sem ele o teste da matriz seria decorativo
- [x] 5.3 Criar o teste de integração parametrizado por célula, com token real de cada perfil, e verificar que todas as 21 células passam
- [x] 5.4 Verificar que acrescentar uma linha à tabela do documento produz três casos novos que falham até serem implementados
- [x] 5.5 Registrar no corpo do PR a tabela mapeando cada célula ao teste que a cobre

## 6. Seed de demonstração

- [x] 6.1 Criar a migration de seed em `db/demo`, com os quatro usuários da §5 e hash BCrypt, sem senha em claro (D5)
- [x] 6.2 Configurar `spring.flyway.locations` para incluir `db/demo` **apenas** no profile `demo`
- [x] 6.3 Criar teste de integração que sobe **com** o profile `demo` e verifica que os quatro usuários existem e autenticam
- [x] 6.4 Criar teste de integração que sobe **sem** o profile e verifica que nenhum usuário de demonstração existe e que as credenciais são recusadas

## 7. Entradas hostis

- [x] 7.1 Acrescentar ao `EntradasHostisIT` a superfície de autenticação (D8): sem cabeçalho, vazio, `Bearer` sem espaço, sem token, esquema errado, truncado, assinatura de outro segredo, expirado, sem perfil, sem sujeito, perfil inexistente, `pacienteId` malformado e texto aleatório
- [x] 7.2 Verificar que nenhum desses casos produz 5xx nem vaza detalhe interno
- [x] 7.3 Verificar que a tabela acrescentada pega o defeito, introduzindo temporariamente um caminho que estoure com token malformado

## 8. Documentação

- [x] 8.1 Escrever `docs/adr/ADR-004-matriz-de-autorizacao.md`, registrando a ambiguidade do enunciado e a resolução adotada, no formato Contexto / Decisão / Alternativas / Consequências / Status
- [x] 8.2 Escrever `docs/adr/ADR-005-jwt-stateless.md`, sobre JWT em vez de sessão, incluindo a escolha de biblioteca (D6)
- [x] 8.3 Atualizar o README com a seção de autenticação, removendo o aviso de endpoints abertos e documentando as credenciais de demonstração
- [x] 8.4 Registrar em `docs/01-arquitetura.md` §8 as entradas de 401 e 403 com seus `type` distintos
- [x] 8.5 Documentar no OpenAPI o esquema de segurança, para o Swagger UI permitir autenticar

## 9. Verificação

- [x] 9.1 `mvn -q clean verify` passa na raiz, sem teste ignorado
- [x] 9.2 As 21 células da matriz têm teste, conferido pela contagem do leitor da tabela
- [x] 9.3 Cobertura do `agendamento-service` e do `shared-security` ≥ 80%, mantendo `domain` e `application` ≥ 90%
- [x] 9.4 Nenhum import de `org.springframework`, `jakarta.persistence`, `jakarta.validation` ou `com.fasterxml` em `domain` e `application`, comprovado por busca
- [x] 9.5 Nenhuma senha em claro no código, nas migrations ou nos logs, comprovado por busca
- [x] 9.6 O `EntradasHostisIT` continua sem nenhuma resposta 5xx, agora com a superfície de autenticação
- [x] 9.7 Os 51 `#### Scenario:` das duas specs delta têm teste correspondente, e a tabela de rastreabilidade vai no corpo do PR
