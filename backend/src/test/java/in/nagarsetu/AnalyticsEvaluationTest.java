package in.nagarsetu;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Offline evaluation of the duplicate detector and the DBSCAN hotspot detector on a SYNTHETIC
 * benchmark with known ground truth. Numbers are printed so they can be quoted; the asserts are
 * regression guards. Run: ./mvnw test -Dtest=AnalyticsEvaluationTest
 */
class AnalyticsEvaluationTest {

    static final double BASE_LAT = 12.97, BASE_LNG = 77.59;

    /** Per category: two genuinely different problems, each with several citizen phrasings. */
    static final String[][][] PROBLEMS = {
            { // pothole
                    {"Huge pothole on the main road", "Big pothole damaging vehicles", "Deep pothole near signal, bike fell", "Road has a dangerous pothole"},
                    {"Road caved in after pipeline work", "Road surface sinking, cave-in after digging", "Digging left the road caved in and uneven"}},
            { // garbage
                    {"Garbage not collected, bins overflowing", "Overflowing garbage bins for a week", "Bins full, garbage not picked up"},
                    {"Construction debris dumped on footpath", "Debris and waste dumped on footpath overnight", "Someone dumped construction waste on footpath"}},
            { // streetlight
                    {"Streetlight not working, very dark", "Street light off, area is dark at night", "Streetlights not working since Monday"},
                    {"Streetlight pole leaning, about to fall", "Light pole tilted dangerously after storm", "Pole leaning over the footpath, may collapse"}},
    };

    static double[] offset(double lat, double lng, double meters, Random r) {
        double ang = r.nextDouble() * 2 * Math.PI;
        return new double[]{lat + Math.sin(ang) * meters / 111_000.0,
                lng + Math.cos(ang) * meters / (111_000.0 * Math.cos(Math.toRadians(lat)))};
    }

    record Pair(double dist, String a, String b, boolean truth) {}

    static List<Pair> duplicateBenchmark(long seed, int n) {
        Random r = new Random(seed);
        List<Pair> out = new ArrayList<>();
        for (int k = 0; k < n; k++) {
            String[][] cat = PROBLEMS[r.nextInt(PROBLEMS.length)];
            int prob = r.nextInt(2);
            String a = cat[prob][r.nextInt(cat[prob].length)];
            double[] p = {BASE_LAT + r.nextDouble() * 0.1, BASE_LNG + r.nextDouble() * 0.1};
            boolean truth;
            String b;
            double dist;
            switch (r.nextInt(3)) {
                case 0 -> { truth = true; b = cat[prob][r.nextInt(cat[prob].length)]; dist = r.nextDouble() * 80; }   // same problem, GPS noise
                case 1 -> { truth = false; b = cat[1 - prob][r.nextInt(cat[1 - prob].length)]; dist = 10 + r.nextDouble() * 90; } // different problem nearby
                default -> { truth = false; b = cat[prob][r.nextInt(cat[prob].length)]; dist = 120 + r.nextDouble() * 400; } // same problem type, another street
            }
            double[] q = offset(p[0], p[1], dist, r);
            out.add(new Pair(Analytics.haversineMeters(p[0], p[1], q[0], q[1]), a, b, truth));
        }
        return out;
    }

    interface Rule { boolean dup(Pair p); }

    /** Returns {precision, recall, F1}. */
    static double[] score(List<Pair> data, Rule rule) {
        int tp = 0, fp = 0, fn = 0;
        for (Pair p : data) {
            boolean pred = rule.dup(p);
            if (pred && p.truth()) tp++; else if (pred) fp++; else if (p.truth()) fn++;
        }
        double pr = tp + fp == 0 ? 0 : tp / (double) (tp + fp), rc = tp / (double) (tp + fn);
        return new double[]{pr, rc, pr + rc == 0 ? 0 : 2 * pr * rc / (pr + rc)};
    }

    @Test
    void duplicateDetectionAblation() {
        var tune = duplicateBenchmark(7, 2000);     // thresholds were chosen on this split
        var heldOut = duplicateBenchmark(99, 2000); // and are reported on this one
        var rules = new LinkedHashMap<String, Rule>();
        rules.put("A distance only (<=50 m)", p -> p.dist() <= 50);
        rules.put("B distance<=100 + word Jaccard>=0.2, <=30 m always", p -> p.dist() <= 30
                || (p.dist() <= 100 && Analytics.jaccard(Analytics.tokens(p.a()), Analytics.tokens(p.b())) >= 0.2));
        rules.put("C distance<=100 + word Jaccard>=0.2", p -> p.dist() <= 100
                && Analytics.jaccard(Analytics.tokens(p.a()), Analytics.tokens(p.b())) >= 0.2);
        rules.put("D distance<=100 + trigram>=0.25", p -> p.dist() <= 100
                && Analytics.trigramSimilarity(p.a(), p.b()) >= 0.25);
        rules.put("E distance-adaptive max(word,trigram) [prod]", p -> Analytics.isDuplicate(p.dist(),
                Analytics.textSimilarity(p.a(), p.b())));
        System.out.println("DUPLICATES (synthetic, n=2000 per split)            tune P/R/F1        held-out P/R/F1");
        rules.forEach((name, rule) -> {
            double[] t = score(tune, rule), h = score(heldOut, rule);
            System.out.printf("  %-52s %.3f/%.3f/%.3f   %.3f/%.3f/%.3f%n", name, t[0], t[1], t[2], h[0], h[1], h[2]);
        });
        double f1 = score(heldOut, rules.get("E distance-adaptive max(word,trigram) [prod]"))[2];
        assertTrue(f1 > 0.85, "duplicate F1 regressed: " + f1);
    }

    @Test
    void hotspotDetection() {
        double sumPairP = 0, sumPairR = 0, sumFound = 0;
        int runs = 50, trueClusters = 6;
        for (int run = 0; run < runs; run++) {
            Random r = new Random(run);
            List<Analytics.Point> pts = new ArrayList<>();
            List<Integer> truth = new ArrayList<>(); // cluster id, -1 = noise
            for (int c = 0; c < trueClusters; c++) {
                double cLat = BASE_LAT + (c % 3) * 0.04, cLng = BASE_LNG + (c / 3) * 0.04; // ~4.4 km apart
                int size = 4 + r.nextInt(8);
                for (int k = 0; k < size; k++) {
                    double[] q = offset(cLat, cLng, r.nextDouble() * 150, r);
                    pts.add(new Analytics.Point(pts.size(), q[0], q[1]));
                    truth.add(c);
                }
            }
            for (int k = 0; k < 40; k++) { // background noise over ~22 km x 22 km
                pts.add(new Analytics.Point(pts.size(), BASE_LAT - 0.05 + r.nextDouble() * 0.2, BASE_LNG - 0.05 + r.nextDouble() * 0.2));
                truth.add(-1);
            }
            int[] pred = new int[pts.size()];
            Arrays.fill(pred, -1);
            var clusters = Analytics.dbscan(pts, IssueController.HOTSPOT_EPS_M, IssueController.HOTSPOT_MIN_ISSUES);
            for (int c = 0; c < clusters.size(); c++) for (int idx : clusters.get(c)) pred[idx] = c;

            // pair-counting precision/recall over points that belong to some cluster
            long both = 0, predPairs = 0, truePairs = 0;
            for (int i = 0; i < pts.size(); i++)
                for (int j = i + 1; j < pts.size(); j++) {
                    boolean t = truth.get(i) >= 0 && truth.get(i).equals(truth.get(j));
                    boolean p = pred[i] >= 0 && pred[i] == pred[j];
                    if (t) truePairs++;
                    if (p) predPairs++;
                    if (t && p) both++;
                }
            sumPairP += predPairs == 0 ? 0 : both / (double) predPairs;
            sumPairR += both / (double) truePairs;
            sumFound += clusters.size();
        }
        double p = sumPairP / runs, rec = sumPairR / runs;
        System.out.printf("HOTSPOTS    runs=%d  true clusters=%d  avg found=%.2f  pair-precision=%.3f pair-recall=%.3f%n",
                runs, trueClusters, sumFound / runs, p, rec);
        assertTrue(p > 0.8 && rec > 0.8, "hotspot detection regressed");
    }

    @Test
    void priorityIsExplainable() {
        Issue i = new Issue();
        i.title = "Open drain, child almost fell";
        i.category = Issue.Category.DRAINAGE;
        i.reportCount = 5;
        i.createdAt = java.time.Instant.parse("2026-11-01T00:00:00Z");
        var b = Analytics.priorityBreakdown(i, true, java.time.Instant.parse("2026-11-04T00:00:00Z"));
        assertTrue(b.get("urgentKeywords") == 20 && b.get("daysOpen") == 3 && b.get("hotspot") == 10, b.toString());
        assertTrue(Math.abs(Analytics.total(b) - b.values().stream().mapToDouble(Double::doubleValue).sum()) < 0.11);
    }
}
