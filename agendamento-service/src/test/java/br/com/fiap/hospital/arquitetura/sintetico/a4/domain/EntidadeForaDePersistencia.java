package br.com.fiap.hospital.arquitetura.sintetico.a4.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

@Entity
public class EntidadeForaDePersistencia {
    @Id
    private Long id;
}
