package br.com.fiap.hospital.agendamento.domain;

import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;

/**
 * Criterios de listagem, com paginacao.
 *
 * <p>Qualquer criterio nulo ou vazio significa "nao filtrar por isto"; os informados sao
 * combinados com E.
 *
 * <p>E um tipo de dominio, e nao de aplicacao, porque a porta de repositorio o recebe:
 * o adaptador traduz tanto os filtros quanto a paginacao para o banco.
 */
public record FiltroDeConsultas(
        UUID pacienteId,
        UUID medicoId,
        Set<StatusConsulta> status,
        OffsetDateTime de,
        OffsetDateTime ate,
        int pagina,
        int tamanho) {

    /**
     * Teto do tamanho de pagina.
     *
     * <p>Sem ele, um unico {@code size=100000} materializa cem mil linhas como entidades,
     * depois como objetos de dominio, depois como DTOs, e derruba o servico por um
     * parametro de query. O pedido acima do teto e <b>aparado</b>, nao recusado: quem
     * pede muito esta pedindo "o maximo possivel", e o servico responde com o maximo
     * que aguenta.
     */
    public static final int TAMANHO_MAXIMO = 100;

    public static final int TAMANHO_PADRAO = 20;

    public FiltroDeConsultas {
        status = status == null ? Set.of() : Set.copyOf(status);

        // Tamanho e pagina sao limitados no mesmo lugar, mas de formas diferentes de
        // proposito. Tamanho excessivo e pedido razoavel mal calibrado — quem manda
        // size=100000 quer "o maximo possivel", e aparar responde exatamente isso.
        // Pagina impossivel e pedido invalido: nao existe resposta correta para a
        // pagina um bilhao, e aparar inventaria uma. Por isso uma e aparada e a outra
        // recusada.
        tamanho = tamanho <= 0 ? TAMANHO_PADRAO : Math.min(tamanho, TAMANHO_MAXIMO);

        if (pagina < 0) {
            throw new IllegalArgumentException("O numero da pagina nao pode ser negativo: " + pagina);
        }
        // O deslocamento e calculado como pagina * tamanho. Em int, isso transborda em
        // silencio e o banco recebe um offset negativo — 500 por um parametro de query.
        if ((long) pagina * tamanho > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "Pagina fora do intervalo permitido: " + pagina + " com tamanho " + tamanho);
        }
    }

    /** Filtro sem criterios, na primeira pagina, com o tamanho padrao. */
    public static FiltroDeConsultas vazio() {
        return new FiltroDeConsultas(null, null, Set.of(), null, null, 0, TAMANHO_PADRAO);
    }

    public boolean aceita(Consulta consulta) {
        if (pacienteId != null && !pacienteId.equals(consulta.pacienteId())) {
            return false;
        }
        if (medicoId != null && !medicoId.equals(consulta.medicoId())) {
            return false;
        }
        if (!status.isEmpty() && !status.contains(consulta.status())) {
            return false;
        }
        OffsetDateTime inicio = consulta.periodo().inicio();
        if (de != null && inicio.isBefore(de)) {
            return false;
        }
        return ate == null || !inicio.isAfter(ate);
    }
}
