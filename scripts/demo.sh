#!/usr/bin/env bash
set -Eeuo pipefail

readonly AGENDAMENTO_URL="${AGENDAMENTO_URL:-http://localhost:8081}"
readonly NOTIFICACAO_URL="${NOTIFICACAO_URL:-http://localhost:8082}"
readonly HISTORICO_URL="${HISTORICO_URL:-http://localhost:8083}"
readonly MAILPIT_URL="${MAILPIT_URL:-http://localhost:8025}"
readonly PACIENTE_ID="bbbbbbbb-0000-0000-0000-000000000001"
readonly MEDICO_ID="aaaaaaaa-0000-0000-0000-000000000001"
readonly ENFERMEIRO_ID="22222222-2222-2222-2222-222222222222"
readonly PACIENTE_EMAIL="paciente@hospital.com"
readonly TIMEOUT="${DEMO_TIMEOUT_SECONDS:-120}"

ULTIMA_RESPOSTA=""
TOKEN=""
CONSULTA_ID=""
CONSULTA_ORIGEM=""
EMAIL_AGENDAMENTO_ID=""
EMAIL_LEMBRETE_ID=""
LEMBRETES_ENVIADOS=""
AGORA_EPOCH=""
ATE_EPOCH=""
DE_ISO=""
ATE_ISO=""

log() {
  printf '[demo] %s\n' "$*"
}

falhar() {
  local codigo="$1" etapa="$2"
  printf '[demo] falha em %s\n' "$etapa" >&2
  if [[ -n "${ULTIMA_RESPOSTA:-}" ]]; then
    printf '[demo] ultima resposta: %s\n' "$ULTIMA_RESPOSTA" >&2
  fi
  exit "$codigo"
}

exigir_comando() {
  command -v "$1" >/dev/null 2>&1 || {
    printf '[demo] pre-requisito ausente: %s\n' "$2" >&2
    return 2
  }
}

preflight() {
  exigir_comando docker 'Docker' || return 2
  docker compose version >/dev/null 2>&1 || {
    printf '[demo] pre-requisito ausente: Docker Compose v2\n' >&2
    return 2
  }
  exigir_comando curl 'curl' || return 2
  exigir_comando jq 'jq 1.6 ou superior' || return 2
  exigir_comando date 'date com suporte a UTC' || return 2

  local versao major minor
  versao="$(jq --version 2>/dev/null | sed 's/^jq-//')"
  major="${versao%%.*}"
  minor="${versao#*.}"
  minor="${minor%%[^0-9]*}"
  if [[ ! "$major" =~ ^[0-9]+$ || ! "$minor" =~ ^[0-9]+$ ]] \
      || (( major < 1 || (major == 1 && minor < 6) )); then
    printf '[demo] pre-requisito ausente: jq 1.6 ou superior (encontrado: %s)\n' "${versao:-desconhecido}" >&2
    return 2
  fi
}

horario_da_consulta() {
  local agora="${1:?informe o instante em segundos}"
  local alvo=$(( ((agora / 3600) + 1) * 3600 + 7200 ))
  date -u -d "@$alvo" '+%Y-%m-%dT%H:%M:%SZ'
}

iso_do_epoch() {
  date -u -d "@$1" '+%Y-%m-%dT%H:%M:%SZ'
}

aguardar_http() {
  local etapa="$1" url="$2" inicio=$SECONDS
  while (( SECONDS - inicio < TIMEOUT )); do
    if ULTIMA_RESPOSTA="$(curl -fsS --connect-timeout 3 --max-time 10 "$url" 2>&1)"; then
      return 0
    fi
    sleep 1
  done
  falhar 3 "$etapa"
}

aguardar_email() {
  local assunto="$1" etapa="$2" inicio=$SECONDS resposta identificador
  while (( SECONDS - inicio < TIMEOUT )); do
    if resposta="$(curl -fsS --get "$MAILPIT_URL/api/v1/search" \
        --data-urlencode "query=to:$PACIENTE_EMAIL subject:\"$assunto\"" 2>&1)"; then
      ULTIMA_RESPOSTA="$resposta"
      identificador="$(jq -r '.messages[0].ID // empty' <<<"$resposta")"
      if [[ -n "$identificador" ]]; then
        printf '%s' "$identificador"
        return 0
      fi
    else
      ULTIMA_RESPOSTA="$resposta"
    fi
    sleep 1
  done
  falhar 3 "$etapa"
}

listar_consultas_demo() {
  local resposta
  resposta="$(curl -fsS --get "$AGENDAMENTO_URL/api/v1/consultas" \
    -H "Authorization: Bearer $TOKEN" \
    --data-urlencode "pacienteId=$PACIENTE_ID" \
    --data-urlencode "medicoId=$MEDICO_ID" \
    --data-urlencode 'status=AGENDADA,CONFIRMADA' \
    --data-urlencode "de=$DE_ISO" \
    --data-urlencode "ate=$ATE_ISO" \
    --data-urlencode 'tamanho=100' 2>&1)" || {
      ULTIMA_RESPOSTA="$resposta"
      falhar 1 'listar consultas de demonstracao'
    }
  ULTIMA_RESPOSTA="$resposta"
  jq -c \
    --arg paciente "$PACIENTE_ID" \
    --arg medico "$MEDICO_ID" \
    --argjson de "$AGORA_EPOCH" \
    --argjson ate "$ATE_EPOCH" \
    '[.conteudo[] | select(
      .pacienteId == $paciente and
      .medicoId == $medico and
      .observacoes == "demo" and
      (.status == "AGENDADA" or .status == "CONFIRMADA") and
      ((.dataHora | fromdateiso8601) > $de) and
      ((.dataHora | fromdateiso8601) <= $ate)
    )]' <<<"$resposta" || falhar 1 'interpretar consultas de demonstracao'
}

autenticar_enfermeiro() {
  local resposta
  resposta="$(curl -fsS -X POST "$AGENDAMENTO_URL/auth/login" \
    -H 'Content-Type: application/json' \
    --data '{"email":"enfermeiro@hospital.com","senha":"Senha@123"}' 2>&1)" || {
      ULTIMA_RESPOSTA="$resposta"
      falhar 1 'autenticar enfermeiro'
    }
  ULTIMA_RESPOSTA="$resposta"
  TOKEN="$(jq -er 'select(.perfil == "ENFERMEIRO") | .accessToken | select(length > 0)' <<<"$resposta")" \
    || falhar 1 'validar token do enfermeiro'
}

selecionar_ou_criar_consulta() {
  local candidatas quantidade resposta horario
  candidatas="$(listar_consultas_demo)"
  quantidade="$(jq -r 'length' <<<"$candidatas")"
  if (( quantidade > 1 )); then
    ULTIMA_RESPOSTA="$candidatas"
    falhar 1 'selecionar consulta de demonstracao: mais de uma candidata'
  fi
  if (( quantidade == 1 )); then
    CONSULTA_ID="$(jq -r '.[0].id' <<<"$candidatas")"
    CONSULTA_ORIGEM='reaproveitada'
    return 0
  fi

  horario="$(horario_da_consulta "$AGORA_EPOCH")"
  resposta="$(curl -fsS -X POST "$AGENDAMENTO_URL/api/v1/consultas" \
    -H "Authorization: Bearer $TOKEN" \
    -H 'Content-Type: application/json' \
    --data "{\"pacienteId\":\"$PACIENTE_ID\",\"medicoId\":\"$MEDICO_ID\",\"registradoPorId\":\"$ENFERMEIRO_ID\",\"dataHora\":\"$horario\",\"duracaoMinutos\":30,\"observacoes\":\"demo\"}" 2>&1)" || {
      ULTIMA_RESPOSTA="$resposta"
      falhar 1 'criar consulta de demonstracao'
    }
  ULTIMA_RESPOSTA="$resposta"
  CONSULTA_ID="$(jq -er '.id | select(length > 0)' <<<"$resposta")" \
    || falhar 1 'validar consulta criada'
  CONSULTA_ORIGEM='criada'
}

aguardar_historico() {
  local inicio=$SECONDS resposta documento
  documento="$(jq -cn --arg id "$CONSULTA_ID" \
    '{query:"query($id: ID!) { consulta(id: $id) { id pacienteId medicoId status observacoes dataHora } }",variables:{id:$id}}')"
  while (( SECONDS - inicio < TIMEOUT )); do
    if resposta="$(curl -fsS -X POST "$HISTORICO_URL/graphql" \
        -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' --data "$documento" 2>&1)"; then
      ULTIMA_RESPOSTA="$resposta"
      if jq -e --arg id "$CONSULTA_ID" '.data.consulta.id == $id and ((.errors // []) | length == 0)' \
          <<<"$resposta" >/dev/null; then
        return 0
      fi
    else
      ULTIMA_RESPOSTA="$resposta"
    fi
    sleep 1
  done
  falhar 3 'aguardar consulta no historico GraphQL'
}

disparar_lembrete() {
  local resposta
  resposta="$(curl -fsS -X POST "$NOTIFICACAO_URL/internal/lembretes/executar" \
    -H "Authorization: Bearer $TOKEN" 2>&1)" || {
      ULTIMA_RESPOSTA="$resposta"
      falhar 1 'disparar lembrete D-1'
    }
  ULTIMA_RESPOSTA="$resposta"
  LEMBRETES_ENVIADOS="$(jq -er '.lembretesEnviados | select(. >= 0)' <<<"$resposta")" \
    || falhar 1 'validar resultado do lembrete D-1'
}

confirmar_contagem_final() {
  local candidatas quantidade id
  candidatas="$(listar_consultas_demo)"
  quantidade="$(jq -r 'length' <<<"$candidatas")"
  id="$(jq -r '.[0].id // empty' <<<"$candidatas")"
  if [[ "$quantidade" != '1' || "$id" != "$CONSULTA_ID" ]]; then
    ULTIMA_RESPOSTA="$candidatas"
    falhar 1 'confirmar uma unica consulta de demonstracao'
  fi
}

resumo() {
  cat <<EOF

[demo] Ambiente pronto
[demo] consultaId=$CONSULTA_ID origem=$CONSULTA_ORIGEM lembretesEnviados=$LEMBRETES_ENVIADOS
[demo] emailAgendamentoId=$EMAIL_AGENDAMENTO_ID emailLembreteId=$EMAIL_LEMBRETE_ID
[demo] Swagger:  $AGENDAMENTO_URL/swagger-ui.html
[demo] GraphiQL: $HISTORICO_URL/graphiql
[demo] Mailpit:  $MAILPIT_URL
[demo] RabbitMQ: http://localhost:15672  (hospital / hospital)
[demo] Usuários: medico@hospital.com, enfermeiro@hospital.com, paciente@hospital.com
[demo] Senha: Senha@123
EOF
}

main() {
  if [[ "${1:-}" == 'preflight' ]]; then
    preflight
    return
  fi
  preflight || exit $?

  AGORA_EPOCH="${DEMO_AGORA:-$(date -u +%s)}"
  [[ "$AGORA_EPOCH" =~ ^[0-9]+$ ]] || {
    ULTIMA_RESPOSTA="$AGORA_EPOCH"
    falhar 1 'validar DEMO_AGORA'
  }
  ATE_EPOCH=$((AGORA_EPOCH + 86400))
  DE_ISO="$(iso_do_epoch "$AGORA_EPOCH")"
  ATE_ISO="$(iso_do_epoch "$ATE_EPOCH")"

  log 'verificando saúde dos serviços'
  aguardar_http 'health do agendamento' "$AGENDAMENTO_URL/actuator/health"
  aguardar_http 'health da notificação' "$NOTIFICACAO_URL/actuator/health"
  aguardar_http 'health do histórico' "$HISTORICO_URL/actuator/health"
  aguardar_http 'health do Mailpit' "$MAILPIT_URL/readyz"

  log 'autenticando enfermeiro de demonstração'
  autenticar_enfermeiro
  selecionar_ou_criar_consulta
  log "consulta $CONSULTA_ORIGEM: $CONSULTA_ID"

  EMAIL_AGENDAMENTO_ID="$(aguardar_email 'Consulta agendada' 'aguardar e-mail de agendamento')"
  aguardar_historico
  disparar_lembrete
  EMAIL_LEMBRETE_ID="$(aguardar_email 'Lembrete de consulta' 'aguardar e-mail de lembrete')"
  confirmar_contagem_final
  resumo
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  main "$@"
fi
