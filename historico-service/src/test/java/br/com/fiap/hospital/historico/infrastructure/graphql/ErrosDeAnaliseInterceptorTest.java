package br.com.fiap.hospital.historico.infrastructure.graphql;

import static org.assertj.core.api.Assertions.assertThat;

import graphql.ErrorType;
import graphql.GraphQLError;
import graphql.GraphqlErrorBuilder;
import java.lang.reflect.Method;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A sanitizacao do caminho pre-execucao, sem subir aplicacao.
 *
 * <p>O erro nao classificado e o caso dificil de produzir por HTTP: e justamente aquele que
 * ninguem previu. Um teste focado consegue construi-lo diretamente e perguntar o que o
 * interceptor faz — que e a unica pergunta relevante aqui.
 *
 * <p>Mora no pacote de producao de proposito: o interceptor e {@code package-private}, e
 * torna-lo publico so para ser testado ampliaria a superficie do componente por conveniencia
 * do teste.
 */
@DisplayName("Sanitizacao dos erros anteriores a execucao")
class ErrosDeAnaliseInterceptorTest {

    private final ErrosDeAnaliseInterceptor interceptor = new ErrosDeAnaliseInterceptor();

    private GraphQLError normalizar(GraphQLError erro) throws Exception {
        Method metodo = ErrosDeAnaliseInterceptor.class
                .getDeclaredMethod("normalizar", GraphQLError.class);
        metodo.setAccessible(true);
        return (GraphQLError) metodo.invoke(interceptor, erro);
    }

    @Test
    @DisplayName("erro de validacao vira BAD_REQUEST preservando o texto do motor")
    void erroDeValidacaoViraBadRequest() throws Exception {
        GraphQLError entrada = GraphqlErrorBuilder.newError()
                .message("Field 'autorId' is not defined")
                .errorType(ErrorType.ValidationError)
                .build();

        GraphQLError saida = normalizar(entrada);

        assertThat(saida.getExtensions()).containsEntry("code", "BAD_REQUEST");
        assertThat(saida.getMessage()).isEqualTo("Field 'autorId' is not defined");
    }

    @Test
    @DisplayName("erro inesperado antes da execucao sai sanitizado como INTERNAL_ERROR")
    void erroInesperadoAntesDaExecucaoESanitizado() throws Exception {
        String sensivel = "NullPointerException em org.hibernate.internal.SessionImpl: "
                + "select * from consulta_historico where paciente_id = ?";
        GraphQLError entrada = GraphqlErrorBuilder.newError()
                .message(sensivel)
                .errorType(ErrorType.ExecutionAborted)
                .build();

        GraphQLError saida = normalizar(entrada);

        assertThat(saida.getExtensions()).containsEntry("code", "INTERNAL_ERROR");
        assertThat(saida.getMessage())
                .as("a mesma politica do tradutor de resolver vale aqui")
                .isEqualTo(ErroDoHistorico.INTERNAL_ERROR.mensagemPadrao())
                .doesNotContain("consulta_historico", "org.hibernate", "NullPointerException");
    }
}
