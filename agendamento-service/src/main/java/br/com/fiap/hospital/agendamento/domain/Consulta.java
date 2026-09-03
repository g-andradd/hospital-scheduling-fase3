package br.com.fiap.hospital.agendamento.domain;

import br.com.fiap.hospital.agendamento.domain.exception.AgendamentoForaDoHorizonteException;
import br.com.fiap.hospital.agendamento.domain.exception.AgendamentoNoPassadoException;
import br.com.fiap.hospital.agendamento.domain.exception.MotivoDeCancelamentoObrigatorioException;
import br.com.fiap.hospital.agendamento.domain.exception.TransicaoDeStatusInvalidaException;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * Consulta agendada. Concentra as regras que podem ser decididas olhando apenas para a
 * propria consulta.
 *
 * <p>Conflito de agenda fica de fora de proposito: verifica-lo exige olhar para outras
 * consultas, o que e trabalho do caso de uso, que tem a porta de repositorio.
 *
 * <p>Nao ha {@code LocalDateTime.now()} aqui. Todo metodo que precisa saber "agora"
 * recebe o instante de referencia como argumento, e quem o produz a partir de um
 * {@link java.time.Clock} injetado e o caso de uso. E isso que torna as regras de
 * janela temporal testaveis de forma deterministica.
 */
public class Consulta {

    public static final int DURACAO_PADRAO_MINUTOS = 30;

    /**
     * Ate quando o futuro e agendavel.
     *
     * <p>Decisao de produto, nao limite tecnico: agenda hospitalar nao se planeja com
     * mais de dois anos, e um pedido alem disso e quase sempre erro de digitacao no ano.
     * O limite tambem fecha uma classe inteira de falha — sem teto, uma data no ano
     * 999999999 atravessa o dominio e so estoura na aritmetica de data, virando 500.
     */
    public static final int HORIZONTE_MAXIMO_MESES = 24;

    private final UUID id;
    private final UUID pacienteId;
    private UUID medicoId;
    private final UUID registradoPorId;
    private PeriodoConsulta periodo;
    private StatusConsulta status;
    private String observacoes;
    private String motivoCancelamento;
    private final OffsetDateTime criadoEm;
    private OffsetDateTime atualizadoEm;

    private Consulta(
            UUID id,
            UUID pacienteId,
            UUID medicoId,
            UUID registradoPorId,
            PeriodoConsulta periodo,
            StatusConsulta status,
            String observacoes,
            String motivoCancelamento,
            OffsetDateTime criadoEm,
            OffsetDateTime atualizadoEm) {
        this.id = Objects.requireNonNull(id, "O id da consulta e obrigatorio");
        this.pacienteId = Objects.requireNonNull(pacienteId, "O paciente da consulta e obrigatorio");
        this.medicoId = Objects.requireNonNull(medicoId, "O medico da consulta e obrigatorio");
        this.registradoPorId =
                Objects.requireNonNull(registradoPorId, "O registrante da consulta e obrigatorio");
        this.periodo = Objects.requireNonNull(periodo, "O periodo da consulta e obrigatorio");
        this.status = Objects.requireNonNull(status, "O status da consulta e obrigatorio");
        this.observacoes = observacoes;
        this.motivoCancelamento = motivoCancelamento;
        this.criadoEm = Objects.requireNonNull(criadoEm, "A data de criacao e obrigatoria");
        this.atualizadoEm = Objects.requireNonNull(atualizadoEm, "A data de atualizacao e obrigatoria");
    }

    /**
     * Registra uma nova consulta com status {@code AGENDADA}.
     *
     * @throws AgendamentoNoPassadoException se o periodo nao comecar depois de {@code agora}
     */
    public static Consulta agendar(
            UUID id,
            UUID pacienteId,
            UUID medicoId,
            UUID registradoPorId,
            PeriodoConsulta periodo,
            String observacoes,
            OffsetDateTime agora) {
        Objects.requireNonNull(periodo, "O periodo da consulta e obrigatorio");
        Objects.requireNonNull(agora, "O instante de referencia e obrigatorio");
        exigirPeriodoFuturo(periodo, agora);
        return new Consulta(
                id,
                pacienteId,
                medicoId,
                registradoPorId,
                periodo,
                StatusConsulta.AGENDADA,
                normalizar(observacoes),
                null,
                agora,
                agora);
    }

    /** Reconstitui uma consulta ja existente. Usado pelos adaptadores de persistencia. */
    public static Consulta reconstituir(
            UUID id,
            UUID pacienteId,
            UUID medicoId,
            UUID registradoPorId,
            PeriodoConsulta periodo,
            StatusConsulta status,
            String observacoes,
            String motivoCancelamento,
            OffsetDateTime criadoEm,
            OffsetDateTime atualizadoEm) {
        return new Consulta(
                id, pacienteId, medicoId, registradoPorId, periodo, status,
                observacoes, motivoCancelamento, criadoEm, atualizadoEm);
    }

    /**
     * Aplica as validacoes de alteracao <b>sem mutar nada</b>.
     *
     * <p>Existe para que o caso de uso possa validar tudo — inclusive o conflito de
     * agenda, que ele checa por fora — antes de qualquer escrita no agregado. Sem
     * isso, uma alteracao recusada deixaria a consulta ja modificada em memoria, e sob
     * JPA a entidade gerenciada sofreria flush no commit: a alteracao rejeitada seria
     * persistida sem ninguem chamar {@code salvar}.
     *
     * @throws TransicaoDeStatusInvalidaException se a consulta estiver em status terminal
     * @throws AgendamentoNoPassadoException se o novo periodo nao comecar depois de {@code agora}
     */
    public void exigirAlteracaoValida(PeriodoConsulta novoPeriodo, OffsetDateTime agora) {
        Objects.requireNonNull(agora, "O instante de referencia e obrigatorio");
        exigirNaoTerminal("alterar");
        if (novoPeriodo != null && !novoPeriodo.equals(periodo)) {
            exigirPeriodoFuturo(novoPeriodo, agora);
        }
    }

    /**
     * Altera periodo, medico e observacoes.
     *
     * <p>Campo nulo mantem o valor atual — periodo, medico e observacoes. Para
     * <i>apagar</i> as observacoes, passe uma string vazia ou em branco: nulo significa
     * "nao mexa", nao "limpe". Observacao de consulta e registro clinico, e uma
     * remarcacao que nao menciona observacoes nao pode apaga-las.
     *
     * @throws TransicaoDeStatusInvalidaException se a consulta estiver em status terminal
     * @throws AgendamentoNoPassadoException se o novo periodo nao comecar depois de {@code agora}
     */
    public void atualizar(
            PeriodoConsulta novoPeriodo,
            UUID novoMedicoId,
            String novasObservacoes,
            OffsetDateTime agora) {
        exigirAlteracaoValida(novoPeriodo, agora);

        if (novoPeriodo != null && !novoPeriodo.equals(periodo)) {
            this.periodo = novoPeriodo;
        }
        if (novoMedicoId != null) {
            this.medicoId = novoMedicoId;
        }
        if (novasObservacoes != null) {
            this.observacoes = normalizar(novasObservacoes);
        }
        this.atualizadoEm = agora;
    }

    /** Leva a consulta a {@code CONFIRMADA}. */
    public void confirmar(OffsetDateTime agora) {
        transicionarPara(StatusConsulta.CONFIRMADA, agora);
    }

    /** Leva a consulta a {@code REALIZADA}. */
    public void registrarRealizacao(OffsetDateTime agora) {
        transicionarPara(StatusConsulta.REALIZADA, agora);
    }

    /**
     * Leva a consulta a {@code CANCELADA}, registrando o motivo.
     *
     * <p>A legalidade da transicao e conferida antes do motivo: uma consulta terminal
     * nao pode ser cancelada, com ou sem justificativa.
     *
     * @throws TransicaoDeStatusInvalidaException se a transicao nao for permitida
     * @throws MotivoDeCancelamentoObrigatorioException se o motivo for ausente ou em branco
     */
    public void cancelar(String motivo, OffsetDateTime agora) {
        Objects.requireNonNull(agora, "O instante de referencia e obrigatorio");
        exigirTransicaoValida(StatusConsulta.CANCELADA);
        if (motivo == null || motivo.isBlank()) {
            throw new MotivoDeCancelamentoObrigatorioException();
        }
        this.status = StatusConsulta.CANCELADA;
        this.motivoCancelamento = motivo.trim();
        this.atualizadoEm = agora;
    }

    private void transicionarPara(StatusConsulta destino, OffsetDateTime agora) {
        Objects.requireNonNull(agora, "O instante de referencia e obrigatorio");
        exigirTransicaoValida(destino);
        this.status = destino;
        this.atualizadoEm = agora;
    }

    private void exigirTransicaoValida(StatusConsulta destino) {
        if (!status.podeTransicionarPara(destino)) {
            throw new TransicaoDeStatusInvalidaException(status, destino);
        }
    }

    private void exigirNaoTerminal(String operacao) {
        if (status.terminal()) {
            throw new TransicaoDeStatusInvalidaException(
                    "Nao e possivel " + operacao + " uma consulta com status " + status);
        }
    }

    private static void exigirPeriodoFuturo(PeriodoConsulta periodo, OffsetDateTime agora) {
        if (!periodo.comecaDepoisDe(agora)) {
            throw new AgendamentoNoPassadoException(periodo.inicio(), agora);
        }
        exigirDentroDoHorizonte(periodo, agora);
    }

    /**
     * O limite e calculado a partir de {@code agora}, nunca do periodo pedido: somar
     * meses a uma data ja proxima do fim do calendario estouraria a propria aritmetica
     * que esta verificacao existe para evitar.
     */
    private static void exigirDentroDoHorizonte(PeriodoConsulta periodo, OffsetDateTime agora) {
        OffsetDateTime limite = agora.plusMonths(HORIZONTE_MAXIMO_MESES);
        if (periodo.inicio().isAfter(limite)) {
            throw new AgendamentoForaDoHorizonteException(
                    periodo.inicio(), limite, HORIZONTE_MAXIMO_MESES);
        }
    }

    private static String normalizar(String texto) {
        return texto == null || texto.isBlank() ? null : texto.trim();
    }

    public UUID id() {
        return id;
    }

    public UUID pacienteId() {
        return pacienteId;
    }

    public UUID medicoId() {
        return medicoId;
    }

    public UUID registradoPorId() {
        return registradoPorId;
    }

    public PeriodoConsulta periodo() {
        return periodo;
    }

    public StatusConsulta status() {
        return status;
    }

    public String observacoes() {
        return observacoes;
    }

    public String motivoCancelamento() {
        return motivoCancelamento;
    }

    public OffsetDateTime criadoEm() {
        return criadoEm;
    }

    public OffsetDateTime atualizadoEm() {
        return atualizadoEm;
    }

    /** Consulta ativa ocupa a agenda e por isso participa da checagem de conflito. */
    public boolean ativa() {
        return status.ativa();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Consulta outra && id.equals(outra.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "Consulta[id=%s, paciente=%s, medico=%s, inicio=%s, status=%s]"
                .formatted(id, pacienteId, medicoId, periodo.inicio(), status);
    }
}
