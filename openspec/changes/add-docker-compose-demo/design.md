## Context

A motivação está em `proposal.md` (Why). Estado atual que orienta o desenho:

- **Compose.** `docker-compose.yml`, com `name: hospital-fase3`, tem só `postgres` (`hospital-postgres`, `postgres:16`) e `rabbitmq` (`hospital-rabbitmq`, `rabbitmq:3.13-management`), ambos com healthcheck. Usa os volumes `postgres-data` e `rabbitmq-data` e a rede `hospital-net`, e o `docker/postgres/init.sql` cria os três databases no volume vazio. O `.env.example` traz usuário, senha, portas e `JWT_SECRET`, e também `RABBITMQ_HOST=localhost`, que só serve para execução local.
- **Build.** Maven multi-módulo com `${revision}`. O reactor lista seis módulos, inclusive o `quality-gates`. O smoke empacota com `mvn -q -DskipTests package -pl <serviços> -am`, e cada serviço gera um único `*-exec.jar` (classifier `exec`). Os serviços dependem do test-jar do `shared-contracts` em escopo de teste, o que exige compilar os testes: `-DskipTests`, e não `-Dmaven.test.skip`.
- **Configuração dos serviços.**
  - Agendamento: `AGENDAMENTO_DB_URL`, `POSTGRES_USER` e `POSTGRES_PASSWORD`.
  - Notificação: `NOTIFICACAO_DB_URL`, `NOTIFICACAO_DB_USER`, `NOTIFICACAO_DB_PASSWORD`, `NOTIFICACAO_SENDER` (padrão `log`), `SMTP_HOST` e `SMTP_PORT`. Não tem padrão para `JWT_SECRET`.
  - Histórico: `HISTORICO_DB_URL`, `HISTORICO_DB_USER` e `HISTORICO_DB_PASSWORD`.
  - Os três leem `RABBITMQ_HOST`, `RABBITMQ_PORT`, `RABBITMQ_USER` e `RABBITMQ_PASSWORD`, e usam as portas 8081, 8082 e 8083.
- **Profiles e saúde.**
  - `demo` acrescenta o seed V900 no agendamento e o GraphiQL no histórico. A notificação não tem profile `demo`.
  - `docker` liga os logs JSON nos três serviços (M11).
  - `health` é público. `management.health.rabbit.enabled=false` no agendamento e `management.health.mail.enabled=false` na notificação, com a decisão sobre SMTP deixada ao M12 (M11, D5).
- **Seed V900.** Médico `aaaaaaaa-…-0001`, ligado ao usuário `111…`; enfermeiro `222…`; paciente `bbbbbbbb-…-0001` (`paciente@hospital.com`). Todos com a senha `Senha@123`.
- **Lembrete D-1.** Lê a agenda local da notificação, materializada só por eventos, e seleciona consultas em `(agora, agora + 24h]`. Os e-mails têm os assuntos "Consulta agendada" e "Lembrete de consulta". Por isso uma consulta escrita direto no banco do agendamento nunca alcançaria notificação, histórico ou lembrete.
- **Proteções existentes.** O smoke (M10/M11) não usa Compose; o `RoteiroDeSmoke` recusa `docker compose` só no roteiro do smoke. As guardas de segredo e de infraestrutura real leem `src/main` e os `*IT`.

## Goals / Non-Goals

**Goals:**
- `docker compose down -v && make demo` verde a partir de volumes vazios, com o fluxo visível: e-mail no Mailpit, GraphQL no histórico e lembrete.
- Nenhuma mudança em código ou configuração dos serviços.
- Garantias de imagem e de Compose verificadas no build, sem subir containers.

**Non-Goals:**
- Orquestração além do Compose, CI, registry de imagens, deploy em nuvem e TLS.
- Imagens nativas, jlink ou tuning de JVM além do padrão do Temurin.
- Collection Postman, relatório, auditoria global e release (M13/M14).
- Mudar o smoke, os seeds V900 ou os profiles.

## Decisions

### D1. Ordem: P0 antes de P1

- **P0 — imagens e Compose funcional:**
  - `.dockerignore` e os três Dockerfiles;
  - Compose com os cinco containers, e a notificação ainda no canal `log`;
  - verificação estrutural das imagens e do Compose;
  - Checkpoint P0: `docker compose up -d --build --wait` com cinco containers `healthy`, processo não-root, logs JSON e login demo.
- **P1 — demonstração completa:**
  - Mailpit, canal SMTP e health `mail`;
  - `scripts/demo.sh` e `Makefile`;
  - extensão da verificação estrutural;
  - documentação, gate global, validação do zero, clone limpo e archive.

Assim o Compose funcional fica verde antes de a demonstração depender dele. O M12 só conclui com P0 e P1.

### D2. Imagem por serviço: multi-stage com cache do Maven

**Um `Dockerfile` por serviço**, em `<serviço>/Dockerfile`, com contexto de build na raiz do repositório (o `docs/01` já prevê um Dockerfile por serviço).

- **Etapa `build`** sobre `maven:3.9.9-eclipse-temurin-21-alpine`, com tag fixada:
  1. copia o POM raiz e os seis POMs de módulo — o reactor precisa de todos para montar o modelo;
  2. copia só as fontes de `shared-contracts`, `shared-security` e do serviço;
  3. executa `mvn -B -q -DskipTests package -pl <serviço> -am` com `RUN --mount=type=cache,target=/root/.m2`;
  4. copia o `*-exec.jar` para um caminho fixo.

  O cache do BuildKit guarda o repositório Maven entre builds; a ordem POMs primeiro mantém estável a camada de descritores.
- **Etapa final** sobre `eclipse-temurin:21-jre-alpine`:
  - cria o grupo e o usuário de sistema `app` e declara `USER app`;
  - copia só o `*-exec.jar` para `/app/app.jar`;
  - declara `EXPOSE` da porta do serviço e `ENTRYPOINT ["java", "-jar", "/app/app.jar"]`;
  - declara `HEALTHCHECK` com o `wget` do BusyBox em `http://127.0.0.1:<porta>/actuator/health`, com `interval`, `timeout`, `start-period` e `retries` explícitos.
- **Testes fora da imagem.** O `-DskipTests` evita repetir no build da imagem o gate que já roda no `mvn verify`.
- **`.dockerignore`** exclui `**/target`, `.git`, `.idea`, `node_modules`, `.env` e `openspec`. Nenhum artefato local entra no contexto.
- **Alternativas descartadas:**
  - `mvn dependency:go-offline` numa camada própria: no reactor, os artefatos internos (inclusive o test-jar do `shared-contracts`) não existem antes do `package`, e o goal falharia ou baixaria parcialmente;
  - copiar para a imagem jars construídos no host: dependeria de Maven e de estado local, contrariando "executável do zero";
  - um Dockerfile único parametrizado por argumento: menos legível para a banca e com o `HEALTHCHECK` dependendo de argumento.
- **Condição de parada:** se a tag do Maven ou do Temurin não existir, se o BuildKit indisponível impedir o cache mount, ou se o `wget` não existir na imagem final, a apply **para** e exige `/opsx:update`, sem trocar de base.

### D3. Compose final no mesmo arquivo

- **Infraestrutura sem mudança.** `postgres` e `rabbitmq` ficam idênticos: nomes de container, volumes, `init.sql` e healthchecks. A rede `hospital-net` é reaproveitada.
- **Serviços novos:** `agendamento`, `notificacao` e `historico`, com `container_name` `hospital-agendamento`, `hospital-notificacao` e `hospital-historico`.
  - `build: { context: ., dockerfile: <serviço>/Dockerfile }` e portas `8081:8081`, `8082:8082` e `8083:8083`;
  - `restart: unless-stopped`;
  - `depends_on` com `postgres` e `rabbitmq` em `condition: service_healthy`; a notificação acrescenta `mailpit` na P1.
- **`environment` sempre em forma de mapa e explícito por serviço.** Isso garante que o `RABBITMQ_HOST=localhost` do `.env` nunca vaze para o container e deixa o arquivo determinístico para a verificação estrutural.
  - Agendamento: `AGENDAMENTO_DB_URL=jdbc:postgresql://postgres:5432/agendamento_db`.
  - Notificação: `NOTIFICACAO_DB_URL=jdbc:postgresql://postgres:5432/notificacao_db`.
  - Histórico: `HISTORICO_DB_URL=jdbc:postgresql://postgres:5432/historico_db`.
  - Usuário e senha do banco, com os nomes de cada serviço, vêm de `${POSTGRES_USER}` e `${POSTGRES_PASSWORD}`.
  - Nos três: `RABBITMQ_HOST=rabbitmq` e `RABBITMQ_PORT=5672`, com usuário e senha de `${RABBITMQ_USER}` e `${RABBITMQ_PASSWORD}`.
  - Nos três: `JWT_SECRET=${JWT_SECRET:?defina JWT_SECRET no .env}` e `SPRING_PROFILES_ACTIVE=demo,docker`.
- **Infraestrutura sozinha** continua possível com `docker compose up -d postgres rabbitmq` (`make infra`), para quem roda os serviços no host.
- **Alternativas descartadas:**
  - `docker-compose.demo.yml` separado: dois arquivos para a banca combinar, contra o RNF-07;
  - `env_file: .env` nos serviços: herdaria `RABBITMQ_HOST=localhost` e variáveis que não pertencem ao serviço.

### D4. Profiles `demo,docker` nos três serviços

- `SPRING_PROFILES_ACTIVE=demo,docker` em todos: seed V900 no agendamento, GraphiQL no histórico e JSON nos três.
- Na notificação, `demo` não tem arquivo nem documento de profile e não altera nada; fica declarado por uniformidade e documentado.
- **Alternativa descartada:** `docker` sozinho na notificação. Funcionaria, mas quebraria a regra única do Compose e da verificação estrutural sem ganho.

### D5. Mailpit, canal SMTP e indicador `mail`

- **Serviço `mailpit`:**
  - imagem `axllent/mailpit:v1.31.1` (tag fixada), `container_name: hospital-mailpit`;
  - porta web `8025:8025` publicada; SMTP `1025` só na rede interna;
  - healthcheck `["CMD", "/mailpit", "readyz"]`.
- **Notificação no Compose:**
  - `NOTIFICACAO_SENDER=smtp`, `SMTP_HOST=mailpit`, `SMTP_PORT=1025`;
  - `MANAGEMENT_HEALTH_MAIL_ENABLED=true`;
  - `depends_on` do `mailpit` com `service_healthy`.
- **Decisão sobre o `mail`**, que fecha a pendência do M11: no Compose o SMTP é a dependência operacional ativa do canal, então o `HEALTHCHECK` da notificação passa a refleti-lo. O `application.yml` não muda (`false`, canal `log`), e fora do Compose tudo segue como o M11 decidiu.
- **Alternativas descartadas:**
  - manter `mail` desabilitado no Compose: a notificação ficaria `healthy` com o canal ativo fora do ar;
  - trocar o padrão do `application.yml`: quebraria o `health` fora do Compose e as provas do M11.
- **Condição de parada:** se a tag não existir ou a imagem não tiver o subcomando `readyz`, a apply **para** e exige `/opsx:update`.

### D6. Consulta de demonstração pela API, no `scripts/demo.sh`

O roteiro roda no host depois de o Compose ficar saudável e usa só a interface pública, com `curl` e `jq`. Não altera o smoke nem compartilha código com ele.

1. **Preflight**, também disponível isolado como `scripts/demo.sh preflight`: `docker`, Compose v2, `curl` e `jq` 1.6 ou superior. Pré-requisito ausente termina com código 2, antes de qualquer escrita. Na execução completa, o roteiro confere em seguida o `health` dos três serviços.
2. **Login único do enfermeiro** `enfermeiro@hospital.com` (V900). O mesmo token serve para todas as chamadas autenticadas do roteiro, sem segundo login nem política paralela. As matrizes existentes permitem ENFERMEIRO nas três operações usadas:
   - criar e listar consultas no agendamento (`docs/02` §3);
   - `consulta(id:)` no histórico, que permite MEDICO e ENFERMEIRO sobre qualquer registro (`historico-de-consultas`, "Autorização por operação e perfil no histórico");
   - `POST /internal/lembretes/executar`, autorizado a MEDICO e ENFERMEIRO (`notificacoes-ao-paciente`, "Disparo manual protegido por perfil").
3. **Idempotência determinística.** `GET /api/v1/consultas` com o token do enfermeiro, filtrando no servidor pelo que a API suporta: `pacienteId=bbbbbbbb-0000-0000-0000-000000000001`, `medicoId=aaaaaaaa-0000-0000-0000-000000000001`, `de=agora` e `ate=agora+24h`. A seleção final é feita com `jq`, e só conta como consulta de demonstração a que atende **ao mesmo tempo** a:
   - paciente `bbbbbbbb-0000-0000-0000-000000000001`;
   - médico `aaaaaaaa-0000-0000-0000-000000000001`;
   - `observacoes == "demo"`;
   - status `AGENDADA` ou `CONFIRMADA`;
   - `dataHora` em `(agora, agora + 24h]`.

   Consulta sem o marcador `observacoes == "demo"` é ignorada, mesmo que seja do mesmo paciente e médico. Havendo exatamente uma consulta de demonstração, ela é reaproveitada. Havendo mais de uma, o roteiro termina com código 1: não escolhe uma arbitrariamente.
4. **Criação.** Sem consulta de demonstração, `POST /api/v1/consultas` com o token do enfermeiro, com médico e paciente acima, registrante `22222222-2222-2222-2222-222222222222`, `duracaoMinutos: 30`, `observacoes: "demo"` e horário gerado pela função `horario_da_consulta`. A função recebe o instante atual em segundos (injetável por `DEMO_AGORA`) e devolve a hora cheia seguinte mais duas horas, em UTC ISO-8601. O resultado fica entre 2 e 3 horas depois do instante, dentro de `(agora, agora + 24h]` e com folga para o disparo.
5. **E-mail de agendamento.** Espera com prazo o Mailpit (`GET /api/v1/search`) conter "Consulta agendada" para `paciente@hospital.com`.
6. **Histórico.** Espera com prazo o GraphQL (`consulta(id:)`, token do enfermeiro) devolver a consulta.
7. **Lembrete.** `POST /internal/lembretes/executar` com o token do enfermeiro. Aceita `lembretesEnviados >= 0` e espera com prazo "Lembrete de consulta" no Mailpit. Na primeira execução do zero o valor é 1; numa reexecução a consulta já foi lembrada e o valor é 0, mas o e-mail já existe.
8. **Contagem final.** Repete a seleção do passo 3 e exige exatamente uma consulta de demonstração, com o mesmo `consultaId` usado nos passos 5 a 7. A saída registra a origem da consulta: `criada` ou `reaproveitada`.
9. **Resumo.** URLs (Swagger, GraphiQL, Mailpit, RabbitMQ Management), credenciais demo e o id da consulta.

- **Códigos:** 0 sucesso, 1 verificação divergente, 2 pré-requisito, 3 prazo esgotado; cada falha informa a etapa e a última resposta.
- **Alternativas descartadas:**
  - migration demo com consulta e outbox em SQL: replicaria o envelope do contrato em SQL, só valeria no primeiro boot do volume e fugiria do fluxo real (decisão do gestor);
  - consulta escrita direto no banco: nunca chegaria à notificação nem ao histórico;
  - roteiro dentro de um container auxiliar: esconderia da banca o que acontece e exigiria imagem com `curl` e `jq`.

### D7. Makefile

- **`demo`:**
  1. executa `bash scripts/demo.sh preflight`;
  2. cria `.env` a partir do `.env.example` se ausente;
  3. executa `docker compose up -d --build --wait`;
  4. executa `bash scripts/demo.sh`.
- **`infra`:** `docker compose up -d --wait postgres rabbitmq`.
- **`ps`:** `docker compose ps`.
- **`logs`:** `docker compose logs -f agendamento notificacao historico`.
- **`down`:** `docker compose down`.
- **`reset`:** `docker compose down -v`, destrutivo e declarado como tal.
- `make demo` não executa `down -v`: a validação do zero é `docker compose down -v && make demo`, explícita.
- Como o preflight é o primeiro passo, pré-requisito ausente para `make demo` com código diferente de zero antes de criar `.env`, container ou volume.
- **`make` é obrigatório para o aceite.** O roadmap e o critério de aceite exigem literalmente `docker compose down -v && make demo`: a evidência oficial é `make -n demo`, a primeira execução real e a reexecução, todas com `make`.
  - O README pode trazer os três comandos equivalentes sem `make`, só como conveniência operacional, e nunca como substituto da evidência oficial.
  - Antes de criar o `Makefile`, a apply confere `make --version`. Se o GNU Make estiver ausente, a apply **para** e pede ao Gabriel que o instale; não instala nada por conta própria nem enfraquece a validação.
- **Alternativa descartada:** `make demo` com `down -v` embutido — apagaria dados sem aviso a cada demonstração.

### D8. Verificação estrutural no `quality-gates`

**`AmbienteDeDemonstracaoTest`**, só com JDK e leitura linha a linha, como o `LogsEstruturadosTest`, sobre os arquivos reais. Ele exige:
- **Dockerfiles:**
  - mais de um `FROM`, com a etapa de build nomeada;
  - final `eclipse-temurin:21-jre-alpine`;
  - `USER` diferente de `root` e `0` antes do `ENTRYPOINT`;
  - `HEALTHCHECK` com `/actuator/health` na porta do serviço;
  - POMs copiados antes das fontes;
  - `--mount=type=cache,target=/root/.m2`;
  - nenhuma cópia de `target/` do host.
- **Compose:**
  - os seis serviços;
  - `depends_on` das aplicações só com `service_healthy`;
  - URL de database própria por serviço, apontando para `postgres`;
  - `RABBITMQ_HOST=rabbitmq`;
  - `SPRING_PROFILES_ACTIVE=demo,docker`;
  - `JWT_SECRET` só como `${JWT_SECRET:?...}`, sem literal;
  - na notificação, `NOTIFICACAO_SENDER=smtp`, `SMTP_HOST=mailpit` e `MANAGEMENT_HEALTH_MAIL_ENABLED=true` (P1);
  - imagens fixadas, sem `latest`.
- **`.dockerignore`:** `target`, `.git` e `.env`.
- **Makefile** (P1): o alvo `demo` contém `up -d --build --wait` e `scripts/demo.sh`, e nenhum `down -v` fora de `reset`.

Negativos sintéticos partem do conteúdo real e mudam uma coisa por caso. A identificação é por arquivo e regra.

**`RoteiroDeDemonstracaoTest`**, carregando o `scripts/demo.sh` por `source`, como o `RoteiroDeSmokeTest`, sem `jq` e sem rede:
- `horario_da_consulta` com instantes fixos (antes, na hora cheia e perto da virada do dia): o resultado fica em `(agora, agora + 24h]`;
- `scripts/demo.sh preflight` com PATH sem `curl`, e depois sem `jq` → código 2, identificando o pré-requisito, sem criar `.env` nem chamar `docker compose up`.

A lógica de rede é provada na execução real.

**Alternativa descartada:** validar o Compose com `docker compose config` no gate — exigiria Docker no `mvn verify`, que hoje não depende dele para esses testes.

### D9. Validação do zero e evidências

A validação do zero **apaga os dados do Compose local** (`hospital-fase3_postgres-data` e `hospital-fase3_rabbitmq-data`): exige autorização explícita do gestor na apply.

Evidências registradas:
- `docker compose down -v`: volumes ausentes;
- `make demo`:
  - código 0;
  - `docker compose ps` com seis containers `healthy`;
  - `id -u` diferente de 0 nos três containers de aplicação;
  - `docker inspect` com a imagem final sem `mvn` e sem `/workspace` do código;
  - uma linha JSON de log de cada serviço;
  - IDs dos e-mails no Mailpit;
  - consulta no GraphQL;
  - resultado do lembrete;
- segunda execução de `make demo`: código 0, o mesmo `consultaId`, origem `reaproveitada` e exatamente uma consulta atendendo aos critérios completos de demonstração do D6, antes e depois;
- negativo de saúde: `docker compose stop mailpit` leva a notificação a `unhealthy` no prazo do healthcheck, e `docker compose start mailpit` a traz de volta a `healthy`;
- negativo de segredo: `JWT_SECRET= docker compose config` (vazio) falha citando a variável, sem subir containers.

**Alternativa descartada:** rodar o smoke contra o Compose. O smoke é deliberadamente independente do Compose (M10) e continua como está; o M12 não altera código nem o roteiro dele.

### D10. Matriz Scenario → evidência

| Requirement | Evidência |
|---|---|
| Imagens executáveis | `AmbienteDeDemonstracaoTest` (multi-stage, runtime, usuário, `HEALTHCHECK`, POMs antes das fontes, cache); validação do zero (`id -u`, `healthy`, inspeção da imagem) |
| Ambiente completo com um comando | validação do zero (`ps` com seis `healthy`, portas); `AmbienteDeDemonstracaoTest` (`depends_on`, URLs, host do broker, profiles, segredo); logs JSON e login demo; `JWT_SECRET` vazio em `docker compose config` |
| Notificações por e-mail | `demo.sh` (e-mail no Mailpit); `stop`/`start` do `mailpit` com a saúde da notificação; `EndpointsOperacionaisIT` da notificação preservado (fora do Compose) |
| Demonstração do zero | `docker compose down -v && make demo`; reexecução; `RoteiroDeDemonstracaoTest` (janela e preflight); códigos de falha do `demo.sh` |
| Configuração verificada | `AmbienteDeDemonstracaoTest` (arquivos reais e negativos) |

### D11. Documentação e limites

- **README:**
  - "Executar a aplicação completa" com `make demo` como caminho oficial; os comandos sem `make` só como conveniência operacional; portas, credenciais, Mailpit, GraphiQL, Swagger, `make reset` e o aviso de que `down -v` apaga dados;
  - "Executar a infraestrutura" passa a citar `make infra` e `docker compose up -d postgres rabbitmq`.
- **`docs/01`:** estrutura com os Dockerfiles, o `Makefile` e o `scripts/demo.sh`, e o Mailpit no Compose.
- **`docs/04`:** nota do M12 com a decisão da consulta pela API e do `mail` no Compose.
- **`docs/02`:** só se a linha do RNF-07 precisar refletir o entregue.
- **Não entra:** Postman, relatório, auditoria global, release, CHANGELOG, versão, código de serviço, migrations e seeds.

## Risks / Trade-offs

- **[Build Maven dentro da imagem quebra por plugin de sessão, flatten ou JaCoCo]** → é o mesmo comando do smoke, `package -DskipTests`. A apply valida o build de uma imagem antes das outras e para se o reactor exigir fontes de módulos fora do `-am`.
- **[Primeiro build lento, com download de dependências]** → cache mount do BuildKit nas execuções seguintes. A validação do zero registra o tempo.
- **[Tag do Mailpit ou do Maven inexistente]** → tags fixadas no design; a apply para e exige `/opsx:update` (D2, D5).
- **[`down -v` apaga os dados do Compose local usados em desenvolvimento]** → `make demo` nunca executa `down -v`; a validação do zero só roda com autorização do gestor; `make reset` fica documentado como destrutivo.
- **[Notificação `unhealthy` com o Mailpit fora]** → é o comportamento desejado no Compose (D5); fora dele o padrão do M11 continua.
- **[Horário da consulta muito próximo da virada da janela ou do disparo]** → hora cheia seguinte mais duas horas, entre 2 e 3 horas à frente; testado por `RoteiroDeDemonstracaoTest` com instantes fixos.
- **[Relógio do container diferente do host]** → o horário é gerado a partir do relógio do host e comparado pelos serviços com o próprio relógio. Os containers usam o relógio do mesmo kernel e a diferença é desprezível perto da folga de 2 horas.
- **[Reexecução com consulta já lembrada devolve `lembretesEnviados` 0]** → o roteiro aceita `>= 0` e comprova pelo e-mail já presente no Mailpit.
- **[Portas 8081 a 8083, 8025, 5432, 5672 ou 15672 ocupadas no host]** → documentado. As da infraestrutura continuam configuráveis pelo `.env`; as das aplicações ficam fixas pela simplicidade da demonstração.
- **[GNU Make ausente na máquina da apply]** → `make --version` antes da task 5.2; ausente, a apply para e pede a instalação ao Gabriel. Os comandos sem `make` do README não substituem a evidência oficial.
- **[Outra consulta ativa do mesmo paciente confundida com a de demonstração]** → seleção determinística por paciente, médico, `observacoes == "demo"`, status e janela; consultas sem o marcador são ignoradas, e mais de uma consulta de demonstração termina com código 1.
- **[Guardas do M10 lerem os arquivos novos por engano]** → Dockerfiles, Compose e scripts ficam fora de `src/main` e dos `*IT`; o gate global confirma.
