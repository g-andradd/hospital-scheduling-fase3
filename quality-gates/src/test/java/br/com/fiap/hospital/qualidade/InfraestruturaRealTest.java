package br.com.fiap.hospital.qualidade;

import static br.com.fiap.hospital.qualidade.InfraestruturaReal.Infraestrutura.POSTGRESQL;
import static br.com.fiap.hospital.qualidade.InfraestruturaReal.Infraestrutura.RABBITMQ;
import static org.assertj.core.api.Assertions.assertThat;

import br.com.fiap.hospital.qualidade.InfraestruturaReal.Infraestrutura;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Guarda de infraestrutura real (RNF-06, D6) sobre o reactor real e sobre fontes sinteticas. */
class InfraestruturaRealTest {

    private static final String IT = "servico/src/test/java/exemplo/ExemploIT.java";

    @TempDir
    Path raiz;

    private void escrever(String caminho, String conteudo) {
        try {
            Path arquivo = raiz.resolve(caminho);
            Files.createDirectories(arquivo.getParent());
            Files.writeString(arquivo, conteudo, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Modulo sintetico {@code servico} com um IT; {@code anotacoes} vao na classe, {@code membros} no corpo. */
    private void servico(String imports, String anotacoes, String membros) {
        escrever("servico/pom.xml", "<project><artifactId>servico</artifactId></project>");
        escrever(IT, "package exemplo;\n\nimport org.junit.jupiter.api.Test;\n" + imports + "\n\n" + anotacoes
                + "\nclass ExemploIT {\n\n" + membros + "\n\n    @Test\n    void caso() { }\n}\n");
    }

    private InfraestruturaReal.Resultado verificar() {
        return InfraestruturaReal.verificar(raiz, List.of("servico"));
    }

    @Test
    @DisplayName("reactor real: nenhum dublê de infraestrutura, e cada módulo prova exatamente a infraestrutura que usa")
    void reactorReal() {
        List<String> modulos = RegrasDoReactor.modulosDeCodigo(PomsDoReactor.raiz().getDocumentElement());
        InfraestruturaReal.Resultado resultado = InfraestruturaReal.verificar(PomsDoReactor.RAIZ, modulos);

        System.out.println("Guarda de infraestrutura real (RNF-06)");
        System.out.printf("%-22s %-28s %-28s%n", "modulo", "containers declarados", "provas runtime");
        modulos.forEach(m -> System.out.printf("%-22s %-28s %-28s%n", m, resultado.usadas().get(m), resultado.provadas().get(m)));
        System.out.println("Guarda de infraestrutura real: " + (resultado.violacoes().isEmpty() ? "APROVADA" : "REPROVADA " + resultado.violacoes()));

        assertThat(resultado.violacoes()).isEmpty();
        Map<String, Set<Infraestrutura>> esperado = Map.of(
                "shared-contracts", Set.of(RABBITMQ),
                "shared-security", Set.of(),
                "agendamento-service", Set.of(POSTGRESQL, RABBITMQ),
                "notificacao-service", Set.of(POSTGRESQL, RABBITMQ),
                "historico-service", Set.of(POSTGRESQL, RABBITMQ));
        assertThat(resultado.usadas()).containsExactlyInAnyOrderEntriesOf(esperado);
        assertThat(resultado.provadas()).containsExactlyInAnyOrderEntriesOf(esperado);
    }

    @Nested
    @DisplayName("Scenario: Dublê de infraestrutura em teste de integração é recusado")
    class Dubles {

        static Stream<Arguments> recusados() {
            return Stream.of(
                    Arguments.of("@Mock", "import org.mockito.Mock;\nimport javax.sql.DataSource;", "",
                            "    @Mock DataSource fonte;",
                            IT + ":10: @Mock sobre javax.sql.DataSource"),
                    Arguments.of("@MockBean", "import org.springframework.boot.test.mock.mockito.MockBean;\n"
                                    + "import org.springframework.jdbc.core.JdbcTemplate;", "",
                            "    @MockBean JdbcTemplate jdbc;",
                            IT + ":10: @MockBean sobre org.springframework.jdbc.core.JdbcTemplate"),
                    Arguments.of("@MockitoBean", "import org.springframework.test.context.bean.override.mockito.MockitoBean;\n"
                                    + "import org.springframework.amqp.rabbit.core.RabbitTemplate;", "",
                            "    @MockitoBean RabbitTemplate rabbit;",
                            IT + ":10: @MockitoBean sobre org.springframework.amqp.rabbit.core.RabbitTemplate"),
                    Arguments.of("@TestBean", "import org.springframework.test.context.bean.override.convention.TestBean;\n"
                                    + "import jakarta.persistence.EntityManager;", "",
                            "    @TestBean EntityManager entidades;",
                            IT + ":10: @TestBean sobre jakarta.persistence.EntityManager"),
                    Arguments.of("Mockito.mock com import estático sobre repositório Spring Data",
                            "import static org.mockito.Mockito.mock;\nimport exemplo.repositorio.ConsultaRepository;", "",
                            "    ConsultaRepository consultas = mock(ConsultaRepository.class);",
                            IT + ":10: Mockito.mock sobre exemplo.repositorio.ConsultaRepository"),
                    Arguments.of("import sob demanda", "import javax.sql.*;\nimport org.springframework.boot.test.mock.mockito.MockBean;", "",
                            "    @MockBean DataSource fonte;",
                            IT + ":10: @MockBean sobre javax.sql.DataSource"));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("recusados")
        @DisplayName("dublê de fonte de dados, JDBC, gerenciador de entidades, repositório ou template AMQP é recusado")
        void recusado(String caso, String imports, String anotacoes, String membros, String violacao) {
            escrever("servico/src/main/java/exemplo/repositorio/ConsultaRepository.java", "package exemplo.repositorio;\n\n"
                    + "import org.springframework.data.jpa.repository.JpaRepository;\n\n"
                    + "public interface ConsultaRepository extends JpaRepository<Object, Long> { }\n");
            servico(imports, anotacoes, membros);
            assertThat(verificar().violacoes()).containsExactly("duble de infraestrutura em teste de integracao: " + violacao);
        }

        @Test
        @DisplayName("@Bean DataSource numa configuração de teste alcançada pelo IT é recusado")
        void beanDeInfraestrutura() {
            servico("import javax.sql.DataSource;\nimport org.springframework.boot.test.context.TestConfiguration;\n"
                    + "import org.springframework.context.annotation.Bean;", "",
                    "    @TestConfiguration\n    static class Configuracao {\n        @Bean DataSource fonte() { return null; }\n    }");
            assertThat(verificar().violacoes()).containsExactly("@Bean de infraestrutura em configuracao de teste: " + IT
                    + ":13: exemplo.ExemploIT.Configuracao#fonte devolve javax.sql.DataSource");
        }

        static Stream<Arguments> aceitos() {
            return Stream.of(
                    Arguments.of("@SpyBean com doThrow sobre o espião",
                            "import org.springframework.boot.test.mock.mockito.SpyBean;\nimport org.springframework.jdbc.core.JdbcTemplate;\n"
                                    + "import org.mockito.Mockito;",
                            "    @SpyBean JdbcTemplate jdbc;\n\n    void falhar() {\n"
                                    + "        Mockito.doThrow(new IllegalStateException()).when(jdbc).execute(\"x\");\n    }"),
                    Arguments.of("@MockitoSpyBean", "import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;\n"
                                    + "import org.springframework.amqp.rabbit.core.RabbitTemplate;",
                            "    @MockitoSpyBean RabbitTemplate rabbit;"),
                    Arguments.of("decorador que delega à fonte de dados real",
                            "import javax.sql.DataSource;\nimport org.springframework.beans.factory.config.BeanPostProcessor;\n"
                                    + "import org.springframework.boot.test.context.TestConfiguration;\nimport org.springframework.context.annotation.Bean;",
                            "    @TestConfiguration\n    static class Configuracao {\n        @Bean\n        static BeanPostProcessor gravador() {\n"
                                    + "            return new BeanPostProcessor() {\n"
                                    + "                public Object postProcessAfterInitialization(Object bean, String nome) {\n"
                                    + "                    return bean instanceof DataSource original ? original : bean;\n"
                                    + "                }\n            };\n        }\n    }"),
                    Arguments.of("@MockBean MailSender e dublê de porta externa",
                            "import org.springframework.boot.test.mock.mockito.MockBean;\nimport org.springframework.mail.MailSender;\n"
                                    + "import static org.mockito.Mockito.mock;\nimport exemplo.sender.NotificationSenderPort;",
                            "    @MockBean MailSender mailSender;\n\n    NotificationSenderPort canal = mock(NotificationSenderPort.class);"));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("aceitos")
        @DisplayName("Scenario: Espião, decorador e dublê de porta externa continuam permitidos")
        void aceito(String caso, String imports, String membros) {
            servico(imports, "", membros);
            assertThat(verificar().violacoes()).isEmpty();
        }
    }

    @Nested
    @DisplayName("Scenario: Banco em memória, broker embarcado ou substituição automática é recusado")
    class Embarcados {

        @Test
        @DisplayName("dependência de H2 no módulo é recusada")
        void h2() {
            servico("", "", "");
            escrever("servico/pom.xml", "<project><artifactId>servico</artifactId><dependencies><dependency>"
                    + "<groupId>com.h2database</groupId><artifactId>h2</artifactId><scope>test</scope></dependency></dependencies></project>");
            assertThat(verificar().violacoes()).containsExactly("banco em memoria ou broker embarcado: servico/pom.xml declara com.h2database:h2");
        }

        @Test
        @DisplayName("@DataJpaTest com @AutoConfigureTestDatabase(replace = ANY) é recusado")
        void replaceAny() {
            servico("import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;\n"
                    + "import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;",
                    "@DataJpaTest\n@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)", "");
            assertThat(verificar().violacoes()).containsExactly(
                    "substituicao automatica do banco: " + IT + ":7: exemplo.ExemploIT declara DataJpaTest sem @AutoConfigureTestDatabase(replace = NONE)",
                    "substituicao automatica do banco: " + IT + ":8: exemplo.ExemploIT declara @AutoConfigureTestDatabase sem replace = NONE");
        }
    }

    @Nested
    @DisplayName("Scenario: Cada módulo comprova a infraestrutura real que usa — prova derivada dos containers declarados")
    class Evidencia {

        private static final String RABBIT = "package exemplo;\n\nimport org.testcontainers.containers.RabbitMQContainer;\n\n"
                + "class Containers {\n    static final RabbitMQContainer RABBIT = new RabbitMQContainer(\"rabbitmq:3.13-management\");\n}\n";

        private void evidencia(String... provas) {
            StringBuilder metodos = new StringBuilder();
            for (String prova : provas) {
                metodos.append("    @Test\n    void ").append(prova).append("() { }\n\n");
            }
            escrever("servico/src/test/java/exemplo/InfraestruturaRealServicoIT.java", "package exemplo;\n\n"
                    + "import org.junit.jupiter.api.Test;\n\nclass InfraestruturaRealServicoIT {\n\n" + metodos + "}\n");
        }

        @Test
        @DisplayName("módulo com PostgreSQLContainer sem prova de PostgreSQL é recusado")
        void postgresSemProva() {
            servico("", "", "");
            escrever("servico/src/test/java/exemplo/Containers.java", "package exemplo;\n\n"
                    + "import org.testcontainers.containers.PostgreSQLContainer;\n\n"
                    + "class Containers {\n    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(\"postgres:16\");\n}\n");
            assertThat(verificar().violacoes()).containsExactly("modulo servico declara PostgreSQLContainer e nenhum IT de evidencia"
                    + " prova PostgreSQL 16 (InfraestruturaReal*IT#provaPostgreSql)");
        }

        @Test
        @DisplayName("prova de infraestrutura que o módulo não usa é recusada")
        void provaNaoUsada() {
            servico("", "", "");
            escrever("servico/src/test/java/exemplo/Containers.java", RABBIT);
            evidencia("provaRabbitMq", "provaPostgreSql");
            assertThat(verificar().violacoes()).containsExactly(
                    "modulo servico prova PostgreSQL 16 sem declarar PostgreSQLContainer: prova de infraestrutura nao usada");
        }

        @Test
        @DisplayName("módulo só com RabbitMQContainer e prova só de RabbitMQ é aceito")
        void soRabbit() {
            servico("", "", "");
            escrever("servico/src/test/java/exemplo/Containers.java", RABBIT);
            evidencia("provaRabbitMq");
            InfraestruturaReal.Resultado resultado = verificar();
            assertThat(resultado.violacoes()).isEmpty();
            assertThat(resultado.usadas()).containsExactly(Map.entry("servico", Set.of(RABBITMQ)));
            assertThat(resultado.provadas()).containsExactly(Map.entry("servico", Set.of(RABBITMQ)));
        }
    }
}
