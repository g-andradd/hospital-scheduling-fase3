package br.com.fiap.hospital.agendamento.infrastructure.web;

import br.com.fiap.hospital.agendamento.application.AgendarConsultaCommand;
import br.com.fiap.hospital.agendamento.application.AtualizarConsultaCommand;
import br.com.fiap.hospital.agendamento.application.CancelarConsultaCommand;
import br.com.fiap.hospital.agendamento.application.ConsultaResumo;
import br.com.fiap.hospital.agendamento.application.ListarConsultasQuery;
import br.com.fiap.hospital.agendamento.domain.FiltroDeConsultas;
import br.com.fiap.hospital.agendamento.domain.StatusConsulta;
import br.com.fiap.hospital.agendamento.infrastructure.transacao.AgendarConsultaUseCaseTransacional;
import br.com.fiap.hospital.agendamento.infrastructure.transacao.AtualizarConsultaUseCaseTransacional;
import br.com.fiap.hospital.agendamento.infrastructure.transacao.BuscarConsultaPorIdUseCaseTransacional;
import br.com.fiap.hospital.agendamento.infrastructure.transacao.CancelarConsultaUseCaseTransacional;
import br.com.fiap.hospital.agendamento.infrastructure.transacao.ConfirmarConsultaUseCaseTransacional;
import br.com.fiap.hospital.agendamento.infrastructure.transacao.ListarConsultasUseCaseTransacional;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoints de consulta.
 *
 * <p>Depende dos decoradores {@code *Transacional}, e nao dos casos de uso nus. Isso nao
 * e so convencao: os casos de uso deixaram de ser beans, entao injetar o tipo nu aqui
 * faria a aplicacao falhar na subida, em vez de rodar sem transacao em silencio.
 *
 * <p>Cada endpoint declara a permissao de perfil conforme a matriz de
 * docs/02-especificacao-funcional.md secao 3. A regra de propriedade — paciente so
 * alcanca o que e dele — nao mora aqui: ela viaja no {@code SolicitanteAutenticado} que
 * os casos de uso exigem, e por isso nao pode ser esquecida por omissao.
 */
@RestController
@RequestMapping("/api/v1/consultas")
@Tag(name = "Consultas", description = "Agendamento e ciclo de vida de consultas")
public class ConsultaController {

    private final AgendarConsultaUseCaseTransacional agendar;
    private final AtualizarConsultaUseCaseTransacional atualizar;
    private final ConfirmarConsultaUseCaseTransacional confirmar;
    private final CancelarConsultaUseCaseTransacional cancelar;
    private final BuscarConsultaPorIdUseCaseTransacional buscar;
    private final ListarConsultasUseCaseTransacional listar;

    public ConsultaController(
            AgendarConsultaUseCaseTransacional agendar,
            AtualizarConsultaUseCaseTransacional atualizar,
            ConfirmarConsultaUseCaseTransacional confirmar,
            CancelarConsultaUseCaseTransacional cancelar,
            BuscarConsultaPorIdUseCaseTransacional buscar,
            ListarConsultasUseCaseTransacional listar) {
        this.agendar = agendar;
        this.atualizar = atualizar;
        this.confirmar = confirmar;
        this.cancelar = cancelar;
        this.buscar = buscar;
        this.listar = listar;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('MEDICO', 'ENFERMEIRO')")
    @Operation(summary = "Registra uma nova consulta")
    public ResponseEntity<ConsultaResponse> registrar(
            @Valid @RequestBody RegistrarConsultaRequest requisicao) {

        ConsultaResumo criada = agendar.executar(new AgendarConsultaCommand(
                requisicao.pacienteId(),
                requisicao.medicoId(),
                requisicao.registradoPorId(),
                requisicao.dataHora(),
                requisicao.duracaoMinutos(),
                requisicao.observacoes()));

        return ResponseEntity
                .created(URI.create("/api/v1/consultas/" + criada.id()))
                .body(ConsultaResponse.de(criada));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('MEDICO', 'ENFERMEIRO')")
    @Operation(
            summary = "Altera uma consulta existente",
            description = "Campo ausente PRESERVA o valor atual. Para apagar as observacoes, "
                    + "envie string vazia; nulo significa 'nao mexa'. E semantica de atualizacao "
                    + "parcial sob o verbo PUT, deliberada: exigir o corpo completo faria uma "
                    + "remarcacao que nao reenvia observacoes apagar registro clinico.")
    public ConsultaResponse alterar(
            @PathVariable UUID id, @Valid @RequestBody AtualizarConsultaRequest requisicao) {

        return ConsultaResponse.de(atualizar.executar(new AtualizarConsultaCommand(
                id,
                requisicao.dataHora(),
                requisicao.duracaoMinutos(),
                requisicao.medicoId(),
                requisicao.observacoes())));
    }

    @PatchMapping("/{id}/confirmar")
    @PreAuthorize("hasAnyRole('MEDICO', 'ENFERMEIRO', 'PACIENTE')")
    @Operation(summary = "Confirma uma consulta agendada")
    public ConsultaResponse confirmar(@PathVariable UUID id) {
        return ConsultaResponse.de(confirmar.executar(id, SolicitanteDaRequisicao.atual()));
    }

    @PatchMapping("/{id}/cancelar")
    @PreAuthorize("hasAnyRole('MEDICO', 'ENFERMEIRO')")
    @Operation(summary = "Cancela uma consulta, com motivo obrigatorio")
    public ConsultaResponse cancelar(
            @PathVariable UUID id, @Valid @RequestBody CancelarConsultaRequest requisicao) {
        return ConsultaResponse.de(
                cancelar.executar(new CancelarConsultaCommand(id, requisicao.motivo())));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('MEDICO', 'ENFERMEIRO', 'PACIENTE')")
    @Operation(summary = "Recupera uma consulta pelo identificador")
    public ConsultaResponse porId(@PathVariable UUID id) {
        return ConsultaResponse.de(buscar.executar(id, SolicitanteDaRequisicao.atual()));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('MEDICO', 'ENFERMEIRO', 'PACIENTE')")
    @Operation(
            summary = "Lista consultas, paginado",
            description = "Filtros opcionais combinados com E. O tamanho de pagina tem teto de "
                    + FiltroDeConsultas.TAMANHO_MAXIMO + "; pedido maior e aparado, nao recusado, "
                    + "e o campo 'tamanho' da resposta informa o valor aplicado.")
    public PaginaResponse<ConsultaResponse> listar(
            @RequestParam(required = false) UUID pacienteId,
            @RequestParam(required = false) UUID medicoId,
            @RequestParam(required = false) Set<StatusConsulta> status,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime de,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime ate,
            @RequestParam(defaultValue = "0") int pagina,
            @RequestParam(defaultValue = "20") int tamanho) {

        return PaginaResponse.de(
                listar.executar(
                        new ListarConsultasQuery(
                                pacienteId, medicoId, status, de, ate, pagina, tamanho),
                        SolicitanteDaRequisicao.atual()),
                ConsultaResponse::de);
    }
}
