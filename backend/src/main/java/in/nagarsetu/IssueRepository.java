package in.nagarsetu;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface IssueRepository extends JpaRepository<Issue, Long> {

    Optional<Issue> findByTicketCode(String ticketCode);

    List<Issue> findByStatusIn(Collection<Issue.Status> statuses);

    /** Bounding-box prefilter for duplicate detection; exact distance is checked in Java. */
    List<Issue> findByCategoryAndStatusNotAndLatitudeBetweenAndLongitudeBetween(
            Issue.Category category, Issue.Status status,
            double minLat, double maxLat, double minLng, double maxLng);
}
