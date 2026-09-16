package br.com.fiap.hospital.qualidade;

import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Parametros de configuracao do JUnit Platform que omitem a execucao ou toleram falha (D5),
 * conferidos no JUnit Jupiter 5.12.2 e no JUnit Platform 1.12.2.
 *
 * <p>A recusa e por chave oficial e pelo valor que muda a semantica, nunca por palavra contida no
 * nome: paralelismo, ciclo de vida, autodeteccao de extensoes, desativacao de listeners e demais
 * parametros legitimos sao aceitos. Selecao por tags, motores, nome ou pacote nao existe como
 * parametro nesses canais; e feita pelos filtros que o Surefire e o Failsafe montam, ja cobertos por
 * {@link PropriedadesDeFiltro} e {@link ConfiguracaoDosPlugins}.
 *
 * <p>Canais, na precedencia do guia: requisicao do Launcher ({@code configurationParameters}),
 * propriedades de sistema da JVM de teste ({@code systemPropertyVariables}, {@code systemProperties},
 * {@code -D} no {@code argLine}, {@code systemPropertiesFile}, linha de comando) e
 * {@code junit-platform.properties} na raiz do classpath de teste. Um canal declarado que nao possa ser
 * lido reprova com diagnostico, nunca com excecao. Nos canais do POM, um alias customizado em posicao
 * capaz de ocultar configuracao reprova mesmo com valor seguro no POM ({@link InterpolacaoMaven}).
 */
public final class ParametrosDoJUnitPlatform {

    /** Parametro oficial, o efeito que ele produz e o valor que o torna ativo. */
    public record Parametro(String chave, String efeito, Predicate<String> ativo) {}

    public static final List<Parametro> PARAMETROS = List.of(
            new Parametro("junit.platform.execution.dryRun.enabled", "nao executa nenhum teste (dry-run)",
                    valor -> Boolean.parseBoolean(valor.trim())),
            new Parametro("junit.platform.discovery.listener.default", "tolera falha de descoberta",
                    valor -> !"abortOnFailure".equals(valor.trim())));

    public static final List<String> CHAVES = PARAMETROS.stream().map(Parametro::chave).toList();

    /** Propriedades de linha de comando que alcancam a JVM de teste e sao repassadas a auditoria. */
    public static final List<String> CANAIS_DE_LINHA_DE_COMANDO = List.of("junit.platform.execution.dryRun.enabled",
            "junit.platform.discovery.listener.default", "argLine", "surefire.systemPropertiesFile",
            "failsafe.systemPropertiesFile");

    private ParametrosDoJUnitPlatform() {}

    /** Violacoes dos pares chave=valor, ja recebidos, de um canal. */
    public static List<String> verificar(String canal, Map<String, String> pares) {
        List<String> violacoes = new ArrayList<>();
        for (Parametro parametro : PARAMETROS) {
            String valor = pares.get(parametro.chave());
            if (valor != null && parametro.ativo().test(valor)) {
                violacoes.add(canal + ": parametro do JUnit Platform que " + parametro.efeito() + ": "
                        + parametro.chave() + "=" + valor.trim() + ": o gate global nao aceita omissao nem tolerancia");
            }
        }
        return violacoes;
    }

    /** Violacao de um alias customizado encontrado numa posicao capaz de ocultar configuracao de teste. */
    static String alias(String canal, String descricao) {
        return canal + ": alias " + descricao + " em posicao capaz de ocultar configuracao de teste: uma user property"
                + " da linha de comando o sobrescreve e a auditoria nao ve o valor recebido";
    }

    /**
     * Pares de um conteudo no formato de {@link Properties}.
     *
     * @throws IllegalArgumentException se o conteudo nao for um arquivo de propriedades valido, como um
     *     escape unicode malformado
     */
    static Map<String, String> propriedades(String conteudo) {
        Properties propriedades = new Properties();
        try {
            propriedades.load(new StringReader(conteudo));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Map<String, String> pares = new LinkedHashMap<>();
        propriedades.stringPropertyNames().stream().sorted().forEach(k -> pares.put(k, propriedades.getProperty(k)));
        return pares;
    }

    /** Violacoes de um conteudo no formato de propriedades; conteudo invalido reprova com diagnostico. */
    static List<String> verificarConteudo(String canal, String conteudo) {
        try {
            return verificar(canal, propriedades(conteudo));
        } catch (IllegalArgumentException e) {
            return List.of(canal + ": conteudo nao pode ser conferido, nao e um arquivo de propriedades valido: "
                    + e.getMessage());
        }
    }

    /**
     * Conteudo de propriedades escrito no POM, como o {@code configurationParameters}. Todo alias no
     * conteudo reprova: ele pode ser sobrescrito e trazer qualquer parametro. Quando o POM resolve tudo, o
     * valor que ele da tambem e analisado, para acusar um parametro inseguro ja escrito.
     */
    static List<String> verificarConteudoRecebido(String canal, String escrito, Map<String, String> escopo) {
        List<String> violacoes = new ArrayList<>();
        InterpolacaoMaven.aliases(escrito, escopo, Set.of()).forEach(descricao -> violacoes.add(alias(canal, descricao)));
        InterpolacaoMaven.Resultado noPom = InterpolacaoMaven.resolver(escrito, escopo);
        if (noPom.completo()) {
            violacoes.addAll(verificarConteudo(canal, noPom.valor()));
        }
        return violacoes;
    }

    /**
     * Pares escritos num canal de propriedades de sistema. Alias no nome reprova, porque o nome pode virar
     * um parametro relevante; alias no valor reprova quando o nome e de um parametro relevante.
     */
    static List<String> verificarParesRecebidos(String canal, Map<String, String> escritos, Map<String, String> escopo) {
        List<String> violacoes = new ArrayList<>();
        Map<String, String> noPom = new LinkedHashMap<>();
        escritos.forEach((nomeEscrito, valorEscrito) -> {
            List<String> aliasesNoNome = InterpolacaoMaven.aliases(nomeEscrito, escopo, Set.of());
            if (!aliasesNoNome.isEmpty()) {
                aliasesNoNome.forEach(descricao -> violacoes.add(alias(canal, descricao)));
                return;
            }
            String chave = nomeEscrito.trim();
            if (CHAVES.contains(chave)) {
                InterpolacaoMaven.aliases(valorEscrito, escopo, Set.of()).forEach(descricao -> violacoes.add(alias(canal, descricao)));
            }
            InterpolacaoMaven.Resultado valor = InterpolacaoMaven.resolver(valorEscrito, escopo);
            if (valor.completo()) {
                noPom.put(chave, valor.valor());
            }
        });
        violacoes.addAll(verificar(canal, noPom));
        return violacoes;
    }

    /**
     * {@code argLine} escrito no POM. Todo alias reprova, salvo {@code @{argLine}}/{@code ${argLine}}, cujo
     * valor efetivo e repassado a auditoria e analisado pela linha de comando, e as expressoes proprias do
     * modelo, que nao sao sobrescreviveis e sao substituidas pelos valores reais antes da analise dos
     * {@code -D} — um caminho de projeto que contenha {@code -D} reprova.
     *
     * @param proprias as expressoes proprias do modelo validas neste ponto, com os valores reais
     */
    static List<String> verificarArgLine(String canal, String argLine, Map<String, String> escopo, Map<String, String> proprias) {
        Set<String> permitidos = new HashSet<>(proprias.keySet());
        permitidos.add("argLine");
        List<String> violacoes = new ArrayList<>();
        InterpolacaoMaven.aliases(argLine, escopo, permitidos).forEach(descricao -> violacoes.add(alias(canal, descricao)));
        Map<String, String> semArgLine = new LinkedHashMap<>(escopo);
        semArgLine.remove("argLine");
        InterpolacaoMaven.Resultado recebido = InterpolacaoMaven.resolver(argLine, InterpolacaoMaven.sobrepor(semArgLine, proprias));
        violacoes.addAll(verificar(canal, propriedadesDoArgLine(recebido.valor())));
        return violacoes;
    }

    /** Propriedades de sistema declaradas por {@code -Dchave=valor} num argLine. */
    static Map<String, String> propriedadesDoArgLine(String argLine) {
        Map<String, String> pares = new LinkedHashMap<>();
        for (String token : argLine.trim().split("\\s+")) {
            String limpo = token.replace("\"", "").replace("'", "");
            if (limpo.startsWith("-D") && limpo.length() > 2) {
                int igual = limpo.indexOf('=');
                pares.put(igual < 0 ? limpo.substring(2) : limpo.substring(2, igual), igual < 0 ? "" : limpo.substring(igual + 1));
            }
        }
        return pares;
    }

    /**
     * Le um arquivo de propriedades — {@code systemPropertiesFile} ou {@code junit-platform.properties}.
     * Um arquivo declarado que nao possa ser lido reprova com diagnostico: inexistente, diretorio, falha
     * de leitura ou conteudo invalido.
     */
    static List<String> verificarArquivo(String canal, Path arquivo) {
        String nome = arquivo.getFileName() == null ? arquivo.toString() : arquivo.getFileName().toString();
        if (!Files.isRegularFile(arquivo)) {
            return List.of(canal + ": " + nome + " nao pode ser conferido, nao e um arquivo legivel: " + arquivo);
        }
        String conteudo;
        try {
            conteudo = Files.readString(arquivo, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return List.of(canal + ": " + nome + " nao pode ser conferido, falha de leitura: " + arquivo + ": " + e);
        }
        return verificarConteudo(canal + " (" + nome + ")", conteudo);
    }

    /**
     * Caminho de um arquivo declarado, relativo a base quando nao for absoluto.
     *
     * @return o caminho, ou nulo quando o texto nao e um caminho valido na plataforma
     */
    static Path caminho(Path base, String declarado) {
        try {
            Path arquivo = Path.of(declarado.trim());
            return arquivo.isAbsolute() ? arquivo : base.resolve(arquivo);
        } catch (InvalidPathException e) {
            return null;
        }
    }

    /**
     * Canais de linha de comando repassados como {@code nome=valor}; o literal {@code ${nome}} conta
     * como indefinido. O valor ja chega com a precedencia da linha de comando; um alias que ainda reste no
     * {@code argLine} seria resolvido so na execucao e reprova.
     */
    public static List<String> verificarLinhaDeComando(Map<String, String> recebidas, Path raiz) {
        List<String> violacoes = new ArrayList<>();
        Map<String, String> definidas = new LinkedHashMap<>();
        for (String canal : CANAIS_DE_LINHA_DE_COMANDO) {
            if (!recebidas.containsKey(canal)) {
                violacoes.add("propriedade " + canal + " nao repassada a auditoria: o canal nao pode ser conferido");
            } else if (!PropriedadesDeFiltro.indefinida(canal, recebidas.get(canal))) {
                definidas.put(canal, recebidas.get(canal));
            }
        }
        violacoes.addAll(verificar("linha de comando", definidas));
        if (definidas.containsKey("argLine")) {
            violacoes.addAll(verificarArgLine("linha de comando (argLine)", definidas.get("argLine"), Map.of(), Map.of()));
        }
        for (String arquivo : List.of("surefire.systemPropertiesFile", "failsafe.systemPropertiesFile")) {
            if (definidas.containsKey(arquivo)) {
                String canal = "linha de comando (" + arquivo + ")";
                Path caminho = caminho(raiz, definidas.get(arquivo));
                violacoes.addAll(caminho == null
                        ? List.of(canal + ": caminho invalido, o canal nao pode ser conferido: " + definidas.get(arquivo))
                        : verificarArquivo(canal, caminho));
            }
        }
        return violacoes;
    }
}
