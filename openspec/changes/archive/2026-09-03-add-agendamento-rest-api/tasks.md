# Tasks — API REST do agendamento

## 1. Build

- [x] 1.1 Adicionar `springdoc-openapi-starter-webmvc-ui` ao `agendamento-service`, com a versão declarada no POM pai, e verificar que `mvn -q test-compile` resolve
- [x] 1.2 Adicionar `spring-boot-starter-validation`, e verificar que as anotações de Bean Validation são aplicadas nos DTOs

## 2. Paginação no domínio e na aplicação

- [x] 2.1 Criar `Pagina<T>` em `domain` como record com conteúdo, número da página, tamanho e total, sem nenhum tipo do Spring, e verificar por teste que continua sem import de framework
- [x] 2.2 Acrescentar página e tamanho a `FiltroDeConsultas`, com o teto de 100 aplicado na construção (D4), e verificar pelo cenário "Tamanho de página acima do teto"
- [x] 2.3 Alterar `ConsultaRepositoryPort.listar` para devolver `Pagina<Consulta>`, e verificar que o `ListarConsultasUseCase` compila devolvendo `Pagina<ConsultaResumo>`
- [x] 2.4 Paginar no `ConsultaRepositoryFake`, e verificar pelos cenários de página da suíte de contrato
- [x] 2.5 Paginar no `ConsultaRepositoryAdapter` usando `findAll(Specification, Pageable)`, e verificar pelo cenário "Paginação resolvida na origem"
- [x] 2.6 Acrescentar à suíte de contrato os cenários de navegação entre páginas, página vazia e teto aplicado, e verificar que passam no fake e no adaptador

## 3. Erros

- [x] 3.1 Criar `TipoDeErro` como enum com `type`, `title` e status por constante (D1), incluindo constantes distintas para conflito de agenda e alteração concorrente (D3), e verificar por teste parametrizado que toda constante está coerente
- [x] 3.2 Criar o `@RestControllerAdvice` global montando `ProblemDetail` no formato exato do §8, com `correlationId` e `timestamp`, e verificar pelo cenário "Correlação presente em toda resposta de erro"
- [x] 3.3 Implementar tratador para cada uma das oito entradas alcançáveis do mapa, e verificar com **um teste por entrada**
- [x] 3.3.1 Exercitar `AlteracaoConcorrente` pela API com o decorador substituído por um dublê que lança a exceção (D8), asseverando status, `type` distinto e `detail` orientando a recarregar — a produção real da exceção continua provada pelo `TransacaoDeAlteracaoIT` do M02
- [x] 3.4 Fazer o tratador de `MethodArgumentNotValid` relacionar todos os campos inválidos com suas mensagens em português, e verificar pelo cenário de múltiplos campos inválidos
- [x] 3.5 Fazer o tratador genérico responder 500 sem stack trace nem nome de classe interna, e verificar pelo cenário correspondente
- [x] 3.6 Criar o teste que varre `domain.exception` por reflexão e exige `@ExceptionHandler` para cada exceção encontrada (D1), e verificar que ele falha ao se introduzir uma exceção sem tratador
- [x] 3.7 Criar o filtro de `correlationId` lendo `X-Correlation-Id` ou gerando um (D7), e verificar que o valor recebido no cabeçalho é o que aparece na resposta de erro

## 4. API

- [x] 4.1 Criar os DTOs de request e response em `infrastructure.web`, separados dos records de `application` (D5), com Bean Validation e mensagens em português
- [x] 4.2 Implementar `POST /api/v1/consultas` respondendo `201` com `Location`, e verificar pelo cenário "Registro bem-sucedido"
- [x] 4.3 Implementar `PUT /api/v1/consultas/{id}` com semântica de preservação (D6), e verificar pelo cenário "Campo ausente na alteração preserva o valor atual"
- [x] 4.4 Implementar `PATCH /api/v1/consultas/{id}/confirmar` e `PATCH /api/v1/consultas/{id}/cancelar`, e verificar pelos cenários correspondentes
- [x] 4.5 Implementar `GET /api/v1/consultas/{id}`, e verificar pelo cenário "Recuperação bem-sucedida"
- [x] 4.6 Implementar `GET /api/v1/consultas` paginado com filtros opcionais, e verificar pelos cenários de listagem do Requirement modificado
- [x] 4.7 Configurar `spring.data.web.pageable.max-page-size=100` e `default-page-size=20` (D4), e verificar que o corpo devolve o tamanho efetivamente aplicado

## 5. Transação

- [x] 5.1 Fazer `CasosDeUsoConfig` expor apenas os seis decoradores, deixando os casos de uso nus fora do contexto (D2), e verificar que o contexto sobe
- [x] 5.2 Injetar os decoradores no controller, e verificar pelo cenário "Operação executada dentro de uma transação"
- [x] 5.3 Ajustar o `CasosDeUsoConfigTest` do M02 para afirmar que os seis decoradores resolvem **e** que nenhum caso de uso nu está registrado como bean

## 6. OpenAPI

- [x] 6.1 Configurar o springdoc com título, descrição e versão da API, e verificar que `/v3/api-docs` responde com todos os endpoints
- [x] 6.2 Documentar no endpoint de alteração a semântica de preservação de campos ausentes (D6), e verificar que aparece na especificação
- [x] 6.3 Verificar que `/swagger-ui.html` lista os seis endpoints

## 7. Documentação

- [x] 7.1 Atualizar o README com a seção de uso da API, apontando para o Swagger UI e registrando que os endpoints ainda estão abertos até o M04
- [x] 7.2 Registrar em `docs/01-arquitetura.md` §8 os dois `type` distintos para os dois 409 (D3), com o que o cliente deve fazer em cada caso

## 8. Verificação

- [x] 8.1 `mvn -q clean verify` passa na raiz, sem teste ignorado
- [x] 8.2 Cada uma das oito entradas alcançáveis do mapa de erros tem tratador e teste próprio, conferido um a um
- [x] 8.3 Cobertura do `agendamento-service` ≥ 80%, mantendo `domain` e `application` ≥ 90%
- [x] 8.4 Nenhum import de `org.springframework`, `jakarta.persistence`, `jakarta.validation` ou `com.fasterxml` em `domain` e `application`, comprovado por busca no código-fonte
- [x] 8.5 Nenhum bean de caso de uso nu registrado no contexto, comprovado por teste
- [x] 8.6 Nenhum `@PreAuthorize`, `@Secured` ou configuração de segurança no código, comprovado por busca — a autorização é o M04
- [x] 8.7 Os 27 `#### Scenario:` da spec delta têm teste correspondente, e a tabela de rastreabilidade vai no corpo do PR
