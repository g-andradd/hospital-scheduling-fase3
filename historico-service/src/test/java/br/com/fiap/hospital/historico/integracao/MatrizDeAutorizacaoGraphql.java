package br.com.fiap.hospital.historico.integracao;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Le a matriz GraphQL de docs/02-especificacao-funcional.md secao 3.
 *
 * <p>Mesmo padrao do agendamento: a tabela do documento e a fonte, e nao uma copia dela
 * escrita aqui. Acrescentar uma operacao la produz tres casos novos, que falham ate serem
 * implementados — ninguem precisa lembrar de sincronizar nada.
 *
 * <p>O risco conhecido desse padrao e o parser deixar de achar a tabela e a suite passar
 * verificando nada, que foi o que aconteceu com a varredura de excecoes do M03. Por isso
 * {@link #celulas()} nunca e usado sozinho: {@code MatrizDeAutorizacaoGraphqlIT} afirma que
 * a leitura encontrou 5 operacoes, 3 perfis e 15 celulas.
 */
final class MatrizDeAutorizacaoGraphql {

    private static final Path DOCUMENTO =
            Path.of("..", "docs", "02-especificacao-funcional.md");

    private static final String INICIO = "### historico-service — GraphQL";
    /**
     * A tabela GraphQL termina onde comeca a do notificacao, acrescentada no M07. Terminar na
     * nota de teste obrigatorio faria esta leitura engolir a linha REST do lembrete como se
     * fosse uma operacao GraphQL.
     */
    private static final String FIM = "### notificacao-service — REST interno";

    /** O que a celula diz que deve acontecer. */
    enum Expectativa {
        PERMITIDO,
        PROIBIDO,
        PERMITIDO_COM_RECORTE
    }

    record Celula(String operacao, String perfil, Expectativa expectativa) {
        @Override
        public String toString() {
            return operacao + " como " + perfil;
        }
    }

    private MatrizDeAutorizacaoGraphql() { }

    static List<Celula> celulas() {
        List<Celula> celulas = new ArrayList<>();
        List<String> linhas = linhasDaTabela();
        if (linhas.isEmpty()) {
            return List.of();
        }
        List<String> perfis = perfis();

        // Linha 0 e o cabecalho e a 1 e o separador; os dados comecam na 2.
        for (String linha : linhas.subList(2, linhas.size())) {
            List<String> colunas = colunasDe(linha);
            if (colunas.size() < 4) {
                continue;
            }
            String operacao = nomeDaOperacao(colunas.get(0));
            for (int i = 0; i < perfis.size(); i++) {
                celulas.add(new Celula(operacao, perfis.get(i), expectativaDe(colunas.get(1 + i))));
            }
        }
        return List.copyOf(celulas);
    }

    static List<String> perfis() {
        List<String> linhas = linhasDaTabela();
        return linhas.isEmpty() ? List.of() : colunasDe(linhas.getFirst()).subList(1, 4);
    }

    static int quantidadeDeOperacoes() {
        List<String> linhas = linhasDaTabela();
        if (linhas.isEmpty()) {
            return 0;
        }
        return (int) linhas.stream().skip(2).filter(l -> colunasDe(l).size() >= 4).count();
    }

    /** A coluna traz o nome da operacao e, as vezes, a assinatura ou uma nota. */
    private static String nomeDaOperacao(String celula) {
        String limpo = celula.replace("`", "").trim();
        int parenteses = limpo.indexOf('(');
        return (parenteses < 0 ? limpo : limpo.substring(0, parenteses)).trim();
    }

    /**
     * O simbolo decide, e nao a mencao a 403.
     *
     * <p>A celula do paciente em {@code consultasDoPaciente} e permitida <b>e</b> cita 403
     * — o 403 e a consequencia de furar o recorte, nao a decisao da celula. Ler o numero
     * primeiro classificaria uma permissao como proibicao.
     */
    private static Expectativa expectativaDe(String celula) {
        String conteudo = celula.toLowerCase();
        boolean permitida = conteudo.contains("✅");
        if (conteudo.contains("❌") || (!permitida && conteudo.contains("403"))) {
            return Expectativa.PROIBIDO;
        }
        if (conteudo.contains("próprio") || conteudo.contains("proprio")
                || conteudo.contains("própria") || conteudo.contains("propria")
                || conteudo.contains("se for sua")) {
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
}
