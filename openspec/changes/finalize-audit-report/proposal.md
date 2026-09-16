## Why

`docs/02-especificacao-funcional.md` declara 20 requisitos funcionais e 10 não funcionais e afirma que "a auditoria final (M14) percorre esta lista". Essa auditoria não existe: hoje a única forma de responder "RF-09 está entregue e onde está a prova?" é ler o repositório inteiro à mão, e nada impede que um requisito seja declarado no documento e nunca ganhe evidência — ou que uma evidência desapareça sem ninguém perceber.

Faltam também os dois documentos de fechamento da entrega: o relatório técnico, que consolida arquitetura, decisões, garantias e limites para quem avalia sem abrir o código, e o roteiro de demonstração cronometrado, que torna a apresentação repetível em vez de improvisada.

## What Changes

- **`scripts/auditoria.sh`** — roteiro Bash que percorre cada RF e RNF declarado em `docs/02-especificacao-funcional.md`, confere a evidência correspondente no repositório e imprime `REQUISITO | STATUS | EVIDÊNCIA`, uma linha por requisito. Falha fechado, com código diferente de zero, diante de requisito sem evidência, identificador duplicado, evidência inexistente ou âncora não encontrada. Não sobe container, não toca banco, não executa Maven e não escreve no repositório.
- **`OK` com significado estreito.** A auditoria é estática: `OK` afirma que a evidência versionada daquele requisito existe e está ancorada no elemento declarado — **não** que o comportamento foi reexecutado. As execuções que provam comportamento (`mvn -q clean verify`, `scripts/smoke-test.sh` e `make demo`) permanecem evidências separadas, registradas no PR e no relatório.
- **Inventário fechado.** Além de derivar os identificadores do documento, a auditoria exige a sequência completa RF-01…RF-20 e RNF-01…RNF-10, contínua, única e na ordem. Documento reduzido, lacuna no meio, número fora da sequência ou catálogo incompleto reprovam, tudo sem executar o build.
- **Evidência ancorada, e só ancorada.** Há um único verificador: o artefato versionado existe, é legível e contém a âncora literal declarada. Não existe verificador de mera existência de arquivo. Uma citação do identificador em prosa, ou o simples nome de um comando, não satisfaz requisito algum — é exatamente o falso positivo que um `grep RF-09` produziria.
- **Uma linha de saída por requisito, um ou mais pares de evidência.** O catálogo declara tantos pares artefato + âncora quantas forem as cláusulas normativas do texto do requisito — quatro para RF-11, um para RF-02 —, e o resultado é a conjunção: faltar qualquer âncora reprova o requisito inteiro. Uma prova real porém parcial não basta. São **75 pares** para os 30 requisitos, 74 deles em suítes que já existem.
- **Uma violação real encontrada e corrigida.** A auditoria não só expôs a cláusula "nunca em log" do RNF-01 sem prova: o teste escrito para fechá-la **encontrou o requisito sendo violado**. Com `org.springframework.web` em `DEBUG`, o resolvedor de `@RequestBody` do Spring MVC registra o objeto desserializado, e a senha em claro ia junto porque `LoginRequest` é um `record` — o `toString()` gerado inclui todos os componentes. Na entrega o nível é `INFO` e nada vazava na prática, mas o RNF-01 diz **nunca**, e a garantia dependia do nível de log calhar de estar alto. O M14 corrige a causa em `LoginRequest` e mantém o teste exigindo ausência total em `DEBUG`.
- **Uma lacuna real fechada, não uma âncora de conveniência.** A revisão semântica das 30 linhas encontrou uma cláusula sem prova alguma no projeto: o "nunca em log" do RNF-01. Nenhuma suíte inspecionava o que a autenticação escreve no log. O M14 acrescenta `SenhaForaDosLogsIT` no `agendamento-service`, que executa o login com senha conhecida — sucesso e recusa —, captura os logs produzidos e afirma que nem a senha em claro nem o hash aparecem. É o único teste novo, e existe porque a auditoria expôs um requisito descoberto, não para dar verde ao catálogo. A afirmação de checklist da task arquivada do M04 não é usada como prova.
- **`docs/relatorio-tecnico.md`** — relatório técnico em português: contexto, arquitetura, decisões e trade-offs, segurança, mensageria e garantias de entrega, estratégia de testes com números verificáveis, limites conhecidos e aprendizados. Os números vivem numa tabela em que cada linha nomeia o comando que a produziu; nada é escrito antes de a execução correspondente existir.
- **`docs/roteiro-demo.md`** — roteiro cronometrado, com a apresentação entre 5 e 8 minutos, cobrindo Swagger, GraphiQL, RabbitMQ Management, Mailpit e os logs com `correlationId`. A preparação — subida, saúde, abertura das interfaces e dados — vive numa seção de pré-demonstração fora da contagem, porque a janela mede o que a banca assiste, não o build das imagens. Comandos, URLs e credenciais vêm dos artefatos reais, nenhum comando remove volumes, e cada bloco tem uma alternativa curta para quando uma interface demorar ou falhar.
- **Três gates estruturais no `quality-gates`** — em JDK puro, um por artefato novo, para que cada um possa ficar verde assim que o artefato que ele verifica existir: inventário e ancoragem da auditoria; janela e superfícies do roteiro; seções e números do relatório. Os negativos sintéticos cobrem requisito omitido, sequência quebrada, identificador duplicado, entrada órfã, artefato ausente, âncora removida e falso positivo.

Esta change não fecha RF ou RNF novo: ela **audita** RF-01 a RF-20 e RNF-01 a RNF-10, já entregues pelos changes M01 a M13. Release alvo: **1.0.0**. Change do roadmap: **M14 · `finalize-audit-report`**.

### O que não muda

- Código de produção, com **uma única exceção nominal**: `agendamento-service/src/main/java/br/com/fiap/hospital/agendamento/infrastructure/web/LoginRequest.java`, cujo `toString()` passa a nunca expor o valor de `senha`. Nenhum outro arquivo sob `src/main` de nenhum módulo é criado ou alterado. Nenhum endpoint, schema GraphQL, regra de negócio, matriz de autorização, contrato AMQP, migration, seed ou configuração de aplicação muda; o contrato JSON de `POST /auth/login` e o fluxo de autenticação continuam idênticos.
- O comportamento das cinco capabilities funcionais. O M14 observa o que já está entregue; não redefine nada.
- `docker-compose.yml`, `Makefile`, `scripts/demo.sh`, `scripts/smoke-test.sh` e o pacote Postman do M13.
- Os gates existentes: agregação JaCoCo, `AuditoriaDeExecucao`, verificação de infraestrutura real e pisos de cobertura continuam como estão. A auditoria de requisitos é um instrumento novo e separado, executado fora do ciclo Maven.
- O ambiente local do Gabriel: nenhum passo desta change executa `docker compose down -v` nem remove volumes. O ensaio da demonstração usa o `make demo` idempotente; a subida do zero já foi comprovada e arquivada no M12.
- `CHANGELOG.md`, a propriedade `revision` do POM e qualquer operação de release. Abertura de `release/1.0.0`, versão, changelog, merge e tag são procedimento posterior ao merge do M14, executado pelo Gabriel.
- Nenhum DOCX é criado ou convertido aqui. A conversão do relatório é feita fora do Claude Code, conforme o roadmap.

## Capabilities

### New Capabilities

- Nenhuma.

### Modified Capabilities

- `operacao-do-ambiente`: acrescenta a auditoria de requisitos, o relatório técnico e o roteiro de demonstração como artefatos operacionais verificáveis do fechamento da entrega.

O roadmap registra o M14 como "Capability: todas", porque a auditoria percorre os requisitos de todas elas. O alcance é transversal na **leitura**; o contrato novo é operacional. Criar deltas nas cinco capabilities funcionais significaria reescrever Requirements de comportamento que esta change não altera — deltas artificiais que aumentariam o risco de divergência no archive sem acrescentar garantia. A verificação foi feita: nenhuma spec promovida menciona RF/RNF, `scripts/auditoria.sh`, `docs/relatorio-tecnico.md` ou `docs/roteiro-demo.md`, portanto não há conflito com contrato já promovido.

## Impact

- Novo roteiro versionado em `scripts/auditoria.sh`, sem dependência de Docker, Maven, rede ou `jq`.
- Dois documentos novos em `docs/`: `relatorio-tecnico.md` e `roteiro-demo.md`.
- Três verificações puramente técnicas novas em `quality-gates/src/test`, sem dependência nova no POM e sem alcançar os módulos de código.
- Uma suíte de integração nova em `agendamento-service/src/test`, `SenhaForaDosLogsIT`, que reaproveita a configuração de contexto já usada no repositório e captura o log por `ListAppender` — sem contexto novo, sem container extra e sem dependência nova. O cliente HTTP é o `java.net.http.HttpClient` do JDK, para que o `ListAppender` no logger raiz observe só o lado servidor.
- Uma alteração mínima de produção, a única desta change: `LoginRequest#toString()` deixa de expor o valor de `senha`.
- Atualização pontual de `README.md` para apontar os três artefatos novos e distinguir auditoria de execução de auditoria de requisitos. Nenhum outro documento é tocado, além da própria change em `openspec/changes/finalize-audit-report/`.
- Evidência de aceite: execução real de `scripts/auditoria.sh`, `scripts/smoke-test.sh`, duas execuções de `mvn -q clean verify` na raiz — uma de medição, antes de o relatório existir, e uma final, depois — e verificação em clone limpo. Os números do relatório vêm da execução de medição e são declarados como tais; o gate final é evidência separada no PR e não reescreve o relatório, de modo que nenhuma alteração ocorre depois da última execução completa.
