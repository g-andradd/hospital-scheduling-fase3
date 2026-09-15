package br.com.fiap.hospital.qualidade;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Verifica, sem Docker, as garantias das imagens e do Compose de demonstracao (M12, D8).
 *
 * <p>Os negativos partem dos arquivos reais e alteram uma unica decisao por caso. Desse modo,
 * a suite prova tanto que a configuracao atual atende quanto que cada protecao detecta regressao.
 */
@DisplayName("Ambiente de demonstração")
class AmbienteDeDemonstracaoTest {

    private static final List<String> SERVICOS =
            List.of("agendamento-service", "notificacao-service", "historico-service");
    private static final Map<String, Integer> PORTAS = Map.of(
            "agendamento-service", 8081,
            "notificacao-service", 8082,
            "historico-service", 8083);
    private static final Pattern FROM = Pattern.compile("(?m)^FROM\\s+([^\\s]+)(?:\\s+AS\\s+(\\S+))?\\s*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern USER = Pattern.compile("(?m)^USER\\s+(\\S+)\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern COPY_TARGET =
            Pattern.compile("(?im)^COPY\\s+.*(?:^|[/\\\\])target(?:[/\\\\]|\\s|$).*$");

    private record Etapa(String imagem, String nome) {}

    private record Ambiente(Map<String, String> dockerfiles, String compose, String dockerignore, String makefile) {
        Ambiente copiar() {
            return new Ambiente(new LinkedHashMap<>(dockerfiles), compose, dockerignore, makefile);
        }

        Ambiente dockerfile(String servico, UnaryOperator<String> mutacao) {
            Ambiente copia = copiar();
            copia.dockerfiles().put(servico, mutacao.apply(copia.dockerfiles().get(servico)));
            return copia;
        }

        Ambiente compose(UnaryOperator<String> mutacao) {
            return new Ambiente(new LinkedHashMap<>(dockerfiles), mutacao.apply(compose), dockerignore, makefile);
        }

        Ambiente dockerignore(UnaryOperator<String> mutacao) {
            return new Ambiente(new LinkedHashMap<>(dockerfiles), compose, mutacao.apply(dockerignore), makefile);
        }

        Ambiente makefile(UnaryOperator<String> mutacao) {
            return new Ambiente(new LinkedHashMap<>(dockerfiles), compose, dockerignore, mutacao.apply(makefile));
        }
    }

    private static Ambiente real() {
        Map<String, String> dockerfiles = new LinkedHashMap<>();
        SERVICOS.forEach(servico -> dockerfiles.put(servico, ler(servico + "/Dockerfile")));
        return new Ambiente(dockerfiles, ler("docker-compose.yml"), ler(".dockerignore"), ler("Makefile"));
    }

    private static String ler(String relativo) {
        try {
            return Files.readString(PomsDoReactor.RAIZ.resolve(relativo), StandardCharsets.UTF_8)
                    .replace("\r", "");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static List<String> verificar(Ambiente ambiente) {
        List<String> violacoes = new ArrayList<>();
        ambiente.dockerfiles().forEach((servico, dockerfile) ->
                verificarDockerfile(servico, PORTAS.get(servico), dockerfile, violacoes));
        verificarCompose(ambiente.compose(), violacoes);
        verificarDockerignore(ambiente.dockerignore(), violacoes);
        verificarMakefile(ambiente.makefile(), violacoes);
        return violacoes;
    }

    private static void verificarDockerfile(
            String servico, int porta, String dockerfile, List<String> violacoes) {
        String arquivo = servico + "/Dockerfile";
        List<Etapa> etapas = new ArrayList<>();
        Matcher from = FROM.matcher(dockerfile);
        while (from.find()) etapas.add(new Etapa(from.group(1), from.group(2)));
        if (etapas.size() < 2 || etapas.getFirst().nome() == null
                || !etapas.getFirst().nome().equalsIgnoreCase("build")) {
            violacoes.add(arquivo + ": build multi-stage sem etapa build nomeada");
        }
        if (etapas.isEmpty() || !etapas.getLast().imagem().equals("eclipse-temurin:21-jre-alpine")) {
            violacoes.add(arquivo + ": runtime final deve ser eclipse-temurin:21-jre-alpine");
        }

        Matcher usuario = USER.matcher(dockerfile);
        int posicaoDoUsuario = -1;
        String nomeDoUsuario = null;
        while (usuario.find()) {
            posicaoDoUsuario = usuario.start();
            nomeDoUsuario = usuario.group(1);
        }
        int entrypoint = dockerfile.indexOf("ENTRYPOINT");
        if (posicaoDoUsuario < 0 || entrypoint < 0 || posicaoDoUsuario > entrypoint
                || nomeDoUsuario.equalsIgnoreCase("root") || nomeDoUsuario.equals("0")) {
            violacoes.add(arquivo + ": USER nao-root deve anteceder o ENTRYPOINT");
        }

        if (!dockerfile.contains("HEALTHCHECK")
                || !dockerfile.contains("http://127.0.0.1:" + porta + "/actuator/health")) {
            violacoes.add(arquivo + ": HEALTHCHECK deve consultar /actuator/health na porta " + porta);
        }
        long poms = dockerfile.lines().filter(l -> l.startsWith("COPY ") && l.contains("pom.xml")).count();
        int primeiroPom = dockerfile.indexOf("COPY pom.xml");
        int primeiraFonte = dockerfile.indexOf("/src ");
        if (poms != 7 || primeiroPom < 0 || primeiraFonte < 0 || primeiroPom > primeiraFonte) {
            violacoes.add(arquivo + ": os sete POMs devem ser copiados antes das fontes");
        }
        if (!dockerfile.contains("RUN --mount=type=cache,target=/root/.m2")) {
            violacoes.add(arquivo + ": build sem cache mount de /root/.m2");
        }
        if (COPY_TARGET.matcher(dockerfile).find()) {
            violacoes.add(arquivo + ": COPY de target do host e proibido");
        }
    }

    private static void verificarCompose(String compose, List<String> violacoes) {
        List<String> esperados = List.of("postgres", "rabbitmq", "mailpit", "agendamento", "notificacao", "historico");
        List<String> encontrados = servicos(compose);
        if (!encontrados.equals(esperados)) {
            violacoes.add("docker-compose.yml: servicos devem ser " + esperados);
        }

        String mailpit = blocoServico(compose, "mailpit");
        if (!mailpit.contains("image: axllent/mailpit:v1.31.1")) {
            violacoes.add("docker-compose.yml: mailpit deve usar a tag fixada axllent/mailpit:v1.31.1");
        }
        if (!mailpit.contains("test: [\"CMD\", \"/mailpit\", \"readyz\"]")) {
            violacoes.add("docker-compose.yml: mailpit deve ter healthcheck /mailpit readyz");
        }
        if (mailpit.lines().map(String::strip).anyMatch(l -> l.matches("- [^#]*1025[^#]*"))) {
            violacoes.add("docker-compose.yml: SMTP 1025 deve ficar somente na rede interna");
        }

        Map<String, String> databases = Map.of(
                "agendamento", "agendamento_db",
                "notificacao", "notificacao_db",
                "historico", "historico_db");
        databases.forEach((servico, database) -> {
            String bloco = blocoServico(compose, servico);
            if (!bloco.contains("postgres:\n        condition: service_healthy")
                    || !bloco.contains("rabbitmq:\n        condition: service_healthy")) {
                violacoes.add("docker-compose.yml: " + servico + " deve esperar dependencias service_healthy");
            }
            if (!bloco.contains("jdbc:postgresql://postgres:5432/" + database)) {
                violacoes.add("docker-compose.yml: " + servico + " deve usar exclusivamente " + database);
            }
            if (!bloco.contains("RABBITMQ_HOST: rabbitmq")) {
                violacoes.add("docker-compose.yml: " + servico + " deve usar RABBITMQ_HOST rabbitmq");
            }
            String profile = valor(bloco, "SPRING_PROFILES_ACTIVE");
            if (profile == null || !List.of(profile.split(",")).contains("demo")) {
                violacoes.add("docker-compose.yml: " + servico + " sem profile demo");
            }
            if (profile == null || !List.of(profile.split(",")).contains("docker")) {
                violacoes.add("docker-compose.yml: " + servico + " sem profile docker");
            }
            String jwt = valor(bloco, "JWT_SECRET");
            if (jwt == null || !jwt.startsWith("${JWT_SECRET:?") || !jwt.endsWith("}")) {
                violacoes.add("docker-compose.yml: " + servico + " deve obter JWT_SECRET por placeholder obrigatorio");
            }
        });

        String notificacao = blocoServico(compose, "notificacao");
        if (!notificacao.contains("mailpit:\n        condition: service_healthy")) {
            violacoes.add("docker-compose.yml: notificacao deve esperar mailpit service_healthy");
        }
        if (!"smtp".equals(valor(notificacao, "NOTIFICACAO_SENDER"))) {
            violacoes.add("docker-compose.yml: notificacao deve usar o canal smtp");
        }
        if (!"mailpit".equals(valor(notificacao, "SMTP_HOST"))) {
            violacoes.add("docker-compose.yml: notificacao deve usar SMTP_HOST mailpit");
        }
        if (!"true".equals(valor(notificacao, "MANAGEMENT_HEALTH_MAIL_ENABLED"))) {
            violacoes.add("docker-compose.yml: notificacao deve habilitar o indicador mail");
        }

        for (String linha : compose.split("\n")) {
            String limpa = linha.strip();
            if (limpa.startsWith("image:") && limpa.substring("image:".length()).strip().endsWith(":latest")) {
                violacoes.add("docker-compose.yml: imagem com tag latest");
            }
        }
    }

    private static List<String> servicos(String compose) {
        List<String> nomes = new ArrayList<>();
        boolean dentro = false;
        for (String linha : compose.split("\n")) {
            if (linha.equals("services:")) {
                dentro = true;
                continue;
            }
            if (dentro && !linha.isBlank() && !linha.startsWith(" ")) break;
            if (dentro && linha.matches("^  [a-z][a-z0-9_-]*:$")) nomes.add(linha.strip().replace(":", ""));
        }
        return nomes;
    }

    private static String blocoServico(String compose, String servico) {
        Pattern bloco = Pattern.compile(
                "(?ms)^  " + Pattern.quote(servico) + ":\\s*\\n(.*?)(?=^  [a-z][a-z0-9_-]*:\\s*$|^volumes:\\s*$)");
        Matcher matcher = bloco.matcher(compose);
        return matcher.find() ? matcher.group() : "";
    }

    private static String valor(String bloco, String chave) {
        Pattern padrao = Pattern.compile("(?m)^\\s+" + Pattern.quote(chave) + ":\\s*(.+?)\\s*$");
        Matcher matcher = padrao.matcher(bloco);
        return matcher.find() ? matcher.group(1).strip().replaceAll("^[\"']|[\"']$", "") : null;
    }

    private static void verificarDockerignore(String dockerignore, List<String> violacoes) {
        List<String> linhas = dockerignore.lines().map(String::strip).toList();
        if (linhas.stream().noneMatch(l -> l.equals("**/target") || l.equals("target") || l.equals("target/"))) {
            violacoes.add(".dockerignore: target deve ser excluido");
        }
        if (!linhas.contains(".git")) violacoes.add(".dockerignore: .git deve ser excluido");
        if (!linhas.contains(".env")) violacoes.add(".dockerignore: .env deve ser excluido");
    }

    private static void verificarMakefile(String makefile, List<String> violacoes) {
        String demo = blocoAlvo(makefile, "demo");
        if (!makefile.contains("BASH := bash") || !makefile.contains("ifeq ($(OS),Windows_NT)")
                || !makefile.contains("BASH := C:/Progra~1/Git/bin/bash.exe")) {
            violacoes.add("Makefile: Windows deve selecionar o Git Bash em vez do launcher WSL");
        }
        if (!demo.contains("$(BASH) scripts/demo.sh preflight")) {
            violacoes.add("Makefile: demo deve executar o preflight antes de alterar o ambiente");
        }
        if (!demo.contains("docker compose up -d --build --wait")) {
            violacoes.add("Makefile: demo deve subir o ambiente completo com build e wait");
        }
        if (!demo.contains("$(BASH) scripts/demo.sh\n")) {
            violacoes.add("Makefile: demo deve executar scripts/demo.sh");
        }
        if (demo.contains("down -v")) {
            violacoes.add("Makefile: down -v nao pode aparecer no alvo demo");
        }
        if (!blocoAlvo(makefile, "reset").contains("docker compose down -v")) {
            violacoes.add("Makefile: reset deve remover os volumes com down -v");
        }
        String semReset = makefile.replace(blocoAlvo(makefile, "reset"), "");
        if (semReset.contains("down -v")) {
            violacoes.add("Makefile: down -v deve existir somente no alvo reset");
        }
    }

    private static String blocoAlvo(String makefile, String alvo) {
        Pattern bloco = Pattern.compile("(?ms)^" + Pattern.quote(alvo) + ":.*?(?=^[a-zA-Z0-9_-]+:|\\z)");
        Matcher matcher = bloco.matcher(makefile);
        return matcher.find() ? matcher.group() : "";
    }

    @Test
    @DisplayName("Scenario: Configuração real atende")
    void configuracaoRealAtende() {
        assertThat(verificar(real())).isEmpty();
    }

    static Stream<Arguments> negativos() {
        Ambiente base = real();
        String agendamento = "agendamento-service";
        return Stream.of(
                Arguments.of("USER root", base.dockerfile(agendamento, d -> d.replace("USER app", "USER root")),
                        "agendamento-service/Dockerfile: USER nao-root"),
                Arguments.of("USER ausente", base.dockerfile(agendamento, d -> d.replace("USER app\n", "")),
                        "agendamento-service/Dockerfile: USER nao-root"),
                Arguments.of("HEALTHCHECK ausente", base.dockerfile(agendamento,
                                d -> d.replaceAll("(?m)^HEALTHCHECK.*\\R  CMD.*\\R", "")),
                        "agendamento-service/Dockerfile: HEALTHCHECK"),
                Arguments.of("health em outro caminho", base.dockerfile(agendamento,
                                d -> d.replace("/actuator/health", "/actuator/info")),
                        "agendamento-service/Dockerfile: HEALTHCHECK"),
                Arguments.of("runtime com JDK", base.dockerfile(agendamento,
                                d -> d.replace("FROM eclipse-temurin:21-jre-alpine", "FROM eclipse-temurin:21-jdk-alpine")),
                        "agendamento-service/Dockerfile: runtime final"),
                Arguments.of("fonte antes dos POMs", base.dockerfile(agendamento,
                                AmbienteDeDemonstracaoTest::fonteAntesDosPoms),
                        "agendamento-service/Dockerfile: os sete POMs"),
                Arguments.of("depends_on sem healthy", base.compose(c -> c.replaceFirst(
                                "condition: service_healthy", "condition: service_started")),
                        "docker-compose.yml: agendamento deve esperar dependencias"),
                Arguments.of("database compartilhado", base.compose(c -> c.replace(
                                "jdbc:postgresql://postgres:5432/notificacao_db",
                                "jdbc:postgresql://postgres:5432/agendamento_db")),
                        "docker-compose.yml: notificacao deve usar exclusivamente notificacao_db"),
                Arguments.of("broker no host", base.compose(c -> c.replaceFirst(
                                "RABBITMQ_HOST: rabbitmq", "RABBITMQ_HOST: localhost")),
                        "docker-compose.yml: agendamento deve usar RABBITMQ_HOST rabbitmq"),
                Arguments.of("profile sem demo", base.compose(c -> c.replaceFirst(
                                "SPRING_PROFILES_ACTIVE: demo,docker", "SPRING_PROFILES_ACTIVE: docker")),
                        "docker-compose.yml: agendamento sem profile demo"),
                Arguments.of("profile sem docker", base.compose(c -> c.replaceFirst(
                                "SPRING_PROFILES_ACTIVE: demo,docker", "SPRING_PROFILES_ACTIVE: demo")),
                        "docker-compose.yml: agendamento sem profile docker"),
                Arguments.of("JWT literal", base.compose(c -> c.replaceFirst(
                                "JWT_SECRET: \\Q${JWT_SECRET:?defina JWT_SECRET no .env}\\E", "JWT_SECRET: segredo-literal")),
                        "docker-compose.yml: agendamento deve obter JWT_SECRET"),
                Arguments.of("imagem latest", base.compose(c -> c.replace("image: postgres:16", "image: postgres:latest")),
                        "docker-compose.yml: imagem com tag latest"),
                Arguments.of("dockerignore sem env", base.dockerignore(d -> d.replace(".env\n", "")),
                        ".dockerignore: .env deve ser excluido"),
                Arguments.of("canal log", base.compose(c -> c.replace(
                                "NOTIFICACAO_SENDER: smtp", "NOTIFICACAO_SENDER: log")),
                        "docker-compose.yml: notificacao deve usar o canal smtp"),
                Arguments.of("SMTP no host", base.compose(c -> c.replace(
                                "SMTP_HOST: mailpit", "SMTP_HOST: localhost")),
                        "docker-compose.yml: notificacao deve usar SMTP_HOST mailpit"),
                Arguments.of("health mail desabilitado", base.compose(c -> c.replace(
                                "MANAGEMENT_HEALTH_MAIL_ENABLED: \"true\"", "MANAGEMENT_HEALTH_MAIL_ENABLED: \"false\"")),
                        "docker-compose.yml: notificacao deve habilitar o indicador mail"),
                Arguments.of("mailpit sem healthcheck", base.compose(c -> c.replaceFirst(
                                "(?ms)(  mailpit:.*?)(    healthcheck:.*?)(?=    networks:)", "$1")),
                        "docker-compose.yml: mailpit deve ter healthcheck"),
                Arguments.of("down -v em demo", base.makefile(m -> m.replace(
                                "\tdocker compose up -d --build --wait", "\tdocker compose down -v\n\tdocker compose up -d --build --wait")),
                        "Makefile: down -v nao pode aparecer no alvo demo"),
                Arguments.of("launcher WSL no Windows", base.makefile(m -> m.replace(
                                "BASH := C:/Progra~1/Git/bin/bash.exe", "BASH := bash")),
                        "Makefile: Windows deve selecionar o Git Bash"));
    }

    private static String fonteAntesDosPoms(String dockerfile) {
        String linha = "COPY agendamento-service/src agendamento-service/src\n";
        return dockerfile.replace(linha, "").replace("COPY pom.xml ./\n", linha + "COPY pom.xml ./\n");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("negativos")
    @DisplayName("Scenario: Desvio de imagem ou de ambiente é recusado")
    void desvioERecusado(String caso, Ambiente ambiente, String trechoEsperado) {
        assertThat(verificar(ambiente)).as(caso).anyMatch(v -> v.startsWith(trechoEsperado));
    }
}
