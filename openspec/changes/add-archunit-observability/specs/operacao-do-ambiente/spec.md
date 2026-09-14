## ADDED Requirements

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
