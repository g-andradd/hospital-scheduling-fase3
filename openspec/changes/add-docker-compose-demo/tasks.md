> **Condições gerais.**
>
> - **Aprovação.** Nenhuma task começa antes da aprovação explícita do Gabriel sobre proposal, design, specs e tasks.
> - **Git.** A branch é `feature/m12-add-docker-compose-demo`. Toda operação Git é do Gabriel; o implementador entrega os comandos prontos.
> - **Nomes.** Nomes de arquivo e classe são indicativos; o comportamento está na delta de `operacao-do-ambiente`, e o desenho, em `design.md` (D1–D11).
> - **Ordem.** P0 (seções 1 a 3: imagens, Compose funcional e verificação estrutural) fica verde no Checkpoint P0 antes de qualquer arquivo de P1 (seções 4 a 8: Mailpit, demonstração, documentação e verificação). O M12 só está concluído com P0 e P1.
> - **Ambiente local.** Subir os serviços com `--build` sobre os volumes existentes é permitido. Qualquer `docker compose down -v` ou `make reset` apaga os dados do Compose local e **só roda com autorização explícita do Gabriel** (seção 8).
> - **Escopo.** Não entra item de M13 ou M14. Código de produção, `application*.yml`, migrations, seeds, contrato AMQP, schema GraphQL, matrizes, smoke e proteções do M10/M11 não mudam.

## 1. P0 — Imagens das aplicações (D2)

- [x] 1.1 Criar o `.dockerignore` na raiz, excluindo `**/target`, `.git`, `.idea`, `node_modules`, `.env` e `openspec`. Verificar que `.env` e `target/` não entram no contexto: o build da 1.2 não pode listar esses caminhos.
- [x] 1.2 Criar `agendamento-service/Dockerfile` conforme o D2:
  - etapa `build` sobre `maven:3.9.9-eclipse-temurin-21-alpine`, com os sete POMs copiados antes das fontes de `shared-contracts`, `shared-security` e do serviço;
  - `RUN --mount=type=cache,target=/root/.m2 mvn -B -q -DskipTests package -pl agendamento-service -am`;
  - etapa final `eclipse-temurin:21-jre-alpine` com usuário de sistema `app`, `USER app`, só o `*-exec.jar` em `/app/app.jar`, `EXPOSE 8081`, `ENTRYPOINT` e `HEALTHCHECK` com `wget` em `http://127.0.0.1:8081/actuator/health`.

  Verificar com `docker build -f agendamento-service/Dockerfile -t hospital-agendamento:m12 .` e com `docker run --rm --entrypoint id hospital-agendamento:m12 -u`, que deve ser diferente de 0. Se a tag, o cache mount ou o `wget` falharem, **parar** e exigir `/opsx:update`.
- [x] 1.3 Criar `notificacao-service/Dockerfile` e `historico-service/Dockerfile` equivalentes, com as portas 8082 e 8083. Verificar os dois builds e o `id -u` diferente de 0 em cada imagem, e que a imagem final de cada serviço não contém `mvn` nem fontes (`docker run --rm --entrypoint sh <imagem> -c 'command -v mvn; ls /app'`).

## 2. P0 — Compose funcional (D3, D4)

- [x] 2.1 Acrescentar ao `docker-compose.yml` os serviços `agendamento`, `notificacao` e `historico`, sem alterar `postgres`, `rabbitmq`, volumes, rede nem `init.sql`:
  - `build` com contexto na raiz e o Dockerfile do serviço;
  - `container_name` `hospital-<serviço>`;
  - portas 8081, 8082 e 8083;
  - `restart: unless-stopped`;
  - `depends_on` de `postgres` e `rabbitmq` com `condition: service_healthy`;
  - `environment` em mapa, conforme o D3: URL do database próprio em `postgres`, credenciais do `.env`, `RABBITMQ_HOST=rabbitmq`, `RABBITMQ_PORT=5672`, `JWT_SECRET=${JWT_SECRET:?defina JWT_SECRET no .env}` e `SPRING_PROFILES_ACTIVE=demo,docker`.

  A notificação fica temporariamente com `NOTIFICACAO_SENDER=log`. Verificar com `docker compose config --quiet`.
- [x] 2.2 Atualizar os comentários do `docker-compose.yml` e do `.env.example`: uso completo, `docker compose up -d postgres rabbitmq` para só a infraestrutura, e nenhum segredo novo. Verificar que o `.env.example` continua sem credencial real e que `docker compose config --quiet` passa com um `.env` copiado dele.

## 3. P0 — Verificação estrutural das imagens e do Compose (D8)

- [x] 3.1 Criar o `AmbienteDeDemonstracaoTest` no `quality-gates`, só com JDK e leitura linha a linha dos arquivos reais. Regras das imagens e do Compose do D8, exceto Mailpit e Makefile, que entram na 6.1:
  - **Dockerfiles:** multi-stage com etapa de build nomeada, runtime `eclipse-temurin:21-jre-alpine`, `USER` não-root antes do `ENTRYPOINT`, `HEALTHCHECK` com `/actuator/health` na porta certa, POMs antes das fontes, cache mount do `.m2` e nenhum `target/` do host;
  - **Compose:** os cinco serviços, `depends_on` só com `service_healthy`, URL de database própria em `postgres`, `RABBITMQ_HOST=rabbitmq`, `SPRING_PROFILES_ACTIVE=demo,docker`, `JWT_SECRET` só como `${JWT_SECRET:?...}` e imagens sem `latest`;
  - **`.dockerignore`:** `target`, `.git` e `.env`.

  Verificar que passa sobre os arquivos reais.
- [x] 3.2 Criar os negativos sintéticos, partindo do conteúdo real e mudando uma coisa por caso:
  - `USER root` e `USER` ausente;
  - `HEALTHCHECK` ausente e em outro caminho;
  - runtime com JDK completo e fontes antes dos POMs;
  - `depends_on` sem `service_healthy`;
  - dois serviços no mesmo database e `RABBITMQ_HOST=localhost`;
  - profile sem `demo` e sem `docker`;
  - `JWT_SECRET` literal;
  - imagem `latest`;
  - `.dockerignore` sem `.env`.

  Cada um falha identificando arquivo e regra. Verificar que passam com `mvn -q -pl quality-gates -am test -Dtest='AmbienteDeDemonstracaoTest' -Dsurefire.failIfNoSpecifiedTests=false`. Cobre "Dependências resolvidas antes do código-fonte" e parte de "Desvio de imagem ou de ambiente é recusado".

**Checkpoint P0:**
- `docker compose up -d --build --wait` com código 0 e `docker compose ps` com `postgres`, `rabbitmq`, `agendamento`, `notificacao` e `historico` `healthy`;
- `docker compose exec <serviço> id -u` diferente de 0 nos três;
- uma linha JSON de log de cada serviço em `docker compose logs`;
- `POST http://localhost:8081/auth/login` com `medico@hospital.com` responde 200;
- `/actuator/health` responde 200 nas três portas;
- o `AmbienteDeDemonstracaoTest` verde.

Registrar as saídas. Nenhum `down -v` nesta etapa.

**Evidência executada do Checkpoint P0 (2026-09-14):**

- os três `docker build` concluíram com código 0; as bases, o cache mount e o `wget` do healthcheck foram aceitos;
- as três imagens executam com UID `100`, não contêm `mvn` e expõem somente `app.jar` em `/app`;
- `docker compose config --quiet` e `docker compose up -d --build --wait` concluíram com código 0 sobre os volumes existentes;
- `postgres`, `rabbitmq`, `agendamento`, `notificacao` e `historico` ficaram `healthy`;
- os três containers de aplicação executam com UID `100`, emitiram linha JSON com o campo `service` correto e responderam HTTP 200 em `/actuator/health`;
- `POST /auth/login` com `medico@hospital.com` respondeu HTTP 200, perfil `MEDICO` e `accessToken` presente;
- `AmbienteDeDemonstracaoTest`: 15 casos, 0 falhas, 0 erros e 0 ignorados (configuração real e 14 negativos sintéticos);
- nenhum `down -v` ou remoção de volume foi executado.

## 4. P1 — Mailpit e canal SMTP (D5)

- [x] 4.1 Acrescentar o serviço `mailpit` ao Compose: `axllent/mailpit:v1.31.1`, `container_name: hospital-mailpit`, porta `8025:8025`, SMTP 1025 só na rede interna, healthcheck `["CMD", "/mailpit", "readyz"]` e `restart: unless-stopped`. Verificar com `docker compose up -d --wait mailpit` e `healthy`. Se a tag ou o `readyz` não existirem, **parar** e exigir `/opsx:update`.
- [x] 4.2 Configurar a notificação no Compose com `NOTIFICACAO_SENDER=smtp`, `SMTP_HOST=mailpit`, `SMTP_PORT=1025` e `MANAGEMENT_HEALTH_MAIL_ENABLED=true`, e acrescentar `mailpit` com `service_healthy` ao `depends_on`. Não alterar `application.yml`. Verificar:
  - `docker compose up -d --wait notificacao` com `healthy`;
  - `/actuator/health` 200;
  - o `EndpointsOperacionaisIT` da notificação continua verde sem mudança, provando o padrão fora do Compose.

## 5. P1 — Demonstração (D6, D7)

- [x] 5.1 Criar `scripts/demo.sh`, carregável por `source` sem executar o fluxo:
  - modo `preflight`: `docker`, Compose v2, `curl` e `jq` 1.6 ou superior, com código 2 e o pré-requisito identificado;
  - função `horario_da_consulta`, que recebe o instante em segundos (padrão `DEMO_AGORA` ou relógio do host) e devolve a hora cheia seguinte mais duas horas, em UTC ISO-8601;
  - fluxo completo do D6:
    - health dos três serviços;
    - **um único login**, do enfermeiro, cujo token é usado para listar e criar a consulta, para `consulta(id:)` no GraphQL e para `POST /internal/lembretes/executar`;
    - seleção com `jq` da consulta de demonstração pelos critérios simultâneos (paciente `bbbbbbbb-0000-0000-0000-000000000001`, médico `aaaaaaaa-0000-0000-0000-000000000001`, `observacoes == "demo"`, status `AGENDADA` ou `CONFIRMADA`, horário em `(agora, agora + 24h]`), ignorando consultas sem o marcador, reaproveitando se houver exatamente uma e terminando com código 1 se houver mais de uma;
    - criação pela API com `observacoes: "demo"` quando não houver;
    - e-mail "Consulta agendada" no Mailpit, consulta no GraphQL, lembrete com `lembretesEnviados >= 0` e e-mail "Lembrete de consulta";
    - contagem final com exatamente uma consulta de demonstração e o mesmo `consultaId`;
    - resumo com URLs, credenciais, `consultaId` e origem `criada` ou `reaproveitada`;
  - esperas com prazo e códigos 0, 1, 2 e 3 com etapa e última resposta.

  Verificar com `bash -n scripts/demo.sh`.

**Evidência parcial da P1 (2026-09-14):**

- `axllent/mailpit:v1.31.1` foi obtida e ficou `healthy` com `/mailpit readyz`;
- a notificação foi recriada com SMTP, esperou o Mailpit saudável e voltou a `healthy`; seu `/actuator/health` respondeu HTTP 200;
- `EndpointsOperacionaisIT` da notificação: 7 casos, 0 falhas, 0 erros e 0 ignorados, sem alteração no teste nem em `application.yml`;
- `scripts/demo.sh` passou em `bash -n`, e `horario_da_consulta` foi caracterizada com instantes fixos sem executar o fluxo;
- `make --version` confirmou GNU Make 4.4.1 para Windows; o plano seco de `demo` preserva a ordem preflight, criação condicional do `.env`, subida completa e roteiro, e `down -v` aparece somente em `reset`.
- no Windows, o Make seleciona o Git Bash da instalação padrão em vez do launcher WSL de `System32`; a prova estrutural recusa a regressão, e as duas suítes afetadas ficaram verdes (26 casos).
- [x] 5.2 Antes de criar o arquivo, executar `make --version` e registrar a saída. Se o GNU Make estiver ausente, **parar** a apply e pedir ao Gabriel que o instale; não instalar por conta própria nem substituir `make` na validação. Criar o `Makefile` com os alvos `demo`, `infra`, `ps`, `logs`, `down` e `reset`, conforme o D7. O `demo` executa, nesta ordem, `bash scripts/demo.sh preflight`, a cópia do `.env.example` quando `.env` não existe, `docker compose up -d --build --wait` e `bash scripts/demo.sh`. Nenhum `down -v` fora de `reset`. Verificar com `make -n demo` e `make -n reset`.
- [x] 5.3 Criar o `RoteiroDeDemonstracaoTest` no `quality-gates`, carregando `scripts/demo.sh` por `source`, sem rede e sem `jq`:
  - `horario_da_consulta` com instantes fixos (minuto zero, meio da hora e 23h30 UTC) sempre em `(agora, agora + 24h]`, entre 2 e 3 horas depois;
  - `scripts/demo.sh preflight` com PATH sem `curl`, e depois sem `jq`, termina com código 2 identificando a ferramenta, sem criar `.env` num diretório de trabalho temporário.

  Verificar que passa. Cobre "Consulta de demonstração fica na janela do lembrete" (parte estrutural) e "Pré-requisito ausente é recusado antes de alterar o ambiente".

## 6. P1 — Verificação estrutural completa (D8)

- [x] 6.1 Estender o `AmbienteDeDemonstracaoTest`:
  - Compose com os seis serviços, `mailpit` com tag fixada e healthcheck;
  - notificação com `NOTIFICACAO_SENDER=smtp`, `SMTP_HOST=mailpit`, `MANAGEMENT_HEALTH_MAIL_ENABLED=true` e `depends_on` do `mailpit` com `service_healthy`;
  - `Makefile` com `demo` contendo o preflight, `up -d --build --wait` e `scripts/demo.sh`, e `down -v` só em `reset`.

  Criar os negativos: canal `log` no Compose, `SMTP_HOST` apontando para `localhost`, health `mail` desabilitado, `mailpit` sem healthcheck e `down -v` no alvo `demo`. Verificar que tudo passa. Cobre "Configuração real atende" e "Desvio de imagem ou de ambiente é recusado".

## 7. P1 — Documentação (D11)

- [x] 7.1 Atualizar o README:
  - "Executar a aplicação completa" com `make demo` como caminho oficial e GNU Make entre os pré-requisitos; os três comandos sem `make` (cópia do `.env`, `docker compose up -d --build --wait` e `bash scripts/demo.sh`) aparecem só como conveniência operacional, sem substituir a validação oficial; demais pré-requisitos, portas, URLs de Swagger, GraphiQL, Mailpit e RabbitMQ Management, credenciais demo, `make ps/logs/down/reset` e o aviso de que `down -v` e `reset` apagam os dados;
  - "Executar a infraestrutura" passa a citar `make infra` e `docker compose up -d postgres rabbitmq`.

  Verificar que os comandos citados são os executados na seção 8.
- [x] 7.2 Atualizar o `docs/01-arquitetura.md` (estrutura com Dockerfiles, `Makefile`, `scripts/demo.sh` e Mailpit no Compose) e a nota do M12 em `docs/04-roadmap.md` (consulta de demonstração pela API e `mail` reabilitado só no Compose). Atualizar o `docs/02` só se a linha do RNF-07 precisar refletir o entregue.

## 8. Verificação final

- [x] 8.1 Executar e registrar as suítes dirigidas, verdes:

  ```bash
  mvn -q -pl quality-gates -am test -Dtest='AmbienteDeDemonstracaoTest,RoteiroDeDemonstracaoTest,RoteiroDeSmokeTest,LogsEstruturadosTest,AuditoriaDeExecucaoTest,InfraestruturaRealTest' -Dsurefire.failIfNoSpecifiedTests=false
  ```

  **Evidência final executada (2026-09-14/15):** 295 casos, 0 falhas, 0 erros e 0 ignorados.
  Inclui `AmbienteDeDemonstracaoTest` (21), `RoteiroDeDemonstracaoTest` (5),
  `LogsEstruturadosTest` (12), as 17 provas de `InfraestruturaRealTest`, as 223 provas de
  `AuditoriaDeExecucaoTest` e as 17 provas de `RoteiroDeSmokeTest`. A guarda de infraestrutura
  real terminou APROVADA.

- [x] 8.2 Executar `mvn -q clean verify` na raiz, sem filtro, uma vez. Registrar: build verde, total de casos, zero ignorados, auditoria e guarda de infraestrutura APROVADAS e cobertura com os pisos de 85%, 90% e 90%.

  **Evidência final executada (2026-09-14/15):** o reactor produziu 1.610 casos em 220
  relatórios, com 0 falhas, 0 erros e 0 ignorados: contracts 138, security 74, agendamento 600,
  notificação 141, histórico 205 e quality-gates 452. A guarda de infraestrutura real e a
  auditoria de execução ficaram APROVADAS. O gate confirmou 97,37% global (1.856/1.906 linhas),
  98,72% em `domain` (309/313) e 100% em `application` (158/158), acima dos pisos de 85%,
  90% e 90%. Esta execução substitui a evidência de 1.609 casos obtida antes da prova adicional
  que impede o launcher do WSL de ser usado como Git Bash no Windows.
- [x] 8.3 **Validação do zero, com autorização explícita do Gabriel:** `docker compose down -v`, conferindo que os volumes `hospital-fase3_postgres-data` e `hospital-fase3_rabbitmq-data` não existem, e depois `make demo`. Registrar:
  - código 0;
  - `docker compose ps` com os seis containers `healthy`;
  - `id -u` diferente de 0 nos três serviços;
  - uma linha JSON de log de cada serviço;
  - o id e o horário da consulta, dentro de `(agora, agora + 24h]`;
  - os ids das mensagens "Consulta agendada" e "Lembrete de consulta" no Mailpit;
  - a resposta GraphQL da consulta;
  - `lembretesEnviados` igual a 1;
  - o resumo final.

  Cobre "Aplicação executa com usuário sem privilégio", "Saúde do container reflete o endpoint de saúde", "Subida completa até o estado saudável", "Serviço só inicia com as dependências saudáveis", "Cada serviço usa o próprio database", "Perfis de demonstração e de container ativos", "Notificação reativa chega à caixa de entrada" e "Demonstração do zero termina com sucesso".

  **Evidência executada (2026-09-14/15):** Gabriel autorizou nominalmente a exclusão dos
  volumes `hospital-fase3_postgres-data` e `hospital-fase3_rabbitmq-data`; ambos foram removidos
  e confirmados ausentes antes da subida. `make demo` terminou com código 0 e os seis containers
  ficaram `healthy`. As aplicações executam com UID 100 e emitiram logs JSON com o `service`
  correto. A consulta `8dcb5998-37e0-486a-8893-d35a8c8c693d`, criada às 04:00Z, ficou 2,52 h
  à frente da conferência, com status `AGENDADA` e marcador `demo`. O GraphQL devolveu o mesmo id,
  paciente, médico, status, observações e horário. Mailpit registrou os ids
  `1twB8Z7BnV9do3xdcXRoTM` (agendamento) e `49ZKWQpyAWMeEgiWzj9rU4` (lembrete), e o resumo informou
  `origem=criada` e `lembretesEnviados=1`.
- [x] 8.4 Reexecução, com o ambiente em pé depois da 8.3:
  1. registrar pela API, com o token do enfermeiro, a lista de consultas que atendem aos critérios completos de demonstração do D6 (deve ser exatamente uma, o `consultaId` da 8.3);
  2. criar pela API uma consulta de controle do mesmo paciente e médico, na janela, sem sobrepor a de demonstração (a hora cheia seguinte mais quatro horas) e com `observacoes` diferente de `demo`;
  3. executar `make demo` de novo.

  Registrar:
  - código 0;
  - origem `reaproveitada`;
  - o mesmo `consultaId` da 8.3;
  - quantidade de consultas de demonstração igual a 1, antes e depois;
  - nenhuma consulta de demonstração nova;
  - a consulta de controle não reaproveitada nem contada.

  Depois, cancelar a consulta de controle pela API e registrar o cancelamento. Cobre "Reexecução não duplica a consulta".

  **Evidência executada (2026-09-14/15):** a listagem autenticada com o token do enfermeiro
  encontrou uma única consulta de demonstração antes da reexecução, id
  `8dcb5998-37e0-486a-8893-d35a8c8c693d`. Foi criada pela API a consulta de controle
  `3fdb4cc6-56d7-4fbc-ab79-0750b968f4e9`, às 06:00Z, para o mesmo paciente e médico,
  com `observacoes=controle-m12`. A segunda execução de `make demo` terminou com código 0,
  `origem=reaproveitada`, o mesmo id e `lembretesEnviados=1`. A contagem das candidatas com
  os cinco critérios completos permaneceu 1 antes e depois; o controle não foi contado nem
  reaproveitado. Ao final, a API cancelou o controle e devolveu status `CANCELADA`.
- [x] 8.5 Negativos de runtime, sem `down -v`:
  - `docker compose stop mailpit` até a notificação ficar `unhealthy`, e `docker compose start mailpit` até ela voltar a `healthy`;
  - `JWT_SECRET= docker compose config --quiet` falhando e citando a variável, sem subir containers;
  - inspeção da imagem final de cada serviço sem `mvn` e sem fontes.

  Cobre "Saúde da notificação inclui o servidor de correio", "Segredo obrigatório ausente impede a subida", "Imagem final contém só o necessário para executar" e o lado de runtime de "Verificação que falha interrompe a demonstração" (código e etapa ao parar o `mailpit` antes de `bash scripts/demo.sh`, restaurando em seguida).

  **Evidência executada (2026-09-14/15):** com o Mailpit parado, o indicador de correio
  registrou falha e o container da notificação ficou `unhealthy` depois da janela configurada
  de 12 tentativas; `scripts/demo.sh`, com prazo reduzido apenas para a prova, terminou com
  código 3 em `health da notificação`. O Mailpit foi reiniciado e ambos voltaram a `healthy`.
  `JWT_SECRET= docker compose config --quiet` terminou com código 1, citou a variável obrigatória
  e preservou os ids dos seis containers. As imagens finais de agendamento, notificação e
  histórico não têm `mvn`, `/workspace`, arquivos `.java` ou `pom.xml`; em `/app` existe somente
  `app.jar`. Ao final, os seis containers estavam `running` e `healthy`.
- [ ] 8.6 Clone limpo: clonar a feature branch, depois do push e com autorização do Gabriel, num diretório criado com `mktemp -d` e validado sob `${TMPDIR:-/tmp}`, com repositório Maven temporário vazio e sem `install`. Executar **somente** `mvn -q -Dmaven.repo.local=<repo> clean verify`. Registrar código 0, contagens, zero skipped e a ausência de artefato interno no repositório temporário.
- [ ] 8.7 Conferir pelo diff publicado do PR:
  - nada de M13 ou M14;
  - código de produção, `application*.yml`, migrations, seeds, contrato, schema, matrizes e o smoke inalterados;
  - `postgres`, `rabbitmq`, volumes e `init.sql` inalterados no Compose;
  - nenhuma proteção do M10/M11 removida;
  - `CHANGELOG.md` e versão inalterados.
- [ ] 8.8 Montar no corpo do PR a matriz dos 19 Scenarios → teste ou evidência registrada, sem Scenario sem evidência. Registrar as decisões do D5 e do D6, a autorização da validação do zero e os riscos residuais.
- [ ] 8.9 Confirmar a prontidão para archive: todas as tasks de 1.1 a 8.8 concluídas, e `openspec status --change add-docker-compose-demo` e `openspec validate add-docker-compose-demo --strict` verdes. O archive é feito na própria feature branch, depois da aprovação do PR e antes do merge.
