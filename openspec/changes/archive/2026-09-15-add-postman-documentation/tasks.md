## 1. Gate estrutural do pacote Postman (D2, D3, D7)

- [x] 1.1 Criar `ColecaoPostmanTest` no `quality-gates`, lendo os dois caminhos normativos em UTF-8 e falhando fechado quando um arquivo estiver ausente ou vazio. Verificar que o teste compila sem acrescentar dependência ao POM e que ainda fica vermelho pela ausência dos artefatos reais.
- [x] 1.2 Implementar as verificações da collection: schema v2.1; exatamente cinco pastas de primeiro nível, com nomes e ordem do D3; todo request com URL e body quando o método/operação exigir; quatro logins; quatro tokens distintos; limpeza inicial; superfícies REST, GraphQL e lembrete; códigos 401/403/422/409/404/400; retry limitado; ao menos dois `pm.test()` por request; nenhum request, token ou segredo de infraestrutura literal fora do environment. Admitir somente a URI normativa de `info.schema` como metadado externo. Verificar o caso positivo com uma fixture sintética mínima.
- [x] 1.3 Implementar as verificações do environment: URLs e credenciais `demo` do `docs/02`, ids de usuário/paciente/médico do V900, e valores vazios para tokens, `consultaId`, horários, `runId` e contador de retry. Verificar que valores derivados preenchidos e segredo não pertencente ao seed ficam vermelhos.
- [x] 1.4 Acrescentar negativos sintéticos mudando uma invariante por teste: pasta removida/reordenada, login sem salvar token, limpeza inicial ausente, token compartilhado, request sem asserção de status, request sem asserção de campo, código de erro ausente, retry ilimitado, URL de request externa e valor derivado persistido. Incluir controle positivo com `info.schema` oficial. Verificar nome da violação em cada caso e que a fixture positiva continua verde.

## 2. Collection e environment (D2, D3, D4)

- [x] 2.1 Criar `postman/Hospital-Scheduling-Local.postman_environment.json` no formato exportável, com as três URLs locais, as quatro credenciais públicas, ids fixos do seed e variáveis derivadas vazias. Verificar a task 1.3 verde e conferir que nenhum JWT, `JWT_SECRET`, senha de banco/broker ou valor de `.env` aparece no arquivo.
- [x] 2.2 Criar `postman/Hospital-Scheduling-Fase3.postman_collection.json`, schema v2.1, com as cinco pastas e requests numerados na ordem do D3. Verificar os dois JSON com `docker run --rm --entrypoint node`, montando `postman/` somente para leitura e aplicando `JSON.parse` a cada arquivo; a importação/executabilidade completa fica para a 5.3 com ambiente ativo.
- [x] 2.3 Implementar `00-Auth`: o pre-request do primeiro login limpa quatro tokens e todos os valores derivados, cria `runId` novo, e os quatro `POST /auth/login` validam status 200, `accessToken`, `expiresIn` e `perfil`, salvando um token diferente. Verificar inclusive com valores anteriores preenchidos que nenhum sobrevive e que não há Authorization literal nem reutilização da variável de outro perfil.
- [x] 2.4 Implementar a seleção do slot no início de `01-Agendamento`: data a sete dias, tentativas de uma hora e limite 24; 201 salva `consultaId`/horário/observação, 409 válido repete, qualquer outra resposta falha. Verificar por teste estrutural o limite e, em runtime, registrar se houve retry.
- [x] 2.5 Completar `01-Agendamento` com listagem, busca pelo id salvo, alteração e confirmação, usando perfis permitidos e `pm.test()` de status e campos em todos. Verificar que todos apontam para o mesmo `consultaId` e que a consulta está confirmada com a observação atualizada antes de iniciar a pasta GraphQL.

## 3. GraphQL, lembrete, segurança e erros (D3, D5)

- [x] 3.1 Implementar em `02-Historico-GraphQL` a leitura `consulta(id:)` com token do enfermeiro, retry para `NOT_FOUND` ou snapshot intermediário, 250 ms entre tentativas e limite 20. Verificar que sucesso exige ausência de `errors`, id, status `CONFIRMADA` e observação atualizada pela jornada REST; simular estruturalmente limite removido e código inesperado.
- [x] 3.2 Acrescentar a mutation `corrigirRegistroHistorico` com token médico e justificativa/observação ligadas ao `runId`, seguida de leitura que repete até observar a correção. Verificar campos de `data` e ausência de `errors` nas duas respostas finais.
- [x] 3.3 Acrescentar o `POST /internal/lembretes/executar` com token do enfermeiro, validando status 200 e `lembretesEnviados` numérico e não negativo. Não afirmar envio para uma consulta fora da janela D-1.
- [x] 3.4 Implementar `03-Cenarios-de-Seguranca`: request protegido sem token com 401, criação pelo paciente com 403 e leitura da consulta da execução pelo segundo paciente com 403. Verificar status, `type`, `title`, `correlationId` e ausência de detalhe interno conforme o contrato de cada fronteira.
- [x] 3.5 Implementar `04-Cenarios-de-Erro`: 422 para data passada, 409 por sobreposição ao período ativo salvo, 404 para UUID válido inexistente e 400 para UUID malformado. Após o 409, executar GET e comparar id, horário, duração e status com o snapshot salvo; como último request, cancelar a consulta original com motivo explícito e confirmar `CANCELADA`, liberando o período ativo.
- [x] 3.6 Fazer a auditoria final de cada item da collection: todo request precisa ter uma asserção explícita de status/código e outra de campos; nenhum script pode silenciar exceção, aceitar `5xx`, usar token literal ou depender de edição manual. Verificar `ColecaoPostmanTest` completo verde.

## 4. Documentação e ADRs (D8, D9)

- [x] 4.1 Criar `DocumentacaoFinalTest` no `quality-gates`: verificar no README início rápido, `make demo`, quatro credenciais, três URLs, catálogo dos métodos/caminhos REST e operações GraphQL, dois arquivos Postman, instrução do Runner, um bloco `flowchart`, um `sequenceDiagram` e links locais existentes; verificar nos ADRs exatamente 001–007, as cinco seções uma vez e na ordem, índice e links para os archives do D9.
- [x] 4.2 Acrescentar negativos sintéticos, um por desvio: link local quebrado, instrução Postman ausente, segundo Mermaid ausente, ADR faltante, seção renomeada/duplicada/fora de ordem, índice incompleto e archive incorreto. Verificar uma violação específica por mutação e o conteúdo real ainda vermelho antes das edições documentais.
- [x] 4.3 Reorganizar o começo do `README.md` como porta de entrada: sumário, arquitetura, início rápido do zero, credenciais, URLs, catálogo conciso de todos os endpoints REST e operações GraphQL e seção Postman com importação e execução no aplicativo e no Newman. Preservar os detalhes técnicos já validados de gate, smoke, operação e garantias; não criar relatório/roteiro do M14.
- [x] 4.4 Manter o flowchart existente coerente e acrescentar um `sequenceDiagram` Mermaid com cliente, agendamento, PostgreSQL/outbox, RabbitMQ, notificação/Mailpit e histórico/GraphQL. Verificar automaticamente delimitadores, tipos, participantes e relações esperadas; conferir visualmente a renderização no preview do GitHub na task 6.6.
- [x] 4.5 Uniformizar os títulos de seção dos sete ADRs para `Contexto`, `Decisão`, `Alternativas`, `Consequências` e `Status`, nessa ordem, sem remover decisões, alternativas ou consequências. Acrescentar em cada `Status` o link relativo para o archive de origem definido em D9; conferir o diff para que a consolidação não reescreva a decisão.
- [x] 4.6 Criar `docs/adr/README.md` com 001–007 em ordem, título, status, decisão resumida, change de origem e links para ADR/archive. Verificar todos os destinos locais e o `DocumentacaoFinalTest` completo verde.

## 5. Execução real da collection (D4, D5, D6)

- [x] 5.1 Confirmar `docker manifest inspect postman/newman:6.1.3-alpine`, executar `docker run --rm postman/newman:6.1.3-alpine --version` e registrar a versão. Se a imagem/tag não existir ou não executar Newman 6.1.3, parar e exigir `/opsx:update`; não trocar tag durante a apply.
- [x] 5.2 Executar `make demo` sem `down -v`, confirmar os seis serviços `healthy` e a rede `hospital-fase3_hospital-net`; não alterar nem apagar os volumes do ambiente para esta validação.
- [x] 5.3 Executar a collection pelo container Newman, com `--rm`, mount `postman/` somente leitura, rede Compose, environment versionado, três URLs sobrescritas para os nomes dos serviços e `--bail`. Registrar código 0, quantidade de requests/assertions, zero falhas, quatro tokens obtidos, `consultaId` criado e correção observada.
- [x] 5.4 Copiar o environment para um diretório temporário validado fora da árvore versionada, preencher nessa cópia tokens e valores derivados para simular `Keep variable values` e reexecutar imediatamente a collection sem editar requests. Registrar novo código 0, novo `runId`, independência do `consultaId` anterior e ausência de falhas. Confirmar o cancelamento final das consultas da execução, que não ficou container Newman, que os seis serviços continuam saudáveis e remover o temporário por trap seguro.

## 6. Verificação final

- [x] 6.1 Executar `openspec validate add-postman-documentation --strict` e conferir 4 Requirements, 22 Scenarios e 4/4 artefatos. Preencher a matriz abaixo com um método automatizado ou evidência Newman por Scenario; falhar se não houver exatamente 22 linhas mapeadas.
- [x] 6.2 Executar a suíte dirigida `mvn -q -pl quality-gates -am test -Dtest='ColecaoPostmanTest,DocumentacaoFinalTest' -Dsurefire.failIfNoSpecifiedTests=false`; registrar casos, zero falhas/erros/skipped e conferir relatórios novos.
- [x] 6.3 Provar sensibilidade, uma mutação por vez e sem Maven concorrente: remover/reordenar pasta, remover salvamento de token, remover um `pm.test`, preencher token no environment, remover limite GraphQL, remover seção/link de ADR e quebrar link/Mermaid do README. Para cada uma, registrar vermelho, restaurar o arquivo byte a byte e executar novamente o teste que ficou vermelho até verde antes da mutação seguinte.
- [x] 6.4 Executar uma única vez `mvn -q clean verify` na raiz, sem filtros. Registrar build verde, casos por módulo, total, zero skipped, auditoria/infraestrutura aprovadas e coberturas global, domain e application acima dos pisos; conferir que nenhum arquivo de produção mudou nesta change.
- [x] 6.5 Depois do push autorizado pelo Gabriel, clonar a feature num `mktemp -d` validado sob `${TMPDIR:-/tmp}`, usar repositório Maven temporário vazio e executar somente `mvn -q -Dmaven.repo.local=<repo> clean verify`. Registrar SHA, código 0, contagens, zero skipped, coberturas e remover o temporário por trap seguro.
- [x] 6.6 Montar o corpo do PR com: matriz dos 22 Scenarios, execução e reexecução Newman, contagens/cobertura, mutações, clone limpo, preservação de produção, decisões D4–D9 e riscos residuais. Conferir o conteúdo publicado e a renderização visual dos dois diagramas no GitHub antes de marcar esta task.
- [x] 6.7 Após aprovação do PR e antes do merge, executar `/opsx:archive add-postman-documentation` na mesma feature branch. Confirmar a change fora da área ativa, presente no archive datado, delta promovido sem alterar Requirements anteriores, nenhuma outra capability tocada, `openspec validate --all --strict` verde e `openspec list` sem change ativa.
- [x] 6.8 Depois do push do archive, conferir no diff publicado do PR que collection, environment, documentação, gates, archive e spec promovida estão na mesma feature branch. Só então liberar o merge `--no-ff` em `develop`.

### Matriz Scenario → evidência

| # | Scenario | Evidência planejada |
|---:|---|---|
| 1 | Collection e environment são importáveis | `ColecaoPostmanTest#artefatosReaisSaoImportaveis` + Newman 5.3 |
| 2 | Pastas obrigatórias preservam a ordem | `ColecaoPostmanTest#pastasObrigatoriasTemOrdemExata` |
| 3 | Quatro logins salvam tokens independentes | `ColecaoPostmanTest#quatroLoginsSalvamTokensIndependentes` + Newman 5.3 |
| 4 | Execução completa não requer intervenção | Newman 5.3 |
| 5 | Nova execução não depende da anterior | Newman 5.4 |
| 6 | Toda requisição comprova status e contrato mínimo | `ColecaoPostmanTest#todoRequestTestaStatusECampos` |
| 7 | Jornada REST encadeia o ciclo da consulta | `ColecaoPostmanTest#jornadaRestEncadeiaConsulta` + Newman 5.3 |
| 8 | Histórico projetado é lido por GraphQL | `ColecaoPostmanTest#leituraGraphqlTemRetryLimitado` + Newman 5.3 |
| 9 | Médico corrige o histórico e a correção é observada | `ColecaoPostmanTest#correcaoGraphqlUsaMedicoEComprovaValor` + Newman 5.3 |
| 10 | Lembrete manual usa perfil permitido | `ColecaoPostmanTest#lembreteUsaTokenPermitido` + Newman 5.3 |
| 11 | Ausência de token produz 401 | `ColecaoPostmanTest#cenarioSemTokenAfirma401` + Newman 5.3 |
| 12 | Perfil sem permissão produz 403 | `ColecaoPostmanTest#cenariosDePerfilAfirmam403` + Newman 5.3 |
| 13 | Entrada de negócio inválida produz 422 | `ColecaoPostmanTest#cenarioDeNegocioAfirma422` + Newman 5.3 |
| 14 | Conflito de agenda produz 409 | `ColecaoPostmanTest#cenarioDeConflitoAfirma409` + Newman 5.3 |
| 15 | Recurso inexistente produz 404 | `ColecaoPostmanTest#cenarioDeAusenciaAfirma404` + Newman 5.3 |
| 16 | Requisição malformada produz 400 | `ColecaoPostmanTest#cenarioMalformadoAfirma400` + Newman 5.3 |
| 17 | Leitor encontra um caminho completo do zero | `DocumentacaoFinalTest#readmeOfereceCaminhoCompleto` |
| 18 | Arquitetura e sequência usam Mermaid coerente | `DocumentacaoFinalTest#readmeTemDoisDiagramasMermaidCoerentes` |
| 19 | Links locais da documentação são válidos | `DocumentacaoFinalTest#linksLocaisExistem` |
| 20 | Catálogo contém os sete ADRs | `DocumentacaoFinalTest#indiceContemSeteAdrs` |
| 21 | Formato dos ADRs é uniforme | `DocumentacaoFinalTest#adrsTemFormatoUniforme` |
| 22 | Origem OpenSpec de cada decisão é navegável | `DocumentacaoFinalTest#adrsApontamParaArchivesDeOrigem` |
