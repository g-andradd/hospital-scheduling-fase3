package br.com.fiap.hospital.agendamento.arquitetura;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.fiap.hospital.agendamento.AgendamentoApplication;
import br.com.fiap.hospital.agendamento.infrastructure.web.AutenticacaoController;
import com.tngtech.archunit.core.domain.JavaClasses;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Fronteiras da Clean Architecture do agendamento, verificadas no build (RNF-05, ADR-007).
 *
 * <p>Metodos {@code @Test} comuns chamando {@code check}, e nao o engine proprio do ArchUnit:
 * a auditoria do M10 so reconhece as anotacoes de teste do JUnit (D2).
 */
@DisplayName("Arquitetura do agendamento")
class ArquiteturaDoAgendamentoTest {

    private static final String BASE = "br.com.fiap.hospital.agendamento";
    private static final JavaClasses CODIGO_PRINCIPAL = RegrasDeArquitetura.importarCodigoPrincipal(BASE);

    private final RegrasDeArquitetura regras = new RegrasDeArquitetura(BASE);

    @Test
    @DisplayName("importa o código principal compilado, sem as classes de teste")
    void importaSoOCodigoPrincipal() {
        assertThat(CODIGO_PRINCIPAL.contain(AgendamentoApplication.class)).isTrue();
        assertThat(CODIGO_PRINCIPAL.contain(AutenticacaoController.class)).isTrue();
        assertThat(CODIGO_PRINCIPAL.contain(ArquiteturaDoAgendamentoTest.class)).isFalse();
    }

    @Test
    @DisplayName("A1: domain e application respeitam a direção das camadas")
    void direcaoDasCamadas() {
        regras.direcaoDasCamadas().check(CODIGO_PRINCIPAL);
    }

    @Test
    @DisplayName("A2: domain não depende de Spring, JPA, Jackson ou Validation")
    void dominioSemFramework() {
        regras.dominioSemFramework().check(CODIGO_PRINCIPAL);
    }

    @Test
    @DisplayName("A3: cada caso de uso declara somente executar como método público de instância")
    void umMetodoPublicoPorCasoDeUso() {
        regras.umMetodoPublicoPorCasoDeUso().check(CODIGO_PRINCIPAL);
    }

    @Test
    @DisplayName("A4: entidades JPA só em infrastructure.persistence")
    void entidadesSoEmPersistencia() {
        regras.entidadesSoEmPersistencia().check(CODIGO_PRINCIPAL);
    }

    @Test
    @DisplayName("A5: controllers passam pelos casos de uso transacionais")
    void controladoresPassamPeloCasoDeUsoTransacional() {
        regras.controladoresPassamPeloCasoDeUsoTransacional().check(CODIGO_PRINCIPAL);
    }

    @Test
    @DisplayName("A6: JwtService só no AutenticacaoController")
    void emissorDeTokenSoNaAutenticacao() {
        regras.emissorDeTokenSoNaAutenticacao().check(CODIGO_PRINCIPAL);
    }

    @Test
    @DisplayName("A7: nenhum acesso à saída padrão ou de erro")
    void semSaidaPadrao() {
        regras.semSaidaPadrao().check(CODIGO_PRINCIPAL);
    }
}
