package br.com.fiap.hospital.historico.estrutura;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * O exemplar canonico do contrato de eventos, como este servico o enxerga no classpath.
 *
 * <p>A fonte e uma so: {@code shared-contracts/src/test/resources/evento-consulta.json},
 * publicada pelo test-jar do shared-contracts. Esta classe existe em cada servico, e nao como
 * auxiliar compartilhado, porque o test-jar publica apenas o JSON — as classes de teste daquele
 * modulo nao viram API — e dependencias de teste nao sao transitivas por ele.
 */
public final class FixtureDoContrato {

    public static final String RECURSO = "evento-consulta.json";

    private static final String CANONICO = "shared-contracts/src/test/resources/" + RECURSO;
    private static final String TESTES_DO_CONTRATO = "shared-contracts/target/test-classes";

    private FixtureDoContrato() {}

    /** Todas as ocorrencias do recurso no classpath de testes. */
    public static List<URL> ocorrencias() {
        try {
            return Collections.list(
                    FixtureDoContrato.class.getClassLoader().getResources(RECURSO));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A unica ocorrencia. Mais de uma e sombreamento; nenhuma e dependencia ausente. */
    public static URL url() {
        List<URL> todas = ocorrencias();
        if (todas.size() != 1) {
            throw new AssertionError("esperava exatamente um " + RECURSO + " no classpath, "
                    + "encontrou " + todas.size() + ": " + todas);
        }
        return todas.getFirst();
    }

    public static byte[] bytes() {
        try (InputStream in = url().openStream()) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static String texto() {
        return new String(bytes(), StandardCharsets.UTF_8);
    }

    public static Path moduloAtual() {
        return Path.of(System.getProperty("basedir", System.getProperty("user.dir")))
                .toAbsolutePath().normalize();
    }

    public static Path raizDoRepositorio() {
        return moduloAtual().getParent();
    }

    public static Path canonico() {
        return raizDoRepositorio().resolve(CANONICO);
    }

    /** O artefato anexado pelo shared-contracts: a origem em package e verify. */
    public static boolean vemDoTestJar(URL url) {
        return "jar".equals(url.getProtocol())
                && arquivoDoJar(url).getFileName().toString()
                        .matches("shared-contracts-.+-tests\\.jar");
    }

    /** A saida de testes do shared-contracts: a origem que o reactor usa em mvn test. */
    public static boolean vemDoDiretorioDeTestesDoContrato(URL url) {
        return "file".equals(url.getProtocol())
                && caminho(url).startsWith(raizDoRepositorio().resolve(TESTES_DO_CONTRATO));
    }

    public static boolean vemDoProprioModulo(URL url) {
        return "file".equals(url.getProtocol()) && caminho(url).startsWith(moduloAtual());
    }

    /**
     * Nos testes de integracao, que rodam depois do package, o exemplar precisa vir do
     * test-jar, e o jar nao pode carregar nada alem dele.
     */
    public static void exigirOrigemNoTestJar() {
        URL url = url();
        if (!vemDoTestJar(url)) {
            throw new AssertionError("nos testes de integracao o exemplar deve vir do test-jar "
                    + "do shared-contracts, e veio de " + url);
        }
        try (JarFile jar = new JarFile(arquivoDoJar(url).toFile())) {
            List<String> indevidas = jar.stream()
                    .map(JarEntry::getName)
                    .filter(nome -> !nome.endsWith("/"))
                    .filter(nome -> !nome.equals(RECURSO) && !nome.startsWith("META-INF/"))
                    .toList();
            if (!indevidas.isEmpty()) {
                throw new AssertionError("o test-jar do contrato publica apenas o exemplar, "
                        + "mas contem tambem " + indevidas);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static String sha256(byte[] conteudo) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(conteudo));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Registra onde o exemplar foi resolvido nesta execucao, como evidencia. */
    public static void registrarOrigem(URL url) {
        Path destino = moduloAtual().resolve("target/evidencias-m10/fixture-origem.txt");
        try {
            Files.createDirectories(destino.getParent());
            Files.writeString(destino, url + System.lineSeparator());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        System.out.println("[fixture] " + RECURSO + " resolvido de " + url);
    }

    private static Path arquivoDoJar(URL url) {
        try {
            return Path.of(((JarURLConnection) url.openConnection()).getJarFileURL().toURI());
        } catch (IOException | URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Path caminho(URL url) {
        try {
            return Path.of(url.toURI()).toAbsolutePath().normalize();
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }
}
