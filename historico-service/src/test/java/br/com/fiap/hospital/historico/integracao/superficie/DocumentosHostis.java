package br.com.fiap.hospital.historico.integracao.superficie;

import graphql.language.Document;
import graphql.language.Field;
import graphql.language.OperationDefinition;
import graphql.language.Selection;
import graphql.parser.InvalidSyntaxException;
import graphql.parser.Parser;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Gera os documentos hostis <b>a partir da operacao-alvo</b>, e prova pelo AST que eles a
 * executam.
 *
 * <p>A primeira versao da varredura usava textos fixos — {@code consulta(id: 42)} para todas
 * as operacoes — e registrava como executada a operacao da vez. Cinco rotulos apontavam para
 * o mesmo ataque, e {@code E = D × V} virava uma contagem sem significado.
 *
 * <p>A guarda que substituiu os textos fixos tambem nao podia ser textual: procurar o nome da
 * operacao no documento aceita o nome dentro de um comentario, de um alias ou de uma string,
 * nenhum dos quais executa nada. Aqui o documento e analisado pelo parser do graphql-java e o
 * que se inspeciona sao os <b>root fields</b> das definicoes de operacao — exatamente o que o
 * servidor resolveria.
 */
public final class DocumentosHostis {

    /** Nao produz AST: nao ha definicao a inspecionar. */
    public static final String VAZIO = "vazio";

    /** Nao produz AST por construcao: e o que a variante ataca. */
    public static final String SINTAXE_INVALIDA = "sintaxe-invalida";

    private DocumentosHostis() {}

    /**
     * @param raiz {@code query} ou {@code mutation}
     * @param operacao nome do campo servido
     * @param argumento nome de um argumento real da operacao
     */
    public static Map<String, String> paraOperacao(String raiz, String operacao, String argumento) {
        String selecao = " { id }";
        Map<String, String> documentos = new LinkedHashMap<>();
        documentos.put(SINTAXE_INVALIDA, raiz + " { " + operacao + " { id ");
        documentos.put(VAZIO, "");
        documentos.put("campo-inexistente", raiz + " { " + operacao + " { naoExiste } }");
        documentos.put("argumento-desconhecido",
                raiz + " { " + operacao + "(argumentoQueNaoExiste: 1)" + selecao + " }");
        documentos.put("literal-incompativel",
                raiz + " { " + operacao + "(" + argumento + ": 42)" + selecao + " }");
        documentos.put("variavel-nao-declarada",
                raiz + " { " + operacao + "(" + argumento + ": $naoDeclarada)" + selecao + " }");
        documentos.put("variavel-com-tipo-divergente",
                raiz + "($divergente: Int!) { " + operacao + "(" + argumento + ": $divergente)"
                        + selecao + " }");
        documentos.put("duas-operacoes-sem-operationName",
                raiz + " A { " + operacao + selecao + " } " + raiz + " B { " + operacao
                        + selecao + " }");
        return documentos;
    }

    /**
     * Todo documento executavel precisa chamar, como root field, a operacao que o rotula — e
     * nenhuma outra —, com o tipo de operacao da raiz correta.
     *
     * <p>{@link #VAZIO} e {@link #SINTAXE_INVALIDA} sao tratados a parte, porque legitimamente
     * nao produzem AST: o primeiro precisa estar vazio, e o segundo precisa realmente falhar
     * na analise — um "sintaxe invalida" que o parser aceita nao ataca o que promete.
     */
    public static List<String> verificar(InventarioGraphql.Operacao alvo,
                                         Map<String, String> documentos,
                                         Set<String> operacoesDoSchema) {
        List<String> erros = new ArrayList<>();
        documentos.forEach((variante, documento) -> {
            String rotulo = "documento '" + variante + "' rotulado para " + alvo;
            if (VAZIO.equals(variante)) {
                if (!documento.isBlank()) {
                    erros.add(rotulo + " deveria estar vazio: " + documento);
                }
                return;
            }
            if (SINTAXE_INVALIDA.equals(variante)) {
                if (analisar(documento) != null) {
                    erros.add(rotulo + " e sintaticamente valido: " + documento);
                }
                return;
            }
            Document arvore = analisar(documento);
            if (arvore == null) {
                erros.add(rotulo + " nao e analisavel: " + documento);
                return;
            }
            erros.addAll(verificarRootFields(rotulo, alvo, arvore, operacoesDoSchema, documento));
        });
        return erros;
    }

    private static List<String> verificarRootFields(String rotulo,
                                                    InventarioGraphql.Operacao alvo,
                                                    Document arvore,
                                                    Set<String> operacoesDoSchema,
                                                    String documento) {
        List<String> erros = new ArrayList<>();
        List<OperationDefinition> definicoes = arvore.getDefinitionsOfType(OperationDefinition.class);
        if (definicoes.isEmpty()) {
            erros.add(rotulo + " nao define operacao alguma: " + documento);
            return erros;
        }
        int chamadasAoAlvo = 0;
        for (OperationDefinition definicao : definicoes) {
            if (!definicao.getOperation().name().equalsIgnoreCase(alvo.raiz())) {
                erros.add(rotulo + " executa como " + definicao.getOperation() + ": " + documento);
            }
            for (Selection<?> selecao : definicao.getSelectionSet().getSelections()) {
                if (!(selecao instanceof Field campo)) {
                    erros.add(rotulo + " tem selecao raiz que nao e campo: " + documento);
                    continue;
                }
                // getName e o campo resolvido; o alias fica em getAlias e nao conta.
                if (campo.getName().equals(alvo.nome())) {
                    chamadasAoAlvo++;
                } else if (operacoesDoSchema.contains(campo.getName())) {
                    erros.add(rotulo + " executa " + campo.getName() + ": " + documento);
                } else {
                    erros.add(rotulo + " chama root field fora do alvo, " + campo.getName()
                            + ": " + documento);
                }
            }
        }
        if (chamadasAoAlvo == 0) {
            erros.add(rotulo + " nao chama essa operacao: " + documento);
        }
        return erros;
    }

    private static Document analisar(String documento) {
        try {
            return Parser.parse(documento);
        } catch (InvalidSyntaxException invalido) {
            return null;
        }
    }
}
