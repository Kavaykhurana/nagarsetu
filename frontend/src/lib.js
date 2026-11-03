export const CATEGORIES = {
  POTHOLE: { label: 'Pothole', emoji: '🕳️' },
  GARBAGE: { label: 'Garbage', emoji: '🗑️' },
  STREETLIGHT: { label: 'Streetlight', emoji: '💡' },
  WATER_LEAK: { label: 'Water leak', emoji: '💧' },
  DRAINAGE: { label: 'Drainage', emoji: '🌊' },
  TRAFFIC_SIGNAL: { label: 'Traffic signal', emoji: '🚦' },
  OTHER: { label: 'Other', emoji: '📍' },
}

// Status is carried by tone (ink -> cloud -> hollow), never by an extra hue.
export const STATUSES = {
  OPEN: { label: 'Open', mark: '●', fill: '#141413', fillOpacity: 0.9 },
  IN_PROGRESS: { label: 'In progress', mark: '◐', fill: '#87867f', fillOpacity: 0.9 },
  RESOLVED: { label: 'Resolved', mark: '○', fill: '#faf9f5', fillOpacity: 0.6 },
}

export const FACTORS = {
  severity: { label: 'Category severity', tone: '#141413' },
  citizensAffected: { label: 'Citizens affected', tone: '#3d3d3a' },
  daysOpen: { label: 'Days open', tone: '#87867f' },
  urgentKeywords: { label: 'Safety keywords', tone: '#b0aea5' },
  hotspot: { label: 'Inside a hotspot', tone: '#e3dacc' },
}

export const CITY_CENTER = [12.955, 77.62]

export async function api(path, { method = 'GET', body, adminKey } = {}) {
  const res = await fetch(`/api${path}`, {
    method,
    headers: {
      ...(body ? { 'Content-Type': 'application/json' } : {}),
      ...(adminKey ? { 'X-Admin-Key': adminKey } : {}),
    },
    body: body ? JSON.stringify(body) : undefined,
  })
  if (!res.ok) {
    // Spring hides exception reasons by default, so map the status to something readable.
    const fallback = { 400: 'Please check the form fields.', 401: 'Invalid authority key.', 404: 'No issue with that ticket code.' }
    const err = await res.json().catch(() => ({}))
    throw new Error(err.message || fallback[res.status] || `Request failed (${res.status})`)
  }
  return res.json()
}

export function timeAgo(iso) {
  const h = (Date.now() - new Date(iso).getTime()) / 36e5
  if (h < 1) return 'just now'
  if (h < 24) return `${Math.round(h)} h ago`
  return `${Math.round(h / 24)} d ago`
}
