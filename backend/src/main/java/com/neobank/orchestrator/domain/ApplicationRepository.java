package com.neobank.orchestrator.domain;

import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ApplicationRepository extends JpaRepository<Application, String> {

    List<Application> findAllByOrderByCreatedAtDesc(Limit limit);

    /**
     * Backs {@code GET /api/v1/applications?name=} — the operator's name search.
     *
     * <p>Matches on the denormalised {@code applicant_name} column rather than reaching into the
     * stored payload, which is why that column is carried on the row at all.</p>
     */
    List<Application> findByApplicantNameContainingIgnoreCaseOrderByCreatedAtDesc(
            String name, Limit limit);

    List<Application> findByOverallStatus(String overallStatus);

    long countByOverallStatus(String overallStatus);
}
