package br.com.fiap.hospital.qualidade;

import java.util.LinkedHashMap;
import java.util.Map;

/** Fontes de teste sinteticas do {@link ReactorDeAuditoria}, com o formato das classes reais. */
final class FontesDeAuditoria {

    private FontesDeAuditoria() {}

    static final String AG = "agendamento/src/test/java/br/com/fiap/hospital/agendamento/";
    static final String SEG = "seguranca/src/test/java/br/com/fiap/hospital/security/";

    static final Map<String, String> PADRAO = padrao();

    private static Map<String, String> padrao() {
        Map<String, String> fontes = new LinkedHashMap<>();
        fontes.put(AG + "contrato/ConsultaRepositoryContractTest.java", """
                package br.com.fiap.hospital.agendamento.contrato;

                import org.junit.jupiter.api.BeforeEach;
                import org.junit.jupiter.api.DisplayName;
                import org.junit.jupiter.api.Nested;
                import org.junit.jupiter.api.Test;

                /** Contrato abstrato: executado pelas subclasses concretas. */
                public abstract class ConsultaRepositoryContractTest {

                    protected abstract Object criarRepositorio();

                    @BeforeEach
                    void preparar() { }

                    @Nested
                    @DisplayName("listagem")
                    class Listagem {
                        @Test void filtraPorIntervalo() { }
                        @Test void ordenaPorInicio() { }
                    }

                    @Nested
                    class GravacaoERecuperacao {
                        @Test void grava() { }
                        @Test void recupera() { }
                    }

                    @Nested
                    class DeteccaoDeConflito {
                        @Test void detecta() { }
                    }
                }
                """);
        fontes.put(AG + "contrato/ConsultaRepositoryFakeTest.java", """
                package br.com.fiap.hospital.agendamento.contrato;

                import br.com.fiap.hospital.agendamento.fake.ConsultaRepositoryFake;

                class ConsultaRepositoryFakeTest extends ConsultaRepositoryContractTest {
                    @Override
                    protected Object criarRepositorio() { return new ConsultaRepositoryFake(); }
                }
                """);
        fontes.put(AG + "contrato/BaseAbstrataTest.java", """
                package br.com.fiap.hospital.agendamento.contrato;

                import org.junit.jupiter.api.Test;

                /** Abstrata com o sufixo da convencao e metodo de teste: nao e exigida como suite. */
                abstract class BaseAbstrataTest {
                    @Test void herdado() { }
                }
                """);
        fontes.put(AG + "integracao/M05JpaBase.java", """
                package br.com.fiap.hospital.agendamento.integracao;

                abstract class M05JpaBase {
                    static final String IMAGEM = "postgres:16-alpine";
                }
                """);
        fontes.put(AG + "integracao/ConsultaRepositoryAdapterIT.java", """
                package br.com.fiap.hospital.agendamento.integracao;

                import br.com.fiap.hospital.agendamento.contrato.ConsultaRepositoryContractTest;
                import org.testcontainers.junit.jupiter.Testcontainers;

                @Testcontainers
                class ConsultaRepositoryAdapterIT extends ConsultaRepositoryContractTest {
                    @Override
                    protected Object criarRepositorio() { return new Object(); }
                }
                """);
        fontes.put(AG + "integracao/GraphiqlPorProfileIT.java", """
                package br.com.fiap.hospital.agendamento.integracao;

                import org.junit.jupiter.api.*;

                class GraphiqlPorProfileIT {

                    @Nested
                    class ProfilePadrao {
                        @Test void graphiqlDesligado() { }
                    }

                    @Nested
                    class ProfileDev {
                        @Test void graphiqlLigado() { }
                    }

                    @Nested
                    class ProfileDemo {
                        @Test void graphiqlLigado() { }
                    }
                }
                """);
        fontes.put(AG + "dominio/PeriodoParametrizadoTest.java", """
                package br.com.fiap.hospital.agendamento.dominio;

                import org.junit.jupiter.api.Test;
                import org.junit.jupiter.params.ParameterizedTest;
                import org.junit.jupiter.params.provider.ValueSource;

                class PeriodoParametrizadoTest {

                    @ParameterizedTest
                    @ValueSource(ints = {1, 2, 3, 4, 5})
                    void recusaDuracaoInvalida(int minutos) { }

                    @Test
                    void aceitaDuracaoValida() { }
                }
                """);
        fontes.put(AG + "dominio/FabricaDinamicaTest.java", """
                package br.com.fiap.hospital.agendamento.dominio;

                import java.util.stream.Stream;
                import org.junit.jupiter.api.DynamicTest;
                import org.junit.jupiter.api.TestFactory;

                class FabricaDinamicaTest {

                    @TestFactory
                    Stream<DynamicTest> casos() {
                        return Stream.of(1, 2, 3).map(n -> DynamicTest.dynamicTest("caso " + n, () -> { }));
                    }
                }
                """);
        fontes.put(AG + "fake/ConsultaRepositoryFake.java", """
                package br.com.fiap.hospital.agendamento.fake;

                import java.time.Clock;

                /** Auxiliar concreto sem teste. */
                public class ConsultaRepositoryFake {
                    static class RelogioDeTeste extends Clock {
                        public java.time.ZoneId getZone() { return null; }
                        public Clock withZone(java.time.ZoneId zona) { return this; }
                        public java.time.Instant instant() { return null; }
                    }
                }
                """);
        fontes.put(AG + "dominio/Situacao.java", "package br.com.fiap.hospital.agendamento.dominio; enum Situacao { AGENDADA }\n");
        fontes.put(AG + "dominio/Dados.java", "package br.com.fiap.hospital.agendamento.dominio; record Dados(int valor) { }\n");
        fontes.put(AG + "dominio/Porta.java", "package br.com.fiap.hospital.agendamento.dominio; interface Porta { void usar(); }\n");
        fontes.put(AG + "dominio/Marcador.java", "package br.com.fiap.hospital.agendamento.dominio; @interface Marcador { }\n");
        fontes.put(SEG + "JwtServiceTest.java", """
                package br.com.fiap.hospital.security;

                import org.junit.jupiter.api.Nested;
                import org.junit.jupiter.api.Test;

                class JwtServiceTest {

                    @Nested
                    class Emissao {
                        @Test void emite() { }
                        @Test void naoCarregaSenha() { }
                    }

                    @Nested
                    class Recusa {
                        @Test void recusaExpirado() { }
                        @Test void recusaAssinaturaInvalida() { }
                    }

                    @Nested
                    class Configuracao {
                        @Test void exigeSegredo() { }
                    }
                }
                """);
        return java.util.Collections.unmodifiableMap(fontes);
    }
}
