package br.com.fiap.hospital.qualidade;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.w3c.dom.Element;

/**
 * Regras estruturais do D3: o {@code quality-gates} agrega os cinco modulos de codigo e
 * permanece estritamente tecnico.
 *
 * <p>Os modulos internos nao sao uma lista fixa: saem do {@code groupId} do POM raiz e de
 * {@code <modules>}. Um sexto modulo de codigo acrescentado sem entrar na agregacao aparece
 * como dependencia interna faltando, e nao passa despercebido.
 */
public final class RegrasDoReactor {

    public static final String MODULO_TECNICO = "quality-gates";

    private static final String ESCOPO_PADRAO = "compile";
    private static final String TIPO_PADRAO = "jar";

    private RegrasDoReactor() {}

    /** Dependencia como declarada, com o groupId ja resolvido quando e o do proprio projeto. */
    public record Dependencia(String groupId, String artifactId, String escopo, String tipo) {

        String coordenada() {
            return groupId + ":" + artifactId;
        }
    }

    /** Os modulos de codigo: {@code <modules>} do POM raiz, menos o modulo tecnico. */
    public static List<String> modulosDeCodigo(Element raiz) {
        return modulos(raiz).stream().filter(m -> !MODULO_TECNICO.equals(m)).toList();
    }

    public static List<String> modulos(Element raiz) {
        return XmlSeguro.filhos(XmlSeguro.filho(raiz, "modules"), "module").stream()
                .map(m -> m.getTextContent().trim())
                .toList();
    }

    /**
     * @param raiz o elemento {@code project} do POM raiz
     * @param poms o elemento {@code project} de cada modulo, pelo nome em {@code <modules>}
     */
    public static List<String> verificar(Element raiz, Map<String, Element> poms) {
        List<String> violacoes = new ArrayList<>();
        String grupo = XmlSeguro.texto(raiz, "groupId");
        List<String> modulos = modulos(raiz);
        if (!modulos.contains(MODULO_TECNICO)) {
            violacoes.add(MODULO_TECNICO + " ausente de <modules>");
            return violacoes;
        }
        List<String> deCodigo = modulosDeCodigo(raiz);

        for (Dependencia gerenciada : dependencias(
                XmlSeguro.filho(raiz, "dependencyManagement"), grupo)) {
            if (grupo.equals(gerenciada.groupId()) && modulos.contains(gerenciada.artifactId())
                    && !ESCOPO_PADRAO.equals(gerenciada.escopo())) {
                violacoes.add("dependencyManagement da raiz fixa escopo " + gerenciada.escopo()
                        + " para o modulo interno " + gerenciada.artifactId());
            }
        }

        for (String modulo : deCodigo) {
            Element pom = poms.get(modulo);
            for (Dependencia dependencia : todasAsDependencias(pom, grupo)) {
                if (grupo.equals(dependencia.groupId())
                        && MODULO_TECNICO.equals(dependencia.artifactId())) {
                    violacoes.add("modulo de codigo " + modulo + " depende do " + MODULO_TECNICO);
                }
            }
        }

        Element tecnico = poms.get(MODULO_TECNICO);
        violacoes.addAll(verificarModuloTecnico(tecnico, grupo, modulos, deCodigo));
        return violacoes;
    }

    private static List<String> verificarModuloTecnico(
            Element pom, String grupo, List<String> modulos, List<String> deCodigo) {
        List<String> violacoes = new ArrayList<>();
        String packaging = XmlSeguro.texto(pom, "packaging");
        if (packaging != null && !TIPO_PADRAO.equals(packaging)) {
            violacoes.add(MODULO_TECNICO + " com packaging " + packaging + ": esperado jar");
        }
        for (Element perfil : XmlSeguro.filhos(XmlSeguro.filho(pom, "profiles"), "profile")) {
            if (!dependencias(perfil, grupo).isEmpty()) {
                violacoes.add(MODULO_TECNICO + " declara dependencias no perfil "
                        + XmlSeguro.texto(perfil, "id"));
            }
        }
        for (Element plugin : pluginsAtivos(pom)) {
            if ("spring-boot-maven-plugin".equals(XmlSeguro.texto(plugin, "artifactId"))) {
                violacoes.add(MODULO_TECNICO
                        + " declara spring-boot-maven-plugin: seria aplicacao executavel");
            }
        }

        Map<String, Integer> internas = new LinkedHashMap<>();
        for (Dependencia dependencia : dependencias(pom, grupo)) {
            if (!grupo.equals(dependencia.groupId())) {
                if (!"test".equals(dependencia.escopo())) {
                    violacoes.add("biblioteca externa " + dependencia.coordenada()
                            + " em escopo " + dependencia.escopo()
                            + ": fora de test, e o codigo principal usa so o JDK");
                }
                continue;
            }
            String artefato = dependencia.artifactId();
            if (MODULO_TECNICO.equals(artefato)) {
                violacoes.add(MODULO_TECNICO + " depende de si mesmo");
                continue;
            }
            if (!modulos.contains(artefato)) {
                violacoes.add("dependencia do groupId do projeto fora de <modules>: " + artefato);
                continue;
            }
            internas.merge(artefato, 1, Integer::sum);
            if (!ESCOPO_PADRAO.equals(dependencia.escopo())) {
                violacoes.add("dependencia interna " + artefato + " em escopo "
                        + dependencia.escopo() + ": o agregado exige compile");
            }
            if (!TIPO_PADRAO.equals(dependencia.tipo())) {
                violacoes.add("dependencia interna " + artefato + " com tipo "
                        + dependencia.tipo() + ": o agregado exige jar");
            }
        }
        internas.forEach((artefato, vezes) -> {
            if (vezes > 1) {
                violacoes.add("dependencia interna duplicada: " + artefato);
            }
        });
        for (String modulo : deCodigo) {
            if (!internas.containsKey(modulo)) {
                violacoes.add("dependencia interna faltando: " + modulo);
            }
        }
        return violacoes;
    }

    /** Dependencias diretas de {@code <dependencies>}, de um project, profile ou dependencyManagement. */
    public static List<Dependencia> dependencias(Element dono, String grupoDoProjeto) {
        List<Dependencia> encontradas = new ArrayList<>();
        for (Element dependencia : XmlSeguro.filhos(XmlSeguro.filho(dono, "dependencies"), "dependency")) {
            String grupo = XmlSeguro.texto(dependencia, "groupId");
            if ("${project.groupId}".equals(grupo) || "${project.parent.groupId}".equals(grupo)) {
                grupo = grupoDoProjeto;
            }
            String escopo = XmlSeguro.texto(dependencia, "scope");
            String tipo = XmlSeguro.texto(dependencia, "type");
            encontradas.add(new Dependencia(grupo, XmlSeguro.texto(dependencia, "artifactId"),
                    escopo == null || escopo.isEmpty() ? ESCOPO_PADRAO : escopo,
                    tipo == null || tipo.isEmpty() ? TIPO_PADRAO : tipo));
        }
        return encontradas;
    }

    private static List<Dependencia> todasAsDependencias(Element pom, String grupo) {
        List<Dependencia> todas = new ArrayList<>(dependencias(pom, grupo));
        todas.addAll(dependencias(XmlSeguro.filho(pom, "dependencyManagement"), grupo));
        for (Element perfil : XmlSeguro.filhos(XmlSeguro.filho(pom, "profiles"), "profile")) {
            todas.addAll(dependencias(perfil, grupo));
            todas.addAll(dependencias(XmlSeguro.filho(perfil, "dependencyManagement"), grupo));
        }
        return todas;
    }

    private static List<Element> pluginsAtivos(Element pom) {
        List<Element> plugins = new ArrayList<>(
                XmlSeguro.filhos(XmlSeguro.caminho(pom, "build", "plugins"), "plugin"));
        for (Element perfil : XmlSeguro.filhos(XmlSeguro.filho(pom, "profiles"), "profile")) {
            plugins.addAll(XmlSeguro.filhos(XmlSeguro.caminho(perfil, "build", "plugins"), "plugin"));
        }
        return plugins;
    }

    /**
     * Fontes principais do modulo tecnico: nenhuma aplicacao Spring e nenhum import do Spring.
     * A leitura e por linha aparada, para que uma constante ou um comentario que cite o nome
     * nao seja confundido com a anotacao ou com o import.
     */
    public static List<String> verificarFontes(Path fontesPrincipais) {
        List<String> violacoes = new ArrayList<>();
        try (Stream<Path> arquivos = Files.walk(fontesPrincipais)) {
            for (Path arquivo : arquivos.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                String relativo = fontesPrincipais.relativize(arquivo).toString().replace('\\', '/');
                for (String linha : Files.readAllLines(arquivo, StandardCharsets.UTF_8)) {
                    String aparada = linha.strip();
                    if (aparada.startsWith("@SpringBootApplication")) {
                        violacoes.add("fonte com @SpringBootApplication: " + relativo);
                    }
                    if (aparada.startsWith("import org.springframework.")
                            || aparada.startsWith("import static org.springframework.")) {
                        violacoes.add("fonte importa Spring: " + relativo);
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return violacoes;
    }
}
