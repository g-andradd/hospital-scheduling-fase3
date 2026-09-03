# Design — Persistência do agendamento

## Context

Ver `proposal.md` — Why. Os requisitos estão em `specs/agendamento-de-consultas/spec.md`, como delta sobre a capability já promovida em `openspec/specs/agendamento-de-consultas/spec.md`.

O que o M01 deixou pronto e condiciona este design:

- Três portas de saída, com as buscas de conflito **já recortadas** por período e por status ativo (`buscarAtivasDoMedicoNoPeriodo`, `buscarAtivasDoPacienteNoPeriodo`). O adaptador não escolhe onde filtrar — a assinatura já escolheu.
- `PeriodoConsulta.sobrepoe` com a convenção `[inicio, fim)`, com Scenario provando que consultas adjacentes não conflitam.
- `Consulta.reconstituir`, fábrica estática criada para reidratação, que não aplica as validações de agendamento.
- `domain` e `application` sem nenhum import de framework, verificado na task 8.4 do M01.
- Fakes em memória cujo javadoc declara que o adaptador do M02 fará a mesma filtragem em SQL.

E um defeito recém-corrigido no M01 que molda a decisão transacional: uma entidade mutada antes da validação é persistida no flush do commit mesmo sem chamada explícita de `salvar`.

## Goals / Non-Goals

**Goals**
- Adaptadores que satisfaçam as portas sem que `domain` ou `application` mudem
- Conflito de agenda resolvido no banco, com índice, reproduzindo exatamente a borda do domínio
- Uma transação por operação de negócio, preparando o terreno para o outbox do M05
- Divergência entre fake e adaptador detectada por teste, não por acaso em produção

**Non-Goals**
- Desempenho além do índice exigido. Sem cache, sem batch, sem tuning de pool.
- Nenhuma leitura paginada. `Pageable` é do M03.
- Nenhuma tabela que este change não use. `outbox_evento` é do M05.

## Decisions

### D1 — O `[inicio, fim)` do domínio traduzido para SQL

O banco guarda `data_hora` (`timestamptz`) e `duracao_minutos` (`int`), como no modelo de dados. O fim não é coluna: é derivado.

A condição de sobreposição em SQL é a mesma do domínio, escrita com o fim calculado:

```sql
SELECT * FROM consulta
 WHERE medico_id = :medicoId
   AND status IN ('AGENDADA', 'CONFIRMADA')
   AND data_hora < :fim
   AND data_hora + (duracao_minutos * INTERVAL '1 minute') > :inicio
```

As duas últimas linhas são a tradução literal de `inicio.isBefore(outro.fim()) && outro.inicio().isBefore(fim())`. Ambas as comparações são **estritas**, e é isso que faz o caso adjacente não ser conflito: quando `data_hora + duracao = :inicio`, a última condição vira `:inicio > :inicio`, falsa.

Escolher `<` em vez de `<=` aqui é a decisão inteira. Um `<=` distraído transformaria toda consulta encaixada em conflito, e o sintoma apareceria como "não consigo agendar às 14h30 depois de uma consulta das 14h" — reclamação de usuário, não erro de teste. Por isso a borda tem Scenario próprio na spec e teste próprio contra o Postgres real, além do teste de domínio que já existe.

*Alternativa descartada:* coluna `data_hora_fim` materializada, mantida por trigger ou pela aplicação. Torna a query mais simples e o índice mais direto, mas cria um segundo lugar onde a duração vive, com risco de divergir de `duracao_minutos`. O ganho não paga o dado redundante nesta escala.

*Alternativa descartada:* tipo `tstzrange` do Postgres com operador `&&` e índice GiST, que é a solução mais idiomática e permitiria até uma constraint de exclusão. Descartada por acoplar o schema a um tipo específico do Postgres e por exigir do leitor familiaridade com GiST — num projeto que é lido e avaliado, a condição explícita comunica melhor a regra. Registrado aqui como o caminho natural caso o volume um dia justifique.

**Índice.** `consulta(medico_id, data_hora)` e `consulta(paciente_id, data_hora)`, como o roadmap exige. Eles servem o predicado `medico_id = ? AND data_hora < ?`; o segundo filtro por `data_hora + intervalo` é avaliado sobre as linhas já recortadas. Uma expressão sobre `data_hora + duracao` não é indexável sem índice funcional, e não vale a complexidade aqui.

### D2 — Uma suíte de contrato, duas implementações

As asserções do contrato de `ConsultaRepositoryPort` ficam numa classe abstrata em `src/test`, escrita apenas contra a interface:

```
ConsultaRepositoryContractTest              (abstrata, define os casos)
├── ConsultaRepositoryFakeTest              (*Test — surefire, sem container)
└── ConsultaRepositoryAdapterIT             (*IT — failsafe, Testcontainers Postgres)
```

A subclasse fornece a implementação e nada mais. Todo caso — sobreposição, adjacência, status encerrado, filtros de listagem — roda duas vezes.

O motivo é o risco que o design do M01 registrou nominalmente: *"fakes em memória divergirem do comportamento do adaptador real e mascararem um bug até o M02"*. Testes separados deixariam essa divergência invisível — o fake continuaria passando com sua própria noção de sobreposição enquanto o SQL faz outra coisa. Com a suíte compartilhada, divergir é impossível sem ficar vermelho.

Isso também dá ao M02 algo que raramente se tem: os 167 testes de caso de uso do M01 já são, indiretamente, testes do contrato. Se o adaptador satisfaz o mesmo contrato que o fake, aqueles testes continuam válidos sem alteração.

*Alternativa descartada:* escrever testes de integração independentes para o adaptador. Mais rápido de começar e é o que a maioria dos projetos faz — e é exatamente por isso que produtor e dublê divergem silenciosamente.

### D3 — `reconstituir` é o único caminho de volta do banco

O mapper chama `Consulta.reconstituir`, nunca `Consulta.agendar`.

A diferença não é estilística. `agendar` aplica `exigirPeriodoFuturo`: usá-la na reidratação faria **toda consulta passada explodir ao ser carregada**, e o sistema ficaria incapaz de ler o próprio histórico depois de algumas semanas — exatamente o cenário do cliente que quer ver o que aconteceu no mês passado. `reconstituir` recebe o status e os instantes já decididos e não revalida nada.

O critério que separa as duas: **invariante de transição** pertence à operação de negócio, não à leitura. Uma consulta gravada é um fato consumado; recusá-la na leitura seria recusar o passado. O que o `reconstituir` continua garantindo são as invariantes **estruturais** — id, paciente, médico, período e status não nulos —, porque uma linha sem eles indica corrupção, não histórico legítimo.

Os value objects são o caso simétrico e merecem atenção: `Cpf`, `Email` e `Crm` validam no construtor, e o mapper os reconstrói a partir do banco. Isso é aceitável e desejável — o banco tem constraint de unicidade mas não de formato, e um valor inválido gravado por outro caminho deve falhar alto na leitura em vez de circular pelo sistema. A spec cobre isso indiretamente: são dados que só entram pela aplicação.

O Scenario "Consulta gravada no passado é recuperável" existe para travar esta decisão. É o teste que quebra se alguém, no futuro, "simplificar" o mapper para usar a fábrica de agendamento.

### D4 — Transação num decorador em `infrastructure`

Cada caso de uso ganha um decorador anotado com `@Transactional`, que delega:

```
infrastructure.transacao.AgendarConsultaTransacional
  implements <mesma assinatura>          @Transactional
  → application.AgendarConsultaUseCase
```

Três forças em tensão, e esta é a única posição que atende as três.

**A transação precisa envolver a operação inteira.** No M05 o caso de uso vai gravar a consulta e a linha do outbox, e a garantia do Transactional Outbox é que as duas caem na mesma transação ou nenhuma cai. `@Transactional` no adaptador tornaria cada chamada de porta sua própria transação — `buscarPorId` numa, `salvar` noutra — o que destrói tanto o outbox quanto o lock otimista.

**`application` fica sem framework.** A task 8.4 do M01 verificou zero imports de `org.springframework` em `domain` e `application`, e o design daquele change tratou isso como propriedade da entrega. Anotar os casos de uso encerraria essa propriedade em silêncio. O `openspec/config.yaml` só proíbe Spring no `domain`, então seria legal pela letra — e é justamente por ser legal pela letra que a perda passaria despercebida.

**O custo é visível e pequeno.** Seis classes de delegação, uma linha cada. É boilerplate, e admiti-lo é honesto: a alternativa de anotar o caso de uso tem menos código. A troca é código explícito por uma fronteira que continua verificável pelo ArchUnit do M11.

**A armadilha do flush, e como o adaptador a evita.** O M01 acabou de expor que entidade JPA gerenciada mutada dentro da transação é persistida no commit, sem ninguém chamar `salvar`. Com o caso de uso agora rodando dentro de uma transação, isso deixa de ser hipótese.

A proteção é estrutural: **o objeto de domínio nunca é a entidade gerenciada.** `buscarPorId` carrega a `ConsultaEntity`, mapeia para uma `Consulta` de domínio e devolve a `Consulta` — que é um objeto solto, invisível para o `EntityManager`. O caso de uso muta esse objeto solto. Mutá-lo não agenda escrita nenhuma.

A escrita só acontece em `salvar`, que localiza a entidade gerenciada e copia o estado do domínio sobre ela. Uma operação recusada nunca chega em `salvar`, logo nunca toca a entidade gerenciada, logo o flush não tem o que escrever. O mapeamento manual, que o roadmap exige por legibilidade, é o que torna essa separação possível — um ORM mapeando direto a entidade de domínio não teria essa folga.

O Scenario "Operação recusada não deixa registro" existe para provar isso contra o Postgres real, e é o teste que pega a regressão se alguém futuramente fizer o mapper devolver a entidade gerenciada.

*Alternativa descartada:* `TransactionTemplate` injetado no caso de uso. Evita a anotação mas injeta um tipo do Spring no construtor — o mesmo acoplamento com mais cerimônia.

### D5 — `@Version` só na entidade JPA

`ConsultaEntity` tem `@Version`; a `Consulta` de domínio continua sem campo `versao`, como o M01 decidiu.

O lock protege o intervalo entre a leitura e a gravação **dentro da transação**: se outra transação alterar a mesma linha nesse intervalo, o flush falha. O adaptador captura `OptimisticLockingFailureException` e lança `AlteracaoConcorrenteException`, do domínio — sem isso, um tipo do Spring subiria pela camada de aplicação e o M03 teria de conhecer exceções de persistência para montar o `ProblemDetail`.

**Limitação aceita e declarada:** isso não protege o ciclo "cliente lê às 10h, envia alteração às 10h05". Para isso o cliente precisaria devolver a versão que leu, o que exigiria a versão no domínio e no contrato HTTP. Fica registrado como candidato ao M03, se a revisão daquele change entender que vale. Para o escopo avaliado aqui, o lock transacional cobre o que a spec descreve.

### D6 — Entidades JPA e mapeamento

Entidades em `infrastructure.persistence.entity`, com sufixo `Entity`. Nenhuma anotação JPA em `domain` — a regra que o ArchUnit do M11 vai verificar.

Mapeamento manual em `infrastructure.persistence.mapper`, sem MapStruct: a banca lê o código, e mapeamento gerado esconde justamente a decisão do D4 sobre entidade gerenciada.

`status` persistido como `varchar` com `@Enumerated(EnumType.STRING)`. Ordinal quebra silenciosamente quando alguém reordena o enum — e `StatusConsulta` é um enum que ganha significado por posição na máquina de estados, então reordenar é plausível.

`data_hora` como `timestamptz` mapeado para `OffsetDateTime`. O Postgres normaliza para UTC internamente; o offset original não é preservado pelo tipo. Consequência: uma consulta gravada com `-03:00` volta com o offset da sessão. Os testes de durabilidade comparam **instantes**, não representações textuais, e a decisão fica registrada aqui porque o contrato de eventos do M05 transmite offset explícito e vai precisar reconstituí-lo a partir do fuso da aplicação.

### D7 — `ddl-auto: validate`

`spring.jpa.hibernate.ddl-auto=validate`. O Flyway cria; o Hibernate confere e recusa subir se o mapeamento divergir do schema.

`none` deixaria a divergência passar até a primeira query falhar em runtime. `update` é proibido pelo projeto e tornaria o schema imprevisível e não versionado. O Scenario "Schema divergente do esperado interrompe a subida" é o teste dessa configuração.

## Risks / Trade-offs

| Risco | Mitigação |
|---|---|
| `<=` no lugar de `<` na query de sobreposição transforma toda consulta adjacente em conflito, com sintoma que parece regra de negócio | Scenario dedicado na spec e teste de integração próprio contra o Postgres real, além do teste de domínio já existente. A borda é verificada nas duas camadas |
| Fake e adaptador divergirem no significado de "ativa no período" | Suíte de contrato única exercendo as duas implementações (D2). Divergir sem ficar vermelho é impossível |
| Mapper usar `Consulta.agendar` na reidratação e quebrar a leitura de consultas passadas | Scenario "Consulta gravada no passado é recuperável", que falha imediatamente se a fábrica errada for usada |
| Entidade gerenciada vazando para o caso de uso e reintroduzindo o defeito do M01 sob transação real | Separação estrutural do D4: o domínio nunca é a entidade gerenciada. Scenario "Operação recusada não deixa registro" prova contra o Postgres |
| Decorador transacional esquecido em um caso de uso, deixando a operação sem transação | Task de verificação conferindo que existe um decorador por caso de uso; no M11 vira regra ArchUnit |
| Índice criado mas não usado pelo plano de execução, com a query varrendo a tabela | Teste de integração inspecionando o plano com `EXPLAIN` para a query de conflito |
| Perda do offset original ao persistir em `timestamptz` surpreender o M05 | Decisão declarada no D6, com asserções de durabilidade comparando instantes; o M05 reconstitui o offset a partir do fuso da aplicação |

## Migration Plan

O banco `agendamento_db` está vazio — nenhum ambiente tem dados. As migrations rodam do zero, e não há migração de dados a fazer.

Reverter é reverter o merge e recriar o banco com `docker compose down -v`. A partir do momento em que houver dados que importem, uma migration aplicada não é revertida por rollback de código; a partir dali o caminho é sempre uma nova migration corretiva.

## Open Questions

Nenhuma. As duas ambiguidades materiais — a posição do `@Transactional` diante da propriedade "sem framework em `application`" estabelecida no M01, e a forma de expor o lock otimista sem dar campo `versao` ao domínio — foram decididas com o Gabriel antes da criação desta change e estão registradas em D4 e D5.
