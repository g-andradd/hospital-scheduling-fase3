# API REST do agendamento

## Why

O M01 escreveu as regras e o M02 as tornou duráveis, mas nada disso é alcançável de fora do processo. Não há um endpoint sequer: o serviço sobe, conecta no banco, e fica inerte.

Este change abre a porta. É também onde o mapa de erros de `docs/01-arquitetura.md` §8 deixa de ser documentação e vira comportamento — e onde ele é cobrado. Três das suas entradas nasceram de furos descobertos tarde: `IllegalArgumentException` teria virado 500, `MotivoDeCancelamentoObrigatorio` estava classificada como erro de formato, e `AlteracaoConcorrente` nem existia. Cada uma que ficar sem tratador vira 500 silencioso, e o lugar onde isso aparece é a pasta de cenários de erro da collection do M13, na frente da banca.

Fazer a API antes da segurança é deliberado: o M04 aplica `@PreAuthorize` sobre endpoints que já funcionam e já têm testes, em vez de depurar roteamento e autorização ao mesmo tempo.

## What Changes

**API** (`infrastructure.web`)
- `ConsultaController` com os seis endpoints da matriz de `docs/02-especificacao-funcional.md` §3, exceto `/auth/login`, que é do M04
- DTOs de request e response em `record`, com Bean Validation e mensagens em português
- `GET /api/v1/consultas` paginado, com filtros opcionais `pacienteId`, `medicoId`, `status`, `de`, `ate`

**Erros** (`infrastructure.web`)
- `@RestControllerAdvice` global devolvendo `ProblemDetail` no formato exato do §8, com `correlationId` e `timestamp`
- Um tratador por entrada do mapa, e um teste por entrada
- `type` distinguindo conflito definitivo de conflito transitório

**Paginação** (`domain`, `application`, `infrastructure.persistence`)
- `FiltroDeConsultas` ganha página e tamanho; a porta passa a devolver um `Pagina<T>` próprio do domínio, sem tipo do Spring
- Fake e adaptador do M02 paginam de verdade, cada um no seu meio

**OpenAPI**
- springdoc, com `/swagger-ui.html` listando todos os endpoints

## Capabilities

### New Capabilities

Nenhuma.

### Modified Capabilities
- `agendamento-de-consultas`: ganha requisitos de exposição HTTP dos casos de uso, de forma dos erros e de paginação da listagem. O Requirement de listagem existente é **modificado** para incorporar paginação com teto de tamanho.

## Impact

- **Capability:** `agendamento-de-consultas` (modificada)
- **Requisitos fechados:** RF-05, RF-06, RF-07, RNF-03
- **Release alvo:** `0.1.0`
- **Branch:** `feature/m03-add-agendamento-rest-api`
- **Módulos tocados:** `agendamento-service` (`infrastructure.web`, `domain`, `application`, `infrastructure.persistence`, `src/test`) e o POM do módulo

## O que NÃO muda nesta change

- **Autorização.** Nenhum `@PreAuthorize`, nenhum JWT, nenhuma regra de propriedade. **Os seis endpoints ficam abertos**, e é assim que o M04 os encontra. As colunas de perfil da matriz da §3 são cumpridas lá; aqui só o roteamento existe.
- **`/auth/login`.** Não é criado. É o primeiro item do M04.
- **As entradas 401 e 403 do mapa de erros.** `AccessDenied` e `Authentication` continuam sem tratador, porque nada neste change as lança. As outras oito entradas são todas implementadas e testadas.
- **Regras de negócio.** Nenhuma regra nova, nenhuma alteração de comportamento do domínio. Os casos de uso são chamados como estão.
- **Mensageria.** O `EventPublisherLogAdapter` provisório do M02 permanece como está. Substituí-lo é task do M05.
- **`outbox_evento`.** Continua fora do schema. É o M05.
- **ArchUnit.** As restrições seguem verificadas por busca; a suíte é o M11.

## Divergências conscientes

- **A porta de repositório muda.** O M02 declarou que nenhuma assinatura de porta mudaria, e essa afirmação valia para aquele change. Aqui ela muda: paginar no controller sobre a lista completa faria o banco devolver a tabela inteira a cada requisição, e o teto de tamanho de página viraria decoração — exatamente o erro que a query de conflito do M02 existe para não cometer. Decidido com o Gabriel em 2026-09-03.
- **`PUT` com semântica de atualização parcial.** Campo ausente preserva o valor atual, como o `AtualizarConsultaCommand` do M01 já faz. Exigir o corpo completo reabriria pelo HTTP o defeito que o Codex encontrou no M01: uma remarcação que esquece de reenviar as observações apaga registro clínico. A divergência entre verbo e semântica é documentada no OpenAPI. Decidido com o Gabriel em 2026-09-03.
