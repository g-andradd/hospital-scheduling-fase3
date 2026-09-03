# ADR-007 — Clean Architecture apenas no serviço de agendamento

## Contexto

O sistema tem três serviços (ADR-002). A pergunta que aparece na primeira linha de código é se todos os três recebem o mesmo tratamento arquitetural.

A resposta reflexiva — "aplicar o mesmo padrão em tudo, por consistência" — merece resistência, porque os três serviços não fazem o mesmo tipo de trabalho.

O **agendamento** decide. Ele responde perguntas que só existem por causa das regras do negócio: esta consulta pode ser marcada neste horário? esta transição de status é legal? este cancelamento tem justificativa? São regras que mudam por decisão de negócio, precisam ser lidas e auditadas, e valem por si — independentes de estarem atrás de REST hoje e talvez de outra coisa amanhã.

**Notificação** e **histórico** traduzem. Cada um recebe um evento e o transforma em outra coisa: uma mensagem para o paciente, uma linha num read model. Praticamente todo o código deles é adaptador — listener AMQP de um lado, JPA ou GraphQL do outro. A quantidade de regra que sobreviveria num núcleo isolado é próxima de zero.

## Decisão

**Clean Architecture estrita no `agendamento-service`**, com três camadas e regra de dependência `infrastructure → application → domain`:

- `domain` — entidades, value objects, enums, exceções de negócio e portas de saída. **Zero import** de `org.springframework`, `jakarta.persistence`, `jakarta.validation` ou `com.fasterxml`.
- `application` — um caso de uso por classe, com um único método público.
- `infrastructure` — todos os adaptadores. Único lugar onde Spring, JPA, AMQP e HTTP aparecem.

**Camadas simples em `notificacao-service` e `historico-service`**: `consumer`, `repository`, `sender`, `scheduler`, `graphql`. Sem núcleo isolado, sem portas, sem inversão de dependência.

A regra do agendamento não fica na disciplina de quem escreve: uma suíte ArchUnit a verifica no build (M11), com regras para a direção das dependências, para os imports proibidos no `domain`, para o método público único dos casos de uso e para a proibição de controller injetar repositório.

## Alternativas consideradas

**Clean Architecture nos três serviços.**
Descartada. Nos serviços satélites, o resultado previsível é uma porta por adaptador e um caso de uso que só repassa a chamada — indireção que não protege nenhuma regra, porque não há regra a proteger. O custo real não é digitar as classes, é a leitura: quem abre o `notificacao-service` para entender como o lembrete D-1 funciona teria de atravessar três camadas para chegar a uma query e um envio de e-mail. Consistência aplicada onde não há o problema que o padrão resolve produz cerimônia, não qualidade.

**Camadas simples nos três, sem Clean em lugar nenhum.**
Descartada. As regras de agendamento são o núcleo avaliado do trabalho, e são justamente as que precisam ser testáveis sem infraestrutura. Com JPA dentro da entidade, "consulta no passado é recusada" vira um teste que sobe um contexto e um banco — mais lento, mais frágil, e verificando a coisa errada. O isolamento também permitiu escrever todo o M01 com fakes em memória, antes de existir qualquer migration.

**Módulos Maven separados por camada dentro do agendamento** (`agendamento-domain`, `agendamento-application`, `agendamento-infrastructure`).
Descartada. O compilador passaria a impedir a violação da regra de dependência, o que é mais forte que verificar depois com ArchUnit. Mas triplicaria a contagem de módulos do repositório para proteger uma regra que uma suíte de testes já protege, e a leitura do serviço passaria a exigir navegar três projetos. A troca não se paga nesta escala.

## Consequências

**Positivas**
- As regras de negócio são testáveis em memória, sem Spring, sem banco e sem broker. O M01 entregou 167 testes com essa característica, e a suíte inteira roda em segundos.
- Trocar o adaptador não toca a regra. A persistência (M02), o HTTP (M03) e a mensageria (M05) satisfazem portas definidas antes deles existirem, sem alterar `domain` nem `application`.
- A fronteira é verificável, não aspiracional: o ArchUnit falha o build quando alguém a cruza.
- O esforço arquitetural fica onde há retorno. Notificação e histórico são lidos de cima a baixo em minutos.

**Negativas, aceitas**
- Duas convenções no mesmo repositório. Quem for do agendamento para o histórico encontra estruturas diferentes — mitigado por este ADR e pelo `openspec/config.yaml`, que declara explicitamente onde cada uma vale.
- Mapeamento manual entre entidade de domínio e entidade JPA, sem MapStruct. É código a mais para escrever e manter, aceito porque mapeamento gerado esconde exatamente o que a banca precisa ver.
- Casos de uso devolvem `record`s próprios em vez do agregado, o que acrescenta uma tradução na fronteira de saída — o preço de o contrato HTTP não ficar refém da forma interna da `Consulta`.
- O critério que sustenta a decisão é a **quantidade de regra própria**, não o tamanho do serviço. Se o `notificacao-service` acumular política de negócio real — janelas de silêncio, escalonamento por canal, preferências por paciente — esta decisão deve ser reavaliada para ele.

## Status

Aceita. Materializada em `add-agendamento-domain` (M01), que cria `domain` e `application` sem nenhuma dependência de framework. A verificação automática por ArchUnit é do M11.
