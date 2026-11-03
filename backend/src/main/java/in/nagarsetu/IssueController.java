package in.nagarsetu;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api")
public class IssueController {

    static final double HOTSPOT_EPS_M = 250;
    static final int HOTSPOT_MIN_ISSUES = 3;
    private static final List<Issue.Status> ACTIVE = List.of(Issue.Status.OPEN, Issue.Status.IN_PROGRESS);

    private final IssueRepository repo;
    private final byte[] adminKey;
    private final SecureRandom random = new SecureRandom();

    public IssueController(IssueRepository repo, @Value("${app.admin-key}") String adminKey) {
        this.repo = repo;
        this.adminKey = adminKey.getBytes(StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ DTOs

    public record NewReport(
            @NotBlank @Size(max = 120) String title,
            @Size(max = 1000) String description,
            @NotNull Issue.Category category,
            @NotNull @DecimalMin("-90") @DecimalMax("90") Double latitude,
            @NotNull @DecimalMin("-180") @DecimalMax("180") Double longitude) {}

    public record IssueView(long id, String ticketCode, String title, String description,
                            Issue.Category category, Issue.Status status, double latitude, double longitude,
                            int reportCount, Instant createdAt, Instant updatedAt, Instant resolvedAt,
                            boolean demo, boolean inHotspot, double priority, Map<String, Double> priorityBreakdown) {}

    public record ReportResult(boolean merged, double distanceMeters, double textSimilarity, IssueView issue) {}

    public record Hotspot(int id, double latitude, double longitude, double radiusMeters, int issues,
                          int citizenReports, Issue.Category dominantCategory, List<Long> issueIds) {}

    public record StatusChange(@NotNull Issue.Status status) {}

    // ------------------------------------------------------------------ citizen endpoints

    @PostMapping("/issues")
    @Transactional
    public ReportResult report(@Valid @RequestBody NewReport r) {
        // ~100 m bounding box, then exact haversine + text check
        double dLat = Analytics.DUP_MAX_M / 111_000.0;
        double dLng = dLat / Math.max(Math.cos(Math.toRadians(r.latitude())), 0.01);
        String newText = r.title() + " " + Objects.toString(r.description(), "");

        Issue best = null;
        double bestDist = Double.MAX_VALUE, bestSim = 0;
        for (Issue c : repo.findByCategoryAndStatusNotAndLatitudeBetweenAndLongitudeBetween(
                r.category(), Issue.Status.RESOLVED,
                r.latitude() - dLat, r.latitude() + dLat, r.longitude() - dLng, r.longitude() + dLng)) {
            double dist = Analytics.haversineMeters(r.latitude(), r.longitude(), c.latitude, c.longitude);
            double sim = Analytics.textSimilarity(newText, c.title + " " + Objects.toString(c.description, ""));
            if (Analytics.isDuplicate(dist, sim) && dist < bestDist) {
                best = c; bestDist = dist; bestSim = sim;
            }
        }

        Instant now = Instant.now();
        if (best != null) {
            best.reportCount++;
            best.updatedAt = now;
            return new ReportResult(true, round(bestDist), round(bestSim), view(repo.save(best), activeHotspotIds()));
        }

        Issue i = new Issue();
        i.ticketCode = newTicketCode();
        i.title = r.title().strip();
        i.description = r.description() == null ? null : r.description().strip();
        i.category = r.category();
        i.latitude = r.latitude();
        i.longitude = r.longitude();
        i.createdAt = now;
        i.updatedAt = now;
        repo.save(i);
        return new ReportResult(false, 0, 0, view(i, activeHotspotIds()));
    }

    @GetMapping("/issues")
    public List<IssueView> list(@RequestParam(required = false) Issue.Status status,
                                @RequestParam(required = false) Issue.Category category) {
        Set<Long> hot = activeHotspotIds();
        return repo.findAll().stream()
                .filter(i -> status == null || i.status == status)
                .filter(i -> category == null || i.category == category)
                .map(i -> view(i, hot))
                .sorted(Comparator.comparingDouble(IssueView::priority).reversed())
                .toList();
    }

    @GetMapping("/issues/track/{ticketCode}")
    public IssueView track(@PathVariable String ticketCode) {
        return repo.findByTicketCode(ticketCode.trim().toUpperCase(Locale.ROOT))
                .map(i -> view(i, activeHotspotIds()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No issue with that ticket code"));
    }

    @GetMapping("/hotspots")
    public List<Hotspot> hotspots() {
        List<Issue> active = repo.findByStatusIn(ACTIVE);
        var pts = active.stream().map(i -> new Analytics.Point(i.id, i.latitude, i.longitude)).toList();
        var clusters = Analytics.dbscan(pts, HOTSPOT_EPS_M, HOTSPOT_MIN_ISSUES);
        List<Hotspot> out = new ArrayList<>();
        for (int c = 0; c < clusters.size(); c++) {
            List<Issue> members = clusters.get(c).stream().map(active::get).toList();
            double lat = members.stream().mapToDouble(i -> i.latitude).average().orElse(0);
            double lng = members.stream().mapToDouble(i -> i.longitude).average().orElse(0);
            double radius = members.stream()
                    .mapToDouble(i -> Analytics.haversineMeters(lat, lng, i.latitude, i.longitude)).max().orElse(0);
            Issue.Category dominant = members.stream()
                    .collect(Collectors.groupingBy(i -> i.category, Collectors.counting()))
                    .entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow().getKey();
            out.add(new Hotspot(c + 1, lat, lng, round(Math.max(radius, 60)), members.size(),
                    members.stream().mapToInt(i -> i.reportCount).sum(), dominant,
                    members.stream().map(i -> i.id).toList()));
        }
        out.sort(Comparator.comparingInt(Hotspot::citizenReports).reversed());
        return out;
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        List<Issue> all = repo.findAll();
        var byStatus = new EnumMap<Issue.Status, Long>(Issue.Status.class);
        for (var s : Issue.Status.values()) byStatus.put(s, 0L);
        all.forEach(i -> byStatus.merge(i.status, 1L, Long::sum));
        var byCategory = all.stream().collect(Collectors.groupingBy(i -> i.category, () -> new EnumMap<>(Issue.Category.class), Collectors.counting()));
        double avgResolutionHours = all.stream().filter(i -> i.resolvedAt != null)
                .mapToLong(i -> Duration.between(i.createdAt, i.resolvedAt).toHours()).average().orElse(0);
        int citizenReports = all.stream().mapToInt(i -> i.reportCount).sum();

        var m = new LinkedHashMap<String, Object>();
        m.put("totalIssues", all.size());
        m.put("citizenReports", citizenReports);
        m.put("duplicatesMerged", citizenReports - all.size());
        m.put("byStatus", byStatus);
        m.put("byCategory", byCategory);
        m.put("avgResolutionHours", round(avgResolutionHours));
        m.put("activeHotspots", hotspots().size());
        return m;
    }

    // ------------------------------------------------------------------ authority endpoints

    @GetMapping("/admin/verify")
    public Map<String, Boolean> verify(@RequestHeader(value = "X-Admin-Key", required = false) String key) {
        requireAdmin(key);
        return Map.of("ok", true);
    }

    @PatchMapping("/admin/issues/{id}/status")
    @Transactional
    public IssueView changeStatus(@PathVariable long id, @Valid @RequestBody StatusChange body,
                                  @RequestHeader(value = "X-Admin-Key", required = false) String key) {
        requireAdmin(key);
        Issue i = repo.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        Instant now = Instant.now();
        i.status = body.status();
        i.updatedAt = now;
        i.resolvedAt = body.status() == Issue.Status.RESOLVED ? now : null;
        return view(repo.save(i), activeHotspotIds());
    }

    // ------------------------------------------------------------------ helpers

    private void requireAdmin(String key) {
        if (key == null || !MessageDigest.isEqual(adminKey, key.getBytes(StandardCharsets.UTF_8)))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid authority key");
    }

    private Set<Long> activeHotspotIds() {
        return hotspots().stream().flatMap(h -> h.issueIds().stream()).collect(Collectors.toSet());
    }

    private IssueView view(Issue i, Set<Long> hotspotIds) {
        boolean hot = hotspotIds.contains(i.id);
        var breakdown = Analytics.priorityBreakdown(i, hot, Instant.now());
        return new IssueView(i.id, i.ticketCode, i.title, i.description, i.category, i.status, i.latitude,
                i.longitude, i.reportCount, i.createdAt, i.updatedAt, i.resolvedAt, i.demo, hot,
                Analytics.total(breakdown), breakdown);
    }

    private static final char[] ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();

    private String newTicketCode() {
        while (true) {
            var sb = new StringBuilder("NS-");
            for (int k = 0; k < 6; k++) sb.append(ALPHABET[random.nextInt(ALPHABET.length)]);
            if (repo.findByTicketCode(sb.toString()).isEmpty()) return sb.toString();
        }
    }

    private static double round(double v) {
        return Math.round(v * 100) / 100.0;
    }
}
