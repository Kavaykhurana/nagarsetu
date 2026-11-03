import { Circle, CircleMarker, MapContainer, Popup, TileLayer, Tooltip, useMapEvents } from 'react-leaflet'
import { CATEGORIES, CITY_CENTER, STATUSES } from './lib'

function ClickCatcher({ onPick }) {
  useMapEvents({ click: (e) => { const p = e.latlng.wrap(); onPick?.([p.lat, p.lng]) } })
  return null
}

export default function MapView({ issues = [], hotspots = [], picked, onPick, className = 'h-[520px]' }) {
  return (
    <MapContainer
      center={CITY_CENTER}
      zoom={12}
      className={`${className} w-full rounded-[24px] border border-stone z-0 ${onPick ? 'cursor-crosshair' : ''}`}
      scrollWheelZoom
    >
      <TileLayer
        attribution='&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors'
        url="https://tile.openstreetmap.org/{z}/{x}/{y}.png"
      />
      <ClickCatcher onPick={onPick} />

      {hotspots.map((h) => (
        <Circle
          key={`h${h.id}`}
          center={[h.latitude, h.longitude]}
          radius={h.radiusMeters + 80}
          pathOptions={{ color: '#141413', fillColor: '#141413', fillOpacity: 0.07, weight: 1.5, dashArray: '5 6' }}
        >
          <Tooltip direction="top" sticky>
            <b>Hotspot {h.id}</b> · {h.issues} issues · {h.citizenReports} citizen reports · mostly{' '}
            {CATEGORIES[h.dominantCategory].label.toLowerCase()}
          </Tooltip>
        </Circle>
      ))}

      {issues.map((i) => (
        <CircleMarker
          key={i.id}
          center={[i.latitude, i.longitude]}
          radius={5 + Math.min(i.reportCount, 10)}
          pathOptions={{ color: '#141413', weight: 1, fillColor: STATUSES[i.status].fill, fillOpacity: STATUSES[i.status].fillOpacity }}
        >
          <Popup>
            <div className="min-w-52 space-y-1">
              <div className="eyebrow">{CATEGORIES[i.category].emoji} {CATEGORIES[i.category].label} · {STATUSES[i.status].label}</div>
              <div className="font-serif text-[16px] leading-snug">{i.title}</div>
              <div className="text-[12px] text-slate-medium">
                {i.ticketCode} · {i.reportCount} citizen{i.reportCount > 1 ? 's' : ''} · priority {i.priority}
              </div>
            </div>
          </Popup>
        </CircleMarker>
      ))}

      {picked && (
        <CircleMarker center={picked} radius={10} pathOptions={{ color: '#141413', weight: 2.5, fillColor: '#f5e3c7', fillOpacity: 1 }}>
          <Tooltip permanent direction="top">Your report</Tooltip>
        </CircleMarker>
      )}
    </MapContainer>
  )
}
