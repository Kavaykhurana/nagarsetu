import { useState } from 'react'
import MapView from './MapView'
import { api, CATEGORIES, FACTORS, STATUSES, timeAgo } from './lib'

export function Notice({ kind, children }) {
  return (
    <div className="notice mb-6 text-[18px]" role={kind === 'Error' ? 'alert' : 'status'}>
      <span className="chrome mr-2 text-[12px] font-semibold uppercase tracking-[0.06em]">{kind} —</span>
      {children}
    </div>
  )
}

export function PriorityBars({ breakdown }) {
  const total = Object.values(breakdown).reduce((a, b) => a + b, 0)
  return (
    <div className="space-y-3">
      <div className="flex h-2.5 gap-px overflow-hidden rounded-[2px] bg-stone">
        {Object.entries(breakdown).map(([k, v]) =>
          v > 0 ? (
            <div key={k} style={{ width: `${(v / Math.max(total, 1)) * 100}%`, background: FACTORS[k].tone }} title={`${FACTORS[k].label}: ${v}`} />
          ) : null,
        )}
      </div>
      <ul className="chrome grid grid-cols-1 sm:grid-cols-2 gap-x-6 gap-y-1.5 text-[12px]">
        {Object.entries(breakdown).map(([k, v]) => (
          <li key={k} className="flex items-center justify-between gap-2">
            <span className="flex items-center gap-2 text-slate-medium">
              <span className="h-2.5 w-2.5 rounded-[1px] border border-slate-dark/30" style={{ background: FACTORS[k].tone }} />
              {FACTORS[k].label}
            </span>
            <span className="font-mono">+{v}</span>
          </li>
        ))}
      </ul>
    </div>
  )
}

function Status({ status }) {
  return (
    <span className="chrome whitespace-nowrap text-[12px] font-semibold uppercase tracking-[0.06em]">
      {STATUSES[status].mark} {STATUSES[status].label}
    </span>
  )
}

// ---------------------------------------------------------------- report

export function ReportPanel({ issues, hotspots, onReported }) {
  const [form, setForm] = useState({ title: '', description: '', category: 'POTHOLE' })
  const [picked, setPicked] = useState(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [result, setResult] = useState(null)

  const locate = () => {
    if (!navigator.geolocation) return setError('Geolocation is not available in this browser.')
    navigator.geolocation.getCurrentPosition(
      (p) => setPicked([p.coords.latitude, p.coords.longitude]),
      () => setError('Location permission denied. Click on the map instead.'),
    )
  }

  const submit = async (e) => {
    e.preventDefault()
    if (!picked) return setError('Drop a pin on the map, or use your current location.')
    setBusy(true)
    setError('')
    try {
      const r = await api('/issues', { method: 'POST', body: { ...form, latitude: picked[0], longitude: picked[1] } })
      setResult(r)
      setForm({ ...form, title: '', description: '' })
      onReported()
    } catch (err) {
      setError(err.message)
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="grid gap-8 lg:grid-cols-[420px_1fr]">
      <div className="space-y-6">
        <form onSubmit={submit} className="card space-y-6">
          <div>
            <div className="eyebrow">For citizens</div>
            <h2 className="chrome mt-1 text-[24px] font-semibold tracking-[-0.05px]">What's wrong, and where?</h2>
          </div>
          <fieldset>
            <legend className="label">Category</legend>
            <div className="flex flex-wrap gap-2">
              {Object.entries(CATEGORIES).map(([k, v]) => (
                <button type="button" key={k} aria-pressed={form.category === k} onClick={() => setForm({ ...form, category: k })} className="btn btn-outline">
                  {v.emoji} {v.label}
                </button>
              ))}
            </div>
          </fieldset>
          <div>
            <label htmlFor="title" className="label">Title</label>
            <input id="title" required maxLength={120} className="field" placeholder="Deep pothole near the bus stop"
              value={form.title} onChange={(e) => setForm({ ...form, title: e.target.value })} />
          </div>
          <div>
            <label htmlFor="desc" className="label">Details</label>
            <textarea id="desc" rows={3} maxLength={1000} className="field min-h-[110px]" placeholder="What do you see? Is anyone at risk?"
              value={form.description} onChange={(e) => setForm({ ...form, description: e.target.value })} />
          </div>
          <div className="chrome flex items-center justify-between gap-2 border-y border-stone py-3 text-[14px]">
            <span className={picked ? '' : 'text-slate-medium'}>
              {picked ? `Pin at ${picked[0].toFixed(5)}, ${picked[1].toFixed(5)}` : 'Click the map to drop a pin'}
            </span>
            <button type="button" onClick={locate} className="btn btn-text">Use my location</button>
          </div>
          {error && <p className="chrome text-[14px]"><b>Error —</b> {error}</p>}
          <button disabled={busy} className="btn btn-clay w-full">{busy ? 'Submitting…' : 'Submit report'}</button>
        </form>
        {result && <ReportResult result={result} />}
      </div>
      <MapView issues={issues.filter((i) => i.status !== 'RESOLVED')} hotspots={hotspots} picked={picked} onPick={setPicked} className="h-[560px] lg:h-[720px]" />
    </div>
  )
}

function ReportResult({ result }) {
  const { merged, issue, distanceMeters, textSimilarity } = result
  return (
    <div className="rounded-[24px] bg-manilla p-8 space-y-4" role="status">
      <div className="eyebrow">{merged ? 'Duplicate detected · merged' : 'New issue created'}</div>
      <p className="text-[20px]">
        {merged ? (
          <>
            This was already reported <b>{Math.round(distanceMeters)} m</b> away, with{' '}
            <b>{Math.round(textSimilarity * 100)}%</b> text similarity. You are citizen <b>#{issue.reportCount}</b> on
            it, so its priority went up instead of the queue growing.
          </>
        ) : (
          <>Thank you. Keep this ticket code to follow the repair.</>
        )}
      </p>
      <div className="flex items-baseline justify-between border-y border-slate-dark/20 py-3">
        <span className="font-mono text-[22px] tracking-wider">{issue.ticketCode}</span>
        <span className="chrome text-[12px] text-slate-medium">priority {issue.priority}</span>
      </div>
      <PriorityBars breakdown={issue.priorityBreakdown} />
    </div>
  )
}

// ---------------------------------------------------------------- track

export function TrackPanel() {
  const [code, setCode] = useState('')
  const [issue, setIssue] = useState(null)
  const [error, setError] = useState('')

  const lookup = async (e) => {
    e.preventDefault()
    setError('')
    setIssue(null)
    try {
      setIssue(await api(`/issues/track/${encodeURIComponent(code.trim())}`))
    } catch (err) {
      setError(err.message)
    }
  }

  const steps = ['OPEN', 'IN_PROGRESS', 'RESOLVED']
  const at = issue ? steps.indexOf(issue.status) : -1

  return (
    <div className="mx-auto max-w-3xl space-y-6">
      <form onSubmit={lookup} className="card space-y-4">
        <label htmlFor="code" className="label">Ticket code</label>
        <div className="flex gap-3">
          <input id="code" className="field font-mono uppercase" placeholder="NS-XXXXXX" value={code} onChange={(e) => setCode(e.target.value)} required />
          <button className="btn btn-ivory whitespace-nowrap">Track</button>
        </div>
        {error && <p className="chrome text-[14px]"><b>Error —</b> {error}</p>}
      </form>

      {issue && (
        <article className="card space-y-8">
          <header className="space-y-2">
            <div className="flex flex-wrap items-baseline justify-between gap-2">
              <div className="eyebrow">{CATEGORIES[issue.category].emoji} {CATEGORIES[issue.category].label} · {issue.ticketCode} · reported {timeAgo(issue.createdAt)}</div>
              <Status status={issue.status} />
            </div>
            <h2 className="font-serif text-[32px] leading-tight">{issue.title}</h2>
            {issue.description && <p className="text-[18px] text-slate-medium">{issue.description}</p>}
          </header>

          <ol className="chrome flex items-center text-[12px]">
            {steps.map((s, idx) => (
              <li key={s} className="flex flex-1 items-center last:flex-none">
                <span className={`flex h-7 w-7 items-center justify-center rounded-full border ${idx <= at ? 'border-slate-dark bg-slate-dark text-ivory-light' : 'border-stone text-cloud-dark'}`}>{idx + 1}</span>
                <span className={`ml-2 ${idx <= at ? 'font-semibold' : 'text-cloud-dark'}`}>{STATUSES[s].label}</span>
                {idx < steps.length - 1 && <span className={`mx-3 h-px flex-1 ${idx < at ? 'bg-slate-dark' : 'bg-stone'}`} />}
              </li>
            ))}
          </ol>

          <dl className="grid grid-cols-3 border-y border-stone">
            {[
              [issue.reportCount, 'citizens reported'],
              [issue.priority, 'priority score'],
              [issue.inHotspot ? 'Yes' : 'No', 'inside a hotspot'],
            ].map(([v, k], idx) => (
              <div key={k} className={`py-4 ${idx ? 'border-l border-stone pl-4' : ''}`}>
                <dd className="font-serif text-[36px] leading-none">{v}</dd>
                <dt className="chrome mt-2 text-[12px] uppercase tracking-[0.04em] text-slate-medium">{k}</dt>
              </div>
            ))}
          </dl>

          <div>
            <div className="eyebrow mb-3">Why it ranks where it does</div>
            <PriorityBars breakdown={issue.priorityBreakdown} />
          </div>
        </article>
      )}
    </div>
  )
}

// ---------------------------------------------------------------- authority

export function AuthorityPanel({ issues, onChanged }) {
  const [key, setKey] = useState(() => {
    try { return sessionStorage.getItem('ns-admin') || '' } catch { return '' }
  })
  const [authed, setAuthed] = useState(false)
  const [error, setError] = useState('')
  const [filter, setFilter] = useState('ACTIVE')
  const [open, setOpen] = useState(null)

  const login = async (e) => {
    e.preventDefault()
    try {
      await api('/admin/verify', { adminKey: key })
      try { sessionStorage.setItem('ns-admin', key) } catch { /* storage may be blocked */ }
      setAuthed(true)
      setError('')
    } catch (err) {
      setError(err.message)
    }
  }

  const setStatus = async (id, status) => {
    try {
      await api(`/admin/issues/${id}/status`, { method: 'PATCH', body: { status }, adminKey: key })
      onChanged()
    } catch (err) {
      setError(err.message)
    }
  }

  if (!authed) {
    return (
      <form onSubmit={login} className="card mx-auto max-w-md space-y-5">
        <div>
          <div className="eyebrow">For municipal staff</div>
          <h2 className="chrome mt-1 text-[24px] font-semibold">Authority sign-in</h2>
          <p className="mt-2 text-[16px] text-slate-medium">Enter the authority key to manage the work queue.</p>
        </div>
        <div>
          <label htmlFor="key" className="label">Authority key</label>
          <input id="key" type="password" className="field" value={key} onChange={(e) => setKey(e.target.value)} required />
        </div>
        {error && <p className="chrome text-[14px]"><b>Error —</b> {error}</p>}
        <button className="btn btn-ivory w-full">Open dashboard</button>
      </form>
    )
  }

  const rows = issues.filter((i) => (filter === 'ALL' ? true : filter === 'ACTIVE' ? i.status !== 'RESOLVED' : i.status === filter))

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <div className="eyebrow">Work queue · {rows.length} issues</div>
          <h2 className="chrome mt-1 text-[24px] font-semibold">Ranked by priority</h2>
          <p className="mt-1 text-[16px] text-slate-medium">Highest score first. Open a row to see why it ranks there.</p>
        </div>
        <select aria-label="Filter" value={filter} onChange={(e) => setFilter(e.target.value)} className="field !w-auto">
          <option value="ACTIVE">Active</option>
          <option value="ALL">All</option>
          {Object.entries(STATUSES).map(([k, v]) => <option key={k} value={k}>{v.label}</option>)}
        </select>
      </div>
      {error && <Notice kind="Error">{error}</Notice>}
      <div className="overflow-x-auto rounded-[24px] border border-stone bg-ivory-light">
        <table className="w-full min-w-[720px]">
          <thead className="chrome border-b border-stone text-left text-[12px] uppercase tracking-[0.06em] text-slate-medium">
            <tr>
              <th className="px-6 py-4 font-semibold">Rank</th>
              <th className="px-6 py-4 font-semibold">Issue</th>
              <th className="px-6 py-4 font-semibold">Citizens</th>
              <th className="px-6 py-4 font-semibold">Age</th>
              <th className="px-6 py-4 font-semibold">Priority</th>
              <th className="px-6 py-4 font-semibold">Status</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((i, idx) => (
              <Row key={i.id} i={i} idx={idx} open={open === i.id} toggle={() => setOpen(open === i.id ? null : i.id)} setStatus={setStatus} />
            ))}
          </tbody>
        </table>
      </div>
    </div>
  )
}

function Row({ i, idx, open, toggle, setStatus }) {
  return (
    <>
      <tr onClick={toggle} className="cursor-pointer border-t border-stone first:border-t-0 transition-colors duration-150 hover:bg-ivory-medium">
        <td className="chrome px-6 py-4 text-[14px] text-slate-medium">{idx + 1}</td>
        <td className="px-6 py-4">
          <div className="text-[18px] leading-snug">
            {i.title}
          </div>
          <div className="chrome mt-1 text-[12px] text-slate-medium">
            {CATEGORIES[i.category].emoji} {CATEGORIES[i.category].label} · {i.ticketCode}
            {i.inHotspot && <b className="ml-2 font-semibold uppercase tracking-[0.06em] text-slate-dark">· Hotspot</b>}
            {i.demo && <span className="ml-2 text-cloud-dark">· demo</span>}
          </div>
        </td>
        <td className="chrome px-6 py-4 text-[14px]">{i.reportCount}</td>
        <td className="chrome px-6 py-4 text-[14px] text-slate-medium">{timeAgo(i.createdAt)}</td>
        <td className="px-6 py-4 font-mono text-[16px]">{i.priority}</td>
        <td className="px-6 py-4" onClick={(e) => e.stopPropagation()}>
          <select aria-label={`Status of ${i.ticketCode}`} value={i.status} onChange={(e) => setStatus(i.id, e.target.value)} className="field !py-2 !text-[14px]">
            {Object.entries(STATUSES).map(([k, v]) => <option key={k} value={k}>{v.label}</option>)}
          </select>
        </td>
      </tr>
      {open && (
        <tr className="bg-ivory-medium">
          <td />
          <td colSpan={5} className="px-6 pb-6 pt-2">
            {i.description && <p className="mb-4 text-[18px] text-slate-medium">{i.description}</p>}
            <div className="max-w-xl"><PriorityBars breakdown={i.priorityBreakdown} /></div>
          </td>
        </tr>
      )}
    </>
  )
}
