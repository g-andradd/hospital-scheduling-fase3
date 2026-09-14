package br.com.fiap.hospital.qualidade;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Versao centralizada (D3): {@code ${revision}} aparece exatamente uma vez no POM raiz, na
 * versao do projeto, e exatamente uma vez em cada modulo, na versao do parent. Dependencias
 * entre modulos do reactor usam {@code ${project.version}}.
 *
 * <p>O {@code flatten-maven-plugin} em {@code resolveCiFriendliesOnly} resolve o placeholder na
 * versao do projeto e do parent, mas o deixava literal nas dependencias dos POMs gerados. A
 * contagem e textual, sobre o arquivo inteiro: uma ocorrencia em comentario ou propriedade
 * tambem reprova, para que o placeholder nao volte por nenhum caminho.
 *
 * <p>O POM publicado e resolvido pelo plugin central, em {@code build/plugins} da raiz:
 * {@code flattenMode=resolveCiFriendliesOnly} com {@code pomElements/dependencies=resolve}. Sem o
 * resolve, as dependencias internas sairiam com {@code ${project.version}} literal. Nenhum modulo
 * redeclara o plugin e nenhuma execucao sobrepoe a configuracao central.
 */
public final class RegrasDeVersao {

    public static final String REVISION = "${revision}";
    public static final String VERSAO_DO_PROJETO = "${project.version}";
    public static final String FLATTEN = "flatten-maven-plugin";
    public static final String MODO = "resolveCiFriendliesOnly";
    public static final String RESOLVE = "resolve";

    private RegrasDeVersao() {}

    /**
     * @param textoDaRaiz o POM raiz, como texto
     * @param textosDosModulos o POM de cada modulo, como texto, pelo nome em {@code <modules>}
     */
    public static List<String> verificar(String textoDaRaiz, Map<String, String> textosDosModulos) {
        List<String> violacoes = new ArrayList<>();
        Element raiz;
        try {
            raiz = XmlSeguro.lerTexto(textoDaRaiz).getDocumentElement();
        } catch (XmlSeguro.XmlRecusado e) {
            violacoes.add("raiz: POM recusado: " + e.getMessage());
            return violacoes;
        }
        String grupo = XmlSeguro.texto(raiz, "groupId");
        List<String> modulos = RegrasDoReactor.modulos(raiz);

        int naRaiz = ocorrencias(textoDaRaiz);
        if (naRaiz != 1) {
            violacoes.add("raiz: " + REVISION + " deve aparecer exatamente uma vez, e aparece " + naRaiz);
        }
        if (!REVISION.equals(XmlSeguro.texto(raiz, "version"))) {
            violacoes.add("raiz: versao do projeto nao e " + REVISION);
        }
        violacoes.addAll(dependencias("raiz", raiz, grupo, modulos));
        violacoes.addAll(flattenCentral(raiz));

        for (String modulo : modulos) {
            String texto = textosDosModulos.get(modulo);
            if (texto == null) {
                violacoes.add(modulo + ": POM ausente");
                continue;
            }
            Element pom;
            try {
                pom = XmlSeguro.lerTexto(texto).getDocumentElement();
            } catch (XmlSeguro.XmlRecusado e) {
                violacoes.add(modulo + ": POM recusado: " + e.getMessage());
                continue;
            }
            int noModulo = ocorrencias(texto);
            if (noModulo != 1) {
                violacoes.add(modulo + ": " + REVISION + " deve aparecer exatamente uma vez, no parent, e aparece " + noModulo);
            }
            if (!REVISION.equals(XmlSeguro.texto(XmlSeguro.filho(pom, "parent"), "version"))) {
                violacoes.add(modulo + ": versao do parent nao e " + REVISION);
            }
            violacoes.addAll(dependencias(modulo, pom, grupo, modulos));
            NodeList artefatos = pom.getElementsByTagName("artifactId");
            for (int i = 0; i < artefatos.getLength(); i++) {
                if (FLATTEN.equals(artefatos.item(i).getTextContent().trim())) {
                    violacoes.add(modulo + ": redeclara o " + FLATTEN + ": a configuracao do POM publicado e central");
                    break;
                }
            }
        }
        return violacoes;
    }

    /** Toda {@code <dependency>} do POM, em dependencies, dependencyManagement, perfis e plugins. */
    private static List<String> dependencias(String dono, Element pom, String grupo, List<String> modulos) {
        List<String> violacoes = new ArrayList<>();
        NodeList todas = pom.getElementsByTagName("dependency");
        for (int i = 0; i < todas.getLength(); i++) {
            Element dependencia = (Element) todas.item(i);
            String artefato = XmlSeguro.texto(dependencia, "artifactId");
            String versao = XmlSeguro.texto(dependencia, "version");
            if (versao != null && versao.contains(REVISION)) {
                violacoes.add(dono + ": dependencia " + artefato + " usa " + REVISION + ": use " + VERSAO_DO_PROJETO);
                continue;
            }
            String grupoDaDependencia = XmlSeguro.texto(dependencia, "groupId");
            boolean interna = (grupo.equals(grupoDaDependencia) || "${project.groupId}".equals(grupoDaDependencia))
                    && modulos.contains(artefato);
            if (interna && !VERSAO_DO_PROJETO.equals(versao)) {
                violacoes.add(dono + ": dependencia interna " + artefato + " com versao " + versao
                        + ": esperado " + VERSAO_DO_PROJETO);
            }
        }
        return violacoes;
    }

    private static List<String> flattenCentral(Element raiz) {
        List<String> violacoes = new ArrayList<>();
        List<Element> centrais = flattens(XmlSeguro.caminho(raiz, "build", "plugins"));
        if (!flattens(XmlSeguro.caminho(raiz, "build", "pluginManagement", "plugins")).isEmpty()) {
            violacoes.add("raiz: " + FLATTEN + " em pluginManagement: a configuracao central fica em build/plugins");
        }
        if (centrais.isEmpty()) {
            violacoes.add("raiz: " + FLATTEN + " ausente de build/plugins");
            return violacoes;
        }
        if (centrais.size() > 1) {
            violacoes.add("raiz: " + FLATTEN + " declarado " + centrais.size() + " vezes em build/plugins");
            return violacoes;
        }
        Element plugin = centrais.getFirst();
        Element configuracao = XmlSeguro.filho(plugin, "configuration");
        String modo = XmlSeguro.texto(configuracao, "flattenMode");
        if (!MODO.equals(modo)) {
            violacoes.add("raiz: flattenMode do " + FLATTEN + " nao e " + MODO + ": " + modo);
        }
        String dependencias = XmlSeguro.texto(XmlSeguro.filho(configuracao, "pomElements"), "dependencies");
        if (!RESOLVE.equals(dependencias)) {
            violacoes.add("raiz: pomElements/dependencies do " + FLATTEN + " nao e " + RESOLVE + ": " + dependencias);
        }
        for (Element execucao : XmlSeguro.filhos(XmlSeguro.filho(plugin, "executions"), "execution")) {
            Element propria = XmlSeguro.filho(execucao, "configuration");
            if (XmlSeguro.filho(propria, "flattenMode") != null || XmlSeguro.filho(propria, "pomElements") != null) {
                violacoes.add("raiz: execucao " + XmlSeguro.texto(execucao, "id")
                        + " sobrepoe a configuracao central do " + FLATTEN);
            }
        }
        return violacoes;
    }

    private static List<Element> flattens(Element plugins) {
        return XmlSeguro.filhos(plugins, "plugin").stream()
                .filter(p -> FLATTEN.equals(XmlSeguro.texto(p, "artifactId")))
                .toList();
    }

    private static int ocorrencias(String texto) {
        int total = 0;
        for (int i = texto.indexOf(REVISION); i >= 0; i = texto.indexOf(REVISION, i + 1)) {
            total++;
        }
        return total;
    }
}
