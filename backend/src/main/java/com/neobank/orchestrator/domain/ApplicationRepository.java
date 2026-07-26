package com.neobank.orchestrator.domain;

import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ApplicationRepository extends JpaRepository<Application, String> {

    List<Application> findAllByOrderByCreatedAtDesc(Limit limit);

    List<Application> findByOverallStatus(String overallStatus);

    long countByOverallStatus(String overallStatus);
}
