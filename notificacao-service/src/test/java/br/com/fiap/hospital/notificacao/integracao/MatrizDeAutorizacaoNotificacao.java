package br.com.fiap.hospital.notificacao.integracao;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Le a tabela do notificacao-service na matriz de docs/02-especificacao-funcional.md secao 3.
 *
 * <p>O mesmo padrao dos leitores do agendamento e do historico: a tabela do documento e a
 * fonte da expectativa, e nao uma copia dela escrita no teste. Mudar uma celula la muda o
 * que os testes exigem.
 *
 * <p>O risco conhecido desse padrao e o parser deixar de achar a tabela e a suite passar
 * verificando nada. Por isso a leitura nunca e usada sozinha: {@code
 * MatrizDeAutorizacaoNotificacaoIT} afirma 1 endpoint, 3 perfis e 3 celulas.
 */
public final class MatrizDeAutorizacaoNotificacao {

    private static final Path DOCUMENTO = Files.exists(Path.of("..", "docs"))
            ? Path.of("..", "docs", "02-especificacao-funcional.md")
            : Path.of("docs", "02-especificacao-funcional.md");

    private static final String INICIO = "### notificacao-service — REST interno";
    private static final String FIM = "**Teste obrigatório:**";

    /** O que a celula diz que deve acontecer. */
    public enum Expectativa { PERMITIDO, PROIBIDO }

    public record Celula(String metodo, String endpoint, String perfil, Expectativa expectativa) {
        @Override
        public String toString() {
            return metodo + " " + endpoint + " como " + perfil;
        }
    }

    private MatrizDeAutorizacaoNotificacao() { }

    public static List<Celula> celulas() {
        List<String> linhas = linhasDaTabela();
        if (linhas.isEmpty()) return List.of();
        List<String> perfis = perfis();

        List<Celula> celulas = new ArrayList<>();
        // Linha 0 e o cabecalho | Endpoint | Metodo | MEDICO | ENFERMEIRO | PACIENTE |, 1 e o
        // separador; os dados comecam na 2.
        for (String linha : linhas.subList(2, linhas.size())) {
            List<String> colunas = colunasDe(linha);
            if (colunas.size() < 5) continue;
            String endpoint = colunas.get(0).replace("`", "").trim();
            String metodo = colunas.get(1);
            for (int i = 0; i < perfis.size(); i++) {
                celulas.add(new Celula(metodo, endpoint, perfis.get(i),
                        expectativaDe(colunas.get(2 + i))));
            }
        }
        return List.copyOf(celulas);
    }

    /** A celula de um perfil. Celula ausente do documento e falha, e nao permissao presumida. */
    public static Celula celula(String perfil) {
        return celulas().stream()
                .filter(c -> c.perfil().equals(perfil))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "docs/02 secao 3 nao tem celula do notificacao para o perfil " + perfil));
    }

    public static List<String> perfis() {
        List<String> linhas = linhasDaTabela();
        return linhas.isEmpty() ? List.of() : colunasDe(linhas.getFirst()).subList(2, 5);
    }

    public static int quantidadeDeEndpoints() {
        return endpoints().size();
    }

    /** "METODO /caminho" de cada linha da tabela. */
    public static Set<String> endpoints() {
        Set<String> endpoints = new TreeSet<>();
        for (Celula celula : celulas()) endpoints.add(celula.metodo() + " " + celula.endpoint());
        return endpoints;
    }

    /**
     * O simbolo decide: so ✅ permite. Celula com ❌, com 403 ou com qualquer outra coisa
     * proibe — na duvida, a leitura nega, e o teste acusa a divergencia com o codigo.
     */
    private static Expectativa expectativaDe(String celula) {
        return celula.contains("✅") ? Expectativa.PERMITIDO : Expectativa.PROIBIDO;
    }

    private static List<String> linhasDaTabela() {
        try {
            String documento = Files.readString(DOCUMENTO);
            int inicio = documento.indexOf(INICIO);
            int fim = documento.indexOf(FIM, inicio + 1);
            if (inicio < 0 || fim < 0) return List.of();
            return Arrays.stream(documento.substring(inicio, fim).split("\n"))
                    .map(String::trim)
                    .filter(l -> l.startsWith("|"))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<String> colunasDe(String linha) {
        String[] partes = linha.split("\\|");
        List<String> colunas = new ArrayList<>();
        for (int i = 1; i < partes.length; i++) colunas.add(partes[i].trim());
        return colunas;
    }
}
