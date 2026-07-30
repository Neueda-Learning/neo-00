package com.neobank.orchestrator.simulator;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface SimulationRepository extends JpaRepository<Simulation, Long> {

    List<Simulation> findAllByOrderByIdDesc();

    List<Simulation> findByTargetServiceIdOrderByIdDesc(String targetServiceId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Simulation> findFirstByApplicationIdAndReportedAtIsNullOrderByIdAsc(String applicationId);

    Optional<Simulation> findFirstByApplicationIdOrderByIdDesc(String applicationId);

    boolean existsByApplicationId(String applicationId);

    long deleteByTargetServiceId(String targetServiceId);
}
