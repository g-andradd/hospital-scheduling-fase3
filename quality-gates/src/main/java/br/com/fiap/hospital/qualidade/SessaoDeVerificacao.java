package br.com.fiap.hospital.qualidade;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Conferencia do marcador da sessao de verificacao (D4), comum ao gate de cobertura e a auditoria.
 *
 * <p>A sessao apagou toda evidencia anterior antes dos testes e gravou o marcador; aqui ele precisa
 * ser textualmente igual ao identificador recebido. Nenhuma decisao usa data de modificacao.
 */
public final class SessaoDeVerificacao {

    public static final Path MARCADOR = Path.of("target", "sessao-de-verificacao", "sessao.txt");

    private SessaoDeVerificacao() {}

    /** @return vazia se o marcador existe e e igual ao identificador; senao, a causa */
    public static List<String> conferir(Path raiz, String sessao) {
        if (sessao == null || sessao.isBlank() || sessao.startsWith("${")) {
            return List.of("identificador de sessao nao informado: " + sessao);
        }
        Path marcador = raiz.resolve(MARCADOR);
        if (!Files.isRegularFile(marcador)) {
            return List.of("marcador de sessao ausente: " + marcador);
        }
        try {
            String gravado = Files.readString(marcador, StandardCharsets.UTF_8);
            if (!gravado.equals(sessao)) {
                return List.of("marcador de outra sessao: esperado [" + sessao + "], gravado [" + gravado + "]");
            }
            return List.of();
        } catch (IOException e) {
            return List.of("marcador de sessao ilegivel: " + marcador);
        }
    }
}
