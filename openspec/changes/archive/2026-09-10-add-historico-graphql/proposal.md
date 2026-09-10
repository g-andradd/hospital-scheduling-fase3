# M09 — API GraphQL do histórico de consultas

## Why

O M08 materializou o read model do histórico, mas ninguém consegue lê-lo: `historico-service` não expõe nenhuma porta de entrada. O enunciado cita nominalmente a consulta flexível por GraphQL — "listar todos os atendimentos de um paciente ou apenas as futuras" — e é o segundo item mais avaliado depois da segurança. Sem esta change, a projeção do M08 é trabalho sem consumidor e a release `0.2.0` não fecha.

## What Changes

- Expor `schema.graphqls` com o scalar `DateTime`, o enum `PeriodoFiltro` (`TODAS`, `FUTURAS`, `PASSADAS`), o enum de status compatível com o contrato de eventos, o input `FiltroConsulta` (período, lista de status, `de`, `ate`) e o tipo `ConsultaHistorico` projetado do snapshot.
- Entregar quatro queries — `consultasDoPaciente(pacienteId:, filtro:)`, `minhasConsultas(filtro:)`, `consultasDoMedico(medicoId:, filtro:)` e `consulta(id:)` — com semântica temporal fixada: `TODAS` sem recorte pelo relógio, `FUTURAS` com `dataHora >= agora`, `PASSADAS` com `dataHora < agora`, intervalo `[de, ate)` e combinação por AND, sempre com `Clock` injetado e filtragem no PostgreSQL.
- Entregar a mutation `corrigirRegistroHistorico(input:)`, exclusiva de MEDICO, que corrige o read model e grava, na mesma transação, uma linha `CORRECAO_MANUAL` em `consulta_evento` com autor, justificativa e valores antes/depois.
- **BREAKING (documentação normativa):** `minhasConsultas` passa a ser exclusiva de PACIENTE. A matriz atual de `docs/02-especificacao-funcional.md` §3 marca a operação como ✅ para os três perfis; MEDICO e ENFERMEIRO passam a receber `FORBIDDEN` e continuam lendo o histórico pelas demais queries. A matriz do documento será corrigida no apply.
- Aplicar a matriz GraphQL célula a célula — 5 operações × 3 perfis = 15 células — por teste de integração que lê a tabela do documento em tempo de execução, afirma estruturalmente 5 linhas / 3 perfis / 15 células e recusa resolver novo sem anotação de autorização.
- Admitir `/graphql` na cadeia de segurança compartilhada sem abrir caminhos não relacionados nem criar cadeias concorrentes, mantendo o `denyAll` como padrão e reaproveitando o JWT e o filtro de `shared-security`.
- Traduzir recusas e falhas para códigos GraphQL estáveis — `FORBIDDEN`, `NOT_FOUND`, `BAD_REQUEST` — sem 500 e sem vazar SQL, stack trace ou nome de classe; habilitar GraphiQL apenas nos profiles `dev` e `demo`.
- Criar migration nova, sem editar `V1`, com os índices que o M08 deliberadamente adiou, cada um justificado pelas queries reais desta change e comprovado por plano de execução em PostgreSQL real.

## Capabilities

### New Capabilities

Nenhuma. Toda a leitura e a correção pertencem à capability já promovida do histórico.

### Modified Capabilities

- `historico-de-consultas`: acrescenta os Requirements de consulta GraphQL filtrada, autorização por operação e perfil, correção manual auditada e resposta de erro estável. Os Requirements do M08 — projeção, idempotência transacional, snapshot monotônico, rejeição segura, desacoplamento e dimensões preservadas — permanecem inalterados; a correção manual convive com a regra de `occurredAt` já promovida em vez de substituí-la.

## Impact

**Release alvo:** `0.2.0`, no ciclo `0.2.0-SNAPSHOT`. **Fecha:** RF-11, RF-12, RF-13 e RF-14. Preserva RF-18, RF-19 e todas as garantias de `mensageria-de-eventos`.

Áreas afetadas: `historico-service` (schema GraphQL, resolvers, serviço de correção, consultas por Specification/SQL, configuração de segurança e de GraphiQL por profile, migration de índices), `shared-security` (admissão do caminho `/graphql` na cadeia compartilhada) e a documentação normativa — README, `docs/01-arquitetura.md` §6, RF-11 a RF-14 e a matriz GraphQL de `docs/02-especificacao-funcional.md` §3. O `historico_db` ganha índices, não tabelas. O contrato compartilhado não muda: `CORRECAO_MANUAL` é tipo local da trilha e não entra em `TipoEvento`, em routing key ou na topologia.

**Fora do escopo:** fixture de contrato cruzado, smoke test e gate agregado de cobertura do M10; propagação completa de `correlationId`, Actuator e suíte ArchUnit geral do M11; Dockerfiles, Compose final, Mailpit e seed final do M12; collection Postman final do M13; qualquer alteração do contrato RabbitMQ ou dos cinco tipos normativos; paginação GraphQL, subscriptions, federation e DataLoader sem necessidade demonstrada; chamada HTTP ao agendamento; edição de identificadores de consulta, paciente ou médico; exposição GraphQL da trilha completa de eventos, para a qual não existe exigência no enunciado nem nos documentos. O CHANGELOG não é tocado aqui — ele fecha com a release `0.2.0`.

**Processo de entrega:** implementação e archive pertencem ao mesmo PR. Diferentemente do M08, o archive **será executado e commitado na própria feature branch, depois da aprovação do PR e antes do merge**, de modo que código e spec promovida entrem em `develop` no mesmo merge `--no-ff`. Todas as operações Git permanecem exclusivas do Gabriel.
