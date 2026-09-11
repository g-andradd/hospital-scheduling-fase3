package br.com.fiap.hospital.notificacao.lembrete;

import java.time.Instant;
import java.util.UUID;

/** Uma consulta da agenda local que a varredura D-1 decidiu lembrar. */
public record CandidatoAoLembrete(
        UUID consultaId, String pacienteNome, String pacienteEmail, String medicoNome,
        Instant dataHora) { }
