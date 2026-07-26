package com.neobank.orchestrator.domain;

import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/**
 * Reads over the append-only log. There are no update or delete methods on purpose —
 * {@link JpaRepository} inherits some, and nothing in this application calls them.
 */
public interface ApplicationEventRepository extends JpaRepository<ApplicationEvent, Long> {

    List<ApplicationEvent> findByApplicationIdOrderByIdAsc(String applicationId);

    List<ApplicationEvent> findByServiceIdOrderByIdDesc(String serviceId, Limit limit);

    List<ApplicationEvent> findAllByOrderByIdDesc(Limit limit);

    List<ApplicationEvent> findByApplicationIdInOrderByIdAsc(List<String> applicationIds);

    /**
     * Per-service tallies for the summary screen, counted in the database rather than by
     * loading every event: how many callbacks of each status each service has sent.
     */
    @Query("""
            select e.serviceId, e.status, count(e)
            from ApplicationEvent e
            where e.eventType = 'CALLBACK'
            group by e.serviceId, e.status
            """)
    List<Object[]> countCallbacksByServiceAndStatus();
}
