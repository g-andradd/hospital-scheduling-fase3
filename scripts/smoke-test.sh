#!/usr/bin/env bash
#
# Smoke ponta a ponta do fluxo principal (M10, D10 a D13).
#
# Sobe PostgreSQL 16 e RabbitMQ 3.13 efemeros e exclusivos desta execucao, roda os tres
# servicos como processos e percorre: login do medico do seed V900, criacao de uma consulta
# futura, notificacao (registro persistido e log do canal) e leitura no historico via GraphQL.
# Nao usa Compose nem imagens das aplicacoes, e nao toca em recursos que nao criou.
#
# Codigos de saida: 0 sucesso; 1 verificacao divergente; 2 preflight; 3 prazo esgotado;
# 4 processo encerrado; 5 limpeza incompleta; 130 SIGINT; 143 SIGTERM.
#
# O fluxo leva um X-Correlation-Id derivado do RUN_ID e exige, nos logs JSON dos tres servicos (profile
# docker), o registro da consulta criada com esse mesmo correlationId (M11, D7).
#
# Uso:  scripts/smoke-test.sh
#       SMOKE_MANTER_LOGS=1 scripts/smoke-test.sh   (preserva o pacote de diagnostico tambem em sucesso)
#       JQ_BIN=/caminho/do/jq scripts/smoke-test.sh (executavel do jq; o padrao e jq)
#
# Carregado por `source`, so define as funcoes: o fluxo roda apenas quando executado.

set -Eeuo pipefail

# ------------------------------------------------------------------ guarda inicial

readonly SUCESSO=0 DIVERGENCIA=1 PREFLIGHT=2 PRAZO=3 PROCESSO=4 ORFAO=5
readonly LABEL="br.com.fiap.hospital.smoke"
readonly PREFIXO_RUNTIME="hospital-smoke."
readonly IMAGEM_POSTGRES="postgres:16"
readonly IMAGEM_RABBITMQ="rabbitmq:3.13-management"
readonly PACIENTE_ID="bbbbbbbb-0000-0000-0000-000000000001"
readonly MEDICO_ID="aaaaaaaa-0000-0000-0000-000000000001"
readonly USUARIO_MEDICO_ID="11111111-1111-1111-1111-111111111111"
readonly EMAIL_MEDICO="medico@hospital.com"
readonly EMAIL_PACIENTE="paciente@hospital.com"
readonly SENHA_DEMO="Senha@123"
readonly LOGGER_RELAY="br.com.fiap.hospital.agendamento.infrastructure.messaging.OutboxRelay"
readonly LOGGER_PROJECAO="br.com.fiap.hospital.historico.infrastructure.messaging.ConsumidorTransacionalDoHistorico"
readonly LOGGER_CANAL="br.com.fiap.hospital.notificacao.sender.LogNotificationSender"
readonly REGEX_UUID='^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'

# Executavel do jq: padrao jq; injetavel para os testes do roteiro, que nao dependem de jq instalado.
JQ_BIN="${JQ_BIN:-jq}"

RAIZ=""
RUN_ID=""
CORRELATION_ID=""
RUNTIME=""
RUNTIME_CRIADO=""
REDE=""
CONTAINER_POSTGRES=""
CONTAINER_RABBITMQ=""
VOLUME_POSTGRES=""
VOLUME_RABBITMQ=""
VOLUMES_REGISTRADOS=()
PIDS=()
declare -A NOME_DO_PID=()
declare -A JAR_DO_PID=()
declare -A LOG_DO_SERVICO=()
declare -A PORTA_DO_SERVICO=()
PG_USUARIO=""
PG_SENHA=""
PG_PORTA=""
RABBIT_USUARIO=""
RABBIT_SENHA=""
RABBIT_PORTA=""
JWT_SECRET_EFEMERO=""
TOKEN=""
ETAPA="inicio"
ULTIMA_RESPOSTA=""
LIMPEZA_INICIADA=0

# ------------------------------------------------------------------ utilitarios

registrar() {
    printf '[smoke %s] %s\n' "${RUN_ID:--}" "$*"
}

falhar() {
    local codigo="$1"
    shift
    printf '[smoke %s] FALHA na etapa "%s": %s\n' "${RUN_ID:--}" "$ETAPA" "$*" >&2
    if [[ -n "$ULTIMA_RESPOSTA" ]]; then
        printf '[smoke %s] ultima resposta: %s\n' "${RUN_ID:--}" "$(mascarar "${ULTIMA_RESPOSTA:0:2000}")" >&2
    fi
    exit "$codigo"
}

# Texto sem o token, o segredo JWT e as senhas desta execucao.
mascarar() {
    local texto="$1" segredo
    for segredo in "$TOKEN" "$JWT_SECRET_EFEMERO" "$PG_SENHA" "$RABBIT_SENHA"; do
        if [[ -n "$segredo" ]]; then
            texto="${texto//"$segredo"/***}"
        fi
    done
    printf '%s' "$texto"
}

# Saida do jq sem CR: o jq do Windows escreve CRLF.
jq_texto() {
    "$JQ_BIN" "$@" | tr -d '\r'
}

aleatorio_hex() {
    head -c "$1" /dev/urandom | od -An -tx1 | tr -d ' \n\r'
}

# ------------------------------------------------------------------ espera condicional (D11)

# aguardar [-l] <descricao> <prazo em segundos> <comando...>
#
# Reavalia o comando ate um prazo absoluto, com backoff de 0,25 s a 2 s. A cada iteracao confere
# que todos os processos da execucao continuam vivos (codigo 4). No prazo, codigo 3 com etapa,
# ultima resposta e logs. Com -l, usado na limpeza, nao confere processos e devolve 3 no prazo.
aguardar() {
    local limpeza=0
    if [[ "$1" == "-l" ]]; then
        limpeza=1
        shift
    fi
    local descricao="$1" prazo="$2"
    shift 2
    local limite=$((SECONDS + prazo)) intervalo="0.25"
    while true; do
        if ((limpeza == 0)); then
            verificar_processos
        fi
        if "$@"; then
            return 0
        fi
        if ((SECONDS >= limite)); then
            if ((limpeza == 1)); then
                return "$PRAZO"
            fi
            exibir_logs
            falhar "$PRAZO" "prazo de ${prazo}s esgotado aguardando ${descricao}"
        fi
        sleep "$intervalo"
        case "$intervalo" in
            0.25) intervalo="0.5" ;;
            0.5) intervalo="1" ;;
            *) intervalo="2" ;;
        esac
    done
}

verificar_processos() {
    local pid
    for pid in "${PIDS[@]}"; do
        if ! kill -0 "$pid" 2>/dev/null; then
            exibir_log "${NOME_DO_PID[$pid]}"
            falhar "$PROCESSO" "o processo ${NOME_DO_PID[$pid]} (PID $pid) deixou de executar"
        fi
    done
}

exibir_log() {
    local nome="$1" arquivo="${LOG_DO_SERVICO[$1]:-}"
    if [[ -n "$arquivo" && -f "$arquivo" ]]; then
        printf '[smoke %s] ---- log de %s (ultimas 80 linhas) ----\n' "${RUN_ID:--}" "$nome" >&2
        mascarar "$(tail -n 80 "$arquivo")" >&2
        printf '\n[smoke %s] ---- fim do log de %s ----\n' "${RUN_ID:--}" "$nome" >&2
    fi
}

exibir_logs() {
    local nome
    for nome in "${!LOG_DO_SERVICO[@]}"; do
        exibir_log "$nome"
    done
}

# ------------------------------------------------------------------ preflight (D10.2)

preflight() {
    local ausentes=() versao
    if ((BASH_VERSINFO[0] < 4)); then
        ausentes+=("bash 4 ou superior (encontrado ${BASH_VERSION})")
    fi
    local ferramenta
    for ferramenta in mktemp head od tr tail grep sed cut base64 date; do
        command -v "$ferramenta" >/dev/null 2>&1 || ausentes+=("$ferramenta")
    done
    if ! command -v docker >/dev/null 2>&1; then
        ausentes+=("docker")
    elif ! docker info >/dev/null 2>&1; then
        ausentes+=("daemon do docker acessivel")
    fi
    if ! command -v java >/dev/null 2>&1; then
        ausentes+=("java 21")
    else
        versao="$(java -version 2>&1 | tr -d '\r' | sed -n 's/.*version "\([0-9][0-9]*\).*/\1/p' | head -n 1)"
        if [[ ! "$versao" =~ ^[0-9]+$ ]] || ((versao < 21)); then
            ausentes+=("java 21 (encontrado: ${versao:-desconhecido})")
        fi
    fi
    command -v mvn >/dev/null 2>&1 || ausentes+=("maven")
    command -v curl >/dev/null 2>&1 || ausentes+=("curl")
    if ! command -v "$JQ_BIN" >/dev/null 2>&1; then
        ausentes+=("jq 1.6 ou superior")
    else
        versao="$("$JQ_BIN" --version 2>/dev/null | tr -d '\r')"
        if [[ ! "$versao" =~ ^jq-([0-9]+)\.([0-9]+) ]] \
            || ((BASH_REMATCH[1] < 1 || (BASH_REMATCH[1] == 1 && BASH_REMATCH[2] < 6))); then
            ausentes+=("jq 1.6 ou superior (encontrado: ${versao:-desconhecido})")
        fi
    fi
    if ((${#ausentes[@]} > 0)); then
        local item
        for item in "${ausentes[@]}"; do
            printf '[smoke] pre-requisito ausente: %s\n' "$item" >&2
        done
        exit "$PREFLIGHT"
    fi
    registrar "preflight aprovado: bash ${BASH_VERSION}, docker, java, maven, curl e ${versao}"
}

# ------------------------------------------------------------------ recursos da execucao (D10.3)

criar_runtime() {
    RUN_ID="$(date -u +%Y%m%d%H%M%S)-$(aleatorio_hex 3)"
    CORRELATION_ID="smoke-$RUN_ID"
    RUNTIME_CRIADO="$(mktemp -d "${TMPDIR:-/tmp}/${PREFIXO_RUNTIME}XXXXXXXX")"
    RUNTIME="$RUNTIME_CRIADO"
    runtime_valido || falhar "$DIVERGENCIA" "diretorio de runtime inesperado: $RUNTIME"
    registrar "RUN_ID=$RUN_ID runtime=$RUNTIME correlationId=$CORRELATION_ID"
}

# O runtime so e tratado como tal com o prefixo e o caminho devolvido pelo mktemp.
runtime_valido() {
    [[ -n "$RUNTIME" && "$RUNTIME" == "$RUNTIME_CRIADO" && "${RUNTIME##*/}" == "${PREFIXO_RUNTIME}"* && -d "$RUNTIME" ]]
}

# ------------------------------------------------------------------ build (D10.4)

construir() {
    ETAPA="build"
    registrar "build: mvn -q -DskipTests package -pl agendamento-service,notificacao-service,historico-service -am"
    if ! (cd "$RAIZ" && mvn -q -DskipTests package -pl agendamento-service,notificacao-service,historico-service -am) \
        >"$RUNTIME/build.log" 2>&1; then
        tail -n 60 "$RUNTIME/build.log" >&2
        falhar "$DIVERGENCIA" "o build falhou"
    fi
}

# Exatamente um *-exec.jar no target do servico.
jar_executavel() {
    local servico="$1" jars
    shopt -s nullglob
    jars=("$RAIZ/$servico/target/"*-exec.jar)
    shopt -u nullglob
    if ((${#jars[@]} != 1)); then
        falhar "$DIVERGENCIA" "esperado exatamente um *-exec.jar em $servico/target, encontrados ${#jars[@]}"
    fi
    printf '%s' "${jars[0]}"
}

# ------------------------------------------------------------------ infraestrutura (D10.6)

subir_infraestrutura() {
    ETAPA="infraestrutura"
    local prefixo="hospital-smoke-$RUN_ID"
    PG_USUARIO="smoke"
    PG_SENHA="$(aleatorio_hex 16)"
    RABBIT_USUARIO="smoke"
    RABBIT_SENHA="$(aleatorio_hex 16)"

    REDE="$prefixo"
    docker network create --label "$LABEL=$RUN_ID" "$REDE" >/dev/null
    VOLUME_POSTGRES="$prefixo-postgres-dados"
    docker volume create --label "$LABEL=$RUN_ID" "$VOLUME_POSTGRES" >/dev/null
    VOLUME_RABBITMQ="$prefixo-rabbitmq-dados"
    docker volume create --label "$LABEL=$RUN_ID" "$VOLUME_RABBITMQ" >/dev/null

    CONTAINER_POSTGRES="$prefixo-postgres"
    MSYS_NO_PATHCONV=1 docker create --name "$CONTAINER_POSTGRES" --label "$LABEL=$RUN_ID" --network "$REDE" \
        -p 127.0.0.1::5432 -e POSTGRES_USER="$PG_USUARIO" -e POSTGRES_PASSWORD="$PG_SENHA" -e POSTGRES_DB=postgres \
        -v "$VOLUME_POSTGRES:/var/lib/postgresql/data" "$IMAGEM_POSTGRES" >/dev/null
    (cd "$RAIZ/docker/postgres" && MSYS_NO_PATHCONV=1 docker cp init.sql "$CONTAINER_POSTGRES:/docker-entrypoint-initdb.d/init.sql")

    CONTAINER_RABBITMQ="$prefixo-rabbitmq"
    MSYS_NO_PATHCONV=1 docker create --name "$CONTAINER_RABBITMQ" --label "$LABEL=$RUN_ID" --network "$REDE" \
        -p 127.0.0.1::5672 -e RABBITMQ_DEFAULT_USER="$RABBIT_USUARIO" -e RABBITMQ_DEFAULT_PASS="$RABBIT_SENHA" \
        -v "$VOLUME_RABBITMQ:/var/lib/rabbitmq" "$IMAGEM_RABBITMQ" >/dev/null

    docker start "$CONTAINER_POSTGRES" "$CONTAINER_RABBITMQ" >/dev/null
    registrar_volumes
    PG_PORTA="$(porta_publicada "$CONTAINER_POSTGRES" 5432)"
    RABBIT_PORTA="$(porta_publicada "$CONTAINER_RABBITMQ" 5672)"
    registrar "postgres em 127.0.0.1:$PG_PORTA, rabbitmq em 127.0.0.1:$RABBIT_PORTA"

    aguardar "PostgreSQL com os tres bancos do init.sql" 120 postgres_pronto
    aguardar "RabbitMQ aceitando conexoes AMQP" 180 rabbitmq_pronto
}

# Volumes montados nos containers da execucao, inclusive anonimos, para a limpeza e os orfaos.
registrar_volumes() {
    local container nome
    for container in "$CONTAINER_POSTGRES" "$CONTAINER_RABBITMQ"; do
        while IFS= read -r nome; do
            nome="${nome//$'\r'/}"
            if [[ -n "$nome" ]]; then
                VOLUMES_REGISTRADOS+=("$nome")
            fi
        done < <(docker inspect -f '{{range .Mounts}}{{if .Name}}{{println .Name}}{{end}}{{end}}' "$container")
    done
}

porta_publicada() {
    local saida
    saida="$(docker port "$1" "$2/tcp" | tr -d '\r' | head -n 1)"
    [[ "${saida##*:}" =~ ^[0-9]+$ ]] || falhar "$DIVERGENCIA" "porta publicada de $1 nao identificada: $saida"
    printf '%s' "${saida##*:}"
}

psql_execucao() {
    local banco="$1" sql="$2"
    MSYS_NO_PATHCONV=1 docker exec -e PGPASSWORD="$PG_SENHA" "$CONTAINER_POSTGRES" \
        psql -h 127.0.0.1 -U "$PG_USUARIO" -d "$banco" -v ON_ERROR_STOP=1 -Atq -c "$sql" | tr -d '\r'
}

postgres_pronto() {
    local bancos
    bancos="$(psql_execucao postgres "SELECT count(*) FROM pg_database WHERE datname IN ('agendamento_db','notificacao_db','historico_db')" 2>/dev/null)" || return 1
    [[ "$bancos" == "3" ]]
}

rabbitmq_pronto() {
    MSYS_NO_PATHCONV=1 docker exec "$CONTAINER_RABBITMQ" rabbitmq-diagnostics -q check_port_connectivity >/dev/null 2>&1
}

# ------------------------------------------------------------------ servicos (D10.8 e D10.9)

registrar_processo() {
    local nome="$1" pid="$2" jar="$3"
    PIDS+=("$pid")
    NOME_DO_PID[$pid]="$nome"
    JAR_DO_PID[$pid]="$jar"
    registrar "$nome iniciado com PID $pid"
}

iniciar_servicos() {
    ETAPA="subida dos servicos"
    local jar_agendamento jar_notificacao jar_historico
    jar_agendamento="$(jar_executavel agendamento-service)"
    jar_notificacao="$(jar_executavel notificacao-service)"
    jar_historico="$(jar_executavel historico-service)"
    local base="jdbc:postgresql://127.0.0.1:$PG_PORTA"
    # 48 bytes aleatorios em hexadecimal: sem "/" inicial, que o MSYS converteria como caminho.
    JWT_SECRET_EFEMERO="$(aleatorio_hex 48)"

    LOG_DO_SERVICO[agendamento]="$RUNTIME/agendamento.log"
    SPRING_PROFILES_ACTIVE=demo,docker SERVER_PORT=0 \
        AGENDAMENTO_DB_URL="$base/agendamento_db" POSTGRES_USER="$PG_USUARIO" POSTGRES_PASSWORD="$PG_SENHA" \
        RABBITMQ_HOST=127.0.0.1 RABBITMQ_PORT="$RABBIT_PORTA" RABBITMQ_USER="$RABBIT_USUARIO" RABBITMQ_PASSWORD="$RABBIT_SENHA" \
        JWT_SECRET="$JWT_SECRET_EFEMERO" \
        java -jar "$jar_agendamento" >"${LOG_DO_SERVICO[agendamento]}" 2>&1 &
    registrar_processo agendamento "$!" "$jar_agendamento"

    LOG_DO_SERVICO[notificacao]="$RUNTIME/notificacao.log"
    SPRING_PROFILES_ACTIVE=docker SERVER_PORT=0 NOTIFICACAO_SENDER=log \
        NOTIFICACAO_DB_URL="$base/notificacao_db" NOTIFICACAO_DB_USER="$PG_USUARIO" NOTIFICACAO_DB_PASSWORD="$PG_SENHA" \
        RABBITMQ_HOST=127.0.0.1 RABBITMQ_PORT="$RABBIT_PORTA" RABBITMQ_USER="$RABBIT_USUARIO" RABBITMQ_PASSWORD="$RABBIT_SENHA" \
        JWT_SECRET="$JWT_SECRET_EFEMERO" \
        java -jar "$jar_notificacao" >"${LOG_DO_SERVICO[notificacao]}" 2>&1 &
    registrar_processo notificacao "$!" "$jar_notificacao"

    LOG_DO_SERVICO[historico]="$RUNTIME/historico.log"
    SPRING_PROFILES_ACTIVE=docker SERVER_PORT=0 \
        HISTORICO_DB_URL="$base/historico_db" HISTORICO_DB_USER="$PG_USUARIO" HISTORICO_DB_PASSWORD="$PG_SENHA" \
        RABBITMQ_HOST=127.0.0.1 RABBITMQ_PORT="$RABBIT_PORTA" RABBITMQ_USER="$RABBIT_USUARIO" RABBITMQ_PASSWORD="$RABBIT_SENHA" \
        JWT_SECRET="$JWT_SECRET_EFEMERO" \
        java -jar "$jar_historico" >"${LOG_DO_SERVICO[historico]}" 2>&1 &
    registrar_processo historico "$!" "$jar_historico"

    local nome
    for nome in agendamento notificacao historico; do
        aguardar "porta efetiva de $nome no log" 180 descobrir_porta "$nome"
        registrar "$nome escutando na porta ${PORTA_DO_SERVICO[$nome]}"
    done
}

# Portas distintas em [1, 65535] anunciadas pelo Tomcat no log, uma por linha, com LF ou CRLF.
portas_do_log() {
    local arquivo="$1" linha
    local -A vistas=()
    [[ -f "$arquivo" ]] || return 0
    while IFS= read -r linha || [[ -n "$linha" ]]; do
        linha="${linha//$'\r'/}"
        if [[ "$linha" =~ Tomcat\ started\ on\ port\ ([0-9]{1,5})([^0-9]|$) ]]; then
            local porta=$((10#${BASH_REMATCH[1]}))
            if ((porta >= 1 && porta <= 65535)); then
                vistas[$porta]=1
            fi
        fi
    done <"$arquivo"
    if ((${#vistas[@]} > 0)); then
        printf '%s\n' "${!vistas[@]}"
    fi
}

# Imprime a unica porta do log. Devolve 1 enquanto nao ha porta e 2 se ha mais de uma distinta.
porta_unica() {
    local portas
    portas="$(portas_do_log "$1")"
    if [[ -z "$portas" ]]; then
        return 1
    fi
    if [[ "$portas" == *$'\n'* ]]; then
        return 2
    fi
    printf '%s' "$portas"
}

descobrir_porta() {
    local nome="$1" porta codigo=0
    porta="$(porta_unica "${LOG_DO_SERVICO[$nome]}")" || codigo=$?
    case "$codigo" in
        0)
            PORTA_DO_SERVICO[$nome]="$porta"
            return 0
            ;;
        1) return 1 ;;
        *)
            exibir_log "$nome"
            falhar "$DIVERGENCIA" "o log de $nome anuncia mais de uma porta distinta"
            ;;
    esac
}

# ------------------------------------------------------------------ HTTP e JSON

# requisitar <metodo> <url> [corpo] [cabecalho...] -> preenche HTTP_STATUS, HTTP_CORPO e HTTP_CABECALHOS.
HTTP_STATUS=""
HTTP_CORPO=""
HTTP_CABECALHOS=""
requisitar() {
    local metodo="$1" url="$2" corpo="${3:-}" cabecalho
    shift 2
    if (($# > 0)); then
        shift
    fi
    HTTP_CORPO="$RUNTIME/resposta.json"
    HTTP_CABECALHOS="$RUNTIME/cabecalhos.txt"
    : >"$HTTP_CORPO"
    : >"$HTTP_CABECALHOS"
    local argumentos=(-sS --max-time 15 -o "$HTTP_CORPO" -D "$HTTP_CABECALHOS" -w '%{http_code}' -X "$metodo"
        -H 'Content-Type: application/json' -H 'Accept: application/json')
    if [[ -n "$TOKEN" ]]; then
        argumentos+=(-H "Authorization: Bearer $TOKEN")
    fi
    if [[ -n "$corpo" ]]; then
        argumentos+=(--data-binary "$corpo")
    fi
    for cabecalho in "$@"; do
        argumentos+=(-H "$cabecalho")
    done
    HTTP_STATUS="$(curl "${argumentos[@]}" "$url" 2>/dev/null | tr -d '\r')" || HTTP_STATUS="000"
    ULTIMA_RESPOSTA="$metodo $url -> HTTP $HTTP_STATUS $(tr -d '\r' <"$HTTP_CORPO" | head -c 2000)"
}

json_valido() {
    "$JQ_BIN" -e . "$1" >/dev/null 2>&1
}

exigir_json() {
    json_valido "$1" || falhar "$DIVERGENCIA" "$2: a resposta nao e JSON valido"
}

exigir() {
    local descricao="$1" filtro="$2"
    shift 2
    "$JQ_BIN" -e "$@" "$filtro" "$HTTP_CORPO" >/dev/null 2>&1 || falhar "$DIVERGENCIA" "$descricao"
}

url() {
    printf 'http://127.0.0.1:%s%s' "${PORTA_DO_SERVICO[$1]}" "$2"
}

# ------------------------------------------------------------------ readiness e fluxo (D11)

login_pronto() {
    local corpo
    corpo="$("$JQ_BIN" -cn --arg email "$EMAIL_MEDICO" --arg senha "$SENHA_DEMO" '{email: $email, senha: $senha}')"
    TOKEN=""
    requisitar POST "$(url agendamento /auth/login)" "$corpo"
    case "$HTTP_STATUS" in
        000) return 1 ;;
        200) ;;
        *) falhar "$DIVERGENCIA" "login respondeu HTTP $HTTP_STATUS" ;;
    esac
    exigir_json "$HTTP_CORPO" "login"
    exigir "login sem token ou com perfil diferente de MEDICO" \
        '(.accessToken | type == "string" and length > 0) and .perfil == "MEDICO"'
    TOKEN="$(jq_texto -r '.accessToken' "$HTTP_CORPO")"
}

# Claims do token conferidas com o seed V900.
conferir_claims() {
    local carga
    carga="$(printf '%s' "$TOKEN" | cut -d. -f2 | tr '_-' '/+')"
    while ((${#carga} % 4 != 0)); do
        carga+="="
    done
    printf '%s' "$carga" | base64 -d >"$RUNTIME/claims.json" 2>/dev/null \
        || falhar "$DIVERGENCIA" "carga do token nao decodificavel"
    "$JQ_BIN" -e --arg sub "$USUARIO_MEDICO_ID" --arg medico "$MEDICO_ID" --arg email "$EMAIL_MEDICO" \
        '.sub == $sub and .medicoId == $medico and .email == $email and .perfil == "MEDICO"' \
        "$RUNTIME/claims.json" >/dev/null 2>&1 || falhar "$DIVERGENCIA" "claims do token divergem do seed V900"
}

graphql() {
    local consulta="$1" variaveis='{}'
    if (($# > 1)); then
        variaveis="$2"
    fi
    requisitar POST "$(url historico /graphql)" "$("$JQ_BIN" -cn --arg q "$consulta" --argjson v "$variaveis" '{query: $q, variables: $v}')"
}

historico_pronto() {
    graphql 'query { __typename }'
    case "$HTTP_STATUS" in
        000) return 1 ;;
        200) ;;
        *) falhar "$DIVERGENCIA" "GraphQL do historico respondeu HTTP $HTTP_STATUS" ;;
    esac
    exigir_json "$HTTP_CORPO" "readiness do historico"
    exigir "readiness do historico com erros GraphQL" '(.errors | not) and .data.__typename == "Query"'
}

notificacao_pronta() {
    requisitar POST "$(url notificacao /internal/lembretes/executar)"
    case "$HTTP_STATUS" in
        000) return 1 ;;
        200) ;;
        *) falhar "$DIVERGENCIA" "disparo do lembrete respondeu HTTP $HTTP_STATUS" ;;
    esac
    exigir_json "$HTTP_CORPO" "readiness da notificacao"
    exigir "o lembrete enviou mensagens antes de a consulta existir" '.lembretesEnviados == 0'
}

aguardar_servicos_prontos() {
    ETAPA="readiness do agendamento"
    aguardar "login 200 com token" 120 login_pronto
    conferir_claims
    registrar "agendamento pronto: login do medico do seed com claims conferidas"
    ETAPA="readiness do historico"
    aguardar "GraphQL autenticado sem erros" 120 historico_pronto
    registrar "historico pronto"
    ETAPA="readiness da notificacao"
    aguardar "lembrete com zero envios" 120 notificacao_pronta
    registrar "notificacao pronta"
}

CONSULTA_ID=""
DATA_HORA=""
OBSERVACOES=""

criar_consulta() {
    ETAPA="criacao da consulta"
    OBSERVACOES="smoke-$RUN_ID"
    DATA_HORA="$(jq_texto -nr 'now + 30 * 86400 | floor | . - (. % 3600) | todate')"
    local corpo
    corpo="$("$JQ_BIN" -cn --arg paciente "$PACIENTE_ID" --arg medico "$MEDICO_ID" --arg registrante "$USUARIO_MEDICO_ID" \
        --arg dataHora "$DATA_HORA" --arg observacoes "$OBSERVACOES" \
        '{pacienteId: $paciente, medicoId: $medico, registradoPorId: $registrante, dataHora: $dataHora,
          duracaoMinutos: 30, observacoes: $observacoes}')"
    requisitar POST "$(url agendamento /api/v1/consultas)" "$corpo" "X-Correlation-Id: $CORRELATION_ID"
    [[ "$HTTP_STATUS" == "201" ]] || falhar "$DIVERGENCIA" "criacao da consulta respondeu HTTP $HTTP_STATUS, esperado 201"
    exigir_json "$HTTP_CORPO" "criacao da consulta"
    exigir "consulta criada com campos divergentes" \
        "$JQ_INSTANTE"' .pacienteId == $paciente and .medicoId == $medico and .status == "AGENDADA"
          and .observacoes == $observacoes and (.dataHora | instante) == ($dataHora | instante)' \
        --arg paciente "$PACIENTE_ID" --arg medico "$MEDICO_ID" --arg observacoes "$OBSERVACOES" --arg dataHora "$DATA_HORA"
    CONSULTA_ID="$(jq_texto -r '.id' "$HTTP_CORPO")"
    [[ "$CONSULTA_ID" =~ $REGEX_UUID ]] || falhar "$DIVERGENCIA" "id da consulta nao e UUID: $CONSULTA_ID"
    local location
    location="$(tr -d '\r' <"$HTTP_CABECALHOS" | sed -n 's/^[Ll]ocation:[[:space:]]*//p' | tail -n 1)"
    [[ "$location" == */api/v1/consultas/"$CONSULTA_ID" ]] \
        || falhar "$DIVERGENCIA" "Location divergente: '$location'"
    local devolvido
    devolvido="$(tr -d '\r' <"$HTTP_CABECALHOS" | sed -n 's/^[Xx]-[Cc]orrelation-[Ii]d:[[:space:]]*//p' | tail -n 1)"
    [[ "$devolvido" == "$CORRELATION_ID" ]] \
        || falhar "$DIVERGENCIA" "X-Correlation-Id devolvido '$devolvido', esperado '$CORRELATION_ID'"
    registrar "consulta $CONSULTA_ID criada para $DATA_HORA com observacoes $OBSERVACOES e X-Correlation-Id devolvido"
}

# Instante de um DateTime ISO-8601 com Z ou deslocamento, em segundos.
readonly JQ_INSTANTE='def instante: capture("^(?<base>[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2})(?<segundos>:[0-9]{2})?(\\.[0-9]+)?(?<tz>Z|[+-][0-9]{2}:[0-9]{2})$")
  | (.base + (.segundos // ":00") + "Z" | fromdateiso8601)
    - (if .tz == "Z" then 0
       else (if .tz[0:1] == "-" then -1 else 1 end) * ((.tz[1:3] | tonumber) * 3600 + (.tz[4:6] | tonumber) * 60) end);'

# ------------------------------------------------------------------ registros de log correlacionados (M11, D7)

# Para cada registro JSON do logger e da consulta da execucao, uma linha "igual|completo" ou
# "divergente|completo": a correlacao comparada com a enviada, e completo quando ha instante,
# nivel, origem, mensagem e servico. Linhas nao JSON, como o banner, sao ignoradas.
readonly JQ_REGISTRO_DO_FLUXO='rtrimstr("\r") | fromjson? | select(type == "object" and .logger_name == $logger
    and (if $mensagem == "" then ((.message // "") | tostring | contains($consulta)) else .message == $mensagem end))
  | (if .correlationId == $correlacao then "igual" else "divergente" end) + "|"
    + ([."@timestamp", .level, .logger_name, .message, .service] | all(type == "string" and length > 0) | tostring)'

# registro_do_fluxo <arquivo> <logger> <consulta> <correlacao> [mensagem exata]
# 0 com o registro presente e correto; 1 enquanto ausente; registro com outro correlationId ou sem os
# campos exigidos e falha imediata, codigo 1.
registro_do_fluxo() {
    local arquivo="$1" logger="$2" consulta="$3" correlacao="$4" mensagem="${5:-}" resultado
    [[ -f "$arquivo" ]] || return 1
    resultado="$(jq_texto -R -r --arg logger "$logger" --arg consulta "$consulta" --arg correlacao "$correlacao" \
        --arg mensagem "$mensagem" "$JQ_REGISTRO_DO_FLUXO" "$arquivo")" || return 1
    ULTIMA_RESPOSTA="registros de $logger da consulta $consulta: ${resultado//$'\n'/, }"
    [[ -n "$resultado" ]] || return 1
    if [[ $'\n'"$resultado" == *$'\n'divergente* ]]; then
        falhar "$DIVERGENCIA" "registro de $logger da consulta $consulta com correlationId diferente de $correlacao"
    fi
    if [[ "$resultado" == *"|false"* ]]; then
        falhar "$DIVERGENCIA" "registro de $logger da consulta $consulta sem instante, nivel, origem, mensagem ou servico"
    fi
    return 0
}

# registro_correlacionado <arquivo> <logger> <consulta> <correlacao> <prazo> [mensagem exata]
# Espera o registro ate o prazo: ausente no prazo e codigo 3; divergente e codigo 1.
registro_correlacionado() {
    local arquivo="$1" logger="$2" consulta="$3" correlacao="$4" prazo="$5" mensagem="${6:-}"
    aguardar "registro JSON de ${logger##*.} da consulta $consulta com correlationId $correlacao" "$prazo" \
        registro_do_fluxo "$arquivo" "$logger" "$consulta" "$correlacao" "$mensagem"
}

verificar_publicacao() {
    ETAPA="correlacao no agendamento"
    registro_correlacionado "${LOG_DO_SERVICO[agendamento]}" "$LOGGER_RELAY" "$CONSULTA_ID" "$CORRELATION_ID" 60
    registrar "agendamento: evento da consulta publicado pelo relay com correlationId $CORRELATION_ID"
}

# ------------------------------------------------------------------ notificacao (D12)

NOTIFICACOES=""

notificacao_persistida() {
    NOTIFICACOES="$(psql_execucao notificacao_db "SELECT coalesce(json_agg(json_build_object('tipo', tipo, 'canal', canal,
        'destinatario', destinatario, 'conteudo', conteudo)), '[]') FROM notificacao_enviada WHERE consulta_id = '$CONSULTA_ID'")" \
        || return 1
    ULTIMA_RESPOSTA="notificacao_enviada da consulta $CONSULTA_ID: $NOTIFICACOES"
    [[ "$(jq_texto -n --argjson n "$NOTIFICACOES" '$n | length')" != "0" ]]
}

verificar_notificacao() {
    ETAPA="notificacao"
    aguardar "registro persistido da notificacao da consulta" 90 notificacao_persistida
    "$JQ_BIN" -e -n --argjson n "$NOTIFICACOES" --arg destinatario "$EMAIL_PACIENTE" \
        '$n | length == 1 and (.[0] | .tipo == "CONSULTA_CRIADA" and .canal == "LOG" and .destinatario == $destinatario
          and (.conteudo | length > 0))' >/dev/null \
        || falhar "$DIVERGENCIA" "notificacao persistida divergente: esperado exatamente um CONSULTA_CRIADA por LOG para $EMAIL_PACIENTE"
    local agenda
    agenda="$(psql_execucao notificacao_db "SELECT status FROM agenda_local WHERE consulta_id = '$CONSULTA_ID'")"
    [[ "$agenda" == "AGENDADA" ]] || falhar "$DIVERGENCIA" "agenda_local da consulta com status '$agenda', esperado AGENDADA"
    local conteudo linha
    conteudo="$(jq_texto -r -n --argjson n "$NOTIFICACOES" '$n[0].conteudo')"
    linha="Notificacao para $EMAIL_PACIENTE: Consulta agendada | $conteudo"
    ETAPA="correlacao na notificacao"
    registro_correlacionado "${LOG_DO_SERVICO[notificacao]}" "$LOGGER_CANAL" "$CONSULTA_ID" "$CORRELATION_ID" 30 "$linha"
    registrar "notificacao comprovada: registro persistido e registro JSON do canal de log com o mesmo conteudo e correlationId"
}

# ------------------------------------------------------------------ historico (D11)

# Valida uma resposta GraphQL: JSON invalido ou erro diferente de NOT_FOUND e falha (codigo 1);
# NOT_FOUND devolve 1, "ainda nao"; sem erros devolve 0.
validar_resposta_graphql() {
    local status="$1" arquivo="$2"
    [[ "$status" == "200" ]] || falhar "$DIVERGENCIA" "GraphQL respondeu HTTP $status"
    json_valido "$arquivo" || falhar "$DIVERGENCIA" "a resposta GraphQL nao e JSON valido"
    if "$JQ_BIN" -e '(.errors // []) | length == 0' "$arquivo" >/dev/null; then
        return 0
    fi
    if "$JQ_BIN" -e '[.errors[] | (.extensions.code // .extensions.classification // "")] | all(. == "NOT_FOUND")' \
        "$arquivo" >/dev/null; then
        return 1
    fi
    falhar "$DIVERGENCIA" "erro GraphQL com status de sucesso: $(jq_texto -c '[.errors[] | (.extensions.code // .extensions.classification)]' "$arquivo")"
}

consulta_no_historico() {
    graphql 'query ($id: ID!) { consulta(id: $id) { id pacienteId medicoId status observacoes dataHora } }' \
        "$("$JQ_BIN" -cn --arg id "$CONSULTA_ID" '{id: $id}')"
    if [[ "$HTTP_STATUS" == "000" ]]; then
        return 1
    fi
    validar_resposta_graphql "$HTTP_STATUS" "$HTTP_CORPO"
}

verificar_historico() {
    ETAPA="historico via GraphQL"
    aguardar "consulta no historico" 90 consulta_no_historico
    exigir "consulta no historico divergente" \
        "$JQ_INSTANTE"' .data.consulta | .id == $id and .pacienteId == $paciente and .medicoId == $medico
          and .status == "AGENDADA" and .observacoes == $observacoes and (.dataHora | instante) == ($dataHora | instante)' \
        --arg id "$CONSULTA_ID" --arg paciente "$PACIENTE_ID" --arg medico "$MEDICO_ID" \
        --arg observacoes "$OBSERVACOES" --arg dataHora "$DATA_HORA"
    ETAPA="correlacao no historico"
    registro_correlacionado "${LOG_DO_SERVICO[historico]}" "$LOGGER_PROJECAO" "$CONSULTA_ID" "$CORRELATION_ID" 30
    registrar "historico comprovado: consulta $CONSULTA_ID AGENDADA com o mesmo instante e observacoes, projetada com correlationId"
}

# ------------------------------------------------------------------ limpeza (D13)

# O PID so e sinalizado se consta de jobs -p e, onde houver linha de comando, se ela contem o *-exec.jar.
processo_da_execucao() {
    local pid="$1" jobs_ativos comando=""
    [[ "$pid" =~ ^[0-9]+$ ]] || return 1
    jobs_ativos="$(jobs -p)"
    [[ $'\n'"$jobs_ativos"$'\n' == *$'\n'"$pid"$'\n'* ]] || return 1
    local jar="${JAR_DO_PID[$pid]:-}"
    [[ -n "$jar" ]] || return 1
    if [[ -r "/proc/$pid/cmdline" ]]; then
        comando="$(tr '\0' ' ' <"/proc/$pid/cmdline")"
    elif ps -o args= -p "$pid" >/dev/null 2>&1; then
        comando="$(ps -o args= -p "$pid")"
    else
        return 0
    fi
    [[ "$comando" == *"${jar##*/}"* ]]
}

processo_encerrado() {
    ! kill -0 "$1" 2>/dev/null
}

encerrar_processos() {
    local pid
    for pid in "${PIDS[@]}"; do
        if processo_encerrado "$pid"; then
            continue
        fi
        if ! processo_da_execucao "$pid"; then
            printf '[smoke %s] PID %s nao confirmado como desta execucao: nao sinalizado\n' "${RUN_ID:--}" "$pid" >&2
            continue
        fi
        kill -TERM "$pid" 2>/dev/null || true
        if ! aguardar -l "encerramento do PID $pid" 20 processo_encerrado "$pid"; then
            if processo_da_execucao "$pid"; then
                kill -KILL "$pid" 2>/dev/null || true
                aguardar -l "encerramento forcado do PID $pid" 10 processo_encerrado "$pid" || true
            fi
        fi
        wait "$pid" 2>/dev/null || true
    done
}

# Remove container ou rede somente pelo nome exato e com o valor completo do label.
remover_container() {
    local nome="$1" nome_real label
    [[ -n "$nome" && -n "$RUN_ID" && "$nome" == "hospital-smoke-$RUN_ID-"* ]] || return 0
    nome_real="$(docker inspect --type container -f '{{.Name}}' "$nome" 2>/dev/null | tr -d '\r')" || return 0
    label="$(docker inspect --type container -f "{{ index .Config.Labels \"$LABEL\" }}" "$nome" 2>/dev/null | tr -d '\r')"
    if [[ "$nome_real" == "/$nome" && "$label" == "$RUN_ID" ]]; then
        docker rm -f -v "$nome" >/dev/null 2>&1 || true
    fi
}

remover_rede() {
    local nome_real label
    [[ -n "$REDE" && -n "$RUN_ID" && "$REDE" == "hospital-smoke-$RUN_ID" ]] || return 0
    nome_real="$(docker network inspect -f '{{.Name}}' "$REDE" 2>/dev/null | tr -d '\r')" || return 0
    label="$(docker network inspect -f "{{ index .Labels \"$LABEL\" }}" "$REDE" 2>/dev/null | tr -d '\r')"
    if [[ "$nome_real" == "$REDE" && "$label" == "$RUN_ID" ]]; then
        docker network rm "$REDE" >/dev/null 2>&1 || true
    fi
}

remover_volumes() {
    local nome label
    for nome in "$VOLUME_POSTGRES" "$VOLUME_RABBITMQ"; do
        [[ -n "$nome" && -n "$RUN_ID" && "$nome" == "hospital-smoke-$RUN_ID-"* ]] || continue
        label="$(docker volume inspect -f "{{ index .Labels \"$LABEL\" }}" "$nome" 2>/dev/null | tr -d '\r')" || continue
        if [[ "$label" == "$RUN_ID" ]]; then
            docker volume rm "$nome" >/dev/null 2>&1 || true
        fi
    done
}

remover_runtime() {
    if runtime_valido; then
        rm -rf -- "$RUNTIME"
    fi
}

promover_diagnostico() {
    [[ -n "$RUN_ID" ]] && runtime_valido || return 0
    local destino="${TMPDIR:-/tmp}/hospital-smoke-diagnostico-$RUN_ID" arquivo container
    mkdir -p "$destino"
    for arquivo in "$RUNTIME"/*; do
        [[ -f "$arquivo" ]] || continue
        mascarar "$(cat "$arquivo")" >"$destino/${arquivo##*/}"
    done
    for container in "$CONTAINER_POSTGRES" "$CONTAINER_RABBITMQ"; do
        if [[ -n "$container" ]] && docker inspect --type container "$container" >/dev/null 2>&1; then
            mascarar "$(docker logs --tail 200 "$container" 2>&1)" >"$destino/${container}.log"
        fi
    done
    {
        printf 'etapa: %s\n' "$ETAPA"
        printf 'ultima resposta: %s\n' "$(mascarar "$ULTIMA_RESPOSTA")"
        printf 'processos: %s\n' "${PIDS[*]:-}"
        printf 'containers com o label: %s\n' "$(docker ps -a -q --filter "label=$LABEL=$RUN_ID" 2>/dev/null | wc -l | tr -d ' ')"
    } >"$destino/resumo.txt"
    printf '[smoke %s] pacote de diagnostico: %s\n' "$RUN_ID" "$destino" >&2
}

verificar_orfaos() {
    local residuos=() pid volume
    if [[ -n "$RUN_ID" ]]; then
        [[ -z "$(docker ps -a -q --filter "label=$LABEL=$RUN_ID" 2>&1 | tr -d '\r')" ]] || residuos+=("containers com o label")
        [[ -z "$(docker network ls -q --filter "label=$LABEL=$RUN_ID" 2>&1 | tr -d '\r')" ]] || residuos+=("rede com o label")
        [[ -z "$(docker volume ls -q --filter "label=$LABEL=$RUN_ID" 2>&1 | tr -d '\r')" ]] || residuos+=("volumes com o label")
        for volume in "${VOLUMES_REGISTRADOS[@]}"; do
            if docker volume inspect "$volume" >/dev/null 2>&1; then
                residuos+=("volume $volume")
            fi
        done
    fi
    for pid in "${PIDS[@]}"; do
        if kill -0 "$pid" 2>/dev/null; then
            residuos+=("processo $pid")
        fi
    done
    if [[ -n "$RUNTIME_CRIADO" && -e "$RUNTIME_CRIADO" ]]; then
        residuos+=("runtime $RUNTIME_CRIADO")
    fi
    if ((${#residuos[@]} > 0)); then
        printf '[smoke %s] limpeza incompleta: %s\n' "${RUN_ID:--}" "${residuos[*]}" >&2
        return 1
    fi
    if [[ -n "$RUN_ID" ]]; then
        registrar "limpeza verificada: nenhum container, rede, volume, processo ou runtime restante"
    fi
}

on_exit() {
    local codigo=$?
    if ((LIMPEZA_INICIADA)); then
        return
    fi
    LIMPEZA_INICIADA=1
    set +e
    trap '' INT TERM
    if ((codigo != SUCESSO)) || [[ "${SMOKE_MANTER_LOGS:-}" == "1" ]]; then
        promover_diagnostico
    fi
    encerrar_processos
    remover_container "$CONTAINER_POSTGRES"
    remover_container "$CONTAINER_RABBITMQ"
    remover_rede
    remover_volumes
    remover_runtime
    if ! verificar_orfaos; then
        codigo=$ORFAO
    fi
    if [[ -n "$RUN_ID" ]]; then
        printf '[smoke %s] codigo de saida: %s\n' "$RUN_ID" "$codigo"
    fi
    exit "$codigo"
}

# ------------------------------------------------------------------ fluxo

main() {
    trap on_exit EXIT
    trap 'exit 130' INT
    trap 'exit 143' TERM

    preflight

    local diretorio="${BASH_SOURCE[0]%/*}"
    if [[ "$diretorio" == "${BASH_SOURCE[0]}" ]]; then
        diretorio="."
    fi
    RAIZ="$(cd "$diretorio/.." && pwd)"
    criar_runtime
    construir
    subir_infraestrutura
    iniciar_servicos
    aguardar_servicos_prontos
    criar_consulta
    verificar_publicacao
    verificar_notificacao
    verificar_historico
    ETAPA="concluido"
    registrar "SMOKE APROVADO: login, consulta, notificacao, historico e correlationId $CORRELATION_ID nos tres logs comprovados"
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
    main "$@"
fi
