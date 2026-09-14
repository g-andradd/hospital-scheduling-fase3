package br.com.fiap.hospital.qualidade;

import java.io.IOException;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * Gate de cobertura do D4, so com o JDK.
 *
 * <p>O contador normativo e LINE. Os percentuais saem da soma de linhas cobertas e perdidas
 * de cada escopo, nunca da media de percentuais, e a comparacao e inteira:
 * {@code cobertas x 100 >= piso x (cobertas + perdidas)}. Os contadores sao lidos como
 * {@link BigInteger}: nenhuma soma ou produto transborda.
 *
 * <p>A evidencia vale por pertencer a sessao, e nao pelo relogio: a sessao de verificacao
 * apagou toda evidencia anterior e gravou o marcador, e aqui o marcador precisa ser
 * textualmente igual ao identificador recebido. Nenhuma decisao usa data de modificacao.
 *
 * <p>Todo package dos cinco groups e validado antes de qualquer escopo, e a mesma contagem
 * validada alimenta a reconciliacao package -> group -> report e os escopos. Pacote sem linhas
 * executaveis conta 0/0 somente no formato que o JaCoCo escreve para ele (opcao A+ do D4).
 *
 * <p>Toda falta ou ambiguidade reprova: relatorio ausente, vazio ou ilegivel; estrutura,
 * group, pacote ou contador ausente ou duplicado; pacote com evidencia executavel e sem LINE;
 * soma dos packages divergente do group ou dos groups divergente do report; total zero.
 * Quando a estrutura e recusada, nenhum percentual e avaliado. A garantia e sobre o que se
 * observa: ausencia acidental de LINE, relatorio sem informacao de linha e inconsistencia entre
 * niveis. Um relatorio reescrito com contadores coerentes entre si nao e distinguivel.
 */
public final class VerificadorDeCobertura {

    public static final int PISO_GLOBAL = 85;
    public static final int PISO_DOMAIN = 90;
    public static final int PISO_APPLICATION = 90;
    public static final String PREFIXO_DOMAIN = "br/com/fiap/hospital/agendamento/domain";
    public static final String PREFIXO_APPLICATION = "br/com/fiap/hospital/agendamento/application";
    public static final Path MARCADOR = SessaoDeVerificacao.MARCADOR;

    private static final String LINE = "LINE";
    private static final BigInteger CEM = BigInteger.valueOf(100);

    private VerificadorDeCobertura() {}

    /** Linhas cobertas e perdidas de um escopo. */
    public record Contagem(BigInteger cobertas, BigInteger perdidas) {

        static final Contagem ZERO = new Contagem(BigInteger.ZERO, BigInteger.ZERO);

        Contagem mais(Contagem outra) {
            return new Contagem(cobertas.add(outra.cobertas), perdidas.add(outra.perdidas));
        }

        BigInteger total() {
            return cobertas.add(perdidas);
        }

        boolean atinge(int piso) {
            return cobertas.multiply(CEM).compareTo(BigInteger.valueOf(piso).multiply(total())) >= 0;
        }

        /** Truncado, nunca arredondado para cima: o texto nao pode sugerir um piso que nao foi atingido. */
        String percentual() {
            if (total().signum() == 0) {
                return "-";
            }
            return new BigDecimal(cobertas.multiply(CEM))
                    .divide(new BigDecimal(total()), 2, RoundingMode.DOWN).toPlainString() + "%";
        }
    }

    public record Resultado(List<String> violacoes, List<String> tabela) {

        public boolean aprovado() {
            return violacoes.isEmpty();
        }
    }

    public static void main(String[] args) {
        System.exit(executar(args, System.out));
    }

    /** @return 0 aprovado, 1 reprovado, 2 invocacao invalida */
    static int executar(String[] args, PrintStream saida) {
        if (args.length != 3) {
            saida.println("uso: VerificadorDeCobertura <jacoco.xml agregado> <raiz do reactor> <sessao>");
            return 2;
        }
        Resultado resultado = verificar(Path.of(args[0]), Path.of(args[1]), args[2]);
        saida.println("Gate de cobertura (contador normativo: LINE)");
        resultado.tabela().forEach(saida::println);
        if (resultado.aprovado()) {
            saida.println("Gate de cobertura: APROVADO");
            return 0;
        }
        saida.println("Gate de cobertura: REPROVADO");
        resultado.violacoes().forEach(v -> saida.println("  - " + v));
        return 1;
    }

    public static Resultado verificar(Path relatorio, Path raiz, String sessao) {
        List<String> violacoes = new ArrayList<>();
        violacoes.addAll(SessaoDeVerificacao.conferir(raiz, sessao));

        List<String> modulos;
        try {
            modulos = RegrasDoReactor.modulosDeCodigo(XmlSeguro.ler(raiz.resolve("pom.xml")).getDocumentElement());
        } catch (XmlSeguro.XmlRecusado e) {
            violacoes.add("POM raiz recusado: " + e.getMessage());
            return new Resultado(violacoes, List.of());
        }
        if (modulos.isEmpty()) {
            violacoes.add("POM raiz sem modulos de codigo");
            return new Resultado(violacoes, List.of());
        }
        for (String modulo : modulos) {
            violacoes.addAll(verificarDadosDeExecucao(raiz, modulo));
        }

        Path indice = relatorio.resolveSibling("index.html");
        if (!naoVazio(indice)) {
            violacoes.add("index.html do relatorio agregado ausente ou vazio: " + indice);
        }

        Document documento;
        try {
            documento = XmlSeguro.ler(relatorio);
        } catch (XmlSeguro.XmlRecusado e) {
            violacoes.add("relatorio de cobertura recusado: " + e.getMessage());
            return new Resultado(violacoes, List.of());
        }

        List<String> estrutura = new ArrayList<>();
        Map<String, Element> grupos = grupos(documento.getDocumentElement(), modulos, estrutura);
        if (!estrutura.isEmpty()) {
            violacoes.addAll(estrutura);
            return new Resultado(violacoes, List.of());
        }
        Element report = documento.getDocumentElement();

        // Um unico caminho: todo package dos cinco groups e validado aqui, e so estas contagens
        // alimentam a reconciliacao package -> group e os escopos domain e application.
        List<PacoteValidado> pacotes = new ArrayList<>();
        Contagem somaDosGrupos = Contagem.ZERO;
        for (Map.Entry<String, Element> grupo : grupos.entrySet()) {
            String nomeDoGrupo = grupo.getKey();
            Contagem doGrupo = linhas(grupo.getValue(), "group " + nomeDoGrupo, estrutura);
            List<PacoteValidado> doGrupoValidados = validarPacotes(nomeDoGrupo, grupo.getValue(), estrutura);
            pacotes.addAll(doGrupoValidados);
            if (doGrupo == null) {
                continue;
            }
            somaDosGrupos = somaDosGrupos.mais(doGrupo);
            Contagem somaDosPacotes = doGrupoValidados.stream()
                    .map(PacoteValidado::linhas).reduce(Contagem.ZERO, Contagem::mais);
            if (estrutura.isEmpty() && !somaDosPacotes.equals(doGrupo)) {
                estrutura.add("soma LINE dos packages do group " + nomeDoGrupo + " ("
                        + descrever(somaDosPacotes) + ") diverge do LINE do group (" + descrever(doGrupo) + ")");
            }
        }
        Contagem total = linhas(report, "report", estrutura);
        Contagem domain = escopo(pacotes, PREFIXO_DOMAIN, estrutura);
        Contagem application = escopo(pacotes, PREFIXO_APPLICATION, estrutura);
        if (!estrutura.isEmpty()) {
            violacoes.addAll(estrutura);
            return new Resultado(violacoes, List.of());
        }

        if (!somaDosGrupos.equals(total)) {
            violacoes.add("soma LINE dos groups (" + descrever(somaDosGrupos)
                    + ") diverge do total do report (" + descrever(total) + ")");
            return new Resultado(violacoes, List.of());
        }
        if (total.total().signum() == 0) {
            violacoes.add("total LINE do report e zero: relatorio sem dados");
            return new Resultado(violacoes, List.of());
        }
        for (Map.Entry<String, Contagem> subarvore : List.of(
                Map.entry(PREFIXO_DOMAIN, domain), Map.entry(PREFIXO_APPLICATION, application))) {
            if (subarvore.getValue().total().signum() == 0) {
                violacoes.add("escopo " + subarvore.getKey() + " sem linhas");
            }
        }
        if (!violacoes.isEmpty()) {
            return new Resultado(violacoes, List.of());
        }

        List<String> tabela = new ArrayList<>();
        tabela.add(String.format("%-13s %12s %12s %10s %6s  %s", "escopo", "cobertas", "perdidas", "percentual", "piso", "situacao"));
        avaliar("global", total, PISO_GLOBAL, tabela, violacoes);
        avaliar("domain", domain, PISO_DOMAIN, tabela, violacoes);
        avaliar("application", application, PISO_APPLICATION, tabela, violacoes);
        for (String informativo : List.of("INSTRUCTION", "BRANCH")) {
            Contagem contagem = contador(report, informativo);
            if (contagem != null) {
                tabela.add(String.format("%-13s %12s %12s %10s %6s  %s", "global " + informativo,
                        contagem.cobertas(), contagem.perdidas(), contagem.percentual(), "-", "informativo"));
            }
        }
        return new Resultado(violacoes, tabela);
    }

    private static List<String> verificarDadosDeExecucao(Path raiz, String modulo) {
        Path exec = raiz.resolve(modulo).resolve("target").resolve("jacoco.exec");
        if (!Files.isRegularFile(exec)) {
            return List.of("jacoco.exec ausente em " + modulo);
        }
        return naoVazio(exec) ? List.of() : List.of("jacoco.exec vazio em " + modulo);
    }

    private static boolean naoVazio(Path arquivo) {
        try {
            return Files.isRegularFile(arquivo) && Files.size(arquivo) > 0;
        } catch (IOException e) {
            return false;
        }
    }

    private static Map<String, Element> grupos(Element report, List<String> modulos, List<String> estrutura) {
        Map<String, Element> grupos = new LinkedHashMap<>();
        if (!"report".equals(report.getTagName())) {
            estrutura.add("relatorio sem elemento report: raiz e " + report.getTagName());
            return grupos;
        }
        List<Element> encontrados = XmlSeguro.filhos(report, "group");
        if (encontrados.isEmpty()) {
            estrutura.add("relatorio sem groups: o agregado precisa de um por modulo de codigo");
            return grupos;
        }
        for (Element grupo : encontrados) {
            String nome = grupo.getAttribute("name");
            if (grupos.containsKey(nome)) {
                estrutura.add("group duplicado: " + nome);
            } else if (!modulos.contains(nome)) {
                estrutura.add("group inesperado: " + nome);
            }
            grupos.putIfAbsent(nome, grupo);
        }
        for (String modulo : modulos) {
            if (!grupos.containsKey(modulo)) {
                estrutura.add("group ausente: " + modulo);
            }
        }
        return grupos;
    }

    /** O contador LINE direto do elemento; ausente, duplicado ou ilegivel vira violacao. */
    private static Contagem linhas(Element dono, String descricao, List<String> estrutura) {
        List<Element> contadores = XmlSeguro.filhos(dono, "counter").stream()
                .filter(c -> LINE.equals(c.getAttribute("type"))).toList();
        if (contadores.isEmpty()) {
            estrutura.add("contador LINE ausente no " + descricao);
            return null;
        }
        if (contadores.size() > 1) {
            estrutura.add("contador LINE duplicado no " + descricao);
            return null;
        }
        Contagem contagem = ler(contadores.getFirst());
        if (contagem == null) {
            estrutura.add("contador LINE ilegivel no " + descricao);
        }
        return contagem;
    }

    private static Contagem contador(Element dono, String tipo) {
        List<Element> contadores = XmlSeguro.filhos(dono, "counter").stream()
                .filter(c -> tipo.equals(c.getAttribute("type"))).toList();
        return contadores.size() == 1 ? ler(contadores.getFirst()) : null;
    }

    private static Contagem ler(Element contador) {
        BigInteger perdidas = naoNegativo(contador.getAttribute("missed"));
        BigInteger cobertas = naoNegativo(contador.getAttribute("covered"));
        return perdidas == null || cobertas == null ? null : new Contagem(cobertas, perdidas);
    }

    private static BigInteger naoNegativo(String valor) {
        if (valor == null || valor.isEmpty() || !valor.chars().allMatch(c -> c >= '0' && c <= '9')) {
            return null;
        }
        return new BigInteger(valor);
    }

    /** Um package ja validado, com a contagem LINE que ele contribui. */
    record PacoteValidado(String grupo, String nome, Contagem linhas) {}

    /**
     * Valida todos os packages de um group. So os validos voltam; cada recusa vira violacao.
     *
     * <p>O JaCoCo nao escreve contador cujo total e zero: um pacote so de interfaces sai com
     * {@code <class>} e {@code <sourcefile>} vazios e nenhum {@code <counter>}. So esse formato
     * conta 0/0. Qualquer contador, metodo ou linha na subarvore prova codigo executavel, e
     * entao a falta do LINE direto reprova.
     */
    private static List<PacoteValidado> validarPacotes(String grupo, Element elemento, List<String> estrutura) {
        List<PacoteValidado> validados = new ArrayList<>();
        List<Element> pacotes = XmlSeguro.filhos(elemento, "package");
        if (pacotes.isEmpty()) {
            estrutura.add("group " + grupo + " sem pacotes");
            return validados;
        }
        List<String> nomes = new ArrayList<>();
        for (Element pacote : pacotes) {
            String nome = pacote.getAttribute("name");
            if (nome.isEmpty()) {
                estrutura.add("pacote sem nome no group " + grupo);
                continue;
            }
            if (nomes.contains(nome)) {
                estrutura.add("pacote duplicado no group " + grupo + ": " + nome);
                continue;
            }
            nomes.add(nome);
            String descricao = "pacote " + nome;
            if (XmlSeguro.filhos(pacote, "class").isEmpty() || XmlSeguro.filhos(pacote, "sourcefile").isEmpty()) {
                estrutura.add(descricao + " vazio ou ambiguo: sem class ou sem sourcefile");
                continue;
            }
            List<Element> diretos = XmlSeguro.filhos(pacote, "counter").stream()
                    .filter(c -> LINE.equals(c.getAttribute("type"))).toList();
            if (diretos.isEmpty()) {
                if (temEvidenciaExecutavel(pacote)) {
                    estrutura.add("contador LINE ausente no " + descricao + ", que tem evidencia executavel");
                } else {
                    validados.add(new PacoteValidado(grupo, nome, Contagem.ZERO));
                }
                continue;
            }
            Contagem contagem = linhas(pacote, descricao, estrutura);
            if (contagem != null) {
                validados.add(new PacoteValidado(grupo, nome, contagem));
            }
        }
        return validados;
    }

    private static boolean temEvidenciaExecutavel(Element pacote) {
        return pacote.getElementsByTagName("counter").getLength() > 0
                || pacote.getElementsByTagName("method").getLength() > 0
                || pacote.getElementsByTagName("line").getLength() > 0;
    }

    /**
     * Soma, das contagens ja validadas, o pacote do prefixo e seus subpacotes;
     * {@code .../domainx} nao pertence a {@code .../domain}.
     */
    private static Contagem escopo(List<PacoteValidado> pacotes, String prefixo, List<String> estrutura) {
        List<PacoteValidado> doEscopo = pacotes.stream()
                .filter(p -> p.nome().equals(prefixo) || p.nome().startsWith(prefixo + "/"))
                .toList();
        if (doEscopo.isEmpty() && estrutura.isEmpty()) {
            estrutura.add("nenhum pacote sob " + prefixo);
        }
        return doEscopo.stream().map(PacoteValidado::linhas).reduce(Contagem.ZERO, Contagem::mais);
    }

    private static void avaliar(String nome, Contagem contagem, int piso, List<String> tabela, List<String> violacoes) {
        boolean atinge = contagem.atinge(piso);
        tabela.add(String.format("%-13s %12s %12s %10s %5d%%  %s", nome, contagem.cobertas(),
                contagem.perdidas(), contagem.percentual(), piso, atinge ? "ok" : "ABAIXO DO PISO"));
        if (!atinge) {
            violacoes.add("cobertura LINE " + nome + " abaixo do piso: " + contagem.percentual()
                    + " < " + piso + "% (" + descrever(contagem) + ")");
        }
    }

    private static String descrever(Contagem contagem) {
        return "cobertas=" + contagem.cobertas() + ", perdidas=" + contagem.perdidas();
    }
}
