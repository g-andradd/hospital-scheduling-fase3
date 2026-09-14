package br.com.fiap.hospital.qualidade;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * Configuracao efetiva do Surefire e do Failsafe (D5): o que um POM de modulo ou um perfil declara
 * nao chega a linha de comando, entao a inspecao le todos os POMs.
 *
 * <p>Exige os includes normativos uma unica vez, no plugin de {@code build/plugins} da raiz, e
 * recusa em qualquer outro lugar — modulo, perfil, {@code pluginManagement}, execucao — includes
 * redefinidos, exclusoes e todo elemento de selecao, omissao ou tolerancia. Recusa tambem as
 * propriedades da tabela em {@code <properties>} de projeto ou perfil, e as quatro chaves internas do
 * provider JUnit Platform em {@code <properties>} do plugin.
 *
 * <p>Os parametros do JUnit Platform sao conferidos por {@link ParametrosDoJUnitPlatform} em todos os
 * canais do D5: {@code configurationParameters}, {@code systemPropertyVariables},
 * {@code systemProperties}, {@code argLine}, {@code systemPropertiesFile} e
 * {@code junit-platform.properties} na raiz do classpath de teste. Configuracao legitima e aceita.
 *
 * <p>A configuracao analisada e a recebida pelo plugin, nao a escrita. Como uma user property da linha de
 * comando prevalece sobre as propriedades do POM, todo alias customizado em posicao capaz de ocultar
 * configuracao de teste reprova, mesmo com valor seguro no POM ({@link InterpolacaoMaven}, estrategia A
 * do D5); as propriedades do escopo — raiz, modulo e perfil — so servem ao diagnostico.
 */
public final class ConfiguracaoDosPlugins {

    public static final String SUREFIRE = "maven-surefire-plugin";
    public static final String FAILSAFE = "maven-failsafe-plugin";
    public static final Map<String, String> INCLUDE_NORMATIVO = Map.of(SUREFIRE, "**/*Test.java", FAILSAFE, "**/*IT.java");

    /** Elementos de configuracao que selecionam, omitem ou toleram casos. */
    public static final List<String> ELEMENTOS_PROIBIDOS = List.of("excludes", "test", "includesFile", "excludesFile",
            "groups", "excludedGroups", "includeJUnit5Engines", "excludeJUnit5Engines", "suiteXmlFiles",
            "dependenciesToScan", "skip", "skipTests", "skipITs", "skipExec", "skipAfterFailureCount",
            "rerunFailingTestsCount", "testFailureIgnore", "failIfNoSpecifiedTests", "testClassesDirectory",
            "summaryFile");

    /**
     * Chaves internas que o provider JUnit Platform do Surefire 3.2.5 le do mapa do elemento
     * {@code <properties>} do plugin para montar os filtros de tag e de motor. Nomes exatos, conferidos no
     * bytecode: {@code AbstractSurefireMojo.convertGroupParameters} e {@code convertJunitEngineParameters}
     * gravam ali os elementos de primeiro nivel, e {@code JUnitPlatformProvider} le essas quatro chaves.
     * Declara-las direto em {@code <properties>} seleciona testes sem passar pelos elementos ja proibidos.
     */
    public static final List<String> CHAVES_DO_PROVIDER = List.of("groups", "excludegroups", "includejunit5engines",
            "excludejunit5engines");

    /** Propriedades de projeto que alcancam a JVM de teste como canal de parametros do JUnit Platform. */
    static final List<String> PROPRIEDADES_DE_CANAL = List.of("argLine", "surefire.systemPropertiesFile", "failsafe.systemPropertiesFile");

    private ConfiguracaoDosPlugins() {}

    /**
     * Escopo de interpolacao de um ponto da configuracao.
     *
     * @param propriedades as propriedades Maven do POM visiveis, do mais geral ao mais especifico — so para
     *     diagnostico, porque a linha de comando as sobrescreve
     * @param proprias as expressoes proprias do modelo validas neste ponto, com os valores reais do projeto;
     *     {@code project.build.directory} fica de fora quando algum POM do escopo redefine
     *     {@code <build><directory>}
     */
    record Escopo(Map<String, String> propriedades, Map<String, String> proprias) {

        static Escopo doProjeto(Path diretorio) {
            return new Escopo(Map.of(), proprias(diretorio));
        }

        private static Map<String, String> proprias(Path diretorio) {
            Map<String, String> proprias = new LinkedHashMap<>();
            proprias.put("project.basedir", diretorio.toString());
            proprias.put("basedir", diretorio.toString());
            proprias.put("project.build.directory", diretorio.resolve("target").toString());
            return proprias;
        }

        /** Escopo do ponto que acrescenta {@code <properties>} e pode redefinir {@code <build><directory>}. */
        Escopo com(Element propriedadesDoPonto, Element buildDoPonto) {
            Map<String, String> novasProprias = new LinkedHashMap<>(proprias);
            if (XmlSeguro.filho(buildDoPonto, "directory") != null) {
                novasProprias.remove("project.build.directory");
            }
            return new Escopo(InterpolacaoMaven.sobrepor(propriedades, InterpolacaoMaven.propriedades(propriedadesDoPonto)),
                    novasProprias);
        }

        /** O mesmo escopo herdado, com a base de outro projeto. */
        Escopo emProjeto(Path diretorio) {
            Map<String, String> novasProprias = proprias(diretorio);
            if (!proprias.containsKey("project.build.directory")) {
                novasProprias.remove("project.build.directory");
            }
            return new Escopo(propriedades, novasProprias);
        }
    }

    /**
     * @param raiz o elemento {@code project} do POM raiz
     * @param poms o elemento {@code project} de cada modulo, pelo nome em {@code <modules>}
     * @param raizDoReactor diretorio da raiz, para resolver arquivos e procurar {@code junit-platform.properties}
     */
    public static List<String> verificar(Element raiz, Map<String, Element> poms, Path raizDoReactor) {
        List<String> violacoes = new ArrayList<>();
        for (String plugin : List.of(SUREFIRE, FAILSAFE)) {
            List<Element> centrais = plugins(XmlSeguro.caminho(raiz, "build", "plugins"), plugin);
            if (centrais.size() != 1) {
                violacoes.add("raiz: " + plugin + " deve ser declarado uma vez em build/plugins, e aparece " + centrais.size());
            } else {
                List<String> includes = textos(XmlSeguro.caminho(centrais.getFirst(), "configuration", "includes"), "include");
                if (!includes.equals(List.of(INCLUDE_NORMATIVO.get(plugin)))) {
                    violacoes.add("raiz: includes do " + plugin + " devem ser exatamente " + INCLUDE_NORMATIVO.get(plugin)
                            + ", e sao " + includes);
                }
            }
        }
        Escopo daRaiz = Escopo.doProjeto(raizDoReactor).com(XmlSeguro.filho(raiz, "properties"), XmlSeguro.filho(raiz, "build"));
        violacoes.addAll(projeto("raiz", raiz, true, raizDoReactor, daRaiz));
        poms.forEach((modulo, pom) -> {
            Path diretorio = raizDoReactor.resolve(modulo);
            Escopo doModulo = daRaiz.emProjeto(diretorio).com(XmlSeguro.filho(pom, "properties"), XmlSeguro.filho(pom, "build"));
            violacoes.addAll(projeto(modulo, pom, false, diretorio, doModulo));
        });
        for (String modulo : poms.keySet()) {
            for (String recursos : List.of("test", "main")) {
                Path arquivo = raizDoReactor.resolve(modulo).resolve("src").resolve(recursos).resolve("resources")
                        .resolve("junit-platform.properties");
                if (Files.exists(arquivo)) {
                    violacoes.addAll(ParametrosDoJUnitPlatform.verificarArquivo(
                            modulo + "/src/" + recursos + "/resources/junit-platform.properties", arquivo));
                }
            }
        }
        return violacoes;
    }

    private static List<String> projeto(String dono, Element pom, boolean raiz, Path diretorio, Escopo escopo) {
        List<String> violacoes = new ArrayList<>();
        violacoes.addAll(propriedades(dono, XmlSeguro.filho(pom, "properties"), diretorio, escopo));
        violacoes.addAll(build(dono, XmlSeguro.filho(pom, "build"), raiz, diretorio, escopo));
        for (Element perfil : XmlSeguro.filhos(XmlSeguro.filho(pom, "profiles"), "profile")) {
            String nome = dono + " (perfil " + XmlSeguro.texto(perfil, "id") + ")";
            Escopo doPerfil = escopo.com(XmlSeguro.filho(perfil, "properties"), XmlSeguro.filho(perfil, "build"));
            violacoes.addAll(propriedades(nome, XmlSeguro.filho(perfil, "properties"), diretorio, doPerfil));
            violacoes.addAll(build(nome, XmlSeguro.filho(perfil, "build"), false, diretorio, doPerfil));
        }
        return violacoes;
    }

    private static List<String> build(String dono, Element build, boolean raiz, Path diretorio, Escopo escopo) {
        List<String> violacoes = new ArrayList<>();
        for (String plugin : List.of(SUREFIRE, FAILSAFE)) {
            for (Element declarado : plugins(XmlSeguro.caminho(build, "plugins"), plugin)) {
                violacoes.addAll(plugin(dono, plugin, declarado, raiz, diretorio, escopo));
            }
            for (Element gerenciado : plugins(XmlSeguro.caminho(build, "pluginManagement", "plugins"), plugin)) {
                violacoes.addAll(plugin(dono + " (pluginManagement)", plugin, gerenciado, false, diretorio, escopo));
            }
        }
        return violacoes;
    }

    /** {@code pontoNormativo}: plugin de build/plugins da raiz, o unico onde os includes sao admitidos. */
    private static List<String> plugin(String dono, String nome, Element plugin, boolean pontoNormativo, Path diretorio,
            Escopo escopo) {
        List<String> violacoes = new ArrayList<>();
        violacoes.addAll(configuracao(dono, nome, XmlSeguro.filho(plugin, "configuration"), pontoNormativo, diretorio, escopo));
        for (Element execucao : XmlSeguro.filhos(XmlSeguro.filho(plugin, "executions"), "execution")) {
            violacoes.addAll(configuracao(dono + " (execucao " + XmlSeguro.texto(execucao, "id") + ")", nome,
                    XmlSeguro.filho(execucao, "configuration"), false, diretorio, escopo));
        }
        return violacoes;
    }

    private static List<String> configuracao(String dono, String plugin, Element configuracao, boolean pontoNormativo,
            Path diretorio, Escopo escopo) {
        List<String> violacoes = new ArrayList<>();
        if (configuracao == null) {
            return violacoes;
        }
        if (!pontoNormativo && XmlSeguro.filho(configuracao, "includes") != null) {
            violacoes.add(dono + ": " + plugin + " redefine includes: " + textos(XmlSeguro.filho(configuracao, "includes"), "include"));
        }
        for (String proibido : ELEMENTOS_PROIBIDOS) {
            if (XmlSeguro.filho(configuracao, proibido) != null) {
                violacoes.add(dono + ": " + plugin + " declara <" + proibido + ">: o gate global nao aceita filtro");
            }
        }
        String local = dono + ": " + plugin;
        for (Element propriedades : XmlSeguro.filhos(configuracao, "properties")) {
            Map<String, String> pares = new LinkedHashMap<>();
            comoProperties(propriedades).forEach((nomeEscrito, valor) -> {
                List<String> aliasesNoNome = InterpolacaoMaven.aliases(nomeEscrito, escopo.propriedades(), Set.of());
                if (aliasesNoNome.isEmpty()) {
                    pares.put(nomeEscrito.trim(), valor);
                } else {
                    aliasesNoNome.forEach(descricao -> violacoes.add(
                            ParametrosDoJUnitPlatform.alias(local + " <properties> (nome de propriedade)", descricao)));
                }
            });
            for (String chave : CHAVES_DO_PROVIDER) {
                if (pares.containsKey(chave)) {
                    violacoes.add(local + " declara <properties> " + chave + "=" + pares.get(chave).trim()
                            + ": chave lida pelo provider JUnit Platform do Surefire 3.2.5 para selecionar "
                            + (chave.endsWith("groups") ? "tags" : "motores") + ": o gate global nao aceita filtro");
                }
            }
            if (pares.containsKey("configurationParameters")) {
                violacoes.addAll(ParametrosDoJUnitPlatform.verificarConteudoRecebido(local + " <configurationParameters>",
                        pares.get("configurationParameters"), escopo.propriedades()));
            }
        }
        for (Element variaveis : XmlSeguro.filhos(configuracao, "systemPropertyVariables")) {
            violacoes.addAll(ParametrosDoJUnitPlatform.verificarParesRecebidos(local + " <systemPropertyVariables>",
                    elementosComoPares(variaveis), escopo.propriedades()));
        }
        for (Element sistema : XmlSeguro.filhos(configuracao, "systemProperties")) {
            violacoes.addAll(ParametrosDoJUnitPlatform.verificarParesRecebidos(local + " <systemProperties>",
                    comoProperties(sistema), escopo.propriedades()));
        }
        String argLine = XmlSeguro.texto(configuracao, "argLine");
        if (argLine != null) {
            violacoes.addAll(ParametrosDoJUnitPlatform.verificarArgLine(local + " <argLine>", argLine,
                    escopo.propriedades(), escopo.proprias()));
        }
        String arquivo = XmlSeguro.texto(configuracao, "systemPropertiesFile");
        if (arquivo != null) {
            violacoes.addAll(arquivo(local + " <systemPropertiesFile>", diretorio, arquivo, escopo));
        }
        return violacoes;
    }

    private static List<String> propriedades(String dono, Element propriedades, Path diretorio, Escopo escopo) {
        List<String> violacoes = new ArrayList<>();
        List<String> tabela = PropriedadesDeFiltro.todas();
        for (Node no = propriedades == null ? null : propriedades.getFirstChild(); no != null; no = no.getNextSibling()) {
            if (!(no instanceof Element propriedade)) {
                continue;
            }
            String nome = propriedade.getTagName();
            String valor = propriedade.getTextContent().trim();
            if (tabela.contains(nome)) {
                violacoes.add(dono + ": <properties> define " + nome + " (" + PropriedadesDeFiltro.classeDe(nome)
                        + "): o gate global nao aceita filtro");
            } else if (nome.equals("argLine")) {
                violacoes.addAll(ParametrosDoJUnitPlatform.verificarArgLine(dono + ": <properties> argLine", valor,
                        escopo.propriedades(), escopo.proprias()));
            } else if (PROPRIEDADES_DE_CANAL.contains(nome)) {
                violacoes.addAll(arquivo(dono + ": <properties> " + nome, diretorio, valor, escopo));
            }
        }
        return violacoes;
    }

    /**
     * Pares de um parametro {@code java.util.Properties} de plugin, como o conversor Plexus do Maven 3.9.3
     * os monta (conferido no bytecode de {@code PropertiesConverter}): um filho {@code <property>} vale
     * pelos filhos {@code <name>} e {@code <value>}; qualquer outro filho vale pelo proprio nome, com o
     * texto como valor.
     */
    static Map<String, String> comoProperties(Element pai) {
        Map<String, String> pares = new LinkedHashMap<>();
        for (Node no = pai.getFirstChild(); no != null; no = no.getNextSibling()) {
            if (!(no instanceof Element filho)) {
                continue;
            }
            if (filho.getTagName().equals("property")) {
                Element nome = XmlSeguro.filho(filho, "name");
                Element valor = XmlSeguro.filho(filho, "value");
                if (nome != null) {
                    pares.put(nome.getTextContent().trim(), valor == null ? "" : valor.getTextContent());
                }
            } else {
                pares.put(filho.getTagName(), filho.getTextContent());
            }
        }
        return pares;
    }

    private static Map<String, String> elementosComoPares(Element pai) {
        Map<String, String> pares = new LinkedHashMap<>();
        for (Node no = pai.getFirstChild(); no != null; no = no.getNextSibling()) {
            if (no instanceof Element filho) {
                pares.put(filho.getTagName(), filho.getTextContent());
            }
        }
        return pares;
    }

    /**
     * Confere um arquivo declarado. Alias no caminho reprova, porque a linha de comando o sobrescreve e
     * aponta outro arquivo; as expressoes proprias do modelo sao substituidas pelos valores reais.
     */
    private static List<String> arquivo(String canal, Path diretorio, String declarado, Escopo escopo) {
        List<String> aliases = InterpolacaoMaven.aliases(declarado, escopo.propriedades(), escopo.proprias().keySet());
        if (!aliases.isEmpty()) {
            return aliases.stream().map(descricao -> ParametrosDoJUnitPlatform.alias(canal, descricao)).toList();
        }
        String recebido = InterpolacaoMaven.resolver(declarado, escopo.proprias()).valor();
        Path caminho = ParametrosDoJUnitPlatform.caminho(diretorio, recebido);
        return caminho == null
                ? List.of(canal + ": caminho invalido, o canal nao pode ser conferido: " + declarado)
                : ParametrosDoJUnitPlatform.verificarArquivo(canal, caminho);
    }

    private static List<Element> plugins(Element plugins, String artifactId) {
        return XmlSeguro.filhos(plugins, "plugin").stream()
                .filter(p -> artifactId.equals(XmlSeguro.texto(p, "artifactId")))
                .toList();
    }

    private static List<String> textos(Element lista, String nome) {
        return XmlSeguro.filhos(lista, nome).stream().map(e -> e.getTextContent().trim()).toList();
    }
}
