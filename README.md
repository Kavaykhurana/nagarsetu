# NagarSetu: civic issue intelligence

> One report. Merged, ranked and fixed in the open.

**Track:** Smart Cities & Social Impact · THREWS ODYSSEY 2026

NagarSetu ("bridge to the city") is a civic issue platform. Citizens drop a pin and describe a problem such as a
pothole, overflowing garbage, a dead streetlight, a leaking pipe, a blocked drain or a broken signal. The backend then does three things:

1. **Duplicate detection.** If someone has already reported the same problem nearby, the new report is merged
   into it instead of creating a second ticket. The existing issue's citizen count goes up, which raises its priority.
2. **Explainable priority.** Every issue gets an additive score, and each factor is shown to citizens and officials.
3. **Hotspot detection.** DBSCAN clusters active issues so the city can send one crew to fix many complaints at once.

Citizens get a ticket code to track progress. Authorities get a priority-ranked work queue.

## Tech stack

| Layer | Choice |
|---|---|
| Backend | Java 21, Spring Boot 4.1 (Web MVC, Data JPA, Validation, Actuator) |
| Database | PostgreSQL (Render managed Postgres) |
| Frontend | React 19, Vite 8, Tailwind CSS 4, Leaflet / react-leaflet, OpenStreetMap tiles |
| Deploy | Single Docker image on Render: Spring serves the built React app and the `/api` |

## How it works

### 1. Duplicate detection (`Analytics.isDuplicate`)
For a new report, a ~100 m bounding box query fetches open issues of the **same category**. For each candidate:

- `distance` = haversine metres
- `textSimilarity` = max(word Jaccard, character-trigram Jaccard) over stop-word-filtered, lightly stemmed tokens
- **duplicate if** `distance ≤ 100 m` **and** `textSimilarity ≥ 0.10 + 0.10 · distance/100`

The required similarity grows with distance. Two reports 5 m apart need very little textual overlap, while two reports
90 m apart must clearly describe the same thing. The closest matching candidate wins.

### 2. Priority score (`Analytics.priorityBreakdown`)
```
priority = 10·severity(category)        // traffic signal 5 … other 1
         + 12·ln(1 + citizensAffected)  // diminishing returns, so 50 duplicate reports can't drown everything else
         + min(daysOpen, 30)            // ageing
         + 20 if safety keywords        // accident, child, school, hospital, sparking, flood, sewage…
         + 10 if inside a hotspot
```
The API returns every term, and the UI draws it as a stacked bar ("why it ranks where it does").

### 3. Hotspots (`Analytics.dbscan`)
Classic DBSCAN with haversine distance, ε = 250 m, minPts = 3, over OPEN and IN_PROGRESS issues. Each cluster
reports its centroid, radius, issue count, total citizen reports and dominant category.

## Evaluation

Run with `cd backend && ./mvnw test -Dtest=AnalyticsEvaluationTest`. Both benchmarks are **synthetic** with known
ground truth, generated in the test itself. They measure the algorithms against controlled scenarios, not real-world
city data.

**Duplicate detection.** Each split has 2,000 report pairs, evenly mixed across three cases: (a) same problem with
paraphrased text and 0–80 m GPS noise (positive), (b) a *different* problem of the same category 10–100 m away (negative),
(c) the same kind of problem on another street 120–520 m away (negative). Thresholds were grid-searched on the tuning
split (seed 7) and are reported on a held-out split (seed 99).

| Rule | Tune P / R / F1 | Held-out P / R / F1 |
|---|---|---|
| A. distance only (≤ 50 m) | 0.548 / 0.584 / 0.566 | 0.586 / 0.629 / 0.606 |
| B. ≤ 30 m always, else ≤ 100 m + word Jaccard ≥ 0.2 | 0.772 / 0.763 / 0.767 | 0.797 / 0.797 / 0.797 |
| C. ≤ 100 m + word Jaccard ≥ 0.2 | 1.000 / 0.643 / 0.783 | 1.000 / 0.660 / 0.795 |
| D. ≤ 100 m + trigram ≥ 0.25 | 0.912 / 0.650 / 0.759 | 0.899 / 0.655 / 0.758 |
| **E. distance-adaptive max(word, trigram), used in production** | **0.927 / 0.930 / 0.928** | **0.917 / 0.917 / 0.917** |

Takeaway: location alone is a poor duplicate signal (F1 0.61), because neighbours report *different* problems.
Adding text similarity with a threshold that tightens with distance reaches F1 0.92 on held-out data.

**Hotspot detection.** 50 random runs, each with 6 true clusters (4–11 issues within 150 m) plus 40 uniform noise
issues across ~22 km × 22 km. DBSCAN found 6.00 clusters on average, with pair-counting precision **0.992** and recall **1.000**.

**Limitations.** The benchmarks are synthetic. The English-only bag-of-words similarity won't catch cross-language
reports (Hindi/Kannada) or pure synonyms. Neighbour search is O(n²), which is fine for thousands of active issues; PostGIS or a
geohash grid would be needed beyond that.

## Run locally

Requirements: Java 21+, Node 20+, PostgreSQL.

```bash
createdb nagarsetu
cd backend && DB_USERNAME=$USER DB_PASSWORD= ./mvnw spring-boot:run     # API on :8080, seeds demo data
cd frontend && npm install && npm run dev                                # UI on :5173, proxies /api
```

| Env var | Default | Meaning |
|---|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5432/nagarsetu` | JDBC URL |
| `DB_USERNAME` / `DB_PASSWORD` | `postgres` / `postgres` | DB credentials |
| `ADMIN_KEY` | `demo-admin` | Key for the authority dashboard. **Change in production.** |
| `SEED_DEMO_DATA` | `true` | Seeds ~50 synthetic Bengaluru reports (flagged `demo`) into an empty DB |

## API

| Method | Path | Purpose |
|---|---|---|
| POST | `/api/issues` | Report an issue → `{merged, distanceMeters, textSimilarity, issue}` |
| GET | `/api/issues?status=&category=` | Issues sorted by priority, each with its breakdown |
| GET | `/api/issues/track/{ticketCode}` | Citizen ticket lookup |
| GET | `/api/hotspots` | DBSCAN clusters |
| GET | `/api/stats` | Totals, duplicates merged, average resolution time |
| PATCH | `/api/admin/issues/{id}/status` | Authority status change (header `X-Admin-Key`) |

## Data & third-party resources

- Demo reports are **synthetic**, generated by `DemoSeeder` and labelled "demo" in the UI. No real complaint data is used.
- Map tiles © [OpenStreetMap](https://www.openstreetmap.org/copyright) contributors (ODbL).
- Open-source libraries: Spring Boot, Hibernate, PostgreSQL JDBC, React, Vite, Tailwind CSS, Leaflet, react-leaflet.
- Fonts: Inter, Source Serif 4, JetBrains Mono (Google Fonts, OFL).
- AI coding assistance (Claude) was used during development, as permitted by the hackathon rules.
