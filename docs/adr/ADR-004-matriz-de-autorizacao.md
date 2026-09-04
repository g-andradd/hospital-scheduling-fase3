# ADR-004 — A matriz de autorização como fonte executável

## Contexto

O enunciado do Tech Challenge descreve três perfis — médico, enfermeiro e paciente — e diz o que
cada um faz, mas não em forma de matriz. A frase que decide o desenho é a que trata o paciente:
ele "consulta suas consultas". Isso é ambíguo em dois pontos que mudam completamente a
implementação.

**Primeira ambiguidade: confirmar é ato médico ou ato do paciente?** O enunciado lista a
confirmação entre as operações de agenda, sem dizer de quem. Ler como ato clínico faria o
paciente ter apenas leitura; ler como confirmação de comparecimento — que é o significado usual
em agendamento hospitalar — dá ao paciente o direito de confirmar, mas só a própria consulta.

**Segunda ambiguidade: "suas consultas" é filtro ou é permissão?** Se for filtro, o paciente
chama `GET /api/v1/consultas?pacienteId=<outro>` e recebe as consultas de outro paciente, porque
o filtro é dele para usar. Se for permissão, o identificador que ele mandar é irrelevante: o
sistema impõe o próprio.

Nenhuma das duas se resolve lendo o enunciado com mais atenção. São decisões de projeto.

Há ainda um problema de forma, independente do conteúdo. Uma matriz de autorização escrita na
documentação e implementada em anotações espalhadas por um controller são duas cópias da mesma
regra. Elas começam iguais e divergem no primeiro endpoint acrescentado com pressa — e a
divergência é silenciosa nos dois sentidos: o documento passa a mentir, ou o código passa a
permitir o que o documento proíbe. O segundo caso é uma brecha de autorização que nenhum teste
existente reprova, porque nenhum teste existente conhece a linha nova.

## Decisão

**Resolvemos as duas ambiguidades a favor do paciente com recorte obrigatório**, e registramos o
resultado na §3 de `docs/02-especificacao-funcional.md` como a matriz de 7 endpoints × 3 perfis.

- Confirmar é ato do paciente **sobre a própria consulta**. Também é permitido a médico e
  enfermeiro, que confirmam em nome de quem ligou.
- "Suas consultas" é **permissão, não filtro**. O `pacienteId` que um paciente envia na listagem
  é descartado e substituído pelo dele. Um filtro que o cliente pode alterar não é controle de
  acesso; é sugestão.

**E tornamos essa tabela executável.** O teste de integração não contém uma cópia da matriz: ele
lê a tabela markdown do documento em tempo de execução e gera uma célula de teste por combinação
de endpoint e perfil. As 21 células são exercitadas contra a aplicação de pé, com token real de
cada perfil.

A consequência é a que interessa: **acrescentar uma linha à tabela do documento faz aparecer três
casos de teste que falham** até alguém implementá-los. Documento e código não podem divergir,
porque o documento é a entrada do teste.

Um leitor de documentação tem um modo de falhar próprio — parar de encontrar a tabela e devolver
lista vazia, com a suíte inteira passando sem verificar nada. Por isso a leitura tem uma segunda
asserção, separada, que afirma o que ela precisa ter encontrado: 7 linhas de endpoint, 3 perfis,
21 células. Sem essa asserção a primeira seria decorativa.

A regra de propriedade — "só a própria" — não vive no controller. Vive nos casos de uso, que
recebem um `SolicitanteAutenticado` como parâmetro obrigatório. Ver ADR-005 para por que essa
separação importa.

## Alternativas consideradas

**Deixar o paciente sem confirmação, só com leitura.**
Descartada. É a leitura mais restritiva do enunciado e a mais fácil de implementar, mas remove do
sistema o caso de uso mais comum de um app de agendamento hospitalar. A ambiguidade é real, e
resolvê-la para o lado que empobrece o produto não é conservadorismo — é evitar a decisão.

**Tratar `pacienteId` como filtro que o paciente pode usar livremente.**
Descartada, e é a alternativa perigosa. Ela é indistinguível da correta em qualquer teste que use
o próprio identificador do paciente; só falha quando alguém manda o identificador de outra
pessoa. Um sistema que vaza dados de saúde só sob entrada maliciosa passa em toda demonstração e
falha em produção.

**Manter a matriz apenas em anotações `@PreAuthorize`, com a documentação descrevendo o código.**
Descartada. Inverte a direção certa: a documentação passaria a ser gerada a partir da
implementação, e uma anotação errada viraria documentação errada, coerente e falsa. A matriz é
requisito; o código é que deve prestar contas a ela.

**Copiar a matriz para uma constante no teste, em vez de ler o documento.**
Descartada. Resolve a cobertura e não resolve a divergência — apenas move a cópia de lugar. Um
endpoint novo continua exigindo que alguém lembre de atualizar dois arquivos.

## Consequências

**Positivas**
- Uma linha nova no documento produz três testes falhando. A proteção não depende de ninguém
  lembrar de nada.
- As duas ambiguidades ficam registradas com a razão da escolha, e não como se o enunciado as
  tivesse resolvido.
- Um `pacienteId` forjado na listagem é inofensivo por construção: o caso de uso substitui o
  valor antes de consultar o repositório.
- A matriz vira o artefato de revisão. Discutir autorização passa a ser discutir uma tabela de 21
  células, não ler anotações espalhadas.

**Negativas, aceitas**
- O teste depende do **formato** da tabela markdown. Trocar a estrutura das colunas quebra a
  leitura — de propósito: a asserção de 7×3×21 falha ruidosamente em vez de esvaziar a suíte.
- O caminho do documento (`../docs/02-especificacao-funcional.md`) é relativo ao módulo, o que
  acopla o teste ao layout do repositório. Aceitável num monorepo (ADR-003); seria frágil se os
  serviços virassem repositórios separados.
- A tabela cobre presença de permissão, não profundidade da regra. Que o paciente só alcance a
  própria consulta é verificado por cenários dedicados, não pela célula da matriz — a célula
  afirma "permitido com recorte", e o recorte tem teste próprio.

## Status

Aceita. Implementada em `add-autenticacao-jwt` (M04), em `MatrizDeAutorizacao` e
`MatrizDeAutorizacaoIT`. A matriz normativa está em `docs/02-especificacao-funcional.md` §3.
