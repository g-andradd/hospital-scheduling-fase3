## Context

Ver `proposal.md` para motivação. O estado relevante do repositório no início do M14:

- `docs/02-especificacao-funcional.md` declara **20 RF** (RF-01 a RF-20) em quatro subseções e **10 RNF** (RNF-01 a RNF-10) numa tabela única, além da matriz de autorização da §3 e do seed da §5.
- Já existe precedente de **documento normativo lido por teste**: `MatrizDeAutorizacao`, `MatrizDeAutorizacaoGraphql` e `MatrizDeAutorizacaoNotificacao` leem a §3 de `docs/02` e geram um caso por célula. Esse precedente trouxe junto a lição registrada no próprio código: *"o risco de ler o documento é o parser parar de achar a tabela e a suíte passar verificando nada"* — por isso as três afirmam contagens exatas.
- O `quality-gates` é um módulo técnico em JDK puro, sem Spring e sem dependência externa fora do escopo `test`. Ele já hospeda verificações estruturais sobre Bash (`RoteiroDeSmokeTest`), sobre o Compose (`AmbienteDeDemonstracaoTest`), sobre o pacote Postman (`ColecaoPostmanTest`) e sobre a documentação (`DocumentacaoFinalTest`). `RoteiroDeDemonstracaoTest` já executa funções Bash reais a partir do teste, com `source`.
- Existe uma `AuditoriaDeExecucao` no `quality-gates`, que audita a **execução das suítes** durante o `mvn verify`. Ela não tem relação com a auditoria de **requisitos** deste change, e a proximidade dos nomes é um risco real de confusão.
- `scripts/smoke-test.sh` e `scripts/demo.sh` estabelecem as convenções de Bash do projeto: `set -Eeuo pipefail`, códigos de saída nomeados, `preflight` antes de criar qualquer recurso e o guarda `if [[ "${BASH_SOURCE[0]}" == "$0" ]]` que permite carregar o arquivo por `source` sem executar o fluxo.
- O ambiente completo do M12 publica Swagger em `:8081/swagger-ui.html`, GraphiQL em `:8083/graphiql`, Mailpit em `:8025`, RabbitMQ Management em `:15672` e as credenciais `demo` com senha comum, tudo já referenciado por `scripts/demo.sh` e pelo README. `make demo` é idempotente: reaproveita a consulta de demonstração existente na janela em vez de criar outra.

Restrições que moldam o desenho: o M14 não pode alterar comportamento, o `quality-gates` não pode ganhar dependência nova, a auditoria precisa rodar numa máquina sem Docker e sem ambiente no ar, remover volumes exige autorização explícita do Gabriel, e o prazo de uma sessão exige simplicidade antes de generalidade.

## Goals / Non-Goals

**Goals:**

- Responder, de forma mecânica e reproduzível, "este requisito tem evidência versionada e ancorada, e onde ela está?" para os 30 requisitos declarados.
- Falhar fechado: qualquer dúvida — inventário divergente, artefato ausente, âncora não encontrada — reprova.
- Entregar dois documentos de fechamento cuja fidelidade seja verificada pelo build, não pela boa vontade de quem escreve.
- Manter o caminho básico simples: um arquivo Bash legível, um catálogo explícito e **um** verificador.

**Non-Goals:**

- Afirmar que a auditoria reexecuta comportamento. Ela é estática; quem prova comportamento são as suítes do reactor e as execuções registradas à parte.
- Substituir ou duplicar os gates existentes: cobertura agregada, `AuditoriaDeExecucao`, infraestrutura real e ArchUnit continuam onde estão.
- Construir uma linguagem de asserção, um motor de plugins ou um formato de catálogo extensível.
- Antecipar o fechamento da release: versão, `CHANGELOG.md`, branch `release/1.0.0`, merge e tag ficam fora.
- Produzir DOCX, PDF ou qualquer binário de escritório.

## Decisions

### D1 — Delta apenas em `operacao-do-ambiente`, com alcance transversal declarado

O roadmap registra o M14 como "Capability: todas". O alcance é transversal na **leitura**: a auditoria percorre requisitos das seis capabilities. O **contrato novo**, porém, é operacional — um instrumento de verificação e dois documentos de entrega. Nenhum Requirement de comportamento muda.

A verificação foi feita antes de decidir: nenhuma spec promovida menciona identificadores RF/RNF, `scripts/auditoria.sh`, `docs/relatorio-tecnico.md` ou `docs/roteiro-demo.md`. Não há conflito com contrato já promovido, e portanto não há motivo para parar e reportar.

**Alternativas descartadas:** seis deltas, um por capability, exigiriam `MODIFIED Requirements` copiando comportamento intacto só para registrar que ele foi auditado — deltas artificiais, com risco de divergência no archive e nenhuma garantia nova. `skip_specs: true` esconderia três artefatos operacionais que o roadmap exige e que o build passa a verificar.

### D2 — `OK` significa evidência presente e ancorada, e nada além disso

Esta é a decisão que define a honestidade do instrumento. A auditoria lê arquivos; não sobe container, não executa Maven, não chama endpoint. Portanto:

- `OK` afirma **uma** coisa: o artefato de evidência daquele requisito existe, é legível e contém o elemento literal declarado.
- `OK` **não** afirma que o teste passou agora, que a cobertura foi medida agora ou que o ambiente subiu agora.
- O cabeçalho da saída e o próprio relatório técnico declaram isso em uma frase, para que ninguém leia a tabela como prova de execução.

As execuções que provam comportamento continuam sendo evidência de primeira classe, registradas **fora** da auditoria: `mvn -q clean verify` na raiz (com auditoria de execução, infraestrutura real e pisos de cobertura), `scripts/smoke-test.sh` e `make demo`. O corpo do PR e a seção de testes do relatório carregam essas saídas.

**Alternativas descartadas:** um verificador `execucao` que aprovasse o requisito por o catálogo nomear um comando seria exatamente o falso positivo que o D5 existe para fechar — verde por citar `mvn`, sem nada ter sido executado. Fazer a auditoria disparar o build resolveria a semântica, mas duplicaria um gate mais rigoroso, exigiria Docker e Maven na máquina e tornaria o resultado dependente do último build em vez do repositório.

### D3 — O inventário nasce de `docs/02` e é fechado na sequência esperada

A auditoria extrai os identificadores da própria especificação funcional: as quatro tabelas de RF da §1 e a tabela de RNF da §2, reconhecidas pelo formato `| RF-nn | ... |` e `| RNF-nn | ... |` dentro dos limites das seções. Não existe cópia da lista mantida à parte.

Derivar não basta. Sobre o conjunto derivado, o script exige **exatamente** `RF-01`…`RF-20` e `RNF-01`…`RNF-10`, contínuos, únicos e na ordem do documento. Documento reduzido, lacuna intermediária, número fora da sequência, identificador repetido ou catálogo reduzido reprovam — tudo sem executar Maven e sem tocar no ambiente.

O risco conhecido é o parser deixar de achar a tabela e a auditoria passar verificando nada. A proteção é dupla e deliberada:

- o próprio script recusa qualquer conjunto que não seja a sequência fechada e relata o total encontrado;
- o gate estrutural do `quality-gates` lê `docs/02` com um parser **independente**, afirma 20 RF e 10 RNF e compara o conjunto com o que a auditoria relatou. Os dois teriam de errar do mesmo jeito para a omissão passar.

**Trade-off aceito e explícito:** acrescentar um RF-21 ao documento quebra a auditoria até que a sequência esperada e o catálogo sejam atualizados. É o comportamento desejado numa auditoria de entrega — um requisito novo não pode entrar no documento e ser silenciosamente ignorado.

**Alternativas descartadas:** manter só a lista no script duplica a fonte e diverge no primeiro requisito acrescentado; aceitar apenas uma "faixa esperada" deixa passar lacuna intermediária e número fora de ordem; deduzir requisitos do código inverte a direção — o documento é a fonte normativa.

### D4 — Um verificador só, aplicado a um conjunto de âncoras por requisito

O catálogo tem um único tipo de verificação: **o artefato existe, é legível e contém a âncora literal declarada**. Não há verificador de mera existência, porque "arquivo presente" contradiz o Requirement de artefato *mais* elemento verificável.

O que varia não é o verificador, mas a **quantidade de pares por requisito**. Um requisito declara **um ou mais** pares artefato + âncora, tantos quantos forem as suas cláusulas normativas, e o resultado é a conjunção: todos precisam passar, e faltar qualquer um reprova o requisito inteiro. RF-11 tem quatro pares porque o texto exige três períodos e o filtro por status; RF-02 tem um, porque o texto tem uma cláusula só.

A saída continua com **uma linha por requisito**: o campo `EVIDÊNCIA` enumera as âncoras daquele requisito, separadas por um delimitador interno que não colide com o `|` das colunas.

**Alternativas descartadas:** manter um verificador `arquivo` "para os raros casos" criaria a porta de saída fácil que o catálogo usaria por preguiça, e nenhuma das 30 linhas a exigiu; uma âncora única por requisito é o que produzia a cobertura parcial corrigida nesta revisão — `void periodoFuturasDevolveApenasOQueNaoPassou()` é real, mas prova um terço do RF-11; uma linha de saída por âncora quebraria o formato pedido pelo roadmap.

### D5 — Evidência ancorada: menção textual, nome de comando e prova parcial não aprovam

A regra que separa esta auditoria de um `grep RF-09 -r .` é simples e verificada por teste:

- a âncora **não** pode ser nem conter o identificador do requisito — um documento que cita "RF-09" em prosa não prova RF-09;
- a âncora **não** pode ser apenas o nome de um comando, de uma ferramenta ou de uma execução — `mvn`, `docker compose` ou `Testcontainers` isolados não provam nada;
- o conjunto de âncoras **precisa cobrir as cláusulas do texto do requisito**. Uma âncora real que prove só parte do que o requisito exige não basta; a revisão semântica do D6 existe exatamente para isso;
- o mesmo par artefato + âncora **não** pode servir a dois requisitos **sem declaração explícita de compartilhamento** no catálogo. O compartilhamento declarado é legítimo e preferível a trocar a prova adequada por uma prova inferior só para manter pares distintos. No catálogo atual ele não é necessário: os 75 pares são todos distintos.

**Nenhum teste é criado para maquiar o catálogo — um é criado para fechar uma lacuna que a auditoria revelou.** Todas as âncoras da tabela abaixo vieram de suítes que já existem, com **uma** exceção deliberada: a cláusula "senha nunca em log" do RNF-01 não tinha nenhuma prova automatizada no projeto. Nenhuma suíte inspecionava o que o login escreve no log. Descobrir isso é exatamente para o que a revisão semântica do D6 serve, e a resposta correta é fechar a lacuna, não escolher uma âncora parcial e declarar o requisito coberto. O `SenhaForaDosLogsIT` do D6-A existe por esse motivo; não existe para dar verde ao catálogo.

A distinção importa e vale como regra: acrescentar teste é legítimo quando a auditoria expõe um requisito sem prova; é ilegítimo quando existe prova e o teste novo só serve para produzir uma âncora mais conveniente. Nenhum outro caso das 30 linhas exigiu teste novo.

### D6 — As 30 linhas e seus 75 pares, revisados semanticamente contra o texto dos requisitos

Cada par abaixo foi aberto e confirmado no repositório antes de entrar aqui, e o conjunto de cada requisito foi lido contra o **texto integral** do RF/RNF em `docs/02-especificacao-funcional.md`, não apenas contra o seu título. Nenhum nome de teste foi inventado. Os caminhos são completos e relativos à raiz do repositório, para que a conferência seja reproduzível.

A única linha que ainda não existe no repositório é a quarta do RNF-01, marcada **(novo)**: ela é a cobertura criada por esta change para a lacuna descrita no D6-A, e o catálogo só a declara depois que o teste existir e passar.

| Requisito | Cláusulas do texto | Artefato real | Âncora real | Natureza da prova |
|---|---|---|---|---|
| RF-01 | autentica por e-mail e senha | `agendamento-service/src/test/java/br/com/fiap/hospital/agendamento/application/AutenticarUsuarioUseCaseTest.java` | `void autenticacaoBemSucedida()` | teste unitário do caso de uso |
| RF-01 | JWT carrega o perfil | `shared-security/src/test/java/br/com/fiap/hospital/security/JwtServiceTest.java` | `void tokenCarregaAIdentidade()` | teste unitário de emissão e validação |
| RF-02 | exatamente três perfis | `agendamento-service/src/test/java/br/com/fiap/hospital/agendamento/domain/PerfilUsuarioTest.java` | `void existemOsTresPerfis()` | teste unitário de domínio |
| RF-03 | sem token → 401 | `shared-security/src/test/java/br/com/fiap/hospital/security/RespostaDeSegurancaTest.java` | `void semTokenRecebe401()` | teste da fronteira HTTP de segurança |
| RF-03 | token não válido é recusado | `shared-security/src/test/java/br/com/fiap/hospital/security/JwtServiceTest.java` | `void tokenDeOutroSegredoERecusado()` | teste unitário de validação do token |
| RF-04 | perfil sem permissão → 403 | `shared-security/src/test/java/br/com/fiap/hospital/security/RespostaDeSegurancaTest.java` | `void semPermissaoRecebe403()` | teste da fronteira HTTP de segurança |
| RF-04 | matriz inteira honrada | `agendamento-service/src/test/java/br/com/fiap/hospital/agendamento/integracao/MatrizDeAutorizacaoIT.java` | `void celulaRespondeConformeODocumento(` | integração dirigida pela matriz de `docs/02` §3 |
| RF-04 | matriz lida por inteiro | `agendamento-service/src/test/java/br/com/fiap/hospital/agendamento/integracao/MatrizDeAutorizacaoIT.java` | `void matrizCobreVinteEUmaCelulas()` | asserção anti-decorativa da leitura |
| RF-05 | registro com paciente, médico e data/hora | `agendamento-service/src/test/java/br/com/fiap/hospital/agendamento/application/AgendarConsultaUseCaseTest.java` | `void consultaRegistradaComSucesso()` | teste unitário do caso de uso |
| RF-05 | registro exposto por HTTP | `agendamento-service/src/test/java/br/com/fiap/hospital/agendamento/infrastructure/web/ConsultaControllerTest.java` | `void registro()` | teste de fronteira HTTP |
| RF-06 | remarcação | `agendamento-service/src/test/java/br/com/fiap/hospital/agendamento/application/AtualizarConsultaUseCaseTest.java` | `void remarcacaoBemSucedida()` | teste unitário do caso de uso |
| RF-06 | observações preservadas na alteração | `agendamento-service/src/test/java/br/com/fiap/hospital/agendamento/application/AtualizarConsultaUseCaseTest.java` | `void alteracaoSoDeHorarioPreservaObservacoes()` | teste unitário do caso de uso |
| RF-06 | alteração exposta por HTTP | `agendamento-service/src/test/java/br/com/fiap/hospital/agendamento/infrastructure/web/ConsultaControllerTest.java` | `void alteracao()` | teste de fronteira HTTP |
| RF-07 | consulta pode ser cancelada | `agendamento-service/src/test/java/br/com/fiap/hospital/agendamento/application/CicloDeVidaDaConsultaUseCaseTest.java` | `void cancelamentoBemSucedido(` | teste unitário do caso de uso |
| RF-07 | motivo é obrigatório | `agendamento-service/src/test/java/br/com/fiap/hospital/agendamento/application/CicloDeVidaDaConsultaUseCaseTest.java` | `void cancelamentoSemMotivoERecusado(` | teste unitário do caso de uso |
| RF-08 | regra do passado | `agendamento-service/src/test/java/br/com/fiap/hospital/agendamento/application/AgendarConsultaUseCaseTest.java` | `void registroNoPassadoERecusado()` | teste unitário do caso de uso |
| RF-08 | status 422 | `agendamento-service/src/test/java/br/com/fiap/hospital/agendamento/infrastructure/web/ConsultaControllerTest.java` | `void agendamentoNoPassado()` | teste de fronteira HTTP |
| RF-09 | conflito na agenda do médico | `agendamento-service/src/test/java/br/com/fiap/hospital/agendamento/application/AgendarConsultaUseCaseTest.java` | `void conflitoComAgendaDoMedicoERecusado()` | teste unitário do caso de uso |
| RF-09 | conflito na agenda do paciente | `agendamento-service/src/test/java/br/com/fiap/hospital/agendamento/application/AgendarConsultaUseCaseTest.java` | `void conflitoComAgendaDoPacienteERecusado()` | teste unitário do caso de uso |
| RF-09 | status 409 | `agendamento-service/src/test/java/br/com/fiap/hospital/agendamento/infrastructure/web/ConsultaControllerTest.java` | `void conflitoDeAgenda()` | teste de fronteira HTTP |
| RF-10 | máquina de estados completa | `agendamento-service/src/test/java/br/com/fiap/hospital/agendamento/domain/StatusConsultaTest.java` | `void cobreTodasAsCombinacoes(` | teste unitário parametrizado de domínio |
| RF-10 | status 409 | `agendamento-service/src/test/java/br/com/fiap/hospital/agendamento/infrastructure/web/ConsultaControllerTest.java` | `void transicaoInvalida()` | teste de fronteira HTTP |
| RF-11 | período TODAS | `historico-service/src/test/java/br/com/fiap/hospital/historico/integracao/FiltroHistoricoIT.java` | `void periodoTodasNaoRecortaPeloRelogio()` | integração GraphQL com Postgres real |
| RF-11 | período FUTURAS | `historico-service/src/test/java/br/com/fiap/hospital/historico/integracao/FiltroHistoricoIT.java` | `void periodoFuturasDevolveApenasOQueNaoPassou()` | integração GraphQL com Postgres real |
| RF-11 | período PASSADAS | `historico-service/src/test/java/br/com/fiap/hospital/historico/integracao/FiltroHistoricoIT.java` | `void periodoPassadasDevolveApenasOQueOcorreu()` | integração GraphQL com Postgres real |
| RF-11 | filtro por status | `historico-service/src/test/java/br/com/fiap/hospital/historico/integracao/FiltroHistoricoIT.java` | `void filtroPorStatusSelecionaOsValoresInformados()` | integração GraphQL com Postgres real |
| RF-12 | paciente vê as próprias, resolvidas do token | `historico-service/src/test/java/br/com/fiap/hospital/historico/integracao/AutorizacaoHistoricoIT.java` | `void consultasDoPacienteAutenticadoVemDoToken()` | integração de autorização |
| RF-12 | consulta de terceiro → 403 | `historico-service/src/test/java/br/com/fiap/hospital/historico/integracao/AutorizacaoHistoricoIT.java` | `void pacienteNaoAlcancaRegistroDeTerceiro()` | integração de autorização |
| RF-12 | leitura por id alheio → 403 | `historico-service/src/test/java/br/com/fiap/hospital/historico/integracao/AutorizacaoHistoricoIT.java` | `void pacienteNaoLePorIdRegistroAlheio()` | integração de autorização |
| RF-13 | correção altera o registro e deixa trilha | `historico-service/src/test/java/br/com/fiap/hospital/historico/integracao/CorrecaoHistoricoIT.java` | `void correcaoValidaAlteraSnapshotERegistraTrilha()` | integração da mutation de correção |
| RF-13 | autoria médica da correção | `historico-service/src/test/java/br/com/fiap/hospital/historico/integracao/CorrecaoHistoricoIT.java` | `void autorDaCorrecaoVemDoToken()` | integração da mutation de correção |
| RF-14 | enfermeiro lê qualquer paciente | `historico-service/src/test/java/br/com/fiap/hospital/historico/integracao/MatrizDeAutorizacaoGraphqlIT.java` | `void celulaDaMatrizEHonrada(` | integração dirigida pela matriz GraphQL |
| RF-14 | matriz lida por inteiro | `historico-service/src/test/java/br/com/fiap/hospital/historico/integracao/MatrizDeAutorizacaoGraphqlIT.java` | `void leituraEncontrouAMatrizCompleta()` | asserção anti-decorativa da leitura |
| RF-15 | criação publica evento | `agendamento-service/src/test/java/br/com/fiap/hospital/agendamento/application/PublicacaoDeEventosUseCaseTest.java` | `void registroPublicaEvento()` | teste unitário do caso de uso |
| RF-15 | cada mudança, inclusive edição, publica o evento correspondente | `agendamento-service/src/test/java/br/com/fiap/hospital/agendamento/application/PublicacaoDeEventosUseCaseTest.java` | `void cadaMudancaPublicaOEventoCorrespondente()` | teste unitário do caso de uso |
| RF-15 | mensagem chega ao broker | `agendamento-service/src/test/java/br/com/fiap/hospital/agendamento/integracao/OutboxRelayIT.java` | `void ackSemReturnPublicaPersistente()` | integração com broker real |
| RF-16 | consome criação e notifica o paciente | `notificacao-service/src/test/java/br/com/fiap/hospital/notificacao/integracao/NotificacaoReativaIT.java` | `void criacaoNotificaOAgendamento()` | integração de consumo e envio |
| RF-16 | consome edição e notifica o paciente | `notificacao-service/src/test/java/br/com/fiap/hospital/notificacao/integracao/NotificacaoReativaIT.java` | `void atualizacaoNotificaAAlteracao()` | integração de consumo e envio |
| RF-17 | dentro da janela de 24h recebe | `notificacao-service/src/test/java/br/com/fiap/hospital/notificacao/integracao/JanelaDoLembreteIT.java` | `void consultaA23h59Recebe()` | integração com relógio fixo |
| RF-17 | fora da janela não recebe | `notificacao-service/src/test/java/br/com/fiap/hospital/notificacao/integracao/JanelaDoLembreteIT.java` | `void consultaA24h01NaoRecebe()` | integração com relógio fixo |
| RF-17 | disparo automático | `notificacao-service/src/test/java/br/com/fiap/hospital/notificacao/scheduler/AgendadorDeLembretesTest.java` | `void semConfiguracaoAVarreduraEHoraria()` | teste unitário do agendamento periódico |
| RF-18 | consome os eventos e materializa o read model | `historico-service/src/test/java/br/com/fiap/hospital/historico/integracao/ProjecaoHistoricoIT.java` | `void sequenciaCompletaAtualizaSnapshotETrilha()` | integração da projeção |
| RF-18 | os mesmos cinco tipos normativos | `historico-service/src/test/java/br/com/fiap/hospital/historico/integracao/ProjecaoHistoricoIT.java` | `void todosOsTiposNormativosSaoProjetaveis()` | integração da projeção |
| RF-19 | idempotência na notificação | `notificacao-service/src/test/java/br/com/fiap/hospital/notificacao/integracao/IdempotenciaNotificacaoIT.java` | `void reentregaNaoNotificaDuasVezes()` | integração por `eventId` |
| RF-19 | idempotência no histórico | `historico-service/src/test/java/br/com/fiap/hospital/historico/integracao/IdempotenciaHistoricoIT.java` | `void reentregaSequencialNaoDuplicaEfeito()` | integração por `eventId` |
| RF-20 | vai para a DLQ sem perda | `shared-contracts/src/test/java/br/com/fiap/hospital/contracts/TopologiaRabbitMqIT.java` | `void rejeicaoPreservaCorpoChaveEMarcaXDeath()` | integração de dead-lettering |
| RF-20 | depois das tentativas configuradas | `notificacao-service/src/test/java/br/com/fiap/hospital/notificacao/integracao/ConfiguracaoNotificacaoIT.java` | `void retryERejeicaoSeguemOContrato()` | integração da configuração de retry |
| RNF-01 | senhas com BCrypt | `shared-security/src/main/java/br/com/fiap/hospital/security/SegurancaAutoConfiguration.java` | `BCryptPasswordEncoder` | configuração normativa do encoder |
| RNF-01 | senha nunca na resposta | `agendamento-service/src/test/java/br/com/fiap/hospital/agendamento/application/AutenticarUsuarioUseCaseTest.java` | `void identidadeNaoCarregaSenha()` | teste unitário do caso de uso |
| RNF-01 | senha nunca no token | `shared-security/src/test/java/br/com/fiap/hospital/security/JwtServiceTest.java` | `void tokenNaoCarregaSenha()` | teste unitário de emissão |
| RNF-01 | senha nunca em log **(novo)** | `agendamento-service/src/test/java/br/com/fiap/hospital/agendamento/integracao/SenhaForaDosLogsIT.java` | `void loginNaoEscreveSenhaNemHashNoLog()` | integração que captura os logs do login e da recusa |
| RNF-02 | política stateless configurada | `shared-security/src/main/java/br/com/fiap/hospital/security/SegurancaAutoConfiguration.java` | `SessionCreationPolicy.STATELESS` | configuração normativa da cadeia |
| RNF-02 | nenhuma sessão criada em execução | `agendamento-service/src/test/java/br/com/fiap/hospital/agendamento/integracao/CadeiaDeSegurancaIT.java` | `void nenhumaSessaoECriada()` | integração da cadeia de filtros |
| RNF-03 | toda exceção de domínio tem tratador | `agendamento-service/src/test/java/br/com/fiap/hospital/agendamento/infrastructure/web/CoberturaDoMapaDeErrosTest.java` | `void todaExcecaoDeDominioTemTratador()` | gate estrutural do mapa de erros |
| RNF-03 | toda exceção do MVC tem tratador | `agendamento-service/src/test/java/br/com/fiap/hospital/agendamento/infrastructure/web/CoberturaDoMapaDeErrosTest.java` | `void todaExcecaoDeMvcEstaCoberta()` | gate estrutural do mapa de erros |
| RNF-03 | `type` estável e sem colisão | `agendamento-service/src/test/java/br/com/fiap/hospital/agendamento/infrastructure/web/CoberturaDoMapaDeErrosTest.java` | `void nenhumTypeSeRepete()` | gate estrutural do mapa de erros |
| RNF-04 | piso global de 85% | `quality-gates/src/main/java/br/com/fiap/hospital/qualidade/VerificadorDeCobertura.java` | `PISO_GLOBAL = 85` | constante que o gate aplica |
| RNF-04 | gate ligado ao `verify` | `quality-gates/pom.xml` | `<id>gate-de-cobertura</id>` | execução declarada no build |
| RNF-04 | ordem dos gates preservada | `quality-gates/src/test/java/br/com/fiap/hospital/qualidade/LigacaoDosGatesTest.java` | `void ordemDosGates()` | teste da ligação dos gates |
| RNF-05 | direção das dependências | `agendamento-service/src/test/java/br/com/fiap/hospital/agendamento/arquitetura/ArquiteturaDoAgendamentoTest.java` | `void direcaoDasCamadas()` | regra ArchUnit sobre o código compilado |
| RNF-05 | domínio sem framework | `agendamento-service/src/test/java/br/com/fiap/hospital/agendamento/arquitetura/ArquiteturaDoAgendamentoTest.java` | `void dominioSemFramework()` | regra ArchUnit sobre o código compilado |
| RNF-05 | regras aplicadas ao código principal | `agendamento-service/src/test/java/br/com/fiap/hospital/agendamento/arquitetura/ArquiteturaDoAgendamentoTest.java` | `void importaSoOCodigoPrincipal()` | asserção anti-decorativa do escopo |
| RNF-06 | nenhum dublê e cada módulo prova a infra que usa | `quality-gates/src/test/java/br/com/fiap/hospital/qualidade/InfraestruturaRealTest.java` | `void reactorReal()` | **gate agregado** sobre o reactor real |
| RNF-06 | PostgreSQL real em execução | `agendamento-service/src/test/java/br/com/fiap/hospital/agendamento/integracao/InfraestruturaRealAgendamentoIT.java` | `void provaPostgreSql()` | prova de container real na suíte |
| RNF-06 | RabbitMQ real em execução | `agendamento-service/src/test/java/br/com/fiap/hospital/agendamento/integracao/InfraestruturaRealAgendamentoIT.java` | `void provaRabbitMq()` | prova de container real na suíte |
| RNF-07 | um comando sobe o ambiente completo | `Makefile` | `docker compose up -d --build --wait` | alvo de build que exige a subida |
| RNF-07 | imagens, saúde e dependências verificadas | `quality-gates/src/test/java/br/com/fiap/hospital/qualidade/AmbienteDeDemonstracaoTest.java` | `void configuracaoRealAtende()` | gate estrutural do Compose |
| RNF-08 | propagação HTTP | `agendamento-service/src/test/java/br/com/fiap/hospital/agendamento/integracao/CorrelacaoHttpIT.java` | `void identificadorRecebidoEHonrado()` | integração da fronteira HTTP |
| RNF-08 | propagação AMQP na notificação | `notificacao-service/src/test/java/br/com/fiap/hospital/notificacao/integracao/CorrelacaoNotificacaoIT.java` | `void correlacaoValeDuranteOProcessamento()` | integração do consumo |
| RNF-08 | propagação AMQP no histórico | `historico-service/src/test/java/br/com/fiap/hospital/historico/integracao/CorrelacaoHistoricoIT.java` | `void correlacaoValeDuranteAProjecao()` | integração da projeção |
| RNF-08 | mesmo id visível nos logs dos três serviços | `scripts/smoke-test.sh` | `registro_correlacionado()` | roteiro versionado que exige o fluxo completo |
| RNF-09 | schema provisionado por migration | `agendamento-service/src/test/java/br/com/fiap/hospital/agendamento/integracao/SchemaVersionadoIT.java` | `void provisionamentoCriaTabelasEIndices()` | integração com Postgres real |
| RNF-09 | nenhuma alteração fora de migration | `agendamento-service/src/test/java/br/com/fiap/hospital/agendamento/integracao/SchemaVersionadoIT.java` | `void hibernateNaoGeraSchema()` | integração com Postgres real |
| RNF-10 | nenhum serviço alcança database alheio | `quality-gates/src/test/java/br/com/fiap/hospital/qualidade/AmbienteDeDemonstracaoTest.java` | `deve usar exclusivamente` | regra de isolamento no gate do Compose |
| RNF-10 | três databases provisionados separadamente | `docker/postgres/init.sql` | `CREATE DATABASE historico_db;` | provisionamento versionado |

**Conferência.** As 30 linhas somam **75 pares**. Os **74** que apontam para artefatos existentes foram verificados no disco antes desta revisão: cada arquivo existe e contém a âncora literal, byte a byte. O 75º é a linha **(novo)** do RNF-01, que esta change cria. Nenhum par se repete entre requisitos, de modo que a cláusula de compartilhamento declarado do D5 não precisou ser usada.

### D6-A — A lacuna do RNF-01 e como ela é fechada

RNF-01 diz: *"Senhas armazenadas com BCrypt; nunca em log ou resposta"*. Três das quatro âncoras cobrem BCrypt, ausência na resposta do caso de uso e ausência no token. **Nenhuma suíte do projeto inspeciona log algum atrás de senha.** A cláusula estava descoberta, e uma varredura do repositório confirmou: não há teste que capture saída de log durante a autenticação. A task arquivada do M04 que menciona "senha jamais em resposta ou log" é uma afirmação de checklist, não prova executável, e não pode ser usada como âncora.

**Onde a cobertura vive: integração no `agendamento-service`.** A infraestrutura de captura já existe no projeto e é reutilizável sem inventar nada:

- `CorrelacaoHistoricoIT` e `OutboxRelayIT` já anexam um `ListAppender<ILoggingEvent>` do Logback a um logger em `@BeforeEach` e o removem em `@AfterEach`, restaurando o nível anterior. É o padrão da casa para observar log em teste.
- `CadeiaDeSegurancaIT` e `MatrizDeAutorizacaoIT` já exercitam `POST /auth/login` de verdade, com o hash BCrypt conhecido de `Senha@123` semeado no banco.
- As duas declaram exatamente `@SpringBootTest(webEnvironment = RANDOM_PORT)` mais `ContainerPostgres.registrarPropriedades` no `@DynamicPropertySource`. **Correção registrada na implementação:** isso não basta para compartilhar o contexto. O `DynamicPropertiesContextCustomizer` do Spring compara os `@DynamicPropertySource` pelo conjunto de métodos, e cada classe declara o seu — então cada IT do projeto já sobe um contexto próprio, e a suíte nova sobe o dela também. O custo medido foi de **1,0 s** com o container de Postgres já em pé, contra 12,7 s da primeira subida a frio: nenhum container extra, nenhuma suíte existente afetada. O desenho segue valendo; o que estava errado era a justificativa de reuso.
- Não há paralelismo configurado: o POM raiz não define `parallel`, `forkCount` nem `junit-platform.properties`, então as classes de integração rodam em sequência no mesmo fork. Anexar e desanexar um appender no logger raiz durante os dois logins não alcança outra suíte.

**O que o teste faz.** `SenhaForaDosLogsIT`, em `agendamento-service/src/test/.../integracao/`, com as mesmas anotações das duas suítes acima:

1. semeia o usuário com o hash BCrypt conhecido de `Senha@123`;
2. anexa um `ListAppender` ao logger raiz, em nível `DEBUG`, guardando o nível anterior;
3. executa `POST /auth/login` **com a senha correta** (sucesso) e **com senha errada** (recusa);
4. desanexa o appender e restaura o nível, em `@AfterEach`, mesmo em falha;
5. afirma que nenhum evento capturado — mensagem formatada, argumentos e cadeia de `throwable` — contém a senha em claro nem o hash armazenado, e que os dois corpos de resposta também não;
6. afirma que a captura **não veio vazia**, para que o teste não passe por não ter observado nada.

**Limite declarado.** A prova cobre o que é escrito por esta JVM durante as duas chamadas de login, no nível `DEBUG` ou acima. Ela não cobre log de bibliotecas que escrevam fora do Logback, nem o que outros serviços registram, nem níveis mais verbosos ligados só em produção. O relatório técnico registra esse limite junto com a evidência.

**Se durante a implementação a suíte criar contexto novo ou interferir nas existentes** — por exemplo, se o appender no logger raiz capturar ruído de outra suíte ou se a configuração divergir da chave de cache —, a alternativa é uma **guarda estrutural no `quality-gates`**: varrer o código de produção do caminho de autenticação e reprovar qualquer chamada de log que receba a senha ou o hash como argumento. É prova mais fraca, porque inspeciona código em vez de comportamento, e nesse caso o limite entra explicitamente no D6, no relatório e no corpo do PR, em vez de ficar implícito.

**O que a execução revelou, e por que `src/main` foi tocado.** O teste subiu, autenticou, recusou e **falhou**, com duas origens nomeadas pelo logger:

- `org.springframework.web.client.RestTemplate` registrando o corpo que a própria suíte envia — ruído do cliente de teste, não produção;
- `org.springframework.web.servlet.mvc.method.annotation.RequestResponseBodyMethodProcessor` registrando `LoginRequest[email=..., senha=Senha@123]` — lado servidor, com a senha em claro.

O segundo é violação real do RNF-01. A causa é o `toString()` gerado do `record`, que inclui todos os componentes. Em `INFO` nada vaza, mas o requisito diz **nunca**, e depender do nível de log é depender de configuração, não de garantia.

Três saídas foram descartadas: restringir o teste a loggers de produção ou baixar o nível esconde o achado; a guarda estrutural alternativa **não pega este caso**, porque quem registra é o framework, não código nosso; e deixar a cláusula sem prova mantém o requisito parcialmente coberto.

**Exceção nominal de produção.** O M14 corrige a causa em `agendamento-service/src/main/java/br/com/fiap/hospital/agendamento/infrastructure/web/LoginRequest.java`: o `toString()` é sobrescrito para nunca conter o valor de `senha`. É a menor correção que torna o RNF-01 verdadeiro em qualquer nível de log. Não muda o contrato JSON, a desserialização, a validação nem a autenticação — `toString()` não participa de nenhum deles. Nenhum outro arquivo de produção é tocado.

**O ruído do cliente sai pela raiz.** Em vez de ignorar `org.springframework.web.client.*` — que abriria a porta para esconder um vazamento de produção por engano —, o IT passa a usar `java.net.http.HttpClient` do JDK. O cliente não fala pelo Logback, então o `ListAppender` no logger raiz observa só o lado servidor, sem exceção genérica alguma.

**O que mudou nesta revisão e por quê.** A versão anterior tinha uma âncora por requisito, e várias eram reais porém parciais. Além dos seis casos apontados na revisão — RF-11, RF-15, RF-19, RNF-01, RNF-06 e RNF-08 —, a releitura semântica das outras 24 linhas trocou ou complementou:

- **RF-01, RF-03, RF-04, RF-05, RF-06, RF-07, RF-08, RF-09, RF-10** ganharam o par que faltava entre a regra de negócio e o status HTTP que o requisito nomeia, ou entre as duas metades do texto (cancelar × motivo obrigatório; conflito de médico × conflito de paciente).
- **RF-12, RF-13, RF-14, RF-16, RF-17, RF-18, RF-20** ganharam o par da cláusula não coberta: leitura das próprias consultas, autoria da correção, leitura completa da matriz, evento de edição, disparo automático, tipos normativos e tentativas configuradas antes da DLQ.
- **RNF-02, RNF-03, RNF-04, RNF-05, RNF-09, RNF-10** passaram a ancorar tanto na configuração normativa quanto na verificação que a exerce.
- **RF-15** trocou `AtomicidadeDoOutboxIT` por `PublicacaoDeEventosUseCaseTest`, cujo `cadaMudancaPublicaOEventoCorrespondente` cobre criação **e** edição, que é o que o texto do requisito exige.
- **RNF-06** passou a ancorar primeiro no gate agregado `InfraestruturaRealTest#reactorReal`, que verifica o reactor inteiro — nenhum dublê e cada módulo provando a infraestrutura que usa —, com as duas provas de runtime do agendamento como complemento.
- **RF-02** permaneceu com um par só: o texto tem uma cláusula, e `existemOsTresPerfis` afirma exatamente os três valores do enunciado.
- **RNF-01** foi o único caso em que a releitura encontrou uma cláusula sem prova alguma no projeto — "nunca em log" —, e não uma prova parcial a complementar. Ganhou a quarta âncora, que esta change cria; o D6-A explica o desenho e o limite.

**Requisitos garantidos por execução.** RNF-04, RNF-06 e RNF-07 não são aprovados por o catálogo citar um comando. RNF-04 ancora no piso numérico, na execução declarada no POM e no teste que preserva a ordem dos gates; RNF-06, no gate agregado e nas provas de container real; RNF-07, no alvo de build e no gate estrutural do Compose. As execuções correspondentes — `mvn -q clean verify`, as suítes de integração e `make demo` — são registradas separadamente no corpo do PR e na seção de testes do relatório.

### D7 — Saída, agrupamento e códigos de saída

A saída é texto simples, estável e legível sem ferramenta:

```
REQUISITO | STATUS | EVIDÊNCIA
```

- uma linha por requisito, na ordem do documento, com `OK` ou `FALHA` no campo do meio;
- as linhas são agrupadas pelos títulos das próprias subseções de `docs/02` — as quatro famílias de RF e a tabela de RNF —, e não por uma divisão inventada. O roadmap manda "espelhar o script de 13 seções da Fase 2"; como aquele script não está neste repositório, o que se espelha é a **forma** (relatório seccionado, uma linha por item, sumário e falha fechada), com as seções derivadas da fonte normativa deste projeto. Essa leitura está registrada aqui de propósito;
- o cabeçalho traz a frase que fixa a semântica do D2: `OK` é evidência presente e ancorada, não reexecução;
- ao final, um sumário com inventariados, aprovados e reprovados.

Códigos de saída, seguindo a convenção já usada em `smoke-test.sh` e `demo.sh`:

| Código | Significado |
|---:|---|
| 0 | todos os requisitos aprovados |
| 1 | ao menos um requisito reprovado: artefato ausente ou âncora não encontrada |
| 2 | pré-requisito ausente |
| 3 | inventário inconsistente: fora da sequência fechada, duplicado, requisito sem entrada ou entrada órfã |
| 4 | especificação funcional ilegível ou tabela não encontrada |

### D8 — Roteiro somente leitura, com raiz injetável e carregável por `source`

Três propriedades, cada uma com uma razão:

- **Somente leitura.** O script não escreve nada, nem arquivo temporário. Não invoca `docker`, `docker compose`, `mvn`, `psql`, `curl`, `pkill`, `killall`, `mktemp` nem `git` de escrita, e não redireciona escrita para caminho do repositório.

**Como o gate verifica isso: invocação, não ocorrência.** O `RoteiroDeSmoke` recusa esses nomes por simples ocorrência no texto, e funciona porque o smoke genuinamente nunca os menciona. Aqui isso não se sustenta: o catálogo do D6 precisa carregar literalmente `docker compose up -d --build --wait`, que é a âncora do RNF-07, e o caminho `docker/postgres/init.sql`, que é o artefato do RNF-10. Uma regra por ocorrência obrigaria a trocar duas âncoras legítimas por âncoras mais fracas só para agradar o gate — enfraquecer a auditoria para fazê-la passar, que é exatamente o que este change existe para impedir.

A regra passa a separar **dado** de **código executável**:

- `docker`, `mvn`, `psql`, `curl`, `pkill`, `killall` e `mktemp` **podem** aparecer em comentários, em texto de documentação e nos valores literais das entradas do catálogo;
- o gate **reprova** qualquer um deles usado como **comando**: em início de comando, depois de separador do shell (`;`, `&&`, `||`, `|`, `&`, `(`, `{`, nova linha), dentro de substituição de comando ou de subshell (`$(...)`, crase, `( ... )`), e também invocado por caminho absoluto (`/usr/bin/docker`, `/usr/local/bin/mvn`);
- antes de analisar, o gate **descarta** os comentários e o conteúdo literal das entradas do catálogo, de modo que a análise recaia só sobre o que a shell executaria.

Isso preserva a garantia real — a auditoria não alcança ambiente algum — sem confundir uma tabela de dados com uma chamada de sistema.

**Controle de invocação em execução, além do textual.** A análise estática pode errar; por isso o gate também prova o ponto executando. Ele monta um diretório com executáveis **sentinela** chamados `docker`, `mvn`, `psql`, `curl`, `pkill`, `killall` e `mktemp`, cada um registrando a própria chamada e saindo com erro, coloca esse diretório no **início do `PATH`** e roda a auditoria inteira. A auditoria precisa terminar com código 0 e **nenhum sentinela pode ter sido chamado**. Se a análise textual algum dia deixar passar uma invocação, este controle a pega.

O catálogo continua dentro do próprio script: nenhum arquivo de dados separado é criado.
- **Raiz injetável.** Aceita um argumento opcional com a raiz do repositório, cujo padrão é o diretório acima do próprio script. É o que permite ao gate montar uma cópia sintética mutilada e exigir vermelho sem tocar na árvore real.
- **Carregável por `source`.** O fluxo só roda sob `if [[ "${BASH_SOURCE[0]}" == "$0" ]]`, como no smoke e no demo, para que o teste exercite funções isoladas.

Pré-requisitos: Bash compatível e as ferramentas de texto do ambiente. **Sem `jq`, sem `curl`, sem rede** — a auditoria precisa rodar em qualquer clone.

### D9 — Quatro arquivos de teste novos e uma única exceção de produção

O M14 acrescenta quatro arquivos de teste: **três** no `quality-gates/src/test` e **um** no `agendamento-service/src/test`, o `SenhaForaDosLogsIT` do D6-A.

Altera, além deles, **exatamente um** arquivo de produção: `agendamento-service/src/main/java/br/com/fiap/hospital/agendamento/infrastructure/web/LoginRequest.java`, pela violação do RNF-01 que a auditoria revelou. Nenhum outro arquivo sob `src/main` de nenhum módulo — inclusive o próprio `quality-gates` — é criado ou alterado, e o diff torna isso verificável numa afirmação só.

A separação dos gates em **três** classes, e não uma, existe para não criar o ciclo do D10: cada gate pode ficar verde assim que o artefato que ele verifica existir. O quarto arquivo não é gate: é cobertura de comportamento, e vive junto das suítes que já exercitam o login.

- **`AuditoriaDeRequisitosTest`** — lê `docs/02` com parser próprio e afirma 20 RF e 10 RNF; executa `scripts/auditoria.sh` de verdade sobre o repositório e exige código 0, cabeçalho exato, 30 linhas de requisito, três campos por linha e sumário coerente; confere o catálogo contra as regras do D5; e aplica os negativos sintéticos do D10.
- **`RoteiroDeApresentacaoTest`** — verifica `docs/roteiro-demo.md`: soma das durações dos blocos cronometrados entre 5 e 8 minutos, seção de pré-demonstração presente e fora da soma, as cinco superfícies com os endereços reais, credenciais conferidas contra `docs/02` §5, comandos existentes em `Makefile` ou `scripts/`, ausência de comando que remova volumes e uma alternativa curta por bloco.
- **`RelatorioTecnicoTest`** — verifica `docs/relatorio-tecnico.md`: as oito seções na ordem, a tabela de números com coluna de origem preenchida, links locais válidos e ausência de binário de escritório versionado.

- **`SenhaForaDosLogsIT`**, em `agendamento-service/src/test/.../integracao/` — a cobertura nova do D6-A. Não é gate estrutural nem verifica artefato desta change; prova comportamento de produção que nenhuma suíte provava.

O nome `RoteiroDeApresentacaoTest` evita colisão semântica com o `RoteiroDeDemonstracaoTest` já existente, que exercita `scripts/demo.sh` e não tem relação com `docs/roteiro-demo.md`.

Como `SenhaForaDosLogsIT` é criado antes do gate de medição, ele entra nas duas execuções completas do D10 e não participa da diferença de contagem entre elas, que continua atribuível apenas ao `RelatorioTecnicoTest`.

### D10 — Ordem que evita o ciclo gate ↔ relatório e a métrica inventada

O relatório precisa de números reais; os números vêm de um build completo; e um gate que exige o relatório reprovaria esse build antes de ele existir. A ordem abaixo quebra o ciclo com **duas** execuções completas, e só duas:

1. **Gate da auditoria e script.** `AuditoriaDeRequisitosTest` primeiro, vermelho; depois `scripts/auditoria.sh`, até verde.
2. **Gate do roteiro e roteiro.** `RoteiroDeApresentacaoTest` vermelho; depois `docs/roteiro-demo.md`, até verde.
3. **Gate de medição** — `mvn -q clean verify` na raiz, uma vez. Nesse ponto `RelatorioTecnicoTest` **ainda não existe**, então nenhum gate exige um artefato ausente e o build é verde por mérito. Dele saem contagens por módulo, total de casos, pulados e as três coberturas. Na mesma etapa rodam `scripts/smoke-test.sh` e a auditoria com e sem ambiente no ar.
4. **Relatório.** `RelatorioTecnicoTest` é criado e fica vermelho; `docs/relatorio-tecnico.md` é escrito **com os números do passo 3**, cada um nomeando o comando que o produziu; o gate fica verde.
5. **Gate final** — `mvn -q clean verify` na raiz, uma segunda e última vez, agora com os três gates novos e o relatório completo.

**Os números do relatório são os do gate de medição, e não mudam depois.** O relatório diz isso na própria seção de testes: a origem declarada é o *gate de medição do M14*, a execução do passo 3. O gate final é evidência **separada**, registrada no corpo do PR, e não reescreve o relatório.

Isso é necessário porque as duas execuções medem árvores diferentes de propósito: o gate final inclui `RelatorioTecnicoTest`, que não existia no gate de medição. A contagem de testes portanto **difere**, e a diferença é esperada e explicável — não é um defeito a corrigir por edição. O que a comparação exige:

- **diferença na quantidade de testes**, atribuível aos casos de `RelatorioTecnicoTest`: esperada, registrada no PR, sem tocar no relatório;
- **cobertura abaixo dos pisos, suíte vermelha, teste pulado ou qualquer divergência não atribuível a esses casos**: a entrega **para** para investigação. Não se edita o relatório para acomodar o número novo, e não se segue adiante com o desvio anotado como curiosidade.

Nenhuma alteração no relatório acontece depois do gate final, de modo que a última execução completa é também a última mutação da árvore. Nenhum gate é declarado verde com artefato obrigatório ausente, nenhum número entra no documento sem execução que o produza, e existem exatamente duas rodadas de `verify`.

### D11 — Negativos sintéticos em cópia temporária, uma mutação por caso

Os negativos vivem no teste automatizado e não mutilam a árvore de trabalho. Cada caso monta, num diretório temporário, uma cópia mínima do que a auditoria lê — `scripts/auditoria.sh`, `docs/02-especificacao-funcional.md` e os artefatos de evidência —, aplica **uma** mutação e executa o script com a raiz apontada para lá:

| Caso | Mutação | Resultado exigido |
|---|---|---|
| requisito omitido | remover a entrada de um RF do catálogo | código 3, identificando o requisito sem evidência |
| sequência quebrada | remover a linha de RF-07 da tabela de `docs/02` | código 3, identificando a lacuna na sequência fechada |
| identificador duplicado | repetir a linha de um RF na tabela de `docs/02` | código 3, identificando o identificador repetido |
| entrada órfã | acrescentar ao catálogo um identificador inexistente | código 3, identificando a entrada órfã |
| evidência inexistente | remover o artefato de evidência de um requisito | código 1, identificando requisito e artefato |
| âncora removida | apagar a âncora, mantendo o arquivo | código 1, identificando o requisito e a âncora |
| âncora obrigatória ausente | num requisito multi-âncora, apagar **uma** das âncoras e manter as outras | código 1, reprovando o requisito inteiro apesar das demais passarem |
| falso positivo | trocar a âncora pela menção textual do identificador | vermelho no gate estrutural, pela regra do D5 |

Como esses oito casos já vivem no teste e rodam em todo `verify`, a prova final de sensibilidade não os repete. Ela se limita às duas mutações que acrescentam evidência nova, porque atacam os arquivos reais e não a cópia: apagar uma âncora real do repositório e substituir uma evidência real por menção textual.

### D12 — Roteiro: pré-demonstração fora do relógio, e nada de apagar volumes

O roteiro tem duas partes. A **pré-demonstração** não entra na contagem: subir o ambiente, esperar os seis containers saudáveis, abrir as abas das quatro interfaces e deixar os dados prontos. O cronômetro começa com tudo no ar. Isso reflete a realidade — construir imagens pode levar mais que a janela inteira — e mantém a promessa de 5 a 8 minutos ligada ao que a banca assiste.

Os blocos cronometrados, todos apoiados em artefatos reais:

1. autenticação e criação da consulta no Swagger de `:8081`;
2. mensagem no broker pela interface de administração de `:15672`;
3. e-mail do paciente na caixa de entrada de `:8025`;
4. leitura do histórico no GraphiQL de `:8083`;
5. o mesmo `correlationId` nos logs dos três serviços;
6. disparo manual do lembrete D-1 e o e-mail correspondente.

As alternativas curtas usam o que já existe: quando uma interface não responde, o mesmo ponto é comprovado pela resposta da API, pelo log do serviço ou pela saída do `make demo`, que já imprime `consultaId`, identificadores dos e-mails e `lembretesEnviados`.

**Nada de `docker compose down -v`.** O ensaio usa o ambiente existente e a idempotência já garantida pelo `make demo`, que reaproveita a consulta de demonstração na janela em vez de criar outra. A subida do zero com volumes vazios já foi comprovada e arquivada no M12; repeti-la aqui destruiria dados locais e exigiria autorização explícita do Gabriel, que esta change não pede.

### D13 — Documentação afetada: só o README

Apenas `README.md` é atualizado, e de forma pontual: um apontamento para os três artefatos novos e a distinção entre auditoria de execução e auditoria de requisitos. `docs/00` a `docs/05`, os sete ADRs, o índice de ADRs, o `CHANGELOG.md` e o `pom.xml` não são tocados — nenhum deles fica incorreto por causa desta change, e mexer neles por hábito criaria diff sem conteúdo.

As afirmações de "nenhum outro documento tocado" nas tasks excluem explicitamente `openspec/changes/finalize-audit-report/`, que é o artefato desta própria change e muda por definição.

## Risks / Trade-offs

- **Ler `OK` como prova de execução** → a semântica está fixada no D2, impressa no cabeçalho da saída, declarada no relatório e no README, e nenhum verificador aprova por nome de comando.
- **Parser do documento deixa de encontrar as tabelas e a auditoria aprova o vazio** → sequência fechada exigida pelo script mais contagem exata afirmada por parser independente no gate, mais dois negativos de inventário.
- **Sequência fechada engessa o documento** → aceito e declarado no D3: acrescentar um requisito exige atualizar script e catálogo, o que é o ponto.
- **Catálogo vira lista de conveniência** → regras do D5 verificadas pelo gate, tabela do D6 conferida no disco e revisada no PR.
- **Âncora real mas parcial aprovar um requisito inteiro** → conjunto de pares por cláusula (D4), revisão semântica das 30 linhas registrada no D6 e conjunção obrigatória: faltar uma âncora reprova o requisito, com negativo automatizado.
- **Catálogo multi-âncora inchar até virar manutenção pesada** → o limite é o texto do requisito, não a ambição: 75 pares para 30 requisitos, entre um e quatro por linha, 74 deles em suítes que já existiam.
- **Acrescentar teste virar hábito para fazer o catálogo fechar** → o D5 separa os dois casos: teste novo só se justifica quando a auditoria expõe uma cláusula **sem prova alguma**, como a de log do RNF-01. Onde havia prova parcial, a resposta foi acrescentar âncora, não acrescentar teste. Foi um caso em 30.
- **A correção em `LoginRequest` quebrar login ou contrato JSON** → `toString()` não participa de desserialização, validação, autenticação nem da resposta; as suítes de login existentes e o próprio `SenhaForaDosLogsIT` exigem 200 no acerto e 401 na recusa.
- **Abrir precedente para o M14 alterar produção** → exceção **nominal e única**, declarada na proposal, no D6-A e no D9, e verificada pela task 6.5, que exige que `LoginRequest.java` seja o único arquivo de produção no diff.
- **Outro DTO expor segredo pelo `toString()` gerado** → fora do escopo do M14 e não corrigido aqui; `LoginRequest` é o único que carrega senha. Fica registrado no relatório como limite conhecido.
- **`SenhaForaDosLogsIT` criar contexto novo ou capturar ruído de outra suíte** → as mesmas anotações de `CadeiaDeSegurancaIT` e `MatrizDeAutorizacaoIT`, que compartilham a chave do cache de contexto; appender anexado e removido por caso, com o nível restaurado; execução sequencial, porque não há paralelismo configurado. Se ainda assim interferir, a alternativa é a guarda estrutural do D6-A, com o limite declarado no relatório e no PR.
- **Teste de log passar por não ter observado nada** → asserção explícita de que a captura não veio vazia, além da ausência da senha e do hash.
- **A prova de log ser lida como garantia absoluta** → limite declarado no D6-A e repetido no relatório: ela cobre o que esta JVM escreve durante os dois logins, em `DEBUG` ou acima, e não alcança bibliotecas fora do Logback nem níveis ligados só em produção.
- **Confusão entre auditoria de execução e auditoria de requisitos** → nomes distintos, arquivos distintos, ciclos distintos e uma linha no README e no relatório explicando a fronteira.
- **Número inventado no relatório** → ordem do D10, com a redação depois da medição e a tabela de origem exigida pelo gate.
- **Contagem do gate final divergir da tabela do relatório** → esperado e explicado no D10: o relatório declara o gate de medição como origem, o gate final é evidência separada no PR, e só divergência não atribuível a `RelatorioTecnicoTest` interrompe a entrega para investigação.
- **Roteiro envelhecer em relação ao ambiente** → o gate confere endereços, credenciais e comandos contra os artefatos reais; uma mudança de porta ou de senha quebra o teste.
- **Demonstração ao vivo depender de uma interface lenta** → pré-demonstração fora do relógio, alternativa curta por bloco e folga dentro da janela.
- **Diferenças de Bash entre Windows e Linux** → o script usa apenas construções já presentes no smoke e no demo, e o teste localiza o Bash nativo com a mesma estratégia de `RoteiroDeDemonstracaoTest`.

## Migration Plan

Ver a ordem completa no D10. Em resumo: gate da auditoria e script; gate do roteiro e roteiro; gate de medição com `verify`, smoke e auditoria; relatório com os números medidos; gate final; README; e archive na mesma feature branch após aprovação do PR, antes do merge.

Rollback remove os três artefatos novos, os quatro arquivos de teste e o trecho do README, e reverte `LoginRequest#toString()` ao `toString()` gerado do `record`. Não há dado, migration nem container a reverter: a auditoria é somente leitura e nada do M14 é executado em runtime pelo sistema. Reverter a correção de produção reabre a violação do RNF-01 que a auditoria encontrou, então ela só deve ser desfeita junto com o teste que a cobre.

## Open Questions

Nenhuma. A única indefinição herdada — o que significa "espelhar o script de 13 seções da Fase 2", já que aquele repositório não está aqui — foi resolvida no D7 e registrada como leitura explícita, não como pendência.
