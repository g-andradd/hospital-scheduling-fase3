## Why

O RNF-07 exige que o ambiente completo suba com um comando, e ele ainda não fecha:
- o `docker-compose.yml` sobe só PostgreSQL e RabbitMQ;
- os três serviços rodam apenas como processos locais ou dentro do smoke efêmero do M10;
- a banca não tem um caminho único para ver login, consulta, e-mail, histórico e lembrete D-1.

O M12 entrega uma demonstração completa e simples, executável do zero, antes da collection Postman (M13) e da release (M14).

Release alvo: **1.0.0**. Change do roadmap: **M12 · `add-docker-compose-demo`**. Fecha **RNF-07**.

## What Changes

A implementação segue duas fases, ambas deste change:
- **P0** entrega imagens e Compose funcional.
- **P1** acrescenta Mailpit, criação da consulta de demonstração, Makefile, documentação e a validação do zero.

O M12 só está concluído com P0 e P1.

**P0 — imagens e Compose funcional:**

- **Imagem Docker multi-stage por serviço** (`agendamento-service`, `notificacao-service`, `historico-service`):
  - build Maven com cache de dependências;
  - runtime `eclipse-temurin:21-jre-alpine` com usuário não-root;
  - `HEALTHCHECK` em `/actuator/health`.
- **Compose final** no mesmo `docker-compose.yml`:
  - `postgres` e `rabbitmq` preservados como estão (nomes de container, volumes, `init.sql` e healthchecks);
  - os três serviços acrescentados, com `depends_on` por `condition: service_healthy`;
  - variáveis explícitas por serviço, cada um no próprio database;
  - `SPRING_PROFILES_ACTIVE=demo,docker` e o segredo JWT obrigatório vindo do `.env`.
- **Verificação estrutural** do ambiente no `quality-gates`, só com JDK, sobre Dockerfiles, Compose e `.dockerignore`, com negativos sintéticos.

**P1 — demonstração completa:**

- **Mailpit** no Compose. A notificação usa `notificacao.sender=smtp` apontando para ele.
- **Decisão pendente do M11:** o indicador de saúde `mail` é reabilitado **somente no Compose**, onde o SMTP é dependência ativa. O padrão do `application.yml` continua `false`, com canal `log`.
- **Consulta de demonstração pela API.** Com os serviços saudáveis, um roteiro de demonstração faz o fluxo real:
  - autentica uma única vez o enfermeiro do seed V900 e usa esse token em todas as chamadas, que as matrizes existentes já permitem a ENFERMEIRO;
  - cria, pelo endpoint real, uma consulta marcada como de demonstração (`observacoes: "demo"`), do paciente e do médico do V900, com horário relativo ao momento da execução, dentro das próximas 24 horas;
  - comprova o e-mail de agendamento no Mailpit e a leitura no histórico por GraphQL;
  - dispara o lembrete D-1 e comprova o e-mail do lembrete.

  A reexecução reaproveita de forma determinística só a consulta que atende a todos os critérios da demonstração (paciente, médico, marcador, status e janela), ignora qualquer outra e não cria outra. Os usuários de demonstração continuam vindo do V900.
- **Makefile** com o fluxo simples: `make demo`, e ainda `infra`, `ps`, `logs`, `down` e `reset`.
- **Documentação e verificação final:**
  - README e `docs/01`;
  - gate global;
  - validação obrigatória do zero, `docker compose down -v && make demo`;
  - clone limpo e archive.

## Capabilities

### New Capabilities

Nenhuma.

### Modified Capabilities

- `operacao-do-ambiente`: cinco Requirements ADDED, 19 Scenarios.
  - **Imagens executáveis das aplicações**;
  - **Ambiente completo com um comando** (RNF-07);
  - **Notificações por e-mail no ambiente de demonstração**;
  - **Demonstração executável do zero**;
  - **Configuração do ambiente verificada estruturalmente**.

  Nenhum Requirement promovido é modificado. "Ambiente de infraestrutura local" continua verdadeiro: PostgreSQL e RabbitMQ seguem saudáveis, com os três databases, e recriáveis por `docker compose down -v`. Também continuam intactos, sem repetição, "Canal de envio selecionável e sem segredo embutido", "Dados de demonstração restritos", "Endpoints operacionais mínimos e seguros", "Logs estruturados no profile docker" e "Smoke ponta a ponta do fluxo principal".

## Impact

- **Novos arquivos:**
  - `Dockerfile` nos três serviços e `.dockerignore` na raiz;
  - `Makefile` e `scripts/demo.sh`;
  - testes estruturais no `quality-gates`.
- **Alterados:**
  - `docker-compose.yml`, com os serviços de aplicação e o Mailpit;
  - `.env.example`, com comentários, sem segredo novo;
  - `README.md`, `docs/01-arquitetura.md` e a nota do M12 em `docs/04-roadmap.md`.
- **Dependências externas:** imagens `eclipse-temurin:21-jre-alpine`, Maven 3.9 com Temurin 21 para o build e Mailpit, todas com tag fixada. Pré-requisitos de host da demonstração: Docker com Compose v2, `make`, `curl` e `jq`.

**O que NÃO muda:**

- **Código e configuração dos serviços:** código de produção, `application*.yml`, migrations, seeds V900, schema GraphQL, contrato e topologia AMQP, regras de negócio, matrizes de autorização e perfis.
- **Proteções do M10 e do M11:** gate, auditoria, cobertura, guarda de infraestrutura real, inventários, ArchUnit, correlação, Actuator e seus indicadores padrão, e logs JSON.
- **Smoke:** `scripts/smoke-test.sh` e o `RoteiroDeSmokeTest` continuam sem Compose e sem imagens das aplicações.
- **Nada de M13 ou M14:** collection Postman, relatório técnico, auditoria global e release.
- **Sem CI nem deploy em nuvem.**
- **Versão:** `CHANGELOG.md` e versão do projeto não mudam.

**Riscos principais**, com mitigação no `design.md`:
- build Maven multi-módulo dentro da imagem;
- tag e healthcheck do Mailpit;
- `down -v` apaga os dados do Compose local;
- GNU Make ausente na máquina da apply, obrigatório para a evidência oficial;
- horário da consulta e fuso;
- tempo da validação do zero.

**Critérios de aceite:**
- os 19 Scenarios com evidência;
- verificação estrutural e `mvn -q clean verify` verdes, com auditoria e pisos de 85%/90%/90%;
- `docker compose down -v && make demo` com código 0 a partir de volumes vazios, seis containers `healthy`, e-mails de agendamento e de lembrete no Mailpit e consulta visível no histórico;
- reexecução de `make demo` com o mesmo `consultaId` e exatamente uma consulta de demonstração;
- clone limpo com `clean verify` verde;
- archive na feature branch antes do merge.
