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

    /**
     * Everything one customer has, newest first — the whole of their own screen, in one read.
     *
     * <p>On the denormalised {@code customer_id} column, for the same reason the name search is:
     * a customer's home must not cost a JSON parse per row, nor a call to any module.</p>
     */
    List<Application> findByCustomerIdOrderByCreatedAtDesc(String customerId, Limit limit);

    List<Application> findByOverallStatus(String overallStatus);

    long countByOverallStatus(String overallStatus);
}
