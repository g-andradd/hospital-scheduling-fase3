#!/usr/bin/env bash
#
# Auditoria de requisitos do Tech Challenge Fase 3 (M14).
#
# Percorre todos os RF e RNF declarados em docs/02-especificacao-funcional.md e confere,
# para cada um, se a evidencia versionada existe e esta ancorada no elemento declarado.
#
# NAO CONFUNDIR com a auditoria de execucao do build, que e outra coisa: aquela verifica
# que todas as suites do reactor executaram integralmente, sem filtro, omissao ou
# tolerancia, e roda dentro do ciclo Maven. Esta aqui e uma auditoria de REQUISITOS,
# estatica, sobre os arquivos versionados do repositorio.
#
# O que OK significa:
#   OK = a evidencia versionada daquele requisito existe, e legivel e contem a ancora
#        literal declarada no catalogo.
#   OK NAO significa que o teste passou agora, que a cobertura foi medida agora ou que o
#        ambiente subiu agora. As execucoes que comprovam comportamento sao evidencia
#        separada, registrada fora desta auditoria.
#
# Um requisito pode ter varias ancoras, uma por clausula normativa do seu texto. O
# resultado e a conjuncao: faltando qualquer uma, o requisito inteiro reprova.
#
# Somente leitura: nao sobe container, nao alcanca banco nem broker, nao executa o build,
# nao depende de rede e nao escreve, move ou remove arquivo algum.
#
# Uso:  scripts/auditoria.sh [raiz-do-repositorio]
#       A raiz e opcional; o padrao e o diretorio acima deste script.
#
# Codigos de saida:
#   0  todos os requisitos aprovados
#   1  ao menos um requisito reprovado: artefato ausente ou ancora nao encontrada
#   2  pre-requisito ausente
#   3  inventario inconsistente: fora da sequencia fechada, duplicado, requisito sem
#      entrada no catalogo ou entrada orfa
#   4  especificacao funcional ilegivel ou secao nao encontrada
#
# Carregado por `source`, so define as funcoes: o fluxo roda apenas quando executado.

set -Eeuo pipefail

readonly SUCESSO=0
readonly REPROVADO=1
readonly PREFLIGHT=2
readonly INVENTARIO=3
readonly ILEGIVEL=4

readonly CABECALHO='REQUISITO | STATUS | EVIDÊNCIA'
readonly SEMANTICA='OK = evidência versionada presente e ancorada; não afirma reexecução de comportamento.'
readonly NOTA='Execuções que comprovam comportamento — build, smoke e ambiente — são evidência separada.'
readonly APROVADO='OK'
readonly ROTULO_REPROVADO='FALHA'
readonly ENTRE_ANCORAS=' ;; '
readonly ENTRE_ARTEFATO_E_ANCORA='::'
readonly RELATIVO_DA_ESPECIFICACAO='docs/02-especificacao-funcional.md'
readonly TOTAL_RF=20
readonly TOTAL_RNF=10

RAIZ="${1:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
ESPECIFICACAO="$RAIZ/$RELATIVO_DA_ESPECIFICACAO"

IDS=()
SECOES=()
INVENTARIADOS=0
APROVADOS=0
REPROVADOS=0
ANCORAS=0

# O catalogo liga cada requisito as suas evidencias. Cada linha e um par:
#   <requisito>|<artefato relativo a raiz>|<ancora literal dentro do artefato>
# Um quarto campo, quando presente, declara compartilhamento legitimo de um par entre
# requisitos distintos. Os valores abaixo sao DADO, nao comando: nomes de ferramenta
# aparecem aqui porque sao o conteudo do artefato ou o proprio caminho dele.
# CATALOGO-INICIO
CATALOGO=$(cat <<'FIM_DO_CATALOGO'
RF-01|agendamento-service/src/test/java/br/com/fiap/hospital/agendamento/application/AutenticarUsuarioUseCaseTest.java|void autenticacaoBemSucedida()
RF-01|shared-security/src/test/java/br/com/fiap/hospital/security/JwtServiceTest.java|void tokenCarregaAIdentidade()
RF-02|agendamento-service/src/test/java/br/com/fiap/hospital/agendamento/domain/PerfilUsuarioTest.java|void existemOsTresPerfis()
RF-03|shared-security/src/test/java/br/com/fiap/hospital/security/RespostaDeSegurancaTest.java|void semTokenRecebe401()
RF-03|shared-security/src/test/java/br/com/fiap/hospital/security/JwtServiceTest.java|void tokenDeOutroSegredoERecusado()
RF-04|shared-security/src/test/java/br/com/fiap/hospital/security/RespostaDeSegurancaTest.java|void semPermissaoRecebe403()
RF-04|agendamento-service/src/test/java/br/com/fiap/hospital/agendamento/integracao/MatrizDeAutorizacaoIT.java|void celulaRespondeConformeODocumento(
RF-04|agendamento-service/src/test/java/br/com/fiap/hospital/agendamento/integracao/MatrizDeAutorizacaoIT.java|void matrizCobreVinteEUmaCelulas()
RF-05|agendamento-service/src/test/java/br/com/fiap/hospital/agendamento/application/AgendarConsultaUseCaseTest.java|void consultaRegistradaComSucesso()
RF-05|agendamento-service/src/test/java/br/com/fiap/hospital/agendamento/infrastructure/web/ConsultaControllerTest.java|void registro()
RF-06|agendamento-service/src/test/java/br/com/fiap/hospital/agendamento/application/AtualizarConsultaUseCaseTest.java|void remarcacaoBemSucedida()
RF-06|agendamento-service/src/test/java/br/com/fiap/hospital/agendamento/application/AtualizarConsultaUseCaseTest.java|void alteracaoSoDeHorarioPreservaObservacoes()
RF-06|agendamento-service/src/test/java/br/com/fiap/hospital/agendamento/infrastructure/web/ConsultaControllerTest.java|void alteracao()
RF-07|agendamento-service/src/test/java/br/com/fiap/hospital/agendamento/application/CicloDeVidaDaConsultaUseCaseTest.java|void cancelamentoBemSucedido(
RF-07|agendamento-service/src/test/java/br/com/fiap/hospital/agendamento/application/CicloDeVidaDaConsultaUseCaseTest.java|void cancelamentoSemMotivoERecusado(
RF-08|agendamento-service/src/test/java/br/com/fiap/hospital/agendamento/application/AgendarConsultaUseCaseTest.java|void registroNoPassadoERecusado()
RF-08|agendamento-service/src/test/java/br/com/fiap/hospital/agendamento/infrastructure/web/ConsultaControllerTest.java|void agendamentoNoPassado()
RF-09|agendamento-service/src/test/java/br/com/fiap/hospital/agendamento/application/AgendarConsultaUseCaseTest.java|void conflitoComAgendaDoMedicoERecusado()
RF-09|agendamento-service/src/test/java/br/com/fiap/hospital/agendamento/application/AgendarConsultaUseCaseTest.java|void conflitoComAgendaDoPacienteERecusado()
RF-09|agendamento-service/src/test/java/br/com/fiap/hospital/agendamento/infrastructure/web/ConsultaControllerTest.java|void conflitoDeAgenda()
RF-10|agendamento-service/src/test/java/br/com/fiap/hospital/agendamento/domain/StatusConsultaTest.java|void cobreTodasAsCombinacoes(
RF-10|agendamento-service/src/test/java/br/com/fiap/hospital/agendamento/infrastructure/web/ConsultaControllerTest.java|void transicaoInvalida()
RF-11|historico-service/src/test/java/br/com/fiap/hospital/historico/integracao/FiltroHistoricoIT.java|void periodoTodasNaoRecortaPeloRelogio()
RF-11|historico-service/src/test/java/br/com/fiap/hospital/historico/integracao/FiltroHistoricoIT.java|void periodoFuturasDevolveApenasOQueNaoPassou()
RF-11|historico-service/src/test/java/br/com/fiap/hospital/historico/integracao/FiltroHistoricoIT.java|void periodoPassadasDevolveApenasOQueOcorreu()
RF-11|historico-service/src/test/java/br/com/fiap/hospital/historico/integracao/FiltroHistoricoIT.java|void filtroPorStatusSelecionaOsValoresInformados()
RF-12|historico-service/src/test/java/br/com/fiap/hospital/historico/integracao/AutorizacaoHistoricoIT.java|void consultasDoPacienteAutenticadoVemDoToken()
RF-12|historico-service/src/test/java/br/com/fiap/hospital/historico/integracao/AutorizacaoHistoricoIT.java|void pacienteNaoAlcancaRegistroDeTerceiro()
RF-12|historico-service/src/test/java/br/com/fiap/hospital/historico/integracao/AutorizacaoHistoricoIT.java|void pacienteNaoLePorIdRegistroAlheio()
RF-13|historico-service/src/test/java/br/com/fiap/hospital/historico/integracao/CorrecaoHistoricoIT.java|void correcaoValidaAlteraSnapshotERegistraTrilha()
RF-13|historico-service/src/test/java/br/com/fiap/hospital/historico/integracao/CorrecaoHistoricoIT.java|void autorDaCorrecaoVemDoToken()
RF-14|historico-service/src/test/java/br/com/fiap/hospital/historico/integracao/MatrizDeAutorizacaoGraphqlIT.java|void celulaDaMatrizEHonrada(
RF-14|historico-service/src/test/java/br/com/fiap/hospital/historico/integracao/MatrizDeAutorizacaoGraphqlIT.java|void leituraEncontrouAMatrizCompleta()
RF-15|agendamento-service/src/test/java/br/com/fiap/hospital/agendamento/application/PublicacaoDeEventosUseCaseTest.java|void registroPublicaEvento()
RF-15|agendamento-service/src/test/java/br/com/fiap/hospital/agendamento/application/PublicacaoDeEventosUseCaseTest.java|void cadaMudancaPublicaOEventoCorrespondente()
RF-15|agendamento-service/src/test/java/br/com/fiap/hospital/agendamento/integracao/OutboxRelayIT.java|void ackSemReturnPublicaPersistente()
RF-16|notificacao-service/src/test/java/br/com/fiap/hospital/notificacao/integracao/NotificacaoReativaIT.java|void criacaoNotificaOAgendamento()
RF-16|notificacao-service/src/test/java/br/com/fiap/hospital/notificacao/integracao/NotificacaoReativaIT.java|void atualizacaoNotificaAAlteracao()
RF-17|notificacao-service/src/test/java/br/com/fiap/hospital/notificacao/integracao/JanelaDoLembreteIT.java|void consultaA23h59Recebe()
RF-17|notificacao-service/src/test/java/br/com/fiap/hospital/notificacao/integracao/JanelaDoLembreteIT.java|void consultaA24h01NaoRecebe()
RF-17|notificacao-service/src/test/java/br/com/fiap/hospital/notificacao/scheduler/AgendadorDeLembretesTest.java|void semConfiguracaoAVarreduraEHoraria()
RF-18|historico-service/src/test/java/br/com/fiap/hospital/historico/integracao/ProjecaoHistoricoIT.java|void sequenciaCompletaAtualizaSnapshotETrilha()
RF-18|historico-service/src/test/java/br/com/fiap/hospital/historico/integracao/ProjecaoHistoricoIT.java|void todosOsTiposNormativosSaoProjetaveis()
RF-19|notificacao-service/src/test/java/br/com/fiap/hospital/notificacao/integracao/IdempotenciaNotificacaoIT.java|void reentregaNaoNotificaDuasVezes()
RF-19|historico-service/src/test/java/br/com/fiap/hospital/historico/integracao/IdempotenciaHistoricoIT.java|void reentregaSequencialNaoDuplicaEfeito()
RF-20|shared-contracts/src/test/java/br/com/fiap/hospital/contracts/TopologiaRabbitMqIT.java|void rejeicaoPreservaCorpoChaveEMarcaXDeath()
RF-20|notificacao-service/src/test/java/br/com/fiap/hospital/notificacao/integracao/ConfiguracaoNotificacaoIT.java|void retryERejeicaoSeguemOContrato()
RNF-01|shared-security/src/main/java/br/com/fiap/hospital/security/SegurancaAutoConfiguration.java|BCryptPasswordEncoder
RNF-01|agendamento-service/src/test/java/br/com/fiap/hospital/agendamento/application/AutenticarUsuarioUseCaseTest.java|void identidadeNaoCarregaSenha()
RNF-01|shared-security/src/test/java/br/com/fiap/hospital/security/JwtServiceTest.java|void tokenNaoCarregaSenha()
RNF-01|agendamento-service/src/test/java/br/com/fiap/hospital/agendamento/integracao/SenhaForaDosLogsIT.java|void loginNaoEscreveSenhaNemHashNoLog()
RNF-02|shared-security/src/main/java/br/com/fiap/hospital/security/SegurancaAutoConfiguration.java|SessionCreationPolicy.STATELESS
RNF-02|agendamento-service/src/test/java/br/com/fiap/hospital/agendamento/integracao/CadeiaDeSegurancaIT.java|void nenhumaSessaoECriada()
RNF-03|agendamento-service/src/test/java/br/com/fiap/hospital/agendamento/infrastructure/web/CoberturaDoMapaDeErrosTest.java|void todaExcecaoDeDominioTemTratador()
RNF-03|agendamento-service/src/test/java/br/com/fiap/hospital/agendamento/infrastructure/web/CoberturaDoMapaDeErrosTest.java|void todaExcecaoDeMvcEstaCoberta()
RNF-03|agendamento-service/src/test/java/br/com/fiap/hospital/agendamento/infrastructure/web/CoberturaDoMapaDeErrosTest.java|void nenhumTypeSeRepete()
RNF-04|quality-gates/src/main/java/br/com/fiap/hospital/qualidade/VerificadorDeCobertura.java|PISO_GLOBAL = 85
RNF-04|quality-gates/pom.xml|<id>gate-de-cobertura</id>
RNF-04|quality-gates/src/test/java/br/com/fiap/hospital/qualidade/LigacaoDosGatesTest.java|void ordemDosGates()
RNF-05|agendamento-service/src/test/java/br/com/fiap/hospital/agendamento/arquitetura/ArquiteturaDoAgendamentoTest.java|void direcaoDasCamadas()
RNF-05|agendamento-service/src/test/java/br/com/fiap/hospital/agendamento/arquitetura/ArquiteturaDoAgendamentoTest.java|void dominioSemFramework()
RNF-05|agendamento-service/src/test/java/br/com/fiap/hospital/agendamento/arquitetura/ArquiteturaDoAgendamentoTest.java|void importaSoOCodigoPrincipal()
RNF-06|quality-gates/src/test/java/br/com/fiap/hospital/qualidade/InfraestruturaRealTest.java|void reactorReal()
RNF-06|agendamento-service/src/test/java/br/com/fiap/hospital/agendamento/integracao/InfraestruturaRealAgendamentoIT.java|void provaPostgreSql()
RNF-06|agendamento-service/src/test/java/br/com/fiap/hospital/agendamento/integracao/InfraestruturaRealAgendamentoIT.java|void provaRabbitMq()
RNF-07|Makefile|docker compose up -d --build --wait
RNF-07|quality-gates/src/test/java/br/com/fiap/hospital/qualidade/AmbienteDeDemonstracaoTest.java|void configuracaoRealAtende()
RNF-08|agendamento-service/src/test/java/br/com/fiap/hospital/agendamento/integracao/CorrelacaoHttpIT.java|void identificadorRecebidoEHonrado()
RNF-08|notificacao-service/src/test/java/br/com/fiap/hospital/notificacao/integracao/CorrelacaoNotificacaoIT.java|void correlacaoValeDuranteOProcessamento()
RNF-08|historico-service/src/test/java/br/com/fiap/hospital/historico/integracao/CorrelacaoHistoricoIT.java|void correlacaoValeDuranteAProjecao()
RNF-08|scripts/smoke-test.sh|registro_correlacionado()
RNF-09|agendamento-service/src/test/java/br/com/fiap/hospital/agendamento/integracao/SchemaVersionadoIT.java|void provisionamentoCriaTabelasEIndices()
RNF-09|agendamento-service/src/test/java/br/com/fiap/hospital/agendamento/integracao/SchemaVersionadoIT.java|void hibernateNaoGeraSchema()
RNF-10|quality-gates/src/test/java/br/com/fiap/hospital/qualidade/AmbienteDeDemonstracaoTest.java|deve usar exclusivamente
RNF-10|docker/postgres/init.sql|CREATE DATABASE historico_db;
FIM_DO_CATALOGO
)
# CATALOGO-FIM

registrar() {
    printf '%s\n' "$*"
}

falhar() {
    local codigo="$1"
    shift
    printf '[auditoria] %s\n' "$*" >&2
    exit "$codigo"
}

preflight() {
    if [[ -z "${BASH_VERSINFO:-}" ]]; then
        falhar "$PREFLIGHT" 'pre-requisito ausente: Bash 4 ou superior'
    fi
    if [[ "${BASH_VERSINFO[0]}" -lt 4 ]]; then
        falhar "$PREFLIGHT" "pre-requisito ausente: Bash 4 ou superior (encontrado ${BASH_VERSINFO[0]})"
    fi
    if [[ ! -d "$RAIZ" ]]; then
        falhar "$PREFLIGHT" "raiz inexistente: $RAIZ"
    fi
    if [[ ! -r "$ESPECIFICACAO" ]]; then
        falhar "$ILEGIVEL" "especificacao ilegivel: $ESPECIFICACAO"
    fi
}

# Le os identificadores das tabelas de docs/02, preservando a ordem e a subsecao de origem.
inventariar() {
    local estado='fora' secao='' linha
    IDS=()
    SECOES=()
    while IFS= read -r linha || [[ -n "$linha" ]]; do
        if [[ "$linha" == '## 1. Requisitos funcionais' ]]; then
            estado='dentro'
            secao='Requisitos funcionais'
            continue
        fi
        if [[ "$linha" == '## 2. Requisitos não funcionais' ]]; then
            estado='dentro'
            secao='Requisitos não funcionais'
            continue
        fi
        if [[ "$linha" == '## 3. '* ]]; then
            estado='fora'
            continue
        fi
        if [[ "$estado" != 'dentro' ]]; then
            continue
        fi
        if [[ "$linha" == '### '* ]]; then
            secao="${linha:4}"
            continue
        fi
        if [[ "$linha" =~ ^\|[[:space:]]*(RF|RNF)-([0-9][0-9])[[:space:]]*\| ]]; then
            IDS+=("${BASH_REMATCH[1]}-${BASH_REMATCH[2]}")
            SECOES+=("$secao")
        fi
    done < "$ESPECIFICACAO"

    if [[ "${#IDS[@]}" -eq 0 ]]; then
        falhar "$ILEGIVEL" "nenhuma tabela de requisitos encontrada em $RELATIVO_DA_ESPECIFICACAO"
    fi
    INVENTARIADOS="${#IDS[@]}"
}

# A sequencia e fechada: exatamente RF-01..RF-20 e RNF-01..RNF-10, continuos, unicos e na ordem.
exigir_sequencia_fechada() {
    local vistos=' ' id indice=0 numero esperado total
    for id in "${IDS[@]}"; do
        if [[ "$vistos" == *" $id "* ]]; then
            falhar "$INVENTARIO" "identificador repetido no inventario: $id"
        fi
        vistos="$vistos$id "
    done

    total=$((TOTAL_RF + TOTAL_RNF))
    for ((numero = 1; numero <= TOTAL_RF; numero++)); do
        esperado="$(printf 'RF-%02d' "$numero")"
        if [[ "${IDS[$indice]:-}" != "$esperado" ]]; then
            falhar "$INVENTARIO" "sequencia fechada quebrada: esperado $esperado na posicao $((indice + 1)), encontrado ${IDS[$indice]:-nenhum}"
        fi
        indice=$((indice + 1))
    done
    for ((numero = 1; numero <= TOTAL_RNF; numero++)); do
        esperado="$(printf 'RNF-%02d' "$numero")"
        if [[ "${IDS[$indice]:-}" != "$esperado" ]]; then
            falhar "$INVENTARIO" "sequencia fechada quebrada: esperado $esperado na posicao $((indice + 1)), encontrado ${IDS[$indice]:-nenhum}"
        fi
        indice=$((indice + 1))
    done
    if [[ "$INVENTARIADOS" -ne "$total" ]]; then
        falhar "$INVENTARIO" "inventario com $INVENTARIADOS requisitos; a sequencia fechada tem $total"
    fi
}

entradas_do_requisito() {
    local requisito="$1"
    printf '%s\n' "$CATALOGO" | grep -E "^${requisito}\|" || true
}

# Cruza catalogo e inventario nos dois sentidos antes de abrir qualquer artefato.
exigir_catalogo_coerente() {
    local id linha requisito conhecidos=' '
    for id in "${IDS[@]}"; do
        conhecidos="$conhecidos$id "
    done
    for id in "${IDS[@]}"; do
        if [[ -z "$(entradas_do_requisito "$id")" ]]; then
            falhar "$INVENTARIO" "requisito sem evidencia declarada no catalogo: $id"
        fi
    done
    while IFS= read -r linha; do
        if [[ -z "$linha" ]]; then
            continue
        fi
        requisito="${linha%%|*}"
        if [[ "$conhecidos" != *" $requisito "* ]]; then
            falhar "$INVENTARIO" "entrada orfa no catalogo: $requisito nao existe em $RELATIVO_DA_ESPECIFICACAO"
        fi
    done <<< "$CATALOGO"
}

# O unico verificador: o artefato existe, e legivel e contem a ancora literal declarada.
verificar_par() {
    local artefato="$1" ancora="$2" caminho
    caminho="$RAIZ/$artefato"
    if [[ ! -f "$caminho" ]]; then
        printf 'artefato ausente: %s' "$artefato"
        return 1
    fi
    if [[ ! -r "$caminho" ]]; then
        printf 'artefato ilegivel: %s' "$artefato"
        return 1
    fi
    if ! grep -qF -- "$ancora" "$caminho"; then
        printf 'ancora nao encontrada em %s: %s' "$artefato" "$ancora"
        return 1
    fi
    return 0
}

auditar() {
    local indice=0 id secao secao_atual='' linha artefato ancora evidencia motivo falhou diagnostico
    APROVADOS=0
    REPROVADOS=0
    ANCORAS=0

    registrar "Auditoria de requisitos — $RAIZ"
    registrar "$SEMANTICA"
    registrar "$NOTA"
    registrar ''
    registrar "$CABECALHO"

    while [[ "$indice" -lt "${#IDS[@]}" ]]; do
        id="${IDS[$indice]}"
        secao="${SECOES[$indice]}"
        if [[ "$secao" != "$secao_atual" ]]; then
            registrar ''
            registrar "== $secao"
            secao_atual="$secao"
        fi

        evidencia=''
        motivo=''
        falhou=0
        while IFS= read -r linha; do
            if [[ -z "$linha" ]]; then
                continue
            fi
            artefato="$(printf '%s' "$linha" | cut -d '|' -f 2)"
            ancora="$(printf '%s' "$linha" | cut -d '|' -f 3)"
            ANCORAS=$((ANCORAS + 1))
            if diagnostico="$(verificar_par "$artefato" "$ancora")"; then
                if [[ -n "$evidencia" ]]; then
                    evidencia="$evidencia$ENTRE_ANCORAS"
                fi
                evidencia="$evidencia$artefato$ENTRE_ARTEFATO_E_ANCORA$ancora"
            else
                falhou=1
                if [[ -n "$motivo" ]]; then
                    motivo="$motivo$ENTRE_ANCORAS"
                fi
                motivo="$motivo$diagnostico"
            fi
        done <<< "$(entradas_do_requisito "$id")"

        if [[ "$falhou" -eq 0 ]]; then
            APROVADOS=$((APROVADOS + 1))
            registrar "$id | $APROVADO | $evidencia"
        else
            REPROVADOS=$((REPROVADOS + 1))
            registrar "$id | $ROTULO_REPROVADO | $motivo"
        fi
        indice=$((indice + 1))
    done

    registrar ''
    registrar "RESUMO | inventariados=$INVENTARIADOS | aprovados=$APROVADOS | reprovados=$REPROVADOS | ancoras=$ANCORAS"
}

main() {
    preflight
    inventariar
    exigir_sequencia_fechada
    exigir_catalogo_coerente
    auditar
    if [[ "$REPROVADOS" -ne 0 ]]; then
        printf '[auditoria] %s requisito(s) reprovado(s)\n' "$REPROVADOS" >&2
        exit "$REPROVADO"
    fi
    exit "$SUCESSO"
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
    main "$@"
fi
