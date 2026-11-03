package in.nagarsetu;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "issues", indexes = {
        @Index(name = "idx_issue_geo", columnList = "latitude,longitude"),
        @Index(name = "idx_issue_ticket", columnList = "ticketCode", unique = true)
})
public class Issue {

    public enum Category { POTHOLE, GARBAGE, STREETLIGHT, WATER_LEAK, DRAINAGE, TRAFFIC_SIGNAL, OTHER }

    public enum Status { OPEN, IN_PROGRESS, RESOLVED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(nullable = false, length = 12)
    public String ticketCode;

    @Column(nullable = false, length = 120)
    public String title;

    @Column(length = 1000)
    public String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    public Category category;

    public double latitude;
    public double longitude;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    public Status status = Status.OPEN;

    /** Number of citizens who reported this same problem (1 + merged duplicates). */
    public int reportCount = 1;

    @Column(nullable = false)
    public Instant createdAt;
    public Instant updatedAt;
    public Instant resolvedAt;

    public boolean demo;
}
