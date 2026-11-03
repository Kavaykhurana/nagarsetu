<div align="center">

# NagarSetu

**Civic issue intelligence: one report, merged, ranked and fixed in the open.**

[Live demo](https://nagarsetu-kqe7.onrender.com) · [Method](#method) · [Evaluation](#evaluation) · [API](#api-reference) · [Run locally](#getting-started)

![Java](https://img.shields.io/badge/Java-21-3d3d3a?style=flat-square)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.1-3d3d3a?style=flat-square)
![React](https://img.shields.io/badge/React-19-3d3d3a?style=flat-square)
![Tailwind CSS](https://img.shields.io/badge/Tailwind%20CSS-4-3d3d3a?style=flat-square)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-3d3d3a?style=flat-square)
![Deployed on Render](https://img.shields.io/badge/Deployed%20on-Render-3d3d3a?style=flat-square)

*THREWS ODYSSEY 2026 · Track: Smart Cities & Social Impact*

</div>

---

## Contents

1. [The problem](#the-problem)
2. [The solution](#the-solution)
3. [Key features](#key-features)
4. [Architecture](#architecture)
5. [Method](#method)
6. [Evaluation](#evaluation)
7. [Getting started](#getting-started)
8. [Configuration](#configuration)
9. [API reference](#api-reference)
10. [Project structure](#project-structure)
11. [Limitations and future work](#limitations-and-future-work)
12. [Acknowledgements](#acknowledgements)

---

## The problem

Indian cities receive civic complaints through many channels: helplines, apps, social media and ward offices. Three
failures repeat everywhere:

- **Duplicate noise.** One pothole on a busy road is reported by dozens of people. Each report becomes a separate
  ticket, which inflates backlogs and hides how many people the problem really affects.
- **Opaque prioritisation.** Citizens cannot see why one complaint is handled before another, and officials rank
  work by hand or by arrival order.
- **No spatial view.** Problems cluster: a failing drain line causes floods and potholes along a whole stretch. Treating
  each ticket in isolation sends several crews where one visit would do.

The result is wasted crew time, slower repairs for the most dangerous issues, and falling public trust in reporting.

## The solution

NagarSetu ("bridge to the city") puts an analysis layer between citizens and the municipal work queue:

| Stage | What happens |
|---|---|
| **Report** | A citizen picks a category, describes the issue and drops a pin on the map. |
| **Deduplicate** | The report is matched against nearby open issues of the same category using distance and text similarity. A match is merged, and the citizen count goes up instead of the queue growing. |
| **Prioritise** | Each issue gets an additive, fully explained priority score. |
| **Cluster** | DBSCAN finds hotspots, i.e. places where active issues concentrate. |
| **Act and track** | Officials work through a ranked queue. Citizens follow progress with a ticket code. |

## Key features

- **Geo-text duplicate merging** with a distance-adaptive similarity threshold, which scores F1 0.92 on a held-out benchmark.
- **Explainable priority.** Every factor (severity, citizens affected, age, safety keywords, hotspot membership) is
  returned by the API and drawn in the UI.
- **Hotspot detection** using DBSCAN with haversine distance (ε = 250 m, minPts = 3).
- **Citizen ticket tracking** with a status timeline (Open → In progress → Resolved).
- **Authority dashboard** with a priority-ranked queue, per-issue score breakdown and status workflow, protected by an
  authority key compared in constant time.
- **Public city map** with status and category filters, hotspot overlays, and markers sized by citizens affected.
- **Transparency metrics**: issues on record, citizen reports, duplicates merged, active hotspots, average days to resolve.

## Architecture

```mermaid
flowchart LR
    C[Citizen browser] -->|React + Tailwind SPA| S
    A[Authority browser] -->|X-Admin-Key| S
    subgraph S[Spring Boot 4 service · single Docker image]
        direction TB
        API[REST controller /api] --> AN[Analytics<br/>duplicates · priority · DBSCAN]
        API --> JPA[Spring Data JPA]
    end
    JPA --> DB[(PostgreSQL<br/>schema: nagarsetu)]
    C -.->|map tiles| OSM[OpenStreetMap]
```

The React app is built at image build time and served by Spring Boot as static content, so one Render web service
hosts both the UI and the API. The analytics are pure Java functions with no framework dependency, which lets them be
benchmarked offline in plain JUnit.

| Layer | Technology |
|---|---|
| Backend | Java 21, Spring Boot 4.1 (Web MVC, Data JPA, Validation, Actuator), Hibernate 7 |
| Database | PostgreSQL 16 (Render managed) |
| Frontend | React 19, Vite 8, Tailwind CSS 4, Leaflet 1.9 / react-leaflet 5 |
| Infrastructure | Multi-stage Docker build (Node 24 → Maven / Temurin 21 → JRE 21), Render |

## Method

### 1 · Duplicate detection

A bounding-box query (about 100 m) fetches open issues of the same category. Each candidate is then scored:

- **Distance**: haversine distance in metres.
- **Text similarity**: `max(word Jaccard, character-trigram Jaccard)` over lower-cased, stop-word-filtered, lightly
  stemmed tokens. Word overlap catches shared vocabulary; trigrams catch spelling variants such as "street light" and "streetlight".
- **Decision**:

```
duplicate  ⇔  distance ≤ 100 m  ∧  similarity ≥ 0.10 + 0.10 · (distance / 100 m)
```

The threshold tightens with distance. Two reports a few metres apart need little textual overlap, while reports
near the 100 m limit must clearly describe the same problem. If several candidates match, the closest one wins.

### 2 · Explainable priority

```
priority = 10 · severity(category)          traffic signal 5, pothole 4, water leak 4, drainage 3, streetlight 3, garbage 2, other 1
         + 12 · ln(1 + citizens affected)   diminishing returns, so volume cannot drown out severity
         + min(days open, 30)               ageing, capped
         + 20  if safety keywords present   accident, child, school, hospital, sparking, flood, sewage, …
         + 10  if inside an active hotspot
```

The score is additive by design: each term is a readable contribution, and the API returns every term alongside the total.

### 3 · Hotspot detection

Classic DBSCAN with haversine distance runs over OPEN and IN_PROGRESS issues (ε = 250 m, minPts = 3). For each cluster
the API reports its centroid, radius, issue count, total citizen reports and dominant category.

## Evaluation

All results can be reproduced with one command:

```bash
cd backend && ./mvnw test -Dtest=AnalyticsEvaluationTest
```

> **Note:** both benchmarks are **synthetic** and generated inside the test with fixed seeds and known ground truth.
> They measure algorithm behaviour in controlled scenarios. They are not results on real municipal data.

### Duplicate detection: ablation

Each split has 2,000 report pairs drawn evenly from three scenarios:

| Scenario | Label |
|---|---|
| Same problem, paraphrased text, 0–80 m GPS noise | duplicate |
| Different problem of the same category, 10–100 m away | not duplicate |
| Same kind of problem on another street, 120–520 m away | not duplicate |

Thresholds were grid-searched on the **tuning split (seed 7)** and are reported on the **held-out split (seed 99)**.

| Rule | Tuning P / R / F1 | Held-out P / R / F1 |
|---|---|---|
| A · distance only (≤ 50 m) | 0.548 / 0.584 / 0.566 | 0.586 / 0.629 / 0.606 |
| B · ≤ 30 m always, else ≤ 100 m and word Jaccard ≥ 0.2 | 0.772 / 0.763 / 0.767 | 0.797 / 0.797 / 0.797 |
| C · ≤ 100 m and word Jaccard ≥ 0.2 | 1.000 / 0.643 / 0.783 | 1.000 / 0.660 / 0.795 |
| D · ≤ 100 m and trigram Jaccard ≥ 0.25 | 0.912 / 0.650 / 0.759 | 0.899 / 0.655 / 0.758 |
| **E · distance-adaptive max(word, trigram) (production)** | **0.927 / 0.930 / 0.928** | **0.917 / 0.917 / 0.917** |

**Findings.** Location alone is a weak signal (F1 0.61), because neighbours often report *different* problems. Fixed
text thresholds trade recall for precision. A threshold that tightens with distance, combined with two complementary
similarity measures, gives balanced precision and recall (F1 0.917 held-out, up from 0.606).

### Hotspot detection

There were 50 randomised runs. Each had 6 ground-truth clusters (4–11 issues within 150 m of a centre) plus 40 uniform noise points
over about 22 km × 22 km.

| Metric | Result |
|---|---|
| Clusters found (mean) | 6.00 of 6 |
| Pair-counting precision | 0.992 |
| Pair-counting recall | 1.000 |

## Getting started

**Prerequisites:** Java 21+, Node.js 20+, PostgreSQL 14+.

```bash
git clone https://github.com/Kavaykhurana/nagarsetu.git && cd nagarsetu
createdb nagarsetu

# API on http://localhost:8080 (seeds demo data into an empty database)
cd backend && DB_USERNAME=$USER DB_PASSWORD= ./mvnw spring-boot:run

# UI on http://localhost:5173 (proxies /api to :8080)
cd frontend && npm install && npm run dev
```

**Docker** (single image, same as production):

```bash
docker build -t nagarsetu .
docker run -p 8080:8080 -e DATABASE_URL=postgresql://user:pass@host:5432/db -e ADMIN_KEY=change-me nagarsetu
```

## Configuration

| Variable | Default | Description |
|---|---|---|
| `DATABASE_URL` | none | `postgresql://user:pass@host:port/db`. Takes precedence over the `DB_*` variables. |
| `DB_URL` | `jdbc:postgresql://localhost:5432/nagarsetu` | JDBC URL |
| `DB_USERNAME` / `DB_PASSWORD` | `postgres` / `postgres` | Database credentials |
| `DB_SCHEMA` | `public` | Schema for NagarSetu's tables (created automatically) |
| `ADMIN_KEY` | `demo-admin` | Authority dashboard key. **Always override in production.** |
| `SEED_DEMO_DATA` | `true` | Seed about 50 synthetic Bengaluru reports, flagged `demo`, into an empty database |
| `PORT` | `8080` | HTTP port (set automatically by Render) |

## API reference

| Method | Endpoint | Description |
|---|---|---|
| `POST` | `/api/issues` | Submit a report. Returns `{ merged, distanceMeters, textSimilarity, issue }`. |
| `GET` | `/api/issues?status=&category=` | Issues sorted by priority, each with its score breakdown |
| `GET` | `/api/issues/track/{ticketCode}` | Citizen ticket lookup |
| `GET` | `/api/hotspots` | Current DBSCAN clusters |
| `GET` | `/api/stats` | Aggregate transparency metrics |
| `GET` | `/api/admin/verify` | Check an authority key (header `X-Admin-Key`) |
| `PATCH` | `/api/admin/issues/{id}/status` | Change issue status (header `X-Admin-Key`) |
| `GET` | `/actuator/health` | Health check |

<details>
<summary>Example: submitting a report that gets merged</summary>

```bash
curl -X POST https://nagarsetu-kqe7.onrender.com/api/issues \
  -H 'Content-Type: application/json' \
  -d '{"title":"Huge pothole near the bus stop","description":"buses stop mid-road",
       "category":"POTHOLE","latitude":12.93460,"longitude":77.62490}'
```

```json
{
  "merged": true,
  "distanceMeters": 25.86,
  "textSimilarity": 0.5,
  "issue": { "ticketCode": "NS-000005", "reportCount": 6, "priority": 99.6,
             "priorityBreakdown": { "severity": 40.0, "citizensAffected": 23.4, "daysOpen": 26.2,
                                    "urgentKeywords": 0.0, "hotspot": 10.0 } }
}
```
</details>

## Project structure

```
nagarsetu/
├── Dockerfile                    multi-stage build: frontend → backend → JRE runtime
├── backend/                      Spring Boot service
│   └── src/
│       ├── main/java/in/nagarsetu/
│       │   ├── Analytics.java        duplicate detection, priority scoring, DBSCAN (pure functions)
│       │   ├── IssueController.java  REST API
│       │   ├── Issue.java            JPA entity
│       │   ├── IssueRepository.java  Spring Data repository
│       │   └── DemoSeeder.java       synthetic demo data
│       └── test/java/in/nagarsetu/
│           └── AnalyticsEvaluationTest.java   benchmarks and ablation
└── frontend/                     React + Tailwind single-page app
    └── src/
        ├── App.jsx               layout, city map, stats
        ├── Panels.jsx            report, track and authority views
        ├── MapView.jsx           Leaflet map, markers, hotspot overlays
        └── lib.js                API client and constants
```

## Limitations and future work

- **Synthetic evaluation.** The next step is validating thresholds on real, labelled complaint data from a municipal
  partner, such as BBMP Sahaaya or a CPGRAMS export.
- **Language.** Similarity is English bag-of-words. Multilingual reports (Hindi, Kannada and others) would need
  transliteration or multilingual sentence embeddings.
- **Scale.** Neighbour search in DBSCAN is O(n²), which suits thousands of active issues. City-wide scale would move it to
  PostGIS or a geohash grid.
- **Evidence.** Photo upload with on-device image classification, plus citizen verification of resolved issues
  before closure.
- **Identity.** OTP-based citizen accounts and role-based authority access instead of a shared key.

## Acknowledgements

- Map data © [OpenStreetMap](https://www.openstreetmap.org/copyright) contributors, available under the ODbL.
- Built with open-source software: Spring Boot, Hibernate, the PostgreSQL JDBC driver, React, Vite, Tailwind CSS, Leaflet and
  react-leaflet. Fonts: Inter, Source Serif 4 and JetBrains Mono (SIL Open Font License).
- All demo reports are synthetic and generated by `DemoSeeder`. No real personal or complaint data is used.
- AI coding assistance was used during development, as permitted by the THREWS ODYSSEY 2026 rules.
