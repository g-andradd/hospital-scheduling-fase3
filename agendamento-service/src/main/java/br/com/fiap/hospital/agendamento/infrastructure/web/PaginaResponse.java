package br.com.fiap.hospital.agendamento.infrastructure.web;

import br.com.fiap.hospital.agendamento.domain.Pagina;
import java.util.List;
import java.util.function.Function;

/**
 * Corpo de uma listagem paginada.
 *
 * <p>{@code tamanho} e o efetivamente aplicado, que pode ser menor que o pedido quando o
 * teto entra em acao. Devolve-lo e o que impede o corte de ser silencioso.
 */
public record PaginaResponse<T>(
        List<T> conteudo,
        int pagina,
        int tamanho,
        long total,
        int totalDePaginas) {

    public static <D, R> PaginaResponse<R> de(Pagina<D> pagina, Function<D, R> conversao) {
        return new PaginaResponse<>(
                pagina.conteudo().stream().map(conversao).toList(),
                pagina.pagina(),
                pagina.tamanho(),
                pagina.total(),
                pagina.totalDePaginas());
    }
}
