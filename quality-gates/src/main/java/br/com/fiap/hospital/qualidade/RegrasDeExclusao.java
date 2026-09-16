package br.com.fiap.hospital.qualidade;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.w3c.dom.Element;

/**
 * A unica exclusao de cobertura admitida e {@code **}{@code /*Application.class}, declarada
 * uma vez, na configuracao do {@code jacoco-maven-plugin} em {@code build/plugins} do POM raiz.
 *
 * <p>Qualquer outra forma de tirar codigo da conta — exclusao adicional, variante mais ampla,
 * exclusao por pacote, exclusao vazia, inclusao restritiva ou a mesma exclusao repetida num
 * modulo ou numa execucao — muda a base do percentual e e recusada.
 */
public final class RegrasDeExclusao {

    public static final String NORMATIVA = "**/*Application.class";
    private static final String JACOCO = "jacoco-maven-plugin";

    private RegrasDeExclusao() {}

    /**
     * @param raiz o elemento {@code project} do POM raiz
     * @param poms o elemento {@code project} de cada modulo, pelo nome em {@code <modules>}
     */
    public static List<String> verificar(Element raiz, Map<String, Element> poms) {
        List<String> violacoes = new ArrayList<>();
        int normativas = 0;

        for (Element plugin : jacoco(XmlSeguro.caminho(raiz, "build", "plugins"))) {
            for (String exclusao : exclusoes(XmlSeguro.filho(plugin, "configuration"))) {
                if (NORMATIVA.equals(exclusao)) {
                    normativas++;
                } else {
                    violacoes.add(recusa("raiz", exclusao));
                }
            }
            violacoes.addAll(inclusoes("raiz", XmlSeguro.filho(plugin, "configuration")));
            violacoes.addAll(emExecucoes("raiz", plugin));
        }
        if (normativas == 0) {
            violacoes.add("exclusao normativa ausente: " + NORMATIVA);
        } else if (normativas > 1) {
            violacoes.add("exclusao normativa repetida na raiz: " + normativas + " vezes");
        }

        violacoes.addAll(foraDoPontoNormativo("raiz (pluginManagement)",
                jacoco(XmlSeguro.caminho(raiz, "build", "pluginManagement", "plugins"))));
        violacoes.addAll(emPerfis("raiz", raiz));
        poms.forEach((modulo, pom) -> {
            violacoes.addAll(foraDoPontoNormativo(modulo, jacoco(XmlSeguro.caminho(pom, "build", "plugins"))));
            violacoes.addAll(foraDoPontoNormativo(modulo + " (pluginManagement)",
                    jacoco(XmlSeguro.caminho(pom, "build", "pluginManagement", "plugins"))));
            violacoes.addAll(emPerfis(modulo, pom));
        });
        return violacoes;
    }

    private static List<String> emPerfis(String dono, Element pom) {
        List<String> violacoes = new ArrayList<>();
        for (Element perfil : XmlSeguro.filhos(XmlSeguro.filho(pom, "profiles"), "profile")) {
            String nome = dono + " (perfil " + XmlSeguro.texto(perfil, "id") + ")";
            violacoes.addAll(foraDoPontoNormativo(nome, jacoco(XmlSeguro.caminho(perfil, "build", "plugins"))));
            violacoes.addAll(foraDoPontoNormativo(nome,
                    jacoco(XmlSeguro.caminho(perfil, "build", "pluginManagement", "plugins"))));
        }
        return violacoes;
    }

    /** Fora do ponto normativo, nem a propria exclusao normativa e admitida. */
    private static List<String> foraDoPontoNormativo(String dono, List<Element> plugins) {
        List<String> violacoes = new ArrayList<>();
        for (Element plugin : plugins) {
            for (String exclusao : exclusoes(XmlSeguro.filho(plugin, "configuration"))) {
                violacoes.add(recusa(dono, exclusao));
            }
            violacoes.addAll(inclusoes(dono, XmlSeguro.filho(plugin, "configuration")));
            violacoes.addAll(emExecucoes(dono, plugin));
        }
        return violacoes;
    }

    private static List<String> emExecucoes(String dono, Element plugin) {
        List<String> violacoes = new ArrayList<>();
        for (Element execucao : XmlSeguro.filhos(XmlSeguro.filho(plugin, "executions"), "execution")) {
            String nome = dono + " (execucao " + XmlSeguro.texto(execucao, "id") + ")";
            Element configuracao = XmlSeguro.filho(execucao, "configuration");
            for (String exclusao : exclusoes(configuracao)) {
                violacoes.add(recusa(nome, exclusao));
            }
            violacoes.addAll(inclusoes(nome, configuracao));
        }
        return violacoes;
    }

    private static String recusa(String dono, String exclusao) {
        if (exclusao.isEmpty()) {
            return "exclusao vazia em " + dono;
        }
        if (NORMATIVA.equals(exclusao)) {
            return "exclusao " + NORMATIVA + " repetida fora de build/plugins da raiz em " + dono;
        }
        return "exclusao alem de " + NORMATIVA + " em " + dono + ": " + exclusao;
    }

    private static List<String> inclusoes(String dono, Element configuracao) {
        List<String> violacoes = new ArrayList<>();
        for (Element lista : XmlSeguro.filhos(configuracao, "includes")) {
            for (Element inclusao : XmlSeguro.filhos(lista, "include")) {
                violacoes.add("inclusao restritiva em " + dono + ": " + inclusao.getTextContent().trim());
            }
        }
        return violacoes;
    }

    private static List<String> exclusoes(Element configuracao) {
        List<String> encontradas = new ArrayList<>();
        for (Element lista : XmlSeguro.filhos(configuracao, "excludes")) {
            List<Element> itens = XmlSeguro.filhos(lista, "exclude");
            if (itens.isEmpty()) {
                encontradas.add("");
            }
            for (Element item : itens) {
                encontradas.add(item.getTextContent().trim());
            }
        }
        return encontradas;
    }

    private static List<Element> jacoco(Element plugins) {
        return XmlSeguro.filhos(plugins, "plugin").stream()
                .filter(p -> JACOCO.equals(XmlSeguro.texto(p, "artifactId")))
                .toList();
    }
}
