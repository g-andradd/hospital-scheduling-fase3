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

### Requirement: Fronteiras da Clean Architecture verificadas automaticamente

As fronteiras da Clean Architecture do serviço de agendamento SHALL ser verificadas automaticamente durante o build, sobre o código principal compilado, e SHALL reprovar o build quando cruzadas. Nenhuma regra SHALL depender de disciplina manual ou de revisão.

- **Direção das dependências.** A camada de domínio SHALL NOT depender das camadas de aplicação ou de infraestrutura, e a camada de aplicação SHALL NOT depender da camada de infraestrutura.
- **Domínio sem framework.** A camada de domínio SHALL NOT depender de framework de injeção de dependência, de persistência, de serialização JSON ou de validação declarativa.
- **Caso de uso.** Cada caso de uso SHALL declarar diretamente exatamente uma operação pública de instância, a sua operação de comportamento. Não contam construtores, operações herdadas — inclusive as da raiz da hierarquia de objetos —, operações estáticas e operações geradas pelo compilador.
- **Entidade persistente.** Entidades de persistência SHALL existir somente na área de persistência da camada de infraestrutura.
- **Controlador.** Os controladores HTTP SHALL NOT acessar repositórios, portas de saída do domínio nem casos de uso sem a demarcação transacional da infraestrutura; operações de negócio SHALL passar pelos casos de uso transacionais.
- **Única exceção nominal.** O componente de emissão de token SHALL ser admitido somente no controlador de autenticação, porque a emissão do token pertence à fronteira HTTP.
- **Saída padrão.** O código principal SHALL NOT escrever na saída padrão nem na saída de erro do processo.

#### Scenario: Código conforme às fronteiras é aceito
- **WHEN** a verificação de arquitetura é executada sobre o código principal atual do serviço de agendamento
- **THEN** todas as regras passam
- **AND** a verificação executa como caso de teste registrado no relatório da suíte

#### Scenario: Dependência contra a direção das camadas é recusada
- **WHEN** uma classe do domínio passa a depender de uma classe da aplicação ou da infraestrutura, ou uma classe da aplicação passa a depender da infraestrutura
- **THEN** a verificação de arquitetura falha identificando a classe de origem e a dependência proibida

#### Scenario: Domínio dependente de framework é recusado
- **WHEN** uma classe do domínio passa a depender de injeção de dependência, persistência, serialização JSON ou validação declarativa
- **THEN** a verificação de arquitetura falha identificando a classe e o tipo proibido

#### Scenario: Caso de uso com mais de uma operação pública é recusado
- **WHEN** um caso de uso passa a declarar diretamente uma segunda operação pública de instância além da sua operação de comportamento
- **THEN** a verificação de arquitetura falha identificando o caso de uso
- **AND** construtores, operações herdadas, operações estáticas e operações geradas pelo compilador não contam para a recusa

#### Scenario: Entidade persistente fora da área de persistência é recusada
- **WHEN** uma entidade de persistência é declarada fora da área de persistência da infraestrutura
- **THEN** a verificação de arquitetura falha identificando a entidade

#### Scenario: Controlador que contorna o caso de uso transacional é recusado
- **WHEN** um controlador passa a depender de um repositório, de uma porta de saída do domínio ou de um caso de uso sem demarcação transacional
- **THEN** a verificação de arquitetura falha identificando o controlador e a dependência

#### Scenario: Emissão de token só é admitida no controlador de autenticação
- **WHEN** o controlador de autenticação depende do componente de emissão de token
- **THEN** a verificação de arquitetura aceita a dependência
- **AND** a mesma dependência em qualquer outro controlador é recusada

#### Scenario: Escrita na saída padrão é recusada
- **WHEN** o código principal passa a escrever na saída padrão ou na saída de erro do processo
- **THEN** a verificação de arquitetura falha identificando a classe

### Requirement: Correlação HTTP consistente nos três serviços

Os serviços de agendamento, notificação e histórico SHALL tratar o identificador de correlação da mesma forma em toda requisição HTTP, antes da cadeia de segurança:

- um cabeçalho `X-Correlation-Id` recebido e não vazio SHALL ser usado como identificador da requisição;
- na ausência, ou com valor vazio, o serviço SHALL gerar um identificador novo;
- a resposta SHALL devolver o identificador no cabeçalho `X-Correlation-Id` — inclusive nas recusas por falta de autenticação ou de permissão —, e o mesmo valor SHALL constar do corpo Problem Detail dessas recusas;
- o identificador SHALL estar no contexto de log durante o processamento da requisição, e o contexto anterior SHALL ser restaurado ao final dela.

#### Scenario: Identificador recebido é honrado e devolvido
- **WHEN** qualquer dos três serviços recebe uma requisição com `X-Correlation-Id` não vazio
- **THEN** a resposta devolve o mesmo valor no cabeçalho `X-Correlation-Id`
- **AND** esse valor é o identificador de correlação usado no processamento da requisição

#### Scenario: Identificador ausente é gerado
- **WHEN** qualquer dos três serviços recebe uma requisição sem `X-Correlation-Id`, ou com valor vazio
- **THEN** a resposta traz no cabeçalho `X-Correlation-Id` um identificador novo e não vazio

#### Scenario: Recusa de segurança carrega o mesmo identificador
- **WHEN** qualquer dos três serviços recusa com 401 ou 403 uma requisição que enviou `X-Correlation-Id`
- **THEN** o cabeçalho da resposta e o `correlationId` do corpo Problem Detail são iguais ao valor enviado

#### Scenario: Contexto de log da requisição não vaza
- **WHEN** uma requisição termina, com sucesso ou com falha
- **THEN** o identificador de correlação dela deixa de estar no contexto de log
- **AND** o contexto que existia antes da requisição é restaurado

### Requirement: Correlação assíncrona observável ponta a ponta

Um fluxo iniciado por uma requisição HTTP de criação de consulta SHALL ser rastreável pelo mesmo identificador de correlação nos logs dos três serviços:

- no agendamento, na publicação do evento correspondente;
- no histórico, na projeção do evento;
- na notificação, na entrega da mensagem ao canal de log.

Cada consumidor SHALL manter o identificador de correlação do envelope no contexto de log durante o processamento de cada tentativa e SHALL limpá-lo ao final da tentativa, inclusive quando ela falha. O roteiro de smoke SHALL comprovar essa rastreabilidade na execução corrente, identificando os registros pela consulta criada naquela execução, e SHALL terminar com código diferente de zero se algum dos três registros estiver ausente ou trouxer outro identificador.

#### Scenario: Mesmo identificador nos logs dos três serviços
- **WHEN** o smoke cria uma consulta enviando um identificador de correlação único da execução
- **THEN** a resposta devolve o mesmo identificador
- **AND** o log do agendamento contém o registro de publicação do evento dessa consulta com esse identificador
- **AND** o log do histórico contém o registro de projeção do evento dessa consulta com esse identificador
- **AND** o log da notificação contém o registro de entrega dessa consulta ao canal de log com esse identificador

#### Scenario: Consumidor do histórico mantém e limpa a correlação
- **WHEN** o histórico processa um evento, com sucesso ou com falha de uma tentativa
- **THEN** o identificador de correlação do envelope está no contexto de log durante o processamento
- **AND** ele não permanece no contexto de log depois da tentativa
- **AND** cada tentativa de retry vê o identificador do próprio envelope

#### Scenario: Registro ausente ou com correlação divergente reprova o smoke
- **WHEN** algum dos três logs não contém, na execução corrente, o registro do fluxo com o identificador enviado
- **THEN** o smoke termina com código diferente de zero, indicando a etapa e o serviço
- **AND** registro de outra execução, de outra consulta ou com outro identificador não satisfaz a verificação

### Requirement: Endpoints operacionais mínimos e seguros

Cada um dos três serviços SHALL expor pela web exatamente os endpoints operacionais de saúde, informação, métricas e métricas no formato Prometheus, e nenhum outro. Endpoints que revelam configuração, variáveis de ambiente ou a composição interna da aplicação SHALL NOT ser expostos em profile algum.

- **Saúde.** O endpoint de saúde e seus subcaminhos SHALL permanecer acessíveis sem credencial e SHALL NOT revelar detalhes de componentes ou de configuração.
- **Demais endpoints.** O índice dos endpoints operacionais, a informação, as métricas e o formato Prometheus SHALL exigir token válido, aceito para qualquer perfil autenticado.
- **Recusa.** Sem credencial válida, a recusa SHALL ser 401 no formato Problem Detail.
- **Segurança.** A proteção SHALL usar a mesma cadeia de segurança dos demais recursos do serviço, sem criar matriz de papéis nova.

#### Scenario: Saúde acessível sem credencial e sem detalhes
- **WHEN** o endpoint de saúde de qualquer dos três serviços é acessado sem credencial
- **THEN** a resposta é bem-sucedida e informa o estado
- **AND** não traz detalhes de componentes nem de configuração

#### Scenario: Informação e métricas exigem credencial
- **WHEN** o índice operacional, a informação, as métricas ou o formato Prometheus de qualquer dos três serviços são acessados sem token, ou com token inválido
- **THEN** a resposta é 401 no formato Problem Detail
- **AND** com token válido de qualquer perfil a resposta é bem-sucedida

#### Scenario: Métricas no formato Prometheus
- **WHEN** o endpoint Prometheus de qualquer dos três serviços é acessado com token válido
- **THEN** a resposta usa o formato textual de exposição do Prometheus e contém métricas da JVM

#### Scenario: Exposição é exatamente a permitida
- **WHEN** os endpoints operacionais expostos pela web de qualquer dos três serviços são enumerados
- **THEN** o conjunto é exatamente saúde, informação, métricas e Prometheus
- **AND** os endpoints de variáveis de ambiente e de beans não respondem com sucesso, nem com token válido

### Requirement: Logs estruturados no profile docker

Com o profile `docker` ativo, cada um dos três serviços SHALL escrever seus registros de log na saída do console como objetos JSON, um por linha. Cada registro SHALL conter:

- instante;
- nível;
- origem do registro;
- mensagem;
- identificação do serviço;
- valores presentes no contexto de log, como campos próprios.

O identificador de correlação SHALL aparecer como campo estruturado do registro, e não apenas interpolado na mensagem. Sem o profile `docker`, o formato de log SHALL permanecer o textual padrão.

#### Scenario: Registro no profile docker é JSON completo
- **WHEN** um serviço é executado com o profile `docker` e produz um registro de log
- **THEN** a linha do registro é um objeto JSON válido
- **AND** o objeto contém instante, nível, origem, mensagem e identificação do serviço

#### Scenario: Correlação é campo estruturado
- **WHEN** um serviço com o profile `docker` registra log com identificador de correlação no contexto
- **THEN** o objeto JSON do registro contém o identificador em campo próprio
- **AND** o valor do campo é igual ao identificador do fluxo

#### Scenario: Profile padrão continua em texto
- **WHEN** um serviço é executado sem o profile `docker`
- **THEN** a configuração não ativa o formato estruturado
- **AND** os registros permanecem no formato textual padrão

#### Scenario: Profile docker sem formato estruturado é detectado
- **WHEN** a configuração do profile `docker` de algum dos três serviços deixa de declarar o formato estruturado de console, ou o declara em outro profile
- **THEN** a verificação estrutural da configuração de logs falha identificando o serviço

### Requirement: Imagens executáveis das aplicações

Cada um dos três serviços SHALL ter uma imagem de container construída a partir do código do repositório, sem artefato pré-construído no host. Toda imagem:

- SHALL separar a etapa de construção da etapa de execução;
- SHALL executar sobre o ambiente de execução Java 21, sem ferramentas de build nem código-fonte;
- SHALL executar o processo da aplicação com usuário sem privilégio de administrador;
- SHALL declarar verificação de saúde que consulte o endpoint de saúde do próprio serviço.

A resolução de dependências SHALL anteceder a cópia do código-fonte, para que uma mudança só no código reaproveite as dependências já resolvidas.

#### Scenario: Aplicação executa com usuário sem privilégio
- **WHEN** o container de qualquer dos três serviços está em execução
- **THEN** o processo da aplicação roda com um usuário diferente de root

#### Scenario: Saúde do container reflete o endpoint de saúde
- **WHEN** o serviço responde com sucesso no endpoint de saúde
- **THEN** o container atinge o estado `healthy`
- **AND** enquanto o endpoint não responde com sucesso, o container não é considerado `healthy`

#### Scenario: Imagem final contém só o necessário para executar
- **WHEN** a imagem final de qualquer dos três serviços é inspecionada
- **THEN** ela contém o ambiente de execução Java 21 e o artefato executável do serviço
- **AND** não contém Maven, JDK completo nem o código-fonte do repositório

#### Scenario: Dependências resolvidas antes do código-fonte
- **WHEN** a definição de construção de qualquer dos três serviços é verificada
- **THEN** os descritores de build são copiados e as dependências resolvidas antes da cópia do código-fonte
- **AND** a construção reaproveita um cache de dependências entre execuções

### Requirement: Ambiente completo com um comando

Um único comando SHALL subir PostgreSQL, RabbitMQ, os três serviços e o servidor de correio de demonstração, todos até o estado saudável.

- Cada serviço SHALL iniciar só depois de suas dependências estarem saudáveis.
- Cada serviço SHALL usar exclusivamente o próprio database e alcançar banco, broker e correio pelos nomes internos do ambiente.
- Os três serviços SHALL executar com os perfis de demonstração e de container ativos, portanto com os usuários de demonstração e os logs estruturados.
- Configuração obrigatória ausente, como o segredo de assinatura dos tokens, SHALL impedir a subida com mensagem que identifica a variável.

PostgreSQL e RabbitMQ SHALL continuar com os nomes, volumes e provisionamento de databases já existentes.

#### Scenario: Subida completa até o estado saudável
- **WHEN** um desenvolvedor sobe o ambiente completo com o `.env` preenchido a partir do `.env.example`
- **THEN** os containers de PostgreSQL, RabbitMQ, agendamento, notificação, histórico e correio atingem o estado `healthy`
- **AND** a API do agendamento, o endpoint GraphQL do histórico, o disparo de lembretes da notificação e a interface web do correio respondem nas portas documentadas

#### Scenario: Serviço só inicia com as dependências saudáveis
- **WHEN** o ambiente completo é iniciado a partir de volumes vazios
- **THEN** nenhum dos três serviços inicia antes de PostgreSQL e RabbitMQ estarem `healthy`
- **AND** a notificação não inicia antes de o servidor de correio estar `healthy`

#### Scenario: Cada serviço usa o próprio database
- **WHEN** o ambiente completo está em execução
- **THEN** o agendamento usa só `agendamento_db`, a notificação só `notificacao_db` e o histórico só `historico_db`
- **AND** nenhum serviço aponta para o broker ou o banco por endereço de host local

#### Scenario: Perfis de demonstração e de container ativos
- **WHEN** o ambiente completo está em execução
- **THEN** os usuários de demonstração conseguem se autenticar
- **AND** os logs dos três serviços saem como objetos JSON

#### Scenario: Segredo obrigatório ausente impede a subida
- **WHEN** o ambiente completo é iniciado sem o segredo de assinatura dos tokens
- **THEN** a subida é recusada antes de criar os containers das aplicações
- **AND** a mensagem identifica a variável ausente

### Requirement: Notificações por e-mail no ambiente de demonstração

No ambiente completo, a notificação SHALL usar o canal de correio apontado para o servidor de correio de demonstração, e as notificações reativas e os lembretes SHALL chegar à caixa de entrada visível na interface web desse servidor. Nesse ambiente, a saúde da notificação SHALL incluir o servidor de correio, porque ele é a dependência ativa do canal. Fora do ambiente completo, o canal padrão de log e a saúde sem o indicador de correio SHALL permanecer como estão.

#### Scenario: Notificação reativa chega à caixa de entrada
- **WHEN** uma consulta é criada no ambiente completo
- **THEN** a caixa de entrada do servidor de correio recebe uma mensagem com assunto de agendamento destinada ao paciente da consulta

#### Scenario: Saúde da notificação inclui o servidor de correio
- **WHEN** o servidor de correio fica indisponível com o ambiente completo em execução
- **THEN** o container da notificação deixa de estar `healthy`
- **AND** volta a `healthy` quando o servidor de correio é restabelecido

#### Scenario: Fora do ambiente completo o canal e a saúde não mudam
- **WHEN** a notificação sobe fora do ambiente completo, sem configurar canal nem saúde de correio
- **THEN** o canal utilizado é o de log
- **AND** o endpoint de saúde não depende de servidor de correio

### Requirement: Demonstração executável do zero

Um único comando de demonstração SHALL, a partir de volumes vazios:
- preparar o `.env` a partir do `.env.example` quando ele não existir;
- subir o ambiente completo até o estado saudável;
- comprovar o fluxo pela interface pública dos serviços, sem escrita direta em banco.

O fluxo comprovado SHALL incluir:
- a autenticação de um usuário de demonstração;
- a criação de uma consulta cujo horário esteja estritamente depois do instante da criação e no máximo 24 horas depois dele;
- o e-mail de agendamento na caixa de entrada;
- a consulta legível no histórico;
- um disparo do lembrete D-1 com o e-mail de lembrete na caixa de entrada.

Ao final, o comando SHALL exibir endereços e credenciais de demonstração.

Consulta de demonstração é a que atende simultaneamente a:
- paciente e médico de demonstração;
- marcador de observações de demonstração;
- status agendado ou confirmado;
- horário na janela das próximas 24 horas.

Consultas que não atendem a todos esses critérios SHALL ser ignoradas, inclusive as do mesmo paciente. A reexecução com o ambiente já em pé SHALL reaproveitar a consulta de demonstração existente e SHALL NOT criar outra; mais de uma consulta de demonstração na janela SHALL encerrar o comando com código diferente de zero. O comando SHALL recusar a execução, antes de alterar o ambiente, quando faltar pré-requisito do host. Qualquer verificação que falhe SHALL encerrar com código diferente de zero, indicando a etapa.

#### Scenario: Demonstração do zero termina com sucesso
- **WHEN** um desenvolvedor remove o ambiente com seus volumes e executa o comando de demonstração
- **THEN** o comando termina com código zero
- **AND** a caixa de entrada contém o e-mail de agendamento e o e-mail de lembrete do paciente da consulta criada
- **AND** a consulta criada é legível no histórico
- **AND** a saída exibe os endereços dos serviços e as credenciais de demonstração

#### Scenario: Consulta de demonstração fica na janela do lembrete
- **WHEN** o comando de demonstração cria a consulta
- **THEN** o horário dela está estritamente depois do instante da criação e no máximo 24 horas depois dele
- **AND** o disparo do lembrete logo em seguida a considera elegível

#### Scenario: Reexecução não duplica a consulta
- **WHEN** o comando de demonstração é executado de novo com o ambiente já em pé e a consulta de demonstração ainda na janela
- **THEN** o comando termina com código zero, reaproveitando a consulta com o mesmo identificador
- **AND** a quantidade de consultas que atendem a todos os critérios de demonstração continua sendo uma
- **AND** uma consulta do mesmo paciente sem o marcador de demonstração não é reaproveitada nem contada

#### Scenario: Pré-requisito ausente é recusado antes de alterar o ambiente
- **WHEN** o comando de demonstração é executado sem um dos pré-requisitos do host
- **THEN** ele termina com código diferente de zero, identificando o pré-requisito
- **AND** nenhum container, volume ou arquivo `.env` é criado por essa execução

#### Scenario: Verificação que falha interrompe a demonstração
- **WHEN** uma etapa da comprovação do fluxo não é satisfeita no prazo
- **THEN** o comando termina com código diferente de zero, indicando a etapa e a última resposta recebida

### Requirement: Configuração do ambiente verificada estruturalmente

O build SHALL verificar a configuração do ambiente completo sem subir containers e SHALL reprovar desvio das garantias de imagem, de dependência saudável, de isolamento de database, de perfis e do canal de correio. A verificação SHALL identificar o arquivo e a regra violada.

#### Scenario: Configuração real atende
- **WHEN** a verificação estrutural é executada sobre os arquivos reais do repositório
- **THEN** nenhuma violação é apontada

#### Scenario: Desvio de imagem ou de ambiente é recusado
- **WHEN** a configuração passa a executar uma aplicação como root, a omitir a verificação de saúde, a iniciar um serviço sem esperar dependência saudável, a compartilhar database entre serviços, a omitir um perfil obrigatório, a apontar a notificação para outro canal ou a embutir o segredo de tokens
- **THEN** a verificação estrutural falha identificando o arquivo e a regra violada
