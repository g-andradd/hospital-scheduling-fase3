# Tasks — Núcleo de domínio e casos de uso do agendamento

## 1. Build

- [x] 1.1 Adicionar `maven-dependency-plugin` ao POM pai com o goal `properties` na fase `initialize`, e verificar que `mvn initialize` expõe a propriedade `org.mockito:mockito-core:jar`
- [x] 1.2 Configurar o `argLine` do surefire como `@{argLine} -javaagent:${org.mockito:mockito-core:jar}` — compondo, nunca substituindo (D10) — e verificar que `mvn test` deixa de emitir `Mockito is currently self-attaching`
- [x] 1.3 Verificar que a cobertura continua sendo medida depois de 1.2: o relatório do JaCoCo do `agendamento-service` reporta classes analisadas e cobertura maior que zero. Se cair para zero, o `@{argLine}` foi sobrescrito
- [x] 1.4 Declarar `mockito-core` e `assertj-core` no `agendamento-service` em escopo `test`, e verificar que `mvn -q test-compile` resolve

## 2. Domínio — enums e value objects

- [x] 2.1 Criar `PerfilUsuario` com `MEDICO`, `ENFERMEIRO` e `PACIENTE`, e verificar por teste que os três valores existem
- [x] 2.2 Criar `StatusConsulta` com `podeTransicionarPara`, implementando a tabela de D2, e verificar com um teste parametrizado que cobre as 16 combinações de origem e destino
- [x] 2.3 Criar `Cpf` validando onze dígitos e dígitos verificadores, normalizando para apenas dígitos, e verificar pelos cenários "CPF inválido é recusado" e "CPF válido é aceito e normalizado"
- [x] 2.4 Criar `Email` validando formato, e verificar pelo cenário "E-mail inválido é recusado"
- [x] 2.5 Criar `Crm` validando unidade federativa seguida de número, e verificar pelo cenário "CRM inválido é recusado"
- [x] 2.6 Criar `PeriodoConsulta` com `OffsetDateTime` de início, duração em minutos e `sobrepoe`, com a convenção de intervalo `[inicio, fim)` de D4, e verificar pelo cenário "Períodos adjacentes não são conflito"

## 3. Domínio — entidades e exceções

- [x] 3.1 Criar as exceções de regra de negócio `AgendamentoNoPassadoException`, `ConflitoDeAgendaException`, `TransicaoDeStatusInvalidaException` e `MotivoDeCancelamentoObrigatorioException`, mais a família de recurso inexistente `RecursoNaoEncontradoException` (base) com `ConsultaNaoEncontradaException`, `PacienteNaoEncontradoException` e `MedicoNaoEncontradoException`, com mensagens em português, e verificar por teste que cada uma preserva a mensagem
- [x] 3.2 Criar `Usuario`, `Paciente` e `Medico` conforme o modelo de `docs/02-especificacao-funcional.md` §4, e verificar por teste que a construção com valores válidos funciona e com valores inválidos é recusada
- [x] 3.3 Criar `Consulta` com a constante `DURACAO_PADRAO_MINUTOS = 30` (D9) e os métodos de negócio `remarcarPara`, `confirmar`, `cancelar` e `registrarRealizacao`, cada um validando a transição via `StatusConsulta` (D5), e verificar pelos cenários do Requirement "Máquina de estados da consulta"
- [x] 3.4 Fazer `cancelar` exigir motivo não vazio, lançando `MotivoDeCancelamentoObrigatorioException` e não `IllegalArgumentException` (D8), e verificar pelo cenário "Cancelamento sem motivo é recusado", incluindo motivo só com espaços
- [x] 3.5 Criar `EventoDeConsulta` como evento de domínio mínimo — identificador da consulta, tipo da mudança e instante (D6) — e verificar por teste que o tipo corresponde à operação

## 4. Domínio — portas de saída

- [x] 4.1 Criar `ConsultaRepositoryPort` com as buscas de conflito já recortadas por período e por status ativo, conforme as assinaturas de D4, e verificar que a interface não importa nenhum tipo de framework
- [x] 4.2 Criar `UsuarioRepositoryPort` com as buscas de paciente e de médico por identificador, e verificar o mesmo isolamento
- [x] 4.3 Criar `EventPublisherPort` com `publicar(EventoDeConsulta)`, e verificar o mesmo isolamento

## 5. Aplicação — casos de uso

- [x] 5.1 Criar os `record`s de entrada e saída dos casos de uso em `application` (D11), e verificar que nenhum caso de uso devolve `Consulta` nua
- [x] 5.2 Implementar `AgendarConsultaUseCase` com `Clock` injetado, e verificar pelos oito cenários do Requirement "Registro de consulta"
- [x] 5.3 Implementar `AtualizarConsultaUseCase`, e verificar pelos seis cenários do Requirement "Alteração de consulta", incluindo "A própria consulta não conflita consigo mesma"
- [x] 5.4 Implementar `ConfirmarConsultaUseCase`, e verificar pelos três cenários do Requirement "Confirmação de consulta"
- [x] 5.5 Implementar `CancelarConsultaUseCase`, e verificar pelos quatro cenários do Requirement "Cancelamento de consulta"
- [x] 5.6 Implementar `BuscarConsultaPorIdUseCase` e `ListarConsultasUseCase`, e verificar pelos cinco cenários do Requirement "Consulta e listagem de consultas"
- [x] 5.7 Fazer cada caso de uso publicar pela `EventPublisherPort` somente após a mudança de estado bem-sucedida, e verificar pelos três cenários do Requirement "Publicação de evento a cada mudança de estado"
- [x] 5.8 Confirmar que cada classe `*UseCase` tem exatamente um método público, e verificar por inspeção — a regra ArchUnit que automatiza isso é do M11

## 6. Testes

- [x] 6.1 Criar os fakes em memória `ConsultaRepositoryFake`, `UsuarioRepositoryFake` e `EventPublisherFake` em `src/test` (D3), com filtragem real de período e status, e verificar que nenhum teste de caso de uso usa Mockito para as portas
- [x] 6.2 Criar um utilitário de teste com `Clock.fixed` e construtores de `Consulta`, `Paciente` e `Medico` válidos, e verificar que nenhum teste chama `now()` sem relógio explícito
- [x] 6.3 Verificar que os 38 `#### Scenario:` da spec delta têm teste correspondente, e registrar a tabela de rastreabilidade Scenario → teste no corpo do PR

## 7. Documentação

- [x] 7.1 Escrever `docs/adr/ADR-007-clean-architecture-so-no-core.md` — por que a Clean Architecture vale no `agendamento-service` e seria boilerplate em notificação e histórico — no formato Contexto / Decisão / Alternativas consideradas / Consequências / Status, e verificar que o arquivo existe e segue as cinco seções

## 8. Verificação

- [x] 8.1 `mvn -q clean verify` passa na raiz, sem teste ignorado
- [x] 8.2 `mvn test` não emite mais o aviso de auto-attach do Mockito
- [x] 8.3 O relatório do JaCoCo do `agendamento-service` reporta cobertura de linha ≥ 90% nos pacotes `domain` e `application`
- [x] 8.4 Nenhum import de `org.springframework`, `jakarta.persistence`, `jakarta.validation` ou `com.fasterxml` em `domain` e `application`, comprovado por busca no código-fonte
- [x] 8.5 Nenhuma ocorrência de `LocalDateTime.now()`, `OffsetDateTime.now()` ou `Instant.now()` sem `Clock` em `domain` e `application`, comprovado por busca no código-fonte
