package in.nagarsetu;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Fills an empty database with synthetic reports around Bengaluru so the map, hotspots and
 * dashboard have something to show. Every seeded row has demo=true and is labelled in the UI.
 */
@Component
public class DemoSeeder implements CommandLineRunner {

    private final IssueRepository repo;
    private final boolean enabled;
    private int seq;

    public DemoSeeder(IssueRepository repo, @Value("${app.seed-demo-data}") boolean enabled) {
        this.repo = repo;
        this.enabled = enabled;
    }

    private static final Map<Issue.Category, String[][]> TEMPLATES = Map.of(
            Issue.Category.POTHOLE, new String[][]{
                    {"Deep pothole on main road", "Two-wheelers swerving to avoid it, one bike fell yesterday."},
                    {"Potholes after rain", "Road surface broken in multiple places, water collects inside."},
                    {"Crater near bus stop", "Buses stop in the middle of the road because of the pothole."}},
            Issue.Category.GARBAGE, new String[][]{
                    {"Garbage not collected for a week", "Overflowing bins attracting stray dogs."},
                    {"Illegal dumping on footpath", "Construction debris and plastic dumped overnight."},
                    {"Garbage burning in open plot", "Smoke entering houses every evening."}},
            Issue.Category.STREETLIGHT, new String[][]{
                    {"Streetlight not working", "Entire stretch is dark after 7 pm, unsafe for women walking back."},
                    {"Flickering streetlight with sparking", "Pole wiring is exposed and sparking at night."}},
            Issue.Category.WATER_LEAK, new String[][]{
                    {"Drinking water pipeline leaking", "Clean water flowing onto the road for three days."},
                    {"Burst pipe flooding the lane", "Water pressure in houses has dropped."}},
            Issue.Category.DRAINAGE, new String[][]{
                    {"Blocked storm drain", "Road floods after even light rain, sewage smell."},
                    {"Open drain without cover", "Child almost fell in, needs a slab urgently."}},
            Issue.Category.TRAFFIC_SIGNAL, new String[][]{
                    {"Traffic signal not working", "Junction chaos during peak hours, near accident this morning."}},
            Issue.Category.OTHER, new String[][]{
                    {"Fallen tree branch on footpath", "Pedestrians forced to walk on the road."}});

    private record Area(String name, double lat, double lng, Issue.Category focus, int count) {}

    @Override
    public void run(String... args) {
        if (!enabled || repo.count() > 0) return;
        Random rnd = new Random(42);
        Instant now = Instant.now();
        List<Issue> out = new ArrayList<>();

        // Areas with a concentrated problem -> these should surface as DBSCAN hotspots.
        for (Area a : List.of(
                new Area("Koramangala", 12.9352, 77.6245, Issue.Category.POTHOLE, 7),
                new Area("Whitefield", 12.9698, 77.7500, Issue.Category.GARBAGE, 6),
                new Area("Indiranagar", 12.9784, 77.6408, Issue.Category.STREETLIGHT, 4),
                new Area("HSR Layout", 12.9116, 77.6474, Issue.Category.DRAINAGE, 5))) {
            for (int k = 0; k < a.count; k++) {
                out.add(make(rnd, now, a.focus, a.lat + jitter(rnd, 120), a.lng + jitter(rnd, 120), true));
            }
        }
        // Scattered background reports across the city -> mostly DBSCAN noise.
        Issue.Category[] cats = Issue.Category.values();
        for (int k = 0; k < 28; k++) {
            out.add(make(rnd, now, cats[rnd.nextInt(cats.length)],
                    12.86 + rnd.nextDouble() * 0.20, 77.50 + rnd.nextDouble() * 0.24, false));
        }
        repo.saveAll(out);
    }

    private Issue make(Random rnd, Instant now, Issue.Category cat, double lat, double lng, boolean inCluster) {
        String[][] t = TEMPLATES.get(cat);
        String[] pick = t[rnd.nextInt(t.length)];
        Issue i = new Issue();
        // "0" never appears in real ticket codes, so demo codes cannot collide with them
        i.ticketCode = "NS-0" + String.format("%05d", ++seq);
        i.title = pick[0];
        i.description = pick[1];
        i.category = cat;
        i.latitude = lat;
        i.longitude = lng;
        i.reportCount = 1 + rnd.nextInt(inCluster ? 9 : 4);
        i.createdAt = now.minus(Duration.ofHours(6 + rnd.nextInt(24 * 28)));
        double roll = rnd.nextDouble();
        // Hotspot areas are mostly still active; background issues are more often resolved.
        if (roll < (inCluster ? 0.75 : 0.5)) i.status = Issue.Status.OPEN;
        else if (roll < (inCluster ? 0.95 : 0.7)) i.status = Issue.Status.IN_PROGRESS;
        else {
            i.status = Issue.Status.RESOLVED;
            long open = Duration.between(i.createdAt, now).toHours();
            i.resolvedAt = i.createdAt.plus(Duration.ofHours(4 + rnd.nextLong(Math.max(open - 4, 1))));
        }
        i.updatedAt = i.resolvedAt != null ? i.resolvedAt : i.createdAt;
        i.demo = true;
        return i;
    }

    /** Random offset of up to ~meters, in degrees. */
    private static double jitter(Random rnd, double meters) {
        return (rnd.nextDouble() * 2 - 1) * meters / 111_000.0;
    }
}
