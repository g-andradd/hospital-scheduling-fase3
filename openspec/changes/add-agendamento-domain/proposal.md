# Núcleo de domínio e casos de uso do agendamento

## Why

O monorepo compila e a infraestrutura sobe, mas nenhuma regra de negócio existe. `agendamento-service` tem os diretórios `domain/` e `application/` vazios.

Este change escreve o núcleo do sistema: as regras que decidem se uma consulta pode ser marcada, remarcada, confirmada ou cancelada. É o único lugar do projeto onde essas decisões vivem, e por isso é o único que precisa ser testável sem banco, sem broker e sem Spring — um teste que precisa de container para verificar "consulta no passado é recusada" está verificando a coisa errada.

Fazer o domínio primeiro, e sozinho, também fixa o contrato que os próximos três changes implementam: as portas de saída definidas aqui são exatamente o que o M02 (persistência), o M03 (REST) e o M05 (mensageria) vão satisfazer.

## What Changes

**Domínio** (`br.com.fiap.hospital.agendamento.domain`)
- Entidades `Consulta`, `Usuario`, `Paciente`, `Medico`
- Value objects `PeriodoConsulta`, `Cpf`, `Email`, `Crm`, com validação no construtor
- Enum `PerfilUsuario` (`MEDICO`, `ENFERMEIRO`, `PACIENTE`)
- Enum `StatusConsulta` com a máquina de estados em `podeTransicionarPara()`
- Exceções de negócio `AgendamentoNoPassadoException`, `ConflitoDeAgendaException`, `ConsultaNaoEncontradaException`, `TransicaoDeStatusInvalidaException` e `MotivoDeCancelamentoObrigatorioException` — recusas de formato usam `IllegalArgumentException`, conforme D8
- Portas de saída `ConsultaRepositoryPort`, `UsuarioRepositoryPort`, `EventPublisherPort`

**Casos de uso** (`br.com.fiap.hospital.agendamento.application`) — seis, um por arquivo, cada um com um único método público:
`AgendarConsultaUseCase`, `AtualizarConsultaUseCase`, `CancelarConsultaUseCase`, `ConfirmarConsultaUseCase`, `BuscarConsultaPorIdUseCase`, `ListarConsultasUseCase`

**Testes** — fakes em memória das três portas em `src/test`, sem Mockito para portas; um cenário por regra de negócio.

**Build** — Mockito configurado como java agent no surefire, compondo com `@{argLine}` para não anular o agente do JaCoCo.

## Capabilities

### New Capabilities
- `agendamento-de-consultas`: as regras que governam o ciclo de vida de uma consulta — quando pode ser criada, alterada, confirmada ou cancelada, e o que torna cada uma dessas operações inválida

### Modified Capabilities

Nenhuma.

## Impact

- **Capability:** `agendamento-de-consultas` (nova)
- **Requisitos fechados:** RF-05, RF-06, RF-07, RF-08, RF-09, RF-10
- **Release alvo:** `0.1.0`
- **Branch:** `feature/m01-add-agendamento-domain`
- **Módulos tocados:** `agendamento-service` (`domain`, `application`, `src/test`) e o POM pai (configuração do surefire)

## O que NÃO muda nesta change

- **Persistência.** Nenhuma entidade JPA, migration Flyway ou adaptador de repositório. As portas ficam sem implementação de produção — só os fakes de teste. É o M02.
- **HTTP.** Nenhum controller, DTO de request/response, Bean Validation ou `ProblemDetail`. É o M03.
- **Autenticação e autorização.** Nenhum JWT, nenhum `@PreAuthorize`, nenhuma verificação de senha. `AutenticarUsuarioUseCase` **não** entra aqui, apesar de `docs/01-arquitetura.md` §4 listá-lo entre os casos de uso do serviço: ele fecha RF-01, que pertence à capability `autenticacao-e-autorizacao`, e escrevê-lo agora exigiria inventar uma porta de verificação de senha que nenhum documento especifica. Vai para o M04, junto com `POST /auth/login`. Divergência consciente com o "sete casos de uso" de `docs/04-roadmap.md`, decidida com o Gabriel em 2026-09-02.
- **Mensageria.** Nenhum RabbitMQ, nenhuma tabela de outbox, nenhum adaptador de publicação. A `EventPublisherPort` é definida e **é chamada** pelos casos de uso a cada mudança de estado — o que satisfaz a regra 6 de `docs/01-arquitetura.md` §4 sem antecipar infraestrutura. Quem a implementa é o M05.
- **ArchUnit.** As restrições de import são respeitadas por construção nesta change, mas a suíte que as verifica automaticamente é o M11.
