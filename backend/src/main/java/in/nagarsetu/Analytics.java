package in.nagarsetu;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * The three pieces of "intelligence" in NagarSetu, kept as pure functions so they can be
 * evaluated offline (see AnalyticsEvaluationTest):
 *  1. duplicate detection  - geo distance + text similarity
 *  2. explainable priority - additive score with a per-factor breakdown
 *  3. hotspot detection    - DBSCAN with haversine distance
 */
public final class Analytics {

    private Analytics() {}

    // ---------------------------------------------------------------- geo + text

    private static final double EARTH_RADIUS_M = 6_371_000;

    public static double haversineMeters(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * EARTH_RADIUS_M * Math.asin(Math.sqrt(a));
    }

    private static final Set<String> STOPWORDS = Set.of(
            "the", "and", "for", "near", "with", "from", "this", "that", "there", "here", "has", "have",
            "are", "was", "were", "our", "very", "since", "days", "not", "road", "street", "please", "lane");

    public static Set<String> tokens(String text) {
        Set<String> out = new HashSet<>();
        if (text == null) return out;
        for (String t : text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (t.length() < 3 || STOPWORDS.contains(t)) continue;
            // crude stemming: "potholes" ~ "pothole", "leaking" ~ "leak"
            if (t.endsWith("ing") && t.length() > 5) t = t.substring(0, t.length() - 3);
            else if (t.endsWith("es") && t.length() > 4) t = t.substring(0, t.length() - 2);
            else if (t.endsWith("s") && t.length() > 3) t = t.substring(0, t.length() - 1);
            out.add(t);
        }
        return out;
    }

    public static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() && b.isEmpty()) return 0;
        Set<String> inter = new HashSet<>(a);
        inter.retainAll(b);
        return (double) inter.size() / (a.size() + b.size() - inter.size());
    }

    /** Character-trigram Jaccard over the stemmed tokens; robust to "street light" vs "streetlight". */
    public static double trigramSimilarity(String a, String b) {
        return jaccard(trigrams(a), trigrams(b));
    }

    private static Set<String> trigrams(String text) {
        Set<String> out = new HashSet<>();
        for (String t : tokens(text)) {
            String s = "#" + t + "#";
            for (int i = 0; i + 3 <= s.length(); i++) out.add(s.substring(i, i + 3));
        }
        return out;
    }

    // ---------------------------------------------------------------- duplicates

    /** Same-category reports further apart than this are never duplicates. */
    public static final double DUP_MAX_M = 100;
    /** Required text similarity grows linearly with distance: 0.10 at 0 m up to 0.20 at 100 m. */
    public static final double DUP_SIM_BASE = 0.10, DUP_SIM_SLOPE = 0.10;

    /** Thresholds were grid-searched on a synthetic tuning split; see AnalyticsEvaluationTest for the ablation. */
    public static boolean isDuplicate(double distanceM, double textSimilarity) {
        return distanceM <= DUP_MAX_M && textSimilarity >= DUP_SIM_BASE + DUP_SIM_SLOPE * distanceM / DUP_MAX_M;
    }

    /** max(word Jaccard, trigram Jaccard): words catch exact vocabulary, trigrams catch spelling variants. */
    public static double textSimilarity(String a, String b) {
        return Math.max(jaccard(tokens(a), tokens(b)), trigramSimilarity(a, b));
    }

    // ---------------------------------------------------------------- priority

    private static final Map<Issue.Category, Integer> SEVERITY = Map.of(
            Issue.Category.TRAFFIC_SIGNAL, 5,
            Issue.Category.POTHOLE, 4,
            Issue.Category.WATER_LEAK, 4,
            Issue.Category.DRAINAGE, 3,
            Issue.Category.STREETLIGHT, 3,
            Issue.Category.GARBAGE, 2,
            Issue.Category.OTHER, 1);

    private static final List<String> URGENT_WORDS = List.of(
            "accident", "injur", "child", "school", "hospital", "spark", "live wire", "electric",
            "flood", "sewage", "overflow", "danger", "fell", "collapse", "blocked", "ambulance");

    /** Additive, explainable score: every factor is returned so citizens and officials see why. */
    public static LinkedHashMap<String, Double> priorityBreakdown(Issue i, boolean inHotspot, Instant now) {
        var b = new LinkedHashMap<String, Double>();
        b.put("severity", SEVERITY.get(i.category) * 10.0);
        b.put("citizensAffected", round(12 * Math.log(1 + i.reportCount)));
        double days = Duration.between(i.createdAt, i.resolvedAt != null ? i.resolvedAt : now).toHours() / 24.0;
        b.put("daysOpen", round(Math.min(days, 30)));
        String text = (i.title + " " + Objects.toString(i.description, "")).toLowerCase(Locale.ROOT);
        b.put("urgentKeywords", URGENT_WORDS.stream().anyMatch(text::contains) ? 20.0 : 0.0);
        b.put("hotspot", inHotspot ? 10.0 : 0.0);
        return b;
    }

    public static double total(Map<String, Double> breakdown) {
        return round(breakdown.values().stream().mapToDouble(Double::doubleValue).sum());
    }

    private static double round(double v) {
        return Math.round(v * 10) / 10.0;
    }

    // ---------------------------------------------------------------- hotspots

    public record Point(long id, double lat, double lng) {}

    /**
     * Classic DBSCAN. Returns clusters as lists of point indexes; noise is dropped.
     * ponytail: O(n^2) neighbour search, fine for a few thousand open issues; use a geohash grid or PostGIS beyond that.
     */
    public static List<List<Integer>> dbscan(List<Point> pts, double epsMeters, int minPts) {
        int n = pts.size();
        int[] label = new int[n]; // 0 = unvisited, -1 = noise, >0 = cluster id
        int clusterId = 0;
        List<List<Integer>> clusters = new ArrayList<>();
        for (int p = 0; p < n; p++) {
            if (label[p] != 0) continue;
            List<Integer> seeds = neighbours(pts, p, epsMeters);
            if (seeds.size() < minPts) { label[p] = -1; continue; }
            clusterId++;
            List<Integer> members = new ArrayList<>();
            Deque<Integer> queue = new ArrayDeque<>(seeds);
            label[p] = clusterId;
            members.add(p);
            while (!queue.isEmpty()) {
                int q = queue.poll();
                if (label[q] == -1) { label[q] = clusterId; members.add(q); }
                if (label[q] != 0) continue;
                label[q] = clusterId;
                members.add(q);
                List<Integer> qn = neighbours(pts, q, epsMeters);
                if (qn.size() >= minPts) queue.addAll(qn);
            }
            clusters.add(members);
        }
        return clusters;
    }

    private static List<Integer> neighbours(List<Point> pts, int p, double eps) {
        List<Integer> out = new ArrayList<>();
        Point a = pts.get(p);
        for (int i = 0; i < pts.size(); i++) {
            Point b = pts.get(i);
            if (haversineMeters(a.lat, a.lng, b.lat, b.lng) <= eps) out.add(i);
        }
        return out;
    }
}
