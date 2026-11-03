import { useCallback, useEffect, useState } from 'react'
import MapView from './MapView'
import { AuthorityPanel, Notice, PriorityBars, ReportPanel, TrackPanel } from './Panels'
import { api, CATEGORIES, STATUSES } from './lib'

const REPO_URL = 'https://github.com/Kavaykhurana/nagarsetu'

const TABS = [
  { id: 'report', label: 'Report an issue' },
  { id: 'map', label: 'City map' },
  { id: 'track', label: 'Track a ticket' },
  { id: 'authority', label: 'Authority dashboard' },
]

export default function App() {
  const [tab, setTab] = useState('report')
  const [issues, setIssues] = useState([])
  const [hotspots, setHotspots] = useState([])
  const [stats, setStats] = useState(null)
  const [error, setError] = useState('')

  const refresh = useCallback(async () => {
    try {
      const [i, h, s] = await Promise.all([api('/issues'), api('/hotspots'), api('/stats')])
      setIssues(i)
      setHotspots(h)
      setStats(s)
      setError('')
    } catch (e) {
      setError(e.message)
    }
  }, [])

  useEffect(() => {
    refresh()
  }, [refresh])

  return (
    <div className="min-h-screen flex flex-col">
      <header className="border-b border-stone">
        <div className="mx-auto flex h-[72px] max-w-[1280px] items-center gap-8 px-4 sm:px-8">
          <a href="/" className="chrome flex items-center gap-2 text-[12px] font-bold uppercase tracking-[0.08em] no-underline">
            <img src="/favicon.svg" alt="" className="h-6 w-6" /> NagarSetu
          </a>
          <nav className="flex gap-6 overflow-x-auto">
            {TABS.map((t) => (
              <button
                key={t.id}
                onClick={() => setTab(t.id)}
                aria-current={tab === t.id ? 'page' : undefined}
                className={`chrome whitespace-nowrap py-2 text-[12px] transition-colors duration-150 ${
                  tab === t.id ? 'text-slate-dark underline underline-offset-[6px]' : 'text-cloud-dark hover:text-slate-dark'
                }`}
              >
                {t.label}
              </button>
            ))}
          </nav>
        </div>
      </header>

      <section className="mx-auto w-full max-w-[1280px] px-4 sm:px-8 pt-16 pb-12">
        <div className="grid gap-8 lg:grid-cols-12 lg:gap-16 lg:items-end">
          <h1 className="chrome lg:col-span-7 text-[40px] lg:text-[61px] font-bold leading-[1.1] tracking-[-0.12px]">
            One report. Merged, ranked and fixed in the open.
          </h1>
          <p className="lg:col-span-5 text-[18px] lg:text-[20px] text-slate-medium">
            NagarSetu turns scattered citizen complaints into a single, de-duplicated work queue. It spots duplicates
            with geo-text similarity, finds hotspots with DBSCAN, and explains every priority score.
          </p>
        </div>
        {stats && <StatRow stats={stats} />}
      </section>

      <main className="mx-auto w-full max-w-[1280px] flex-1 px-4 sm:px-8 pb-24">
        {error && <Notice kind="Error">Could not reach the server. {error}</Notice>}
        {tab === 'report' && <ReportPanel issues={issues} hotspots={hotspots} onReported={refresh} />}
        {tab === 'map' && <CityMap issues={issues} hotspots={hotspots} />}
        {tab === 'track' && <TrackPanel />}
        {tab === 'authority' && <AuthorityPanel issues={issues} onChanged={refresh} />}
      </main>

      <footer className="bg-slate-dark text-ivory-light">
        <div className="mx-auto grid max-w-[1280px] gap-8 px-4 sm:px-8 pt-20 pb-12 sm:grid-cols-3 chrome text-[12px]">
          <div>
            <div className="mb-3 font-semibold">NagarSetu</div>
            <p className="text-cloud-medium max-w-xs">A bridge between citizens and the city. Built for THREWS Odyssey 2026, Smart Cities &amp; Social Impact track.</p>
          </div>
          <div>
            <div className="mb-3 font-semibold">Method</div>
            <ul className="space-y-2 text-cloud-medium">
              <li>Duplicates: distance-adaptive geo + text similarity</li>
              <li>Hotspots: DBSCAN, ε = 250 m, minPts = 3</li>
              <li>Priority: additive, fully explained</li>
            </ul>
          </div>
          <div>
            <div className="mb-3 font-semibold">Project</div>
            <ul className="space-y-2 text-cloud-medium">
              <li><a href={REPO_URL} className="hover:text-ivory-light">Source code on GitHub</a></li>
              <li>Map data © OpenStreetMap contributors</li>
            </ul>
          </div>
        </div>
        <div className="mx-auto max-w-[1280px] px-4 sm:px-8 pb-12">
          <div className="chrome border-t border-slate-medium pt-6 text-[12px] text-cloud-dark">
            Issues tagged “demo” are synthetic seed data for demonstration only.
          </div>
        </div>
      </footer>
    </div>
  )
}

function StatRow({ stats }) {
  const items = [
    ['Issues on record', stats.totalIssues],
    ['Citizen reports', stats.citizenReports],
    ['Duplicates merged', stats.duplicatesMerged],
    ['Active hotspots', stats.activeHotspots],
    ['Avg. days to resolve', Math.round(stats.avgResolutionHours / 24)],
  ]
  return (
    <dl className="mt-12 grid grid-cols-2 sm:grid-cols-5 border-t border-stone">
      {items.map(([k, v], idx) => (
        <div key={k} className={`py-5 sm:px-6 ${idx > 0 ? 'sm:border-l border-stone' : 'sm:pl-0'}`}>
          <dd className="font-serif text-[40px] lg:text-[48px] leading-none">{v}</dd>
          <dt className="chrome mt-2 text-[12px] font-medium uppercase tracking-[0.04em] text-slate-medium">{k}</dt>
        </div>
      ))}
    </dl>
  )
}

function CityMap({ issues, hotspots }) {
  const [category, setCategory] = useState('')
  const [status, setStatus] = useState('ACTIVE')
  const shown = issues.filter(
    (i) =>
      (!category || i.category === category) &&
      (status === 'ALL' || (status === 'ACTIVE' ? i.status !== 'RESOLVED' : i.status === status)),
  )
  return (
    <div className="grid gap-8 lg:grid-cols-[1fr_360px]">
      <section className="space-y-4">
        <div className="flex flex-wrap items-center gap-2">
          <select aria-label="Status" value={status} onChange={(e) => setStatus(e.target.value)} className="field !w-auto">
            <option value="ACTIVE">Active issues</option>
            <option value="ALL">All issues</option>
            {Object.entries(STATUSES).map(([k, v]) => <option key={k} value={k}>{v.label}</option>)}
          </select>
          <select aria-label="Category" value={category} onChange={(e) => setCategory(e.target.value)} className="field !w-auto">
            <option value="">All categories</option>
            {Object.entries(CATEGORIES).map(([k, v]) => <option key={k} value={k}>{v.emoji} {v.label}</option>)}
          </select>
          <div className="chrome ml-auto flex flex-wrap gap-4 text-[12px] text-slate-medium">
            {Object.entries(STATUSES).map(([k, v]) => <span key={k}>{v.mark} {v.label}</span>)}
            <span>◌ Hotspot</span>
            <span>Size = citizens</span>
          </div>
        </div>
        <MapView issues={shown} hotspots={hotspots} className="h-[620px]" />
      </section>

      <aside className="space-y-4">
        <div>
          <div className="eyebrow">DBSCAN · ε 250 m · minPts 3</div>
          <h2 className="chrome mt-1 text-[24px] font-semibold tracking-[-0.05px]">Hotspots</h2>
          <p className="mt-2 text-[16px] text-slate-medium">
            Where active issues pile up. One crew visit here clears many complaints at once.
          </p>
        </div>
        {hotspots.length === 0 && <p className="text-slate-medium">No hotspots right now.</p>}
        {hotspots.map((h) => {
          const top = issues.filter((i) => h.issueIds.includes(i.id)).sort((a, b) => b.priority - a.priority)[0]
          return (
            <div key={h.id} className="card !p-6">
              <div className="chrome flex items-baseline justify-between text-[12px]">
                <span className="font-semibold uppercase tracking-[0.06em]">Hotspot {h.id}</span>
                <span className="text-slate-medium">{h.citizenReports} citizen reports</span>
              </div>
              <p className="mt-2 text-[18px]">
                {h.issues} issues within ~{Math.round(h.radiusMeters)} m, mostly{' '}
                {CATEGORIES[h.dominantCategory].label.toLowerCase()}.
              </p>
              {top && <p className="mt-1 text-[16px] text-slate-medium">Most urgent: <i>{top.title}</i> ({top.priority})</p>}
            </div>
          )
        })}
        <div className="panel !p-6">
          <div className="eyebrow mb-3">How priority is scored</div>
          <PriorityBars breakdown={{ severity: 40, citizensAffected: 21.5, daysOpen: 9, urgentKeywords: 20, hotspot: 10 }} />
          <p className="mt-3 text-[16px] text-slate-medium">
            Category severity + 12·ln(1 + citizens) + days open (capped at 30) + safety keywords + hotspot bonus. Each
            factor is shown, so there is no black box.
          </p>
        </div>
      </aside>
    </div>
  )
}
