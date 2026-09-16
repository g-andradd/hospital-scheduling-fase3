package br.com.fiap.hospital.qualidade;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tabela do D5: user properties do Surefire e do Failsafe 3.2.5 capazes de selecionar, excluir,
 * pular ou tolerar casos, conferida contra os descritores {@code plugin.xml} dos dois plugins.
 *
 * <p>O processo da auditoria recebe cada propriedade como {@code nome=valor}. O Maven deixa literal
 * a expressao de uma propriedade nao definida, entao {@code ${...}} conta como indefinida; qualquer
 * outro valor, inclusive vazio ou {@code false}, conta como definida e reprova — o gate global nao
 * aceita filtro nenhum, nem o que declara o valor padrao.
 */
public final class PropriedadesDeFiltro {

    /** Classe do mecanismo -> propriedades. A ordem e a do D5. */
    public static final Map<String, List<String>> TABELA = tabela();

    private PropriedadesDeFiltro() {}

    private static Map<String, List<String>> tabela() {
        Map<String, List<String>> tabela = new LinkedHashMap<>();
        tabela.put("selecao por nome", List.of("test", "it.test", "surefire.includes", "failsafe.includes",
                "surefire.includesFile", "failsafe.includesFile"));
        tabela.put("exclusao por nome", List.of("surefire.excludes", "failsafe.excludes",
                "surefire.excludesFile", "failsafe.excludesFile"));
        tabela.put("tags", List.of("groups", "excludedGroups"));
        tabela.put("motores", List.of("surefire.includeJUnit5Engines", "surefire.excludeJUnit5Engines",
                "failsafe.includeJUnit5Engines", "failsafe.excludeJUnit5Engines"));
        tabela.put("suites declaradas", List.of("surefire.suiteXmlFiles", "failsafe.suiteXmlFiles"));
        tabela.put("ampliacao por dependencia", List.of("dependenciesToScan"));
        tabela.put("omissao", List.of("skipTests", "skipITs", "maven.test.skip", "maven.test.skip.exec",
                "surefire.skipAfterFailureCount", "failsafe.skipAfterFailureCount"));
        tabela.put("tolerancia", List.of("maven.test.failure.ignore", "surefire.rerunFailingTestsCount",
                "failsafe.rerunFailingTestsCount"));
        tabela.put("tolerancia a selecao vazia", List.of("surefire.failIfNoSpecifiedTests",
                "failsafe.failIfNoSpecifiedTests", "it.failIfNoSpecifiedTests"));
        return java.util.Collections.unmodifiableMap(tabela);
    }

    public static List<String> todas() {
        return TABELA.values().stream().flatMap(List::stream).toList();
    }

    public static String classeDe(String propriedade) {
        return TABELA.entrySet().stream()
                .filter(e -> e.getValue().contains(propriedade))
                .map(Map.Entry::getKey)
                .findFirst().orElse(null);
    }

    /**
     * @param recebidas os valores repassados ao processo, por nome
     * @return uma violacao por propriedade definida ou nao repassada
     */
    public static List<String> verificar(Map<String, String> recebidas) {
        List<String> violacoes = new ArrayList<>();
        for (String propriedade : todas()) {
            if (!recebidas.containsKey(propriedade)) {
                violacoes.add("propriedade " + propriedade + " nao repassada a auditoria: a recusa nao pode ser conferida");
                continue;
            }
            String valor = recebidas.get(propriedade);
            if (!indefinida(propriedade, valor)) {
                violacoes.add("filtro ativo por parametro (" + classeDe(propriedade) + "): -D" + propriedade + "="
                        + valor + ": o gate global nao aceita filtro");
            }
        }
        recebidas.keySet().stream()
                .filter(nome -> !todas().contains(nome))
                .forEach(nome -> violacoes.add("propriedade desconhecida repassada a auditoria: " + nome));
        return violacoes;
    }

    /** O Maven deixa literal a expressao de uma propriedade nao definida. */
    static boolean indefinida(String propriedade, String valor) {
        return ("${" + propriedade + "}").equals(valor);
    }
}
