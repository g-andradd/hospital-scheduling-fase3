package br.com.fiap.hospital.historico.infrastructure.graphql;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Recusa na fronteira o corpo que o endpoint GraphQL nao consegue sequer interpretar.
 *
 * <p>Um corpo JSON valido que nao seja objeto — {@code null}, um array, um escalar — passa
 * pela desserializacao e chega ao handler como {@code null}. Ali ele estoura em
 * {@code NullPointerException} dentro da biblioteca, e o container responde <b>500</b>, com
 * a pagina de erro padrao: sem codigo estavel, sem corpo GraphQL, e com a aparencia de
 * defeito de servidor para uma requisicao malformada do cliente.
 *
 * <p>A recusa acontece <b>antes</b> de qualquer execucao. O filtro le o corpo uma unica vez
 * e o repassa adiante intacto, entao nada muda para as requisicoes bem formadas; e ele so
 * decide quando o JSON e valido e nao e objeto, ou quando nao ha corpo algum. JSON
 * malformado continua seguindo o caminho normal, que ja responde requisicao invalida.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 100)
class FronteiraDoCorpoGraphql extends OncePerRequestFilter {

    private static final String CAMINHO = "/graphql";

    private static final String CORPO_DE_RECUSA = """
            {"errors":[{"message":"Requisicao invalida.",\
            "extensions":{"code":"BAD_REQUEST","classification":"BAD_REQUEST"}}],"data":null}""";

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    protected boolean shouldNotFilter(HttpServletRequest requisicao) {
        return !HttpMethod.POST.matches(requisicao.getMethod())
                || !CAMINHO.equals(requisicao.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest requisicao, HttpServletResponse resposta,
                                    FilterChain cadeia) throws ServletException, IOException {

        byte[] corpo = StreamUtils.copyToByteArray(requisicao.getInputStream());

        if (recusavel(corpo)) {
            resposta.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            resposta.setContentType(MediaType.APPLICATION_JSON_VALUE);
            resposta.getWriter().write(CORPO_DE_RECUSA);
            return;
        }
        cadeia.doFilter(new RequisicaoComCorpoLido(requisicao, corpo), resposta);
    }

    /** Só o que a execucao nao consegue interpretar como requisicao GraphQL. */
    private boolean recusavel(byte[] corpo) {
        String texto = new String(corpo, StandardCharsets.UTF_8);
        if (texto.isBlank()) {
            return true;
        }
        try {
            JsonNode arvore = mapper.readTree(texto);
            return arvore == null || !arvore.isObject();
        } catch (IOException malformado) {
            // JSON invalido ja e tratado adiante, com codigo estavel: nao e deste filtro.
            return false;
        }
    }

    /** Devolve adiante exatamente os bytes lidos: nada e consumido do cliente seguinte. */
    private static final class RequisicaoComCorpoLido extends HttpServletRequestWrapper {

        private final byte[] corpo;

        private RequisicaoComCorpoLido(HttpServletRequest original, byte[] corpo) {
            super(original);
            this.corpo = corpo;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream fonte = new ByteArrayInputStream(corpo);
            return new ServletInputStream() {
                @Override public int read() { return fonte.read(); }
                @Override public boolean isFinished() { return fonte.available() == 0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(ReadListener listener) { }
            };
        }

        @Override
        public java.io.BufferedReader getReader() {
            return new java.io.BufferedReader(
                    new java.io.InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
    }
}
