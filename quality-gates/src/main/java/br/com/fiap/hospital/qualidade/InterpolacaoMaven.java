package br.com.fiap.hospital.qualidade;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * Interpolacao Maven na configuracao dos plugins de teste (D5, estrategia A).
 *
 * <p>O Maven interpola {@code ${...}} antes de entregar a configuracao ao Surefire e ao Failsafe, e os
 * dois resolvem {@code @{...}} no {@code argLine} na execucao. Uma user property da linha de comando
 * prevalece sobre a propriedade do POM, e a auditoria nao recebe propriedades arbitrarias da execucao:
 * por isso um alias customizado numa posicao capaz de ocultar configuracao de teste e recusado mesmo
 * quando o valor atual do POM parece seguro. So escapam as expressoes que o canal permite — as proprias
 * do modelo, que o interpolador resolve pelo projeto antes das user properties, e {@code argLine}, cujo
 * valor efetivo e repassado a auditoria.
 *
 * <p>A resolucao pelas propriedades do POM continua existindo, mas so para o diagnostico e para acusar um
 * valor inseguro ja escrito no POM; nunca para aceitar um alias.
 */
public final class InterpolacaoMaven {

    /**
     * Expressoes proprias do modelo que o interpolador resolve pelo projeto antes das user properties:
     * {@code -Dproject.basedir} e {@code -Dproject.build.directory} nao as mudam (conferido com
     * {@code help:evaluate}).
     */
    public static final Set<String> PROPRIAS_DO_MODELO = Set.of("project.basedir", "basedir", "project.build.directory");

    private InterpolacaoMaven() {}

    /**
     * Texto depois da interpolacao.
     *
     * @param naoResolvidas expressoes sem propriedade no escopo, como escritas: {@code ${nome}} ou {@code @{nome}}
     * @param ciclos expressoes que voltam a si mesmas
     */
    public record Resultado(String valor, List<String> naoResolvidas, List<String> ciclos) {

        public boolean completo() {
            return naoResolvidas.isEmpty() && ciclos.isEmpty();
        }
    }

    /** Propriedades de {@code <properties>}, pelo nome do elemento. */
    public static Map<String, String> propriedades(Element propriedades) {
        Map<String, String> pares = new LinkedHashMap<>();
        for (Node no = propriedades == null ? null : propriedades.getFirstChild(); no != null; no = no.getNextSibling()) {
            if (no instanceof Element propriedade) {
                pares.put(propriedade.getTagName(), propriedade.getTextContent().trim());
            }
        }
        return pares;
    }

    /** Escopo mais especifico sobre o mais geral: as propriedades do segundo prevalecem. */
    public static Map<String, String> sobrepor(Map<String, String> geral, Map<String, String> especifico) {
        Map<String, String> escopo = new LinkedHashMap<>(geral);
        escopo.putAll(especifico);
        return escopo;
    }

    /**
     * Aliases do texto: toda expressao {@code ${nome}} ou {@code @{nome}} cujo nome nao esteja entre os
     * permitidos no canal. Cada item descreve a expressao e o valor que o POM lhe da, quando da — o valor
     * serve ao diagnostico e nao torna o alias aceitavel, porque a linha de comando o sobrescreve.
     */
    public static List<String> aliases(String texto, Map<String, String> escopo, Set<String> permitidos) {
        List<String> aliases = new ArrayList<>();
        for (String expressao : expressoes(texto)) {
            String nome = nome(expressao);
            if (permitidos.contains(nome)) {
                continue;
            }
            aliases.add(expressao + (escopo.containsKey(nome) ? " (no POM: " + escopo.get(nome) + ")" : " (sem valor no POM)"));
        }
        return aliases;
    }

    /** Expressoes {@code ${...}} e {@code @{...}} escritas no texto, na ordem e sem repeticao. */
    static List<String> expressoes(String texto) {
        List<String> expressoes = new ArrayList<>();
        int posicao = 0;
        String conteudo = texto == null ? "" : texto;
        while (true) {
            int inicio = proximaExpressao(conteudo, posicao);
            if (inicio < 0) {
                return expressoes;
            }
            int fim = conteudo.indexOf('}', inicio + 2);
            if (fim < 0) {
                return expressoes;
            }
            String expressao = conteudo.substring(inicio, fim + 1);
            if (!expressoes.contains(expressao)) {
                expressoes.add(expressao);
            }
            posicao = fim + 1;
        }
    }

    static String nome(String expressao) {
        return expressao.substring(2, expressao.length() - 1);
    }

    public static Resultado resolver(String texto, Map<String, String> escopo) {
        List<String> naoResolvidas = new ArrayList<>();
        List<String> ciclos = new ArrayList<>();
        String valor = expandir(texto == null ? "" : texto, escopo, new ArrayDeque<>(), naoResolvidas, ciclos);
        return new Resultado(valor, List.copyOf(naoResolvidas), List.copyOf(ciclos));
    }

    private static String expandir(String texto, Map<String, String> escopo, Deque<String> pilha,
            List<String> naoResolvidas, List<String> ciclos) {
        StringBuilder resultado = new StringBuilder();
        int posicao = 0;
        while (posicao < texto.length()) {
            int inicio = proximaExpressao(texto, posicao);
            if (inicio < 0) {
                resultado.append(texto, posicao, texto.length());
                break;
            }
            int fim = texto.indexOf('}', inicio + 2);
            if (fim < 0) {
                resultado.append(texto, posicao, texto.length());
                break;
            }
            resultado.append(texto, posicao, inicio);
            String expressao = texto.substring(inicio, fim + 1);
            String nome = nome(expressao);
            if (!escopo.containsKey(nome)) {
                if (!naoResolvidas.contains(expressao)) {
                    naoResolvidas.add(expressao);
                }
                resultado.append(expressao);
            } else if (pilha.contains(nome)) {
                if (!ciclos.contains(expressao)) {
                    ciclos.add(expressao);
                }
                resultado.append(expressao);
            } else {
                pilha.push(nome);
                resultado.append(expandir(escopo.get(nome), escopo, pilha, naoResolvidas, ciclos));
                pilha.pop();
            }
            posicao = fim + 1;
        }
        return resultado.toString();
    }

    private static int proximaExpressao(String texto, int desde) {
        int dolar = texto.indexOf("${", desde);
        int arroba = texto.indexOf("@{", desde);
        if (dolar < 0) {
            return arroba;
        }
        return arroba < 0 ? dolar : Math.min(dolar, arroba);
    }
}
