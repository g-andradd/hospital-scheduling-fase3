package br.com.fiap.hospital.qualidade;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Regras estruturais do roteiro de smoke (D10, D11 e D13), sobre o texto do script Bash.
 *
 * <p>Comentarios nao contam. Recusa roteiro sem modo estrito como primeiro comando, pausa
 * {@code sleep} fora da funcao {@code aguardar}, {@code docker compose}, encerramento por nome com
 * {@code pkill} ou {@code killall}, e {@code mktemp} alcancado antes do preflight na funcao
 * {@code main}.
 */
public final class RoteiroDeSmoke {

    static final String ESPERA = "aguardar";
    static final String PRINCIPAL = "main";
    static final String PREFLIGHT = "preflight";

    private static final Pattern INICIO_DE_FUNCAO = Pattern.compile("^([A-Za-z_][A-Za-z0-9_]*)\\(\\)\\s*\\{\\s*$");
    private static final Pattern MODO_ESTRITO = Pattern.compile("^set\\s+-[A-Za-z]*e[A-Za-z]*u[A-Za-z]*o\\s+pipefail\\s*$");
    private static final Pattern SLEEP = Pattern.compile("(^|[^A-Za-z0-9_-])sleep([^A-Za-z0-9_-]|$)");
    private static final Pattern COMPOSE = Pattern.compile("docker(\\s+|-)compose");
    private static final Pattern POR_NOME = Pattern.compile("(^|[^A-Za-z0-9_-])(pkill|killall)([^A-Za-z0-9_-]|$)");
    private static final Pattern MKTEMP = Pattern.compile("(^|[^A-Za-z0-9_-])mktemp([^A-Za-z0-9_-]|$)");

    private RoteiroDeSmoke() {}

    /** Linha de codigo, sem comentario de linha inteira, com o numero original e a funcao que a contem. */
    private record Linha(int numero, String codigo, String funcao) {}

    public static List<String> verificar(String roteiro) {
        List<Linha> linhas = linhas(roteiro);
        List<String> violacoes = new ArrayList<>();
        if (linhas.isEmpty() || !MODO_ESTRITO.matcher(linhas.getFirst().codigo()).matches()) {
            violacoes.add("roteiro sem modo estrito: o primeiro comando deve ser set -euo pipefail");
        }
        for (Linha linha : linhas) {
            if (SLEEP.matcher(linha.codigo()).find() && !ESPERA.equals(linha.funcao())) {
                violacoes.add("pausa fixa fora de " + ESPERA + ", linha " + linha.numero() + ": " + linha.codigo());
            }
            if (COMPOSE.matcher(linha.codigo()).find()) {
                violacoes.add("docker compose no roteiro, linha " + linha.numero() + ": " + linha.codigo());
            }
            if (POR_NOME.matcher(linha.codigo()).find()) {
                violacoes.add("encerramento de processo por nome, linha " + linha.numero() + ": " + linha.codigo());
            }
            if (MKTEMP.matcher(linha.codigo()).find() && linha.funcao() == null) {
                violacoes.add("mktemp fora de funcao, executado antes do preflight, linha " + linha.numero());
            }
        }
        violacoes.addAll(mktempAntesDoPreflight(linhas));
        return violacoes;
    }

    /** Na {@code main}, a chamada do preflight precede todo mktemp, direto ou numa funcao chamada. */
    private static List<String> mktempAntesDoPreflight(List<Linha> linhas) {
        Map<String, Boolean> criaTemporario = new LinkedHashMap<>();
        for (Linha linha : linhas) {
            if (linha.funcao() != null && MKTEMP.matcher(linha.codigo()).find()) {
                criaTemporario.put(linha.funcao(), true);
            }
        }
        List<Linha> principal = linhas.stream().filter(l -> PRINCIPAL.equals(l.funcao())).toList();
        if (principal.isEmpty()) {
            return List.of("roteiro sem funcao " + PRINCIPAL + ": a ordem do preflight nao pode ser conferida");
        }
        for (Linha linha : principal) {
            if (chama(linha.codigo(), PREFLIGHT)) {
                return List.of();
            }
            boolean temporario = MKTEMP.matcher(linha.codigo()).find()
                    || criaTemporario.keySet().stream().anyMatch(funcao -> chama(linha.codigo(), funcao));
            if (temporario) {
                return List.of("mktemp antes do preflight, linha " + linha.numero() + ": " + linha.codigo());
            }
        }
        return List.of("a funcao " + PRINCIPAL + " nao chama o " + PREFLIGHT);
    }

    private static boolean chama(String codigo, String funcao) {
        return Pattern.compile("(^|[^A-Za-z0-9_-])" + Pattern.quote(funcao) + "([^A-Za-z0-9_-]|$)").matcher(codigo).find();
    }

    private static List<Linha> linhas(String roteiro) {
        List<Linha> linhas = new ArrayList<>();
        String funcao = null;
        String[] brutas = roteiro.replace("\r", "").split("\n", -1);
        for (int indice = 0; indice < brutas.length; indice++) {
            String codigo = brutas[indice].strip();
            if (codigo.isEmpty() || codigo.startsWith("#")) {
                continue;
            }
            var inicio = INICIO_DE_FUNCAO.matcher(brutas[indice]);
            if (inicio.matches()) {
                funcao = inicio.group(1);
                continue;
            }
            if (brutas[indice].equals("}") && funcao != null) {
                funcao = null;
                continue;
            }
            linhas.add(new Linha(indice + 1, codigo, funcao));
        }
        return linhas;
    }
}
