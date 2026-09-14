# operacao-do-ambiente

## Purpose

Define as garantias de build reprodutível, infraestrutura local isolada e separação entre testes unitários e de integração necessárias para executar e verificar o monorepo de forma consistente.

## Requirements

### Requirement: Build reprodutível do monorepo

O projeto SHALL ser construído por um único comando Maven na raiz, compilando todos os módulos com versão centralizada.

Além do POM raiz agregador, o reactor SHALL ser composto por cinco módulos de código — `shared-contracts`, `shared-security`, `agendamento-service`, `notificacao-service` e `historico-service` — e por um único módulo técnico de verificação.

- O módulo técnico SHALL NOT conter serviço, aplicação Spring Boot, regra de negócio nem código consumido por outro módulo.
- Nenhum módulo de código SHALL depender do módulo técnico.
- O módulo técnico SHALL depender de todos os módulos de código, de modo que seja construído depois deles independentemente da ordem de declaração.
- O módulo técnico SHALL concentrar a agregação das evidências de teste e de cobertura e a aplicação dos gates ao final do reactor.

O empacotamento SHALL produzir, para cada um dos três serviços, um artefato executável diretamente pela JVM, sem Maven, sem substituir o artefato de biblioteca do módulo.

#### Scenario: Build completo a partir de um clone limpo
- **WHEN** um desenvolvedor executa `mvn clean verify` na raiz do repositório recém-clonado
- **THEN** os cinco módulos de código e o módulo técnico compilam sem erro
- **AND** o módulo técnico é o último projeto construído no reactor
- **AND** nenhum plugin é resolvido sem versão explícita
- **AND** o build termina com sucesso

#### Scenario: Alteração de versão em um único ponto
- **WHEN** a propriedade `revision` do POM pai é alterada
- **THEN** todos os módulos passam a ser construídos com a nova versão
- **AND** os POMs gerados não contêm o placeholder `${revision}` literal

#### Scenario: Módulo de código fora da agregação é recusado
- **WHEN** um módulo de código é declarado no reactor sem que o módulo técnico dependa dele
- **THEN** a verificação estrutural do módulo técnico falha identificando o módulo não agregado

#### Scenario: Módulo técnico permanece estritamente técnico
- **WHEN** os módulos do reactor são inspecionados
- **THEN** nenhum módulo de código declara dependência do módulo técnico
- **AND** o módulo técnico não contém aplicação Spring Boot nem código de domínio de serviço algum

#### Scenario: Serviços empacotados como aplicações executáveis
- **WHEN** um desenvolvedor executa o empacotamento a partir da raiz
- **THEN** cada um dos três serviços possui um artefato que inicia com `java -jar`, sem Maven
- **AND** o artefato de biblioteca de cada módulo continua sendo o consumido pelo reactor

### Requirement: Ambiente de infraestrutura local

O ambiente SHALL prover PostgreSQL e RabbitMQ prontos para uso por um único comando, sem configuração manual.

#### Scenario: Subida da infraestrutura
- **WHEN** um desenvolvedor executa `docker compose up -d` com o `.env` preenchido a partir do `.env.example`
- **THEN** o container do PostgreSQL atinge o estado `healthy`
- **AND** o container do RabbitMQ atinge o estado `healthy`
- **AND** o painel de gerenciamento do RabbitMQ responde na porta configurada

#### Scenario: Isolamento de dados por serviço
- **WHEN** a infraestrutura sobe pela primeira vez
- **THEN** existem os databases `agendamento_db`, `notificacao_db` e `historico_db`
- **AND** nenhum deles compartilha tabelas com os demais

#### Scenario: Recriação do ambiente do zero
- **WHEN** um desenvolvedor executa `docker compose down -v` seguido de `docker compose up -d`
- **THEN** os três databases são recriados
- **AND** o ambiente volta ao estado inicial sem intervenção manual

### Requirement: Separação entre testes unitários e de integração

O build SHALL executar testes unitários e de integração em fases distintas, identificados por convenção de nome.

#### Scenario: Execução apenas dos testes unitários
- **WHEN** um desenvolvedor executa `mvn test`
- **THEN** apenas as classes terminadas em `Test` são executadas
- **AND** nenhum container do Testcontainers é iniciado

#### Scenario: Execução da suíte completa
- **WHEN** um desenvolvedor executa `mvn verify`
- **THEN** as classes terminadas em `Test` e em `IT` são executadas

### Requirement: Gate agregado de cobertura

O build completo SHALL gerar, ao final do reactor, um relatório de cobertura agregado dos cinco módulos de código, em formato legível por máquina e em HTML, a partir dos dados de execução dos testes unitários e de integração da mesma execução. O gate SHALL ler exatamente o relatório legível por máquina publicado nessa execução.

O gate global SHALL abrir uma sessão de verificação antes de qualquer teste dos módulos:

- os dados de execução de cobertura, os relatórios de teste e o relatório agregado deixados por execuções anteriores SHALL ser removidos;
- um identificador da sessão SHALL ser registrado.

O gate SHALL aceitar somente evidências produzidas depois dessa abertura e pertencentes à mesma sessão, sem depender da data de modificação dos arquivos. Cada módulo de código SHALL ter produzido dados de execução de cobertura na sessão.

O build SHALL falhar quando qualquer destes pisos, medidos em linhas, não for atendido:

- cobertura global dos cinco módulos de código de pelo menos 85%;
- cobertura do conjunto de pacotes sob `br.com.fiap.hospital.agendamento.domain` de pelo menos 90%;
- cobertura do conjunto de pacotes sob `br.com.fiap.hospital.agendamento.application` de pelo menos 90%.

O percentual de cada escopo SHALL ser a razão entre as linhas cobertas e a soma de linhas cobertas e perdidas de todo o escopo. Média de percentuais de módulos ou de pacotes SHALL NOT ser usada. Um escopo exatamente no piso SHALL ser aceito.

O gate SHALL falhar fechado quando:

- o relatório estiver ausente, vazio ou ilegível;
- faltar o identificador da sessão, ou ele pertencer a outra execução;
- faltarem os dados de execução de algum módulo de código;
- faltar um módulo de código, os pacotes de uma subárvore ou o contador de linhas;
- o total de linhas de um escopo for zero.

Cada módulo de código e o relatório como um todo SHALL declarar exatamente um contador de linhas válido. Todo pacote de cada um dos cinco módulos SHALL ser validado antes do cálculo de qualquer escopo, e a mesma contagem validada SHALL alimentar a reconciliação e os escopos:

- um pacote com contador de linhas válido contribui com esse contador;
- um pacote legitimamente sem linhas executáveis contribui com zero linhas cobertas e zero perdidas somente quando declara ao menos uma classe e ao menos um arquivo-fonte e não contém, em toda a sua subárvore, nenhum contador, nenhum método e nenhuma linha;
- um pacote com qualquer outro contador ou evidência executável e sem contador de linhas SHALL falhar o gate;
- um pacote vazio ou estruturalmente ambíguo SHALL falhar o gate;
- a soma das linhas dos pacotes de cada módulo, incluindo os pacotes sem linhas executáveis, SHALL ser exatamente igual ao contador de linhas do módulo;
- a soma das linhas dos módulos SHALL ser exatamente igual ao contador de linhas do relatório;
- as subárvores `domain` e `application` SHALL existir e ter total de linhas positivo.

A garantia observável é que a ausência acidental do contador de linhas, um relatório sem informação de linha sobre código executável e a inconsistência entre pacote, módulo e relatório são recusados. Não se afirma que toda adulteração deliberada do relatório seja distinguível.

As únicas classes excluídas da medição SHALL ser as classes de inicialização `*Application` dos serviços. Qualquer outra exclusão SHALL exigir alteração desta especificação.

#### Scenario: Cobertura acima dos pisos é aceita
- **WHEN** o relatório agregado da sessão corrente mostra os três escopos acima dos respectivos pisos
- **THEN** o gate aceita e o build prossegue
- **AND** as linhas cobertas, as linhas perdidas e o percentual de cada escopo são exibidos

#### Scenario: Escopo exatamente no piso é aceito
- **WHEN** o escopo global tem exatamente 85% das linhas cobertas, ou uma subárvore tem exatamente 90%
- **THEN** o gate aceita esse escopo

#### Scenario: Cobertura global abaixo do piso falha o build
- **WHEN** a cobertura de linhas agregada dos cinco módulos de código fica abaixo de 85%
- **THEN** o build falha
- **AND** a mensagem identifica o escopo global, o percentual apurado e o piso

#### Scenario: Subárvore domain abaixo do piso falha mesmo com cobertura global suficiente
- **WHEN** a cobertura global atende ao piso e a subárvore `domain` do agendamento fica abaixo de 90%
- **THEN** o build falha identificando a subárvore `domain`

#### Scenario: Subárvore application abaixo do piso falha mesmo com cobertura global suficiente
- **WHEN** a cobertura global atende ao piso e a subárvore `application` do agendamento fica abaixo de 90%
- **THEN** o build falha identificando a subárvore `application`

#### Scenario: Percentual é calculado por soma, e não por média
- **WHEN** a média simples dos percentuais dos módulos atinge o piso, mas a razão entre o total de linhas cobertas e o total de linhas do escopo não o atinge
- **THEN** o gate falha
- **AND** na situação inversa, com a razão acima do piso e a média abaixo, o gate aceita

#### Scenario: Relatório ausente, vazio ou ilegível falha fechado
- **WHEN** o relatório agregado não existe, não contém dados ou não é um documento válido
- **THEN** o build falha
- **AND** nenhum escopo é considerado aprovado

#### Scenario: Módulo, pacote, contador ou dados de execução ausentes falham fechado
- **WHEN** falta no relatório um dos cinco módulos de código, os pacotes de uma subárvore ou o contador de linhas, falta um total de linhas, ou um módulo de código não produziu dados de execução de cobertura na sessão
- **THEN** o build falha identificando o elemento ausente
- **AND** o mesmo ocorre para pacote de qualquer módulo com evidência executável e sem contador de linhas, para pacote vazio e para soma dos pacotes divergente do contador do módulo

#### Scenario: Pacote sem linhas executáveis contribui 0/0
- **WHEN** um pacote de um módulo de código declara classes e arquivos-fonte e não contém contador, método nem linha em toda a sua subárvore, como um pacote só de interfaces
- **THEN** o gate aceita o pacote com zero linhas cobertas e zero perdidas
- **AND** a soma dos pacotes do módulo, incluindo esse, continua obrigada a igualar o contador de linhas do módulo

#### Scenario: Evidência de outra sessão é recusada
- **WHEN** o identificador da sessão de verificação está ausente ou pertence a outra execução
- **THEN** o gate falha declarando que a evidência não pertence à sessão corrente

#### Scenario: Evidência residual é descartada antes dos testes
- **WHEN** o gate global começa com dados de cobertura, relatórios de teste ou relatório agregado de uma execução anterior no diretório de build, mesmo sem limpeza prévia
- **THEN** essas evidências são removidas antes de qualquer teste dos módulos
- **AND** o gate usa apenas as evidências produzidas na sessão corrente

#### Scenario: Aceitação não depende da resolução do relógio do sistema de arquivos
- **WHEN** as evidências produzidas na sessão corrente têm datas de modificação arredondadas para antes do início da sessão
- **THEN** o gate aceita essas evidências

#### Scenario: Exclusão adicional de cobertura é recusada
- **WHEN** uma configuração de cobertura exclui qualquer classe além das classes `*Application`
- **THEN** a verificação estrutural falha apontando a exclusão

### Requirement: Auditoria da execução integral das suítes

O build completo executado na raiz SHALL verificar, depois de todas as suítes, que cada suíte executável de cada módulo produziu evidência válida de execução na sessão de verificação corrente. A auditoria SHALL ser distinta do gate de cobertura e SHALL falhar o build por conta própria.

**Inventário.** O inventário SHALL partir de todas as classes das fontes de teste de todos os módulos.

- Suíte executável é a classe concreta de nível superior que declara ou herda ao menos um método de teste. A herança inclui métodos declarados em classes aninhadas herdadas.
- Toda suíte executável SHALL seguir a convenção de nome de um dos tipos de teste: terminada em `Test` para unitários e em `IT` para integração. Uma suíte executável fora da convenção SHALL fazer a auditoria falhar.
- Classes abstratas, bases herdadas, contratos abstratos e classes auxiliares SHALL NOT ser exigidos como suítes. Os testes que eles declaram SHALL ser exigidos na evidência das subclasses concretas que os executam.
- Uma classe de teste cuja classificação não possa ser determinada SHALL fazer a auditoria falhar, em vez de ser ignorada.

**Família de relatórios.** Somente os relatórios de suíte, no formato `TEST-*.xml`, participam das famílias. O resumo agregado do plugin de integração não é relatório de suíte e SHALL NOT ser tratado como órfão.

- A evidência de uma suíte SHALL ser avaliada como família: o relatório da própria classe e os relatórios de suas classes aninhadas, inclusive as herdadas de contratos abstratos.
- Um relatório da própria classe sem casos SHALL ser aceito quando a família executou casos.
- A família SHALL ter total de casos positivo.
- Todo relatório de suíte SHALL ser atribuível a exatamente uma família.

**Proteção contra omissão.** A auditoria SHALL NOT afirmar que prova a execução individual de cada método de teste; a soma de casos de uma família não prova métodos. A auditoria SHALL exigir evidência por suíte e por família de relatórios e SHALL recusar os mecanismos enumerados capazes de omitir casos:

- seleção e exclusão de testes;
- pulo de testes;
- desabilitação;
- suposição condicional;
- caso pulado;
- tolerância a falhas.

A recusa vale tanto para parâmetro da execução quanto para configuração de build de qualquer módulo.

**Falhas.** A auditoria SHALL falhar quando:

- uma suíte não tiver família de relatórios;
- uma classe aninhada com testes não tiver relatório;
- uma família tiver total zero;
- houver falha, erro ou teste pulado;
- um relatório de suíte estiver ilegível, não pertencer à sessão corrente, for órfão ou for atribuível a mais de uma família;
- um módulo com suítes não tiver relatórios;
- um mecanismo de seleção, exclusão, omissão ou tolerância estiver ativo;
- o código de teste declarar desabilitação ou suposição condicional de execução.

#### Scenario: Execução integral e verde é aceita
- **WHEN** `mvn clean verify` é executado na raiz sem filtro e todas as suítes passam
- **THEN** a auditoria aceita
- **AND** informa, por módulo, a quantidade de suítes executáveis, de relatórios e de casos executados

#### Scenario: Suíte omitida é recusada
- **WHEN** uma suíte executável não tem nenhum relatório da sua família na sessão corrente
- **THEN** o build falha identificando o módulo e a suíte

#### Scenario: Filtro de seleção, omissão ou tolerância é recusado
- **WHEN** o build completo é executado com seleção, exclusão, omissão ou tolerância a falhas de teste — por parâmetro da execução ou por configuração de build de qualquer módulo —, ainda que todos os relatórios estejam presentes
- **THEN** a auditoria falha declarando o mecanismo ativo e que o gate global não aceita filtro

#### Scenario: Módulo sem relatórios é recusado
- **WHEN** um módulo com suítes executáveis não produz nenhum relatório do tipo de teste correspondente
- **THEN** o build falha identificando o módulo

#### Scenario: Teste falho, com erro ou pulado é recusado
- **WHEN** um relatório de qualquer família registra falha, erro ou teste pulado
- **THEN** o build falha identificando a suíte e o caso

#### Scenario: Família sem casos executados é recusada
- **WHEN** o total de casos da família de uma suíte é zero
- **THEN** o build falha identificando a suíte

#### Scenario: Relatório de outra sessão é recusado pela auditoria
- **WHEN** um relatório de teste não pertence à sessão de verificação corrente
- **THEN** a auditoria falha declarando o relatório como obsoleto

#### Scenario: Relatório ilegível é recusado
- **WHEN** um relatório de suíte não é um documento válido
- **THEN** a auditoria falha identificando o arquivo

#### Scenario: Abstratas, bases, contratos herdados e auxiliares não contam como omissões
- **WHEN** um módulo contém classe abstrata com o sufixo da convenção, contrato abstrato com classes aninhadas executado por subclasses concretas, bases herdadas e classes auxiliares
- **THEN** nenhuma delas é exigida como suíte
- **AND** os relatórios das classes aninhadas herdadas contam na família da subclasse concreta que as executou
- **AND** o relatório da própria subclasse sem casos é aceito quando a família executou casos

#### Scenario: Desabilitação declarada no código de teste é recusada
- **WHEN** o código de teste de algum módulo desabilita um teste ou condiciona sua execução a uma suposição
- **THEN** a auditoria falha identificando o arquivo

#### Scenario: Teste concreto fora da convenção é recusado
- **WHEN** uma classe concreta que declara ou herda métodos de teste não segue a convenção de nome de nenhum tipo de teste
- **THEN** a auditoria falha identificando a classe, que nunca seria executada pelo build

#### Scenario: Relatório órfão ou atribuição ambígua é recusada
- **WHEN** um relatório de suíte não pertence à família de nenhuma suíte, ou poderia pertencer a mais de uma
- **THEN** a auditoria falha identificando o relatório
- **AND** o resumo agregado do plugin de integração não é considerado órfão

### Requirement: Testes de integração sobre infraestrutura real

Os testes de integração SHALL exercitar a infraestrutura real que cada módulo efetivamente usa, em containers efêmeros criados pela própria suíte:

- `agendamento-service`, `notificacao-service` e `historico-service` usam PostgreSQL 16 e RabbitMQ 3.13;
- `shared-contracts` usa apenas RabbitMQ 3.13;
- `shared-security` não tem teste de integração de infraestrutura e não é obrigado a prova alguma.

Nenhum teste de integração SHALL substituir o banco ou o broker por dublê, mock, banco em memória ou broker embarcado. Continuam permitidos:

- observar ou provocar falha sobre o componente real;
- instrumentá-lo por decoradores que delegam a ele;
- usar dublê de portas externas que não sejam banco nem broker, como envio de e-mail e canal de notificação.

A verificação estrutural SHALL falhar diante de:

- dependência de banco em memória ou de broker embarcado;
- substituição de fonte de dados, acesso JDBC, gerenciador de entidades, repositório, fábrica de conexões ou template e administração AMQP por dublê, em teste de integração. Isso vale para dublê declarado por anotação, criado programaticamente ou registrado por configuração de teste;
- troca automática do banco configurado por um banco embarcado;
- container de banco ou de broker com imagem diferente da versão adotada pelo projeto.

Cada módulo com testes de integração SHALL comprovar, em execução, cada infraestrutura que usa, e somente ela:

- a fonte de dados aponta para o PostgreSQL do container da suíte e executa uma operação real na versão 16;
- a fábrica de conexões aponta para o RabbitMQ do container da suíte e executa uma operação real na versão 3.13.

#### Scenario: Cada módulo comprova a infraestrutura real que usa
- **WHEN** as suítes de integração de cada módulo executam
- **THEN** agendamento, notificação e histórico comprovam PostgreSQL 16 e RabbitMQ 3.13 reais, cada um com uma operação real no container da própria suíte
- **AND** o módulo de contratos comprova somente RabbitMQ 3.13 real
- **AND** o módulo de segurança, sem teste de integração de infraestrutura, não é exigido

#### Scenario: Dublê de infraestrutura em teste de integração é recusado
- **WHEN** um teste de integração substitui fonte de dados, acesso JDBC, gerenciador de entidades, repositório, fábrica de conexões ou template AMQP por um dublê — declarado por anotação, criado programaticamente ou registrado por configuração de teste
- **THEN** a verificação estrutural falha identificando o arquivo e o tipo substituído

#### Scenario: Banco em memória, broker embarcado ou substituição automática é recusado
- **WHEN** um módulo declara banco em memória ou broker embarcado, ou um teste permite trocar o banco configurado por um embarcado
- **THEN** a verificação estrutural falha

#### Scenario: Espião, decorador e dublê de porta externa continuam permitidos
- **WHEN** um teste de integração espiona um componente real, instrumenta-o por decorador que delega a ele, ou substitui por dublê uma porta externa que não é banco nem broker
- **THEN** a verificação estrutural aceita o teste

#### Scenario: Container declarado sem uso efetivo não satisfaz a verificação
- **WHEN** uma suíte declara containers, mas a fonte de dados ou a fábrica de conexões do contexto não aponta para eles
- **THEN** a verificação de infraestrutura do módulo falha

### Requirement: Smoke ponta a ponta do fluxo principal

O repositório SHALL oferecer um roteiro de smoke executável em Bash que não dependa de estado externo prévio, de Compose nem de imagens das aplicações. Antes de criar qualquer recurso — inclusive diretório temporário — o roteiro SHALL verificar todos os pré-requisitos. Depois, ele:

- sobe PostgreSQL 16 e RabbitMQ 3.13 efêmeros e exclusivos da execução, cujos bancos são criados apenas pelo script de inicialização existente;
- executa os três serviços como processos, com o mesmo segredo de assinatura efêmero;
- identifica sem ambiguidade a porta efetiva de cada serviço e a confirma por requisição real;
- percorre, em ordem: autenticação de um médico de demonstração; criação de uma consulta futura; espera pelo processamento assíncrono; verificação da notificação; e consulta dessa mesma consulta no histórico via GraphQL.

Nenhuma consulta pré-carregada SHALL ser usada.

Toda resposta JSON SHALL ser validada. Resposta GraphQL com erros SHALL ser tratada como falha, ainda que o status HTTP seja de sucesso. As esperas SHALL ser por condição com prazo máximo absoluto, nunca por intervalo fixo presumido suficiente, e SHALL falhar de imediato se um processo iniciado pelo roteiro deixar de executar.

A notificação SHALL ser comprovada por duas evidências da execução corrente: o registro persistido para a consulta criada e a invocação do canal de log com o mesmo conteúdo. A garantia de entrega continua sendo ao-menos-uma-vez.

O roteiro SHALL terminar com código zero somente se todas as verificações passarem, e com código diferente de zero em qualquer falha. Ao final — em sucesso, falha ou interrupção — ele:

- SHALL remover os recursos de runtime que ele próprio criou: processos, containers, rede, volumes e arquivos temporários de runtime;
- SHALL falhar se algum deles restar;
- SHALL encerrar somente processos confirmados como iniciados por aquela execução;
- SHALL NOT parar, remover nem reutilizar recursos que não criou.

Em falha, somente um pacote de diagnóstico SHALL ser preservado, com seu caminho informado, e ele não é recurso de runtime.

#### Scenario: Fluxo completo bem-sucedido termina com código zero
- **WHEN** o roteiro é executado numa máquina que atende aos pré-requisitos
- **THEN** ele autentica um médico, cria uma consulta futura, observa a notificação dessa consulta e a encontra no histórico via GraphQL
- **AND** termina com código zero

#### Scenario: Verificação divergente termina com código não zero
- **WHEN** qualquer resposta chega com status inesperado, JSON inválido ou campo divergente do esperado
- **THEN** o roteiro termina com código diferente de zero
- **AND** exibe qual verificação falhou

#### Scenario: Erro GraphQL com status de sucesso é falha
- **WHEN** o histórico responde com status HTTP de sucesso contendo erros que não sejam a ausência temporária do registro durante a espera
- **THEN** o roteiro termina com código diferente de zero

#### Scenario: Processo encerrado durante a execução é falha imediata
- **WHEN** um dos serviços iniciados pelo roteiro deixa de executar antes do fim do fluxo
- **THEN** o roteiro falha sem aguardar o prazo máximo
- **AND** exibe o log do serviço encerrado

#### Scenario: Prazo esgotado é falha
- **WHEN** uma condição esperada não ocorre até o prazo máximo da etapa, com os processos ainda em execução
- **THEN** o roteiro termina com código diferente de zero
- **AND** exibe a etapa, a última resposta obtida e os logs relevantes

#### Scenario: Espera assíncrona é condicional
- **WHEN** o roteiro é inspecionado
- **THEN** toda pausa ocorre dentro de uma espera que reavalia uma condição até um prazo absoluto
- **AND** não existe pausa fixa seguida de presunção de sucesso

#### Scenario: Pré-requisito ausente é recusado antes de criar recursos
- **WHEN** falta Bash compatível, Docker acessível, Java 21, Maven, curl ou jq
- **THEN** o roteiro termina com código diferente de zero identificando o pré-requisito
- **AND** nenhum diretório temporário, container, rede ou processo é criado

#### Scenario: Recursos de runtime são removidos em sucesso, falha e interrupção
- **WHEN** o roteiro termina com sucesso, com falha em etapa intermediária ou por interrupção
- **THEN** não resta processo, container, rede, volume ou arquivo temporário de runtime criado por aquela execução
- **AND** em falha, somente o pacote de diagnóstico é preservado e seu caminho é exibido, sem ser considerado resíduo

#### Scenario: Recursos alheios permanecem intactos
- **WHEN** o ambiente local do Compose ou outros containers estão ativos durante a execução
- **THEN** o roteiro não os para, não os remove, não os reutiliza e não disputa suas portas

#### Scenario: Execuções consecutivas são independentes
- **WHEN** o roteiro é executado duas vezes seguidas
- **THEN** cada execução usa infraestrutura e dados próprios, sem consulta pré-carregada
- **AND** as duas terminam com código zero

#### Scenario: Notificação é comprovada pela execução corrente
- **WHEN** a consulta criada pelo roteiro é processada pelo serviço de notificações
- **THEN** existe exatamente um registro persistido de notificação de agendamento para aquela consulta
- **AND** o log do serviço naquela execução contém a entrega do mesmo conteúdo ao canal de log
- **AND** nenhuma evidência de execução anterior pode satisfazer a verificação

#### Scenario: Porta efetiva é identificada sem ambiguidade
- **WHEN** um serviço inicia com porta atribuída pelo sistema
- **THEN** o roteiro identifica exatamente uma porta válida no log daquele processo, qualquer que seja o fim de linha, e a confirma por requisição real ao serviço
- **AND** se o processo termina, ou se a porta não é identificada no prazo, o roteiro falha exibindo o log

#### Scenario: Somente processos da própria execução são encerrados
- **WHEN** a limpeza encerra processos
- **THEN** apenas os processos iniciados por aquela execução, confirmados como tais antes do sinal, são encerrados
- **AND** nenhum processo é encerrado por nome ou padrão genérico
