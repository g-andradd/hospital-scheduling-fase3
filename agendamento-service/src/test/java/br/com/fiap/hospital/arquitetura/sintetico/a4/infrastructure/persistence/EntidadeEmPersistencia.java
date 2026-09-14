package br.com.fiap.hospital.arquitetura.sintetico.a4.infrastructure.persistence;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

@Entity
public class EntidadeEmPersistencia {
    @Id
    private Long id;
}
