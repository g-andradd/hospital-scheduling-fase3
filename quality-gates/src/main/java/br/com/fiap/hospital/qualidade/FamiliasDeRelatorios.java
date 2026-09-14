package br.com.fiap.hospital.qualidade;

import br.com.fiap.hospital.qualidade.InventarioDeTestes.Plugin;
import br.com.fiap.hospital.qualidade.InventarioDeTestes.Suite;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.w3c.dom.Element;

/**
 * Familias de relatorios (D5): so {@code TEST-*.xml} participa, e cada um pertence a exatamente uma
 * familia do mesmo modulo e plugin.
 *
 * <p>A associacao e por igualdade do nome binario — {@code TEST-<nome>.xml} —, nunca por prefixo. O
 * {@code failsafe-summary.xml} e conferido a parte e nunca e orfao; os demais arquivos dos plugins
 * ({@code *.txt}, {@code *-output.txt}, {@code *.dumpstream}) sao ignorados.
 *
 * <p>A auditoria nao prova a execucao de cada metodo: prova presenca por suite e por aninhada
 * esperada, familia com total positivo e relatorios sem falha, erro ou caso pulado.
 */
public final class FamiliasDeRelatorios {

    public static final String PREFIXO = "TEST-";
    public static final String SUMARIO = "failsafe-summary.xml";

    /** Elementos de caso que registram falha, erro, pulo ou repeticao tolerada. */
    static final List<String> ELEMENTOS_DE_INSUCESSO = List.of("failure", "error", "skipped", "flakyFailure",
            "flakyError", "rerunFailure", "rerunError");

    private FamiliasDeRelatorios() {}

    /** Totais de um modulo, para a tabela da auditoria. */
    public record Totais(String modulo, int suitesSurefire, int suitesFailsafe, int relatorios, long casos) {}

    public record Resultado(List<String> violacoes, List<Totais> totais) {}

    private record Relatorio(Path arquivo, String nome, long casos, boolean valido) {}

    public static Resultado verificar(Path raiz, List<String> modulos, List<Suite> suites) {
        List<String> violacoes = new ArrayList<>();
        List<Totais> totais = new ArrayList<>();
        for (String modulo : modulos) {
            int relatorios = 0;
            long casos = 0;
            for (Plugin plugin : Plugin.values()) {
                List<Suite> doPlugin = suites.stream().filter(s -> s.modulo().equals(modulo) && s.plugin() == plugin).toList();
                Path diretorio = raiz.resolve(modulo).resolve("target").resolve(plugin.diretorio);
                if (plugin == Plugin.FAILSAFE) {
                    violacoes.addAll(sumario(modulo, diretorio, !doPlugin.isEmpty()));
                }
                if (!Files.isDirectory(diretorio)) {
                    if (!doPlugin.isEmpty()) {
                        violacoes.add("modulo " + modulo + " sem relatorios: " + doPlugin.size() + " suites " + plugin.name().toLowerCase()
                                + " e nenhum diretorio " + plugin.diretorio);
                    }
                    continue;
                }
                List<Relatorio> lidos = ler(diretorio, modulo, violacoes);
                relatorios += lidos.size();
                casos += lidos.stream().filter(Relatorio::valido).mapToLong(Relatorio::casos).sum();
                if (!doPlugin.isEmpty() && lidos.isEmpty()) {
                    violacoes.add("modulo " + modulo + " sem relatorios: " + doPlugin.size() + " suites " + plugin.name().toLowerCase()
                            + " e nenhum " + PREFIXO + "*.xml em " + plugin.diretorio);
                    continue;
                }
                violacoes.addAll(atribuir(modulo, plugin, doPlugin, lidos));
            }
            int surefire = (int) suites.stream().filter(s -> s.modulo().equals(modulo) && s.plugin() == Plugin.SUREFIRE).count();
            int failsafe = (int) suites.stream().filter(s -> s.modulo().equals(modulo) && s.plugin() == Plugin.FAILSAFE).count();
            totais.add(new Totais(modulo, surefire, failsafe, relatorios, casos));
        }
        return new Resultado(violacoes, totais);
    }

    private static List<Relatorio> ler(Path diretorio, String modulo, List<String> violacoes) {
        List<Path> arquivos;
        try (Stream<Path> conteudo = Files.list(diretorio)) {
            arquivos = conteudo.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().startsWith(PREFIXO) && p.getFileName().toString().endsWith(".xml"))
                    .sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        List<Relatorio> relatorios = new ArrayList<>();
        for (Path arquivo : arquivos) {
            String nomeDoArquivo = arquivo.getFileName().toString();
            String nome = nomeDoArquivo.substring(PREFIXO.length(), nomeDoArquivo.length() - ".xml".length());
            String descricao = modulo + "/" + diretorio.getFileName() + "/" + nomeDoArquivo;
            List<String> problemas = new ArrayList<>();
            long casos = validar(arquivo, nome, descricao, problemas);
            violacoes.addAll(problemas);
            relatorios.add(new Relatorio(arquivo, nome, Math.max(casos, 0), problemas.isEmpty()));
        }
        return relatorios;
    }

    /** @return os casos do relatorio, ou -1 se ele for ilegivel ou incoerente */
    private static long validar(Path arquivo, String nome, String descricao, List<String> problemas) {
        Element suite;
        try {
            suite = XmlSeguro.ler(arquivo).getDocumentElement();
        } catch (XmlSeguro.XmlRecusado e) {
            problemas.add("relatorio ilegivel: " + descricao + ": " + e.getMessage());
            return -1;
        }
        if (!"testsuite".equals(suite.getTagName())) {
            problemas.add("relatorio ilegivel: " + descricao + ": raiz e " + suite.getTagName() + ", e nao testsuite");
            return -1;
        }
        if (!nome.equals(suite.getAttribute("name"))) {
            problemas.add("relatorio incoerente: " + descricao + ": name=" + suite.getAttribute("name"));
            return -1;
        }
        Map<String, Long> atributos = new LinkedHashMap<>();
        for (String atributo : List.of("tests", "failures", "errors", "skipped")) {
            String valor = suite.getAttribute(atributo);
            if (valor.isEmpty() || !valor.chars().allMatch(Character::isDigit)) {
                problemas.add("relatorio ilegivel: " + descricao + ": atributo " + atributo + "=[" + valor + "]");
                return -1;
            }
            atributos.put(atributo, Long.parseLong(valor));
        }
        for (String atributo : List.of("failures", "errors", "skipped")) {
            if (atributos.get(atributo) > 0) {
                problemas.add("relatorio com " + atributo + "=" + atributos.get(atributo) + ": " + descricao);
            }
        }
        List<Element> casos = XmlSeguro.filhos(suite, "testcase");
        for (Element caso : casos) {
            for (String insucesso : ELEMENTOS_DE_INSUCESSO) {
                if (!XmlSeguro.filhos(caso, insucesso).isEmpty()) {
                    problemas.add("caso com <" + insucesso + ">: " + descricao + ": " + caso.getAttribute("classname")
                            + "#" + caso.getAttribute("name"));
                }
            }
        }
        if (casos.size() != atributos.get("tests")) {
            problemas.add("relatorio incoerente: " + descricao + ": tests=" + atributos.get("tests") + " e "
                    + casos.size() + " testcase");
            return -1;
        }
        return atributos.get("tests");
    }

    private static List<String> atribuir(String modulo, Plugin plugin, List<Suite> suites, List<Relatorio> relatorios) {
        List<String> violacoes = new ArrayList<>();
        String local = modulo + "/" + plugin.diretorio;
        Map<String, Relatorio> porNome = new LinkedHashMap<>();
        relatorios.forEach(r -> porNome.put(r.nome(), r));
        for (Relatorio relatorio : relatorios) {
            List<String> donos = suites.stream().filter(s -> s.familia().contains(relatorio.nome())).map(Suite::nome).toList();
            if (donos.isEmpty()) {
                violacoes.add("relatorio orfao: " + local + "/" + relatorio.arquivo().getFileName()
                        + ": nenhuma suite executavel tem essa familia");
            } else if (donos.size() > 1) {
                violacoes.add("relatorio ambiguo: " + local + "/" + relatorio.arquivo().getFileName()
                        + ": atribuivel as familias de " + donos);
            }
        }
        for (Suite suite : suites) {
            List<String> ausentes = suite.obrigatorios().stream().filter(n -> !porNome.containsKey(n)).toList();
            if (ausentes.size() == suite.obrigatorios().size()
                    && suite.familia().stream().noneMatch(porNome::containsKey)) {
                violacoes.add("suite omitida: " + modulo + ": " + suite.nome() + " sem nenhum relatorio da familia em "
                        + plugin.diretorio);
                continue;
            }
            for (String ausente : ausentes) {
                violacoes.add(ausente.equals(suite.nome())
                        ? "relatorio externo ausente: " + local + ": " + PREFIXO + ausente + ".xml da suite " + suite.nome()
                        : "classe aninhada sem relatorio: " + local + ": " + PREFIXO + ausente + ".xml na familia de " + suite.nome());
            }
            long total = suite.familia().stream().map(porNome::get).filter(r -> r != null && r.valido())
                    .mapToLong(Relatorio::casos).sum();
            boolean todosValidos = suite.familia().stream().map(porNome::get).allMatch(r -> r == null || r.valido());
            if (ausentes.isEmpty() && todosValidos && total == 0) {
                violacoes.add("familia sem casos executados: " + local + ": " + suite.nome() + " tem total zero");
            }
        }
        return violacoes;
    }

    /**
     * O resumo do Failsafe e conferido a parte: legivel, sem falha, erro ou pulado, com resultado nulo
     * e casos executados quando o modulo tem suites de integracao. Sem suites, o
     * Failsafe grava resultado 254 com zero casos, o que e aceito.
     */
    private static List<String> sumario(String modulo, Path diretorio, boolean temSuites) {
        List<String> violacoes = new ArrayList<>();
        Path arquivo = diretorio.resolve(SUMARIO);
        String descricao = modulo + "/" + InventarioDeTestes.Plugin.FAILSAFE.diretorio + "/" + SUMARIO;
        if (!Files.isRegularFile(arquivo)) {
            if (temSuites) {
                violacoes.add(SUMARIO + " ausente: " + descricao);
            }
            return violacoes;
        }
        Element sumario;
        try {
            sumario = XmlSeguro.ler(arquivo).getDocumentElement();
        } catch (XmlSeguro.XmlRecusado e) {
            violacoes.add(SUMARIO + " ilegivel: " + descricao + ": " + e.getMessage());
            return violacoes;
        }
        Map<String, Long> valores = new LinkedHashMap<>();
        for (String campo : List.of("completed", "errors", "failures", "skipped")) {
            String texto = XmlSeguro.texto(sumario, campo);
            if (!"failsafe-summary".equals(sumario.getTagName()) || texto == null || texto.isEmpty()
                    || !texto.chars().allMatch(Character::isDigit)) {
                violacoes.add(SUMARIO + " ilegivel: " + descricao + ": campo " + campo + "=[" + texto + "]");
                return violacoes;
            }
            valores.put(campo, Long.parseLong(texto));
        }
        String resultado = sumario.getAttribute("result");
        boolean falhou = valores.get("errors") + valores.get("failures") + valores.get("skipped") > 0
                || "true".equals(sumario.getAttribute("timeout"));
        boolean resultadoAceito = temSuites ? resultado.isEmpty() || "null".equals(resultado)
                : resultado.isEmpty() || "null".equals(resultado) || ("254".equals(resultado) && valores.get("completed") == 0);
        if (falhou || !resultadoAceito) {
            violacoes.add(SUMARIO + " registra resultado falho: " + descricao + ": result=" + resultado + ", completed="
                    + valores.get("completed") + ", errors=" + valores.get("errors") + ", failures=" + valores.get("failures")
                    + ", skipped=" + valores.get("skipped") + ", timeout=" + sumario.getAttribute("timeout"));
            return violacoes;
        }
        if (!temSuites && valores.get("completed") > 0) {
            violacoes.add(SUMARIO + " registra casos sem suite de integracao: " + descricao + ": completed=" + valores.get("completed"));
        }
        if (temSuites && valores.get("completed") == 0) {
            violacoes.add(SUMARIO + " sem casos executados: " + descricao);
        }
        return violacoes;
    }
}
