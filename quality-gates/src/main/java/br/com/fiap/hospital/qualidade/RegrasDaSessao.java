package br.com.fiap.hospital.qualidade;

import java.util.ArrayList;
import java.util.List;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * Regras estruturais da sessao de verificacao declarada no POM raiz (D4).
 *
 * <p>A sessao e o que permite ao gate confiar no que le sem olhar data de arquivo: ela apaga as
 * evidencias anteriores e grava o identificador que o gate compara. Uma execucao declarada so
 * em {@code pluginManagement} nao roda; uma herdada roda em cada modulo e apaga o que os
 * anteriores produziram; uma fase errada roda tarde demais. Todas parecem configuradas.
 */
public final class RegrasDaSessao {

    /** Os unicos alvos de remocao. Qualquer outro caminho e fora do que a sessao pode apagar. */
    public static final List<String> ALVOS = List.of(
            "*/target/jacoco.exec",
            "*/target/surefire-reports/**/*",
            "*/target/failsafe-reports/**/*",
            "quality-gates/target/site/jacoco-aggregate/**");

    public static final String MARCADOR = "${project.build.directory}/sessao-de-verificacao/sessao.txt";
    public static final String PROPRIEDADE = "${sessao.verificacao}";
    public static final String FORMATO = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'";

    private RegrasDaSessao() {}

    public static List<String> verificar(Element projetoRaiz) {
        List<String> erros = new ArrayList<>();

        Element propriedades = XmlSeguro.filho(projetoRaiz, "properties");
        if (!"${maven.build.timestamp}".equals(XmlSeguro.texto(propriedades, "sessao.verificacao"))) {
            erros.add("propriedade sessao.verificacao nao e o ${maven.build.timestamp}");
        }
        if (!FORMATO.equals(XmlSeguro.texto(propriedades, "maven.build.timestamp.format"))) {
            erros.add("formato do identificador nao e " + FORMATO);
        }

        Element antrun = plugin(XmlSeguro.caminho(projetoRaiz, "build", "plugins"));
        if (antrun == null) {
            erros.add(plugin(XmlSeguro.caminho(projetoRaiz, "build", "pluginManagement", "plugins")) != null
                    ? "sessao declarada so em pluginManagement: a execucao nunca roda"
                    : "sessao de verificacao ausente de build/plugins");
            return erros;
        }
        String versao = XmlSeguro.texto(antrun, "version");
        if (versao == null || versao.isBlank()) {
            erros.add("maven-antrun-plugin sem versao explicita");
        }

        List<Element> execucoes = XmlSeguro.filhos(XmlSeguro.filho(antrun, "executions"), "execution");
        if (execucoes.size() != 1) {
            erros.add("a sessao precisa de exatamente uma execucao, e tem " + execucoes.size());
            return erros;
        }
        Element execucao = execucoes.getFirst();
        if (!"initialize".equals(XmlSeguro.texto(execucao, "phase"))) {
            erros.add("execucao da sessao fora da fase initialize: " + XmlSeguro.texto(execucao, "phase"));
        }
        if (!"false".equals(XmlSeguro.texto(execucao, "inherited"))) {
            erros.add("execucao da sessao sem inherited=false: rodaria em cada modulo");
        }
        boolean roda = XmlSeguro.filhos(XmlSeguro.filho(execucao, "goals"), "goal").stream()
                .anyMatch(g -> "run".equals(g.getTextContent().trim()));
        if (!roda) {
            erros.add("execucao da sessao sem goal run");
        }

        Element alvo = XmlSeguro.caminho(execucao, "configuration", "target");
        erros.addAll(verificarTarget(alvo));
        return erros;
    }

    private static List<String> verificarTarget(Element alvo) {
        List<String> erros = new ArrayList<>();
        if (alvo == null) {
            erros.add("execucao da sessao sem target");
            return erros;
        }
        List<Element> remocoes = XmlSeguro.filhos(alvo, "delete");
        List<Element> ecos = XmlSeguro.filhos(alvo, "echo");
        if (remocoes.size() != 1) {
            erros.add("a sessao precisa de exatamente uma remocao, e tem " + remocoes.size());
        }
        if (ecos.size() != 1) {
            erros.add("a sessao precisa gravar exatamente um marcador, e grava " + ecos.size());
        }
        if (remocoes.size() == 1) {
            Element conjunto = XmlSeguro.filho(remocoes.getFirst(), "fileset");
            if (conjunto == null || !"${project.basedir}".equals(conjunto.getAttribute("dir"))) {
                erros.add("remocao fora do diretorio do workspace");
            }
            List<String> incluidos = XmlSeguro.filhos(conjunto, "include").stream()
                    .map(i -> i.getAttribute("name")).toList();
            for (String esperado : ALVOS) {
                if (!incluidos.contains(esperado)) {
                    erros.add("alvo de remocao faltando: " + esperado);
                }
            }
            for (String incluido : incluidos) {
                if (!ALVOS.contains(incluido)) {
                    erros.add("alvo de remocao fora do D4: " + incluido);
                }
            }
        }
        if (ecos.size() == 1) {
            Element eco = ecos.getFirst();
            if (!MARCADOR.equals(eco.getAttribute("file"))) {
                erros.add("marcador gravado fora de target/sessao-de-verificacao/sessao.txt");
            }
            if (!PROPRIEDADE.equals(eco.getAttribute("message"))) {
                erros.add("marcador nao grava a propriedade da sessao");
            }
        }
        if (remocoes.size() == 1 && ecos.size() == 1 && posicao(ecos.getFirst()) < posicao(remocoes.getFirst())) {
            erros.add("marcador gravado antes da remocao das evidencias");
        }
        return erros;
    }

    private static Element plugin(Element plugins) {
        return XmlSeguro.filhos(plugins, "plugin").stream()
                .filter(p -> "maven-antrun-plugin".equals(XmlSeguro.texto(p, "artifactId")))
                .findFirst().orElse(null);
    }

    private static int posicao(Element elemento) {
        int indice = 0;
        for (Node no = elemento.getParentNode().getFirstChild(); no != null; no = no.getNextSibling()) {
            if (no == elemento) {
                return indice;
            }
            indice++;
        }
        return -1;
    }
}
