## Context

See `proposal.md` for motivation. O M12 já entrega os seis containers saudáveis, quatro usuários no seed `demo`, URLs fixas e `make demo`; não existe hoje nenhum arquivo Postman. A superfície pública relevante é REST em `:8081`, GraphQL HTTP em `:8083/graphql` e o disparo interno protegido em `:8082/internal/lembretes/executar`.

O roadmap chama o M13 de transversal a todas as capabilities, mas esta change não altera comportamento funcional. O único delta fica em `operacao-do-ambiente`: collection e documentação são artefatos operacionais. As specs das outras cinco capabilities continuam como fonte do comportamento que a collection demonstra.

O `quality-gates` é um módulo técnico, deliberadamente sem Spring nem dependência de aplicação. Docker, Bash, `make`, `curl` e `jq` já são pré-requisitos do ambiente completo; Postman CLI e Newman não estão instalados no host. O enunciado pede Postman “ou similar”, e o roadmap exige compatibilidade com o Collection Runner.

## Goals / Non-Goals

**Goals:**

- Entregar arquivos importáveis, legíveis e executáveis tanto no Postman Collection Runner quanto no runner automatizado.
- Provar o pacote estruturalmente no gate Maven e dinamicamente contra o Compose.
- Encadear dados e quatro credenciais sem estado manual ou segredo novo.
- Tornar README e ADRs uma navegação coerente sobre o que já foi entregue.

**Non-Goals:**

- Cobrir combinatoriamente todos os endpoints, argumentos GraphQL e células das matrizes; isso já pertence às suítes M03/M09/M10.
- Usar a collection como novo gate global dependente de Docker.
- Modificar produção, seed, Compose, scripts do M12 ou documentação exclusiva do M14.
- Publicar collection em workspace remoto do Postman ou exigir conta/chave de API.

## Decisions

### D1 — Delta apenas em `operacao-do-ambiente`

Os Scenarios descrevem a forma observável de validar e compreender o sistema, não novas regras de autenticação, consulta, mensageria, notificação ou histórico. A proposta declara o alcance transversal, mas só `operacao-do-ambiente` recebe Requirements ADDED.

**Alternativas descartadas:** criar seis deltas repetindo comportamentos existentes confundiria evidência com contrato e aumentaria o risco de divergência no archive; `skip_specs` esconderia um novo artefato operacional exigido pelo enunciado.

### D2 — Dois exports Postman v2.1 sob `postman/`

Serão criados:

- `postman/Hospital-Scheduling-Fase3.postman_collection.json`, schema Collection v2.1;
- `postman/Hospital-Scheduling-Local.postman_environment.json`, com apenas URLs, credenciais públicas do profile `demo`, ids fixos do seed e variáveis derivadas vazias.

Tokens, `consultaId`, horários, contador de retry e `runId` nunca terão valor inicial persistido. A collection não usa globals, data file, script externo, URL remota nem segredo diferente do seed documentado. Nomes de variável serão centralizados no environment; scripts usam `pm.environment` para que a execução no aplicativo e no Newman tenha a mesma semântica.

**Alternativas descartadas:** collection variables para credenciais duplicariam o environment; um arquivo por serviço quebraria o encadeamento e a execução única; formato v3 excluiria o Newman 6 e não é necessário para o requisito.

### D3 — Cinco pastas obrigatórias e ordem executável

As pastas de primeiro nível serão exatamente as cinco do roadmap, na ordem normativa. Requests internos terão prefixos numéricos. O fluxo mínimo será:

1. `00-Auth`: login de médico, enfermeiro, paciente e paciente 2; cada resposta valida `perfil`, `expiresIn`, `accessToken` e salva token próprio.
2. `01-Agendamento`: listar, criar consulta futura, guardar `consultaId`, buscar, alterar e confirmar usando os perfis permitidos.
3. `02-Historico-GraphQL`: aguardar a projeção, consultar o snapshot, corrigir como médico, aguardar e comprovar a correção; disparar o lembrete manual com enfermeiro.
4. `03-Cenarios-de-Seguranca`: 401 sem token, 403 de paciente em criação e 403 de paciente 2 ao registro do paciente 1. O corpo de segurança também é validado e não só o status.
5. `04-Cenarios-de-Erro`: 422 no passado, 409 por sobreposição à consulta criada, 404 para UUID válido inexistente e 400 para UUID malformado, todos com Problem Detail e sem detalhe interno.

Cada request terá ao menos dois `pm.test()`: status/código e campos relevantes. GraphQL HTTP 200 só passa como sucesso quando `errors` está ausente; erros esperados precisam afirmar `errors[0].extensions.code`.

**Alternativas descartadas:** requests soltos favorecem execução fora de ordem; usar somente os dados fixos não demonstra escrita; colar Bearer token contradiz o critério central do roadmap.

### D4 — Dados dinâmicos com colisão tratada e limite

No início do run, um script cria `runId` e uma base temporal futura. A criação tenta um slot de 30 minutos a partir de sete dias no futuro. Se receber o Problem Detail 409 de conflito, avança o slot em uma hora e repete o mesmo request, por no máximo 24 tentativas; qualquer outro status falha imediatamente. A resposta 201 guarda `consultaId`, horário e observação da execução.

Isso torna a reexecução independente inclusive quando uma execução anterior deixou dados. O limite impede loop infinito e continua dentro do horizonte de 24 meses. O conflito intencional da pasta de erros usa exatamente o horário bem-sucedido e não participa do mecanismo de escolha.

**Alternativas descartadas:** horário aleatório só reduz, mas não elimina, colisão; apagar dados diretamente quebraria a API como fronteira; `docker compose down -v` tornaria a collection destrutiva e lenta.

### D5 — Consistência eventual tratada dentro do Runner

A primeira query `consulta(id:)` pode observar `NOT_FOUND` enquanto o evento ainda está no outbox ou na fila. Seu post-response script aceita apenas esse código transitório, incrementa um contador e usa `pm.execution.setNextRequest` para repetir a própria leitura até 20 vezes. Entre tentativas, o pre-request aguarda 250 ms. No limite, o teste falha com diagnóstico. Qualquer outro erro GraphQL falha na primeira resposta.

Depois da mutation de correção, a leitura posterior aplica o mesmo padrão até observar o valor corrigido. O token usado em cada operação segue as matrizes promovidas: enfermeiro lê, médico corrige.

**Alternativas descartadas:** delay fixo deixa a suíte lenta e ainda intermitente; consulta direta ao banco não representa a API; aceitar qualquer `errors` mascara defeitos.

### D6 — Newman oficial em container, fora do reactor Maven

A execução automatizada usa `postman/newman:6.1.3-alpine`, tag fixa existente no repositório oficial. O container entra na rede `hospital-fase3_hospital-net`, monta `postman/` somente para leitura e sobrescreve as três URLs para `http://agendamento:8081`, `http://notificacao:8082` e `http://historico:8083`. O comando usa `run`, `-e` e `--bail` e deve terminar em zero, sem assertion failure.

Antes da evidência, a apply confere `docker manifest inspect`, executa a imagem e registra `newman --version`; ausência ou mudança incompatível da tag exige `/opsx:update`. A execução oficial no aplicativo Postman continua documentada: importar os dois JSON, selecionar o environment e executar a collection inteira uma vez, em ordem. O Newman comprova automaticamente a mesma collection exportada sem exigir conta Postman, Node/npm ou instalação global.

O Maven não chama Docker/Newman. `mvn -q clean verify` continua um gate reproduzível de código e estrutura; a execução da collection é um aceite de runtime separado, como `make demo` no M12.

**Alternativas descartadas:** `latest` não é reprodutível; Postman CLI exige instalação adicional; dependência npm adicionaria uma segunda árvore de build; só a inspeção Java não prova que requests e scripts funcionam juntos.

### D7 — Gate estrutural sem parser ou dependência nova

`ColecaoPostmanTest`, no `quality-gates`, lê os arquivos reais em UTF-8 e verifica invariantes estreitas por trechos e expressões regulares: schema v2.1; dois JSON presentes; cinco pastas exatas e ordenadas; requests com URL/body; quatro logins e quatro variáveis de token; ausência de token literal; uso dos ids do seed; presença de REST, GraphQL e lembrete; pelo menos dois `pm.test()` por request; códigos 401/403/422/409/404/400; retry limitado; valores derivados vazios; nenhuma referência externa.

`DocumentacaoFinalTest` verifica os sete ADRs, seções e ordem, índice e destinos locais; no README, verifica instruções de importação/Runner, URLs, credenciais e ao menos dois blocos Mermaid com marcadores de fluxo coerentes. A validade sintática integral dos JSON e dos scripts é provada pelo Newman; o teste Java não finge ser um parser JSON ou Mermaid.

Negativos sintéticos mudam uma invariante por caso. A cobertura Scenario → método vive em uma matriz no `tasks.md`, e uma asserção exige a contagem completa para não passar vazia.

**Alternativas descartadas:** adicionar Jackson ao módulo contraria seu papel técnico mínimo; regex genérica demais produziria falsa confiança; verificar apenas existência não detectaria collection vazia ou token colado.

### D8 — README consolidado sem antecipar M14

O README mantém a visão estrutural existente e ganha um `sequenceDiagram` do request REST à projeção/notificação. A primeira metade concentra: estado do projeto, arquitetura, início rápido com `make demo`, credenciais, URLs e como importar/rodar Postman. Seções profundas de gate, smoke e operação continuam disponíveis, mas links internos reduzem a rolagem. Todos os comandos e nomes de campo são conferidos contra arquivos reais.

Não será criado relatório técnico, roteiro de apresentação ou status de release final; o M14 ainda fará auditoria e fechamento de `1.0.0`.

### D9 — Consolidação dos ADRs preserva conteúdo

Os sete arquivos mantêm decisão, alternativas e consequências técnicas. Apenas títulos de seção, navegação e rastreabilidade são normalizados. `Alternativas consideradas` vira `Alternativas`; cada `Status` inclui link relativo para o archive correspondente:

- ADR-001 e ADR-006 → `2026-09-04-add-event-publishing-outbox`;
- ADR-002 e ADR-003 → `2026-09-02-bootstrap-monorepo`;
- ADR-004 e ADR-005 → `2026-09-04-add-autenticacao-jwt`;
- ADR-007 → `2026-09-02-add-agendamento-domain`.

`docs/adr/README.md` lista exatamente 001–007, em ordem, com título, status, decisão resumida, origem e link. Não nasce ADR-008: o M13 consolida decisões, não cria uma decisão arquitetural nova.

## Risks / Trade-offs

- **Imagem oficial do Newman está atrás da versão npm mais recente** → usar a última tag Docker fixa publicada e validar manifest/versão antes do trabalho; mudança exige update do design.
- **`pm.execution.setNextRequest` só altera fluxo em Runner** → documentar execução da collection inteira; o gate estrutural e o Newman exercitam precisamente esse modo.
- **Estado acumulado pode ocupar slots futuros** → tentativa limitada e avanço determinístico; diagnóstico inclui último Problem Detail.
- **Environment versiona senha demo** → ela já é pública e exclusiva do profile `demo`; nenhum JWT, segredo de infraestrutura ou valor derivado é persistido.
- **Regex do gate pode não compreender JSON válido reformatado** → invariantes pequenas e negativos; Newman é a prova sintática e dinâmica.
- **Runner em container não acessa `localhost` do host** → entrar na rede Compose e sobrescrever URLs para nomes de serviço.
- **README grande pode continuar pouco navegável** → início rápido no topo e sumário com âncoras; não apagar detalhes operacionais já validados.
- **Uniformização pode alterar sentido dos ADRs** → limitar mudanças a estrutura, links e correções factuais verificadas; comparar blocos decisórios antes/depois.

## Migration Plan

1. Criar e provar os gates estruturais contra fixtures sintéticas antes dos artefatos reais.
2. Adicionar collection/environment e fazê-los satisfazer o gate.
3. Consolidar README e ADRs, preservando o conteúdo decisório.
4. Subir o ambiente existente com `make demo` e executar a collection duas vezes pelo Newman.
5. Rodar suíte dirigida, gate global e clone limpo.
6. Após aprovação do PR, sincronizar o delta de `operacao-do-ambiente` e arquivar a change na mesma feature branch antes do merge.

Rollback remove os dois JSON e os testes/documentação desta change; não há dado, migration ou produção a reverter. Dados criados pela evidência ficam no ambiente local de demonstração e podem ser removidos pelo `make reset` já existente, nunca automaticamente durante a collection.
