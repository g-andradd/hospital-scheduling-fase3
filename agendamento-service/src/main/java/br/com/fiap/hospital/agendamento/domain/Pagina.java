package br.com.fiap.hospital.agendamento.domain;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * Uma fatia de um resultado maior, com o suficiente para o chamador navegar.
 *
 * <p>Deliberadamente nao e {@code org.springframework.data.domain.Page}: aquele tipo e
 * do Spring e nao entra em {@code domain} nem em {@code application}. A traducao entre
 * os dois acontece na borda web.
 *
 * @param conteudo elementos desta pagina
 * @param pagina numero da pagina, base zero
 * @param tamanho tamanho efetivamente aplicado, que pode ser menor que o pedido
 * @param total quantidade de elementos que satisfazem o filtro, em todas as paginas
 */
public record Pagina<T>(List<T> conteudo, int pagina, int tamanho, long total) {

    public Pagina {
        Objects.requireNonNull(conteudo, "O conteudo da pagina e obrigatorio");
        conteudo = List.copyOf(conteudo);
        if (pagina < 0) {
            throw new IllegalArgumentException("O numero da pagina nao pode ser negativo: " + pagina);
        }
        if (tamanho <= 0) {
            throw new IllegalArgumentException("O tamanho da pagina deve ser positivo: " + tamanho);
        }
        if (total < 0) {
            throw new IllegalArgumentException("O total nao pode ser negativo: " + total);
        }
    }

    public static <T> Pagina<T> vazia(int pagina, int tamanho) {
        return new Pagina<>(List.of(), pagina, tamanho, 0);
    }

    /** Converte os elementos preservando os metadados de paginacao. */
    public <R> Pagina<R> mapear(Function<T, R> conversao) {
        return new Pagina<>(conteudo.stream().map(conversao).toList(), pagina, tamanho, total);
    }

    public int totalDePaginas() {
        return (int) Math.ceil((double) total / tamanho);
    }

    public boolean vazia() {
        return conteudo.isEmpty();
    }
}
