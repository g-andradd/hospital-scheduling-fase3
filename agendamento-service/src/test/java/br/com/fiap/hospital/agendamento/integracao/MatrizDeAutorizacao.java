package br.com.fiap.hospital.agendamento.integracao;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Le a matriz de autorizacao de docs/02-especificacao-funcional.md secao 3.
 *
 * <p>A tabela do documento e a fonte, e nao uma copia dela escrita no teste. A
 * consequencia e a que importa: acrescentar uma linha ao documento faz aparecer tres
 * casos de teste novos, que falham ate serem implementados. Ninguem precisa lembrar de
 * sincronizar nada.
 *
 * <p>O risco de ler o documento e o parser parar de achar a tabela e a suite passar
 * verificando nada — foi exatamente o que aconteceu com a primeira versao da varredura
 * de excecoes do M03. Por isso {@link #celulas()} nunca e usado sozinho: um teste
 * separado afirma que a leitura encontrou 7 linhas, 3 perfis e 21 celulas. Sem essa
 * segunda assercao, a primeira seria decorativa.
 */
final class MatrizDeAutorizacao {

    private static final Path DOCUMENTO =
            Path.of("..", "docs", "02-especificacao-funcional.md");

    private static final String INICIO = "### agendamento-service — REST";
    private static final String FIM = "### historico-service";

    /** O que a celula da tabela diz que deve acontecer. */
    enum Expectativa {
        PERMITIDO,
        PROIBIDO,
        PUBLICO,
        PERMITIDO_COM_RECORTE
    }

    record Celula(String endpoint, String metodo, String perfil, Expectativa expectativa) {
        @Override
        public String toString() {
            return metodo + " " + endpoint + " como " + perfil;
        }
    }

    private MatrizDeAutorizacao() {}

    static List<Celula> celulas() {
        List<Celula> celulas = new ArrayList<>();
        List<String> linhas = linhasDaTabela();

        // A primeira linha e o cabecalho: | Endpoint | Metodo | MEDICO | ENFERMEIRO | PACIENTE |
        List<String> perfis = colunasDe(linhas.getFirst()).subList(2, 5);

        for (String linha : linhas.subList(2, linhas.size())) {
            List<String> colunas = colunasDe(linha);
            if (colunas.size() < 5) {
                continue;
            }
            String endpoint = semCrase(colunas.get(0));
            String metodo = colunas.get(1);

            for (int i = 0; i < perfis.size(); i++) {
                celulas.add(new Celula(
                        endpoint, metodo, perfis.get(i), expectativaDe(colunas.get(2 + i))));
            }
        }
        return List.copyOf(celulas);
    }

    static List<String> perfis() {
        return colunasDe(linhasDaTabela().getFirst()).subList(2, 5);
    }

    static int quantidadeDeEndpoints() {
        return (int) linhasDaTabela().stream().skip(2)
                .filter(l -> colunasDe(l).size() >= 5)
                .count();
    }

    private static Expectativa expectativaDe(String celula) {
        String conteudo = celula.toLowerCase();
        if (conteudo.contains("público") || conteudo.contains("publico")) {
            return Expectativa.PUBLICO;
        }
        if (conteudo.contains("❌") || conteudo.contains("403")) {
            return Expectativa.PROIBIDO;
        }
        if (conteudo.contains("só a própria") || conteudo.contains("filtro forçado")) {
            return Expectativa.PERMITIDO_COM_RECORTE;
        }
        return Expectativa.PERMITIDO;
    }

    private static List<String> linhasDaTabela() {
        try {
            String documento = Files.readString(DOCUMENTO);
            int inicio = documento.indexOf(INICIO);
            int fim = documento.indexOf(FIM, inicio + 1);
            if (inicio < 0 || fim < 0) {
                return List.of();
            }
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
        for (int i = 1; i < partes.length; i++) {
            colunas.add(partes[i].trim());
        }
        return colunas;
    }

    private static String semCrase(String valor) {
        return valor.replace("`", "").trim();
    }
}
