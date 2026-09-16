package br.com.fiap.hospital.qualidade;

import br.com.fiap.hospital.qualidade.InventarioDeTestes.Plugin;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.w3c.dom.Element;

/**
 * Auditoria da execucao integral das suites (D5), distinta do gate de cobertura.
 *
 * <p>Protege contra omissao por evidencia por suite e por familia e pela recusa dos mecanismos
 * enumerados de selecao, exclusao, omissao e tolerancia — por parametro, por configuracao efetiva e
 * por declaracao no codigo de teste. Nao prova a execucao individual de cada metodo: a soma de casos
 * de uma familia nao prova metodos. Limite residual declarado: uma {@code @TestFactory} que emite
 * zero testes numa suite que tem outros casos nao e detectavel por familia; nenhum gate obrigatorio
 * do M10 usa {@code @TestFactory}.
 *
 * <p>A validade dos relatorios vem da sessao de verificacao (D4): limpeza previa e marcador igual ao
 * identificador recebido. Nenhuma decisao usa data de modificacao.
 */
public final class AuditoriaDeExecucao {

    private AuditoriaDeExecucao() {}

    public record Resultado(List<String> violacoes, List<String> tabela,
            List<InventarioDeTestes.Suite> suites, List<FamiliasDeRelatorios.Totais> totais) {

        public boolean aprovada() {
            return violacoes.isEmpty();
        }
    }

    public static void main(String[] args) {
        System.exit(executar(args, System.out));
    }

    /** @return 0 aprovada, 1 reprovada, 2 invocacao invalida */
    static int executar(String[] args, PrintStream saida) {
        if (args.length < 2) {
            saida.println("uso: AuditoriaDeExecucao <raiz do reactor> <sessao> [propriedade=valor ...]");
            return 2;
        }
        Map<String, String> propriedades = new LinkedHashMap<>();
        for (int i = 2; i < args.length; i++) {
            int igual = args[i].indexOf('=');
            if (igual <= 0) {
                saida.println("argumento invalido, esperado propriedade=valor: " + args[i]);
                return 2;
            }
            propriedades.put(args[i].substring(0, igual), args[i].substring(igual + 1));
        }
        Resultado resultado = auditar(Path.of(args[0]), args[1], propriedades);
        saida.println("Auditoria de execucao (evidencia por suite e familia; nao prova execucao por metodo)");
        resultado.tabela().forEach(saida::println);
        if (resultado.aprovada()) {
            saida.println("Auditoria de execucao: APROVADA");
            return 0;
        }
        saida.println("Auditoria de execucao: REPROVADA");
        resultado.violacoes().forEach(v -> saida.println("  - " + v));
        return 1;
    }

    public static Resultado auditar(Path raiz, String sessao, Map<String, String> propriedades) {
        List<String> violacoes = new ArrayList<>();
        SessaoDeVerificacao.conferir(raiz, sessao)
                .forEach(v -> violacoes.add("relatorios obsoletos, fora da sessao corrente: " + v));
        Map<String, String> doSurefire = new LinkedHashMap<>(propriedades);
        ParametrosDoJUnitPlatform.CANAIS_DE_LINHA_DE_COMANDO.forEach(doSurefire::remove);
        violacoes.addAll(PropriedadesDeFiltro.verificar(doSurefire));
        violacoes.addAll(ParametrosDoJUnitPlatform.verificarLinhaDeComando(propriedades, raiz));

        Element pomRaiz;
        Map<String, Element> poms = new LinkedHashMap<>();
        try {
            pomRaiz = XmlSeguro.ler(raiz.resolve("pom.xml")).getDocumentElement();
            for (String modulo : RegrasDoReactor.modulos(pomRaiz)) {
                poms.put(modulo, XmlSeguro.ler(raiz.resolve(modulo).resolve("pom.xml")).getDocumentElement());
            }
        } catch (XmlSeguro.XmlRecusado e) {
            violacoes.add("POM recusado: " + e.getMessage());
            return new Resultado(violacoes, List.of(), List.of(), List.of());
        }
        List<String> modulos = List.copyOf(poms.keySet());
        violacoes.addAll(ConfiguracaoDosPlugins.verificar(pomRaiz, poms, raiz));

        InventarioDeTestes.Resultado inventario = InventarioDeTestes.inventariar(raiz, modulos, sufixos(pomRaiz, violacoes));
        violacoes.addAll(inventario.violacoes());
        FamiliasDeRelatorios.Resultado familias = FamiliasDeRelatorios.verificar(raiz, modulos, inventario.suites());
        violacoes.addAll(familias.violacoes());

        List<String> tabela = new ArrayList<>();
        tabela.add(String.format("%-22s %8s %8s %11s %9s", "modulo", "surefire", "failsafe", "relatorios", "casos"));
        int surefire = 0;
        int failsafe = 0;
        int relatorios = 0;
        long casos = 0;
        for (FamiliasDeRelatorios.Totais totais : familias.totais()) {
            tabela.add(String.format("%-22s %8d %8d %11d %9d", totais.modulo(), totais.suitesSurefire(),
                    totais.suitesFailsafe(), totais.relatorios(), totais.casos()));
            surefire += totais.suitesSurefire();
            failsafe += totais.suitesFailsafe();
            relatorios += totais.relatorios();
            casos += totais.casos();
        }
        tabela.add(String.format("%-22s %8d %8d %11d %9d", "total", surefire, failsafe, relatorios, casos));
        return new Resultado(violacoes, tabela, inventario.suites(), familias.totais());
    }

    /**
     * Sufixos de nome derivados dos includes do POM raiz, na forma {@code **}{@code /*Sufixo.java}. Um
     * include em outra forma nao permite classificar suites e reprova; o normativo continua sendo
     * exigido por {@link ConfiguracaoDosPlugins}.
     */
    static Map<Plugin, String> sufixos(Element pomRaiz, List<String> violacoes) {
        Map<Plugin, String> sufixos = new EnumMap<>(Plugin.class);
        Map<Plugin, String> artefatos = Map.of(Plugin.SUREFIRE, ConfiguracaoDosPlugins.SUREFIRE,
                Plugin.FAILSAFE, ConfiguracaoDosPlugins.FAILSAFE);
        for (Plugin plugin : Plugin.values()) {
            sufixos.put(plugin, plugin.sufixo);
            Element plugins = XmlSeguro.caminho(pomRaiz, "build", "plugins");
            List<String> includes = XmlSeguro.filhos(plugins, "plugin").stream()
                    .filter(p -> artefatos.get(plugin).equals(XmlSeguro.texto(p, "artifactId")))
                    .flatMap(p -> XmlSeguro.filhos(XmlSeguro.caminho(p, "configuration", "includes"), "include").stream())
                    .map(e -> e.getTextContent().trim())
                    .toList();
            if (includes.size() != 1) {
                continue;
            }
            String include = includes.getFirst();
            if (include.startsWith("**/*") && include.endsWith(".java") && include.length() > "**/*.java".length()
                    && include.indexOf('*', 4) < 0) {
                sufixos.put(plugin, include.substring(4, include.length() - ".java".length()));
            } else {
                violacoes.add("include do " + artefatos.get(plugin) + " fora da forma **/*Sufixo.java: " + include
                        + ": as suites nao podem ser classificadas");
            }
        }
        return sufixos;
    }
}
