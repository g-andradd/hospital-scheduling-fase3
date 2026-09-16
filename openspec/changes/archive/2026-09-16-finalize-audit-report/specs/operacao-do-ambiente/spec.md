## ADDED Requirements

### Requirement: Auditoria de requisitos executável e fechada

O repositório SHALL oferecer um roteiro de auditoria executável em Bash que percorra **todos** os requisitos funcionais e não funcionais declarados na especificação funcional e relate, para cada um, se a evidência que o sustenta está presente e íntegra.

**Significado do resultado.** `OK` SHALL significar exatamente que a evidência versionada daquele requisito existe e está ancorada no elemento declarado. `OK` SHALL NOT significar que o comportamento foi reexecutado, medido ou observado durante a auditoria. A auditoria é estática por construção; execuções reais — build completo, smoke e ambiente de demonstração — SHALL permanecer evidências separadas, registradas fora dela.

**Inventário fechado.** O inventário SHALL ser derivado da própria especificação funcional, e não de uma cópia mantida à parte. Sobre o conjunto derivado, a auditoria SHALL exigir que os identificadores sejam exatamente `RF-01` a `RF-20` e `RNF-01` a `RNF-10`, contínuos, únicos e na ordem do documento.

- Documento reduzido, lacuna intermediária, identificador fora da sequência, identificador repetido ou requisito sem evidência declarada SHALL reprovar.
- Evidência declarada para identificador que não existe na especificação SHALL reprovar.
- A auditoria SHALL informar a quantidade de requisitos inventariados, de aprovados e de reprovados, de modo que um inventário vazio ou reduzido seja visível em vez de silencioso.
- Todas essas recusas SHALL ocorrer sem executar o build, sem alcançar o ambiente e sem depender de rede.

**Saída.** Cada requisito SHALL produzir **uma única** linha com três campos, nesta ordem: o identificador, o resultado da verificação e a evidência que o sustenta, separados por um delimitador estável e precedidos de um cabeçalho `REQUISITO | STATUS | EVIDÊNCIA`. O campo de evidência SHALL enumerar todas as âncoras declaradas para aquele requisito, sem quebrar a linha.

**Falha fechada.** A auditoria SHALL terminar com código zero somente quando todos os requisitos forem aprovados, e com código diferente de zero em qualquer outro caso, inclusive quando não conseguir ler a especificação ou uma evidência. Ausência de resultado SHALL ser tratada como reprovação, nunca como aprovação.

**Ausência de efeito.** A auditoria SHALL ser somente leitura: não SHALL iniciar, parar ou alterar containers, não SHALL alcançar banco de dados ou broker, não SHALL executar o build, não SHALL depender de rede e não SHALL escrever, mover ou remover arquivo do repositório.

#### Scenario: Inventário completo é percorrido
- **WHEN** a auditoria é executada sobre o repositório íntegro
- **THEN** cada requisito funcional e não funcional declarado na especificação funcional aparece exatamente uma vez no relatório, na ordem do documento
- **AND** o total relatado coincide com a quantidade de identificadores declarados na especificação

#### Scenario: Inventário fora da sequência fechada é recusado
- **WHEN** a especificação apresenta menos requisitos do que a sequência fechada, uma lacuna no meio dela ou um identificador fora da sequência
- **THEN** a auditoria termina com código diferente de zero identificando a divergência
- **AND** a recusa ocorre sem executar o build e sem alcançar o ambiente

#### Scenario: Relatório usa o formato de três campos
- **WHEN** a saída da auditoria é inspecionada
- **THEN** ela começa pelo cabeçalho `REQUISITO | STATUS | EVIDÊNCIA`
- **AND** cada requisito ocupa uma linha com identificador, resultado e evidência, sem campo vazio

#### Scenario: Repositório íntegro termina com código zero
- **WHEN** todas as evidências declaradas estão presentes e ancoradas
- **THEN** todos os requisitos aparecem como aprovados
- **AND** a auditoria termina com código zero

#### Scenario: Requisito sem evidência é recusado
- **WHEN** um requisito declarado na especificação não tem evidência declarada para a auditoria
- **THEN** a auditoria termina com código diferente de zero identificando o requisito
- **AND** nenhum requisito é omitido do relatório por causa da falha

#### Scenario: Identificador duplicado é recusado
- **WHEN** um identificador aparece mais de uma vez no inventário da especificação ou no conjunto de evidências
- **THEN** a auditoria termina com código diferente de zero identificando o identificador repetido

#### Scenario: Evidência inexistente é recusada
- **WHEN** a evidência declarada para um requisito aponta para um artefato que não existe no repositório
- **THEN** a auditoria termina com código diferente de zero identificando o requisito e o artefato ausente

#### Scenario: Auditoria não altera o ambiente
- **WHEN** a auditoria é executada com o ambiente de demonstração no ar e com ele desligado
- **THEN** o resultado é o mesmo nas duas execuções
- **AND** nenhum container, volume, banco, arquivo do repositório ou artefato de build é criado, alterado ou removido

### Requirement: Evidência de requisito ancorada em artefato verificável

A evidência de um requisito SHALL ser composta por **um ou mais** pares de artefato versionado e elemento literal verificável dentro dele — um caso de teste, uma regra de gate, uma constante normativa, uma migration, uma configuração ou um alvo de build. A auditoria SHALL comprovar o elemento; a mera existência do arquivo SHALL NOT aprovar requisito algum.

**Cobertura das cláusulas.** O conjunto de pares declarado para um requisito SHALL cobrir as cláusulas normativas do texto daquele requisito. Uma âncora real que comprove apenas parte do que o requisito exige SHALL NOT bastar sozinha.

**Conjunção.** Todos os pares declarados para um requisito SHALL existir e passar. A ausência de qualquer um deles SHALL reprovar aquele requisito, e não apenas reduzir a evidência.

- A menção do identificador do requisito em prosa SHALL NOT satisfazer a evidência daquele requisito.
- Nenhum requisito SHALL ser aprovado por conter apenas o nome de um comando, de uma ferramenta ou de uma execução.
- Um mesmo par de artefato e elemento SHALL NOT satisfazer requisitos distintos sem que o compartilhamento esteja declarado e justificado no catálogo. Compartilhamento legítimo SHALL ser preferível a substituir a prova adequada por uma prova inferior apenas para manter pares distintos.
- Requisito que só se materializa em execução SHALL ancorar-se no gate, no teste ou na configuração versionada que **exige** aquela execução, e a execução em si SHALL ser registrada como evidência separada.

#### Scenario: Evidência aponta para artefato e elemento
- **WHEN** o relatório da auditoria é inspecionado
- **THEN** a linha de cada requisito enumera todos os pares declarados, cada um identificando o artefato versionado e o elemento literal comprovado dentro dele
- **AND** requisitos com mais de uma cláusula normativa apresentam um par por cláusula

#### Scenario: Âncora ausente do artefato existente é recusada
- **WHEN** o artefato de evidência existe e é legível, mas não contém um dos elementos literais declarados para aquele requisito
- **THEN** o requisito aparece como reprovado no relatório, ainda que as demais âncoras dele passem
- **AND** a auditoria termina com código diferente de zero identificando o requisito e o elemento não encontrado

#### Scenario: Menção textual do identificador não aprova o requisito
- **WHEN** o identificador de um requisito é citado em prosa num documento do repositório, sem que o artefato de evidência comprove o elemento correspondente
- **THEN** o requisito aparece como reprovado
- **AND** a auditoria termina com código diferente de zero

#### Scenario: Evidência compartilhada sem declaração é recusada
- **WHEN** dois requisitos distintos declaram o mesmo artefato e o mesmo elemento comprovado sem que o compartilhamento esteja declarado e justificado no catálogo
- **THEN** a verificação estrutural falha identificando os requisitos que dividem a evidência
- **AND** o compartilhamento declarado é aceito, desde que cada requisito continue com pares que cubram as suas próprias cláusulas

#### Scenario: Requisito garantido por execução aponta para quem a exige
- **WHEN** um requisito só se materializa em execução — cobertura agregada, infraestrutura real em containers ou subida do ambiente completo
- **THEN** sua evidência é o gate, o teste ou a configuração versionada que torna aquela execução obrigatória
- **AND** a auditoria não declara a execução realizada, que permanece registrada como evidência separada

### Requirement: Relatório técnico da entrega

O projeto SHALL manter um relatório técnico em português, com tom técnico, que permita entender a solução sem abrir o código. Ele SHALL conter, em seções próprias e identificáveis: contexto e problema; arquitetura; decisões e trade-offs; segurança; mensageria e garantias de entrega; estratégia de testes com números; limites conhecidos e o que ficou fora do escopo; e aprendizados.

Todo número apresentado no relatório SHALL nomear a execução ou o artefato que o produziu. O relatório SHALL NOT apresentar métrica que não tenha sido efetivamente medida.

Os números da seção de testes SHALL declarar como origem uma execução de medição identificada, e SHALL NOT ser reescritos por execuções de verificação posteriores. Uma execução posterior que divergir em contagem por causa dos próprios testes desta entrega SHALL ser registrada como evidência separada, sem alterar o relatório; divergência de cobertura ou resultado SHALL interromper a entrega para investigação, em vez de ser acomodada por edição.

O relatório SHALL distinguir a auditoria estática de requisitos das execuções que comprovam comportamento, e SHALL declarar as garantias reais de entrega já registradas pelo projeto, inclusive o caráter ao-menos-uma-vez da publicação e do envio de notificação, sem prometer garantia mais forte do que a implementada.

#### Scenario: Seções obrigatórias estão presentes
- **WHEN** o relatório técnico é inspecionado
- **THEN** cada uma das seções obrigatórias aparece uma única vez, na ordem declarada

#### Scenario: Cada número nomeia sua origem
- **WHEN** um número de cobertura, de quantidade de testes ou de execução aparece no relatório
- **THEN** ele está acompanhado do comando ou artefato que o produziu
- **AND** os números da seção de testes identificam a execução de medição de onde vieram

#### Scenario: Limites e cortes são declarados
- **WHEN** a seção de limites é lida
- **THEN** ela declara a entrega ao-menos-uma-vez, o que ficou fora do escopo, as ambiguidades do enunciado decididas pelo projeto e o alcance da auditoria estática de requisitos

#### Scenario: Links locais do relatório são válidos
- **WHEN** os links relativos do relatório são resolvidos a partir do próprio arquivo
- **THEN** cada destino local existe e permanece dentro do repositório

#### Scenario: Relatório permanece um documento de texto versionado
- **WHEN** o repositório é inspecionado
- **THEN** o relatório existe como documento de texto versionado
- **AND** nenhum binário de escritório é gerado ou versionado por esta entrega

### Requirement: Roteiro de demonstração cronometrado

O projeto SHALL manter um roteiro de demonstração dividido em blocos cronometrados, cuja soma das durações fique entre 5 e 8 minutos, cobrindo o fluxo da entrega de ponta a ponta.

A preparação SHALL viver em uma seção própria de pré-demonstração, fora da contagem: subida do ambiente, espera pela saúde dos serviços, abertura das interfaces e preparação dos dados. O cronômetro SHALL começar com o ambiente já saudável e as interfaces prontas, de modo que a janela cubra a apresentação e não a construção das imagens.

O roteiro SHALL exibir, com URL real, a documentação interativa da API REST, a interface de consulta GraphQL, a interface de administração do broker, a caixa de entrada de correio da demonstração e os logs correlacionados dos três serviços por um mesmo identificador de correlação.

Os comandos, endereços, credenciais e a sequência de operações SHALL corresponder aos artefatos reais do repositório. Cada bloco SHALL prever uma alternativa curta para o caso de uma interface demorar ou falhar durante a apresentação, de modo que a demonstração continue sem depender daquele recurso.

#### Scenario: Duração total cabe na janela de 5 a 8 minutos
- **WHEN** as durações declaradas dos blocos cronometrados são somadas
- **THEN** o total é de no mínimo 5 e no máximo 8 minutos
- **AND** cada bloco cronometrado declara sua própria duração
- **AND** a seção de pré-demonstração não entra na soma

#### Scenario: As cinco superfícies obrigatórias aparecem com endereço real
- **WHEN** o roteiro é inspecionado
- **THEN** a documentação interativa da API, a interface GraphQL, a administração do broker, a caixa de entrada de correio e os logs correlacionados aparecem com os endereços efetivamente publicados pelo ambiente completo

#### Scenario: Comandos e credenciais conferem com os artefatos reais
- **WHEN** os comandos e as credenciais citados no roteiro são comparados aos do ambiente completo e aos dados de demonstração
- **THEN** cada um coincide com o artefato versionado correspondente, sem credencial inventada nem comando inexistente
- **AND** nenhum comando do roteiro remove volumes ou apaga dados do ambiente

#### Scenario: Correlação é demonstrada por um único identificador
- **WHEN** o bloco de logs é executado conforme o roteiro
- **THEN** o mesmo identificador de correlação é localizado nos logs dos três serviços para o fluxo demonstrado

#### Scenario: Falha de uma interface tem alternativa curta
- **WHEN** uma das interfaces não responde no tempo previsto durante a apresentação
- **THEN** o roteiro oferece, para aquele bloco, uma alternativa curta que comprova o mesmo ponto sem aquela interface
