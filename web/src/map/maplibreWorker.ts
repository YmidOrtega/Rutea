import { setWorkerUrl } from 'maplibre-gl'
import workerUrl from 'maplibre-gl/dist/maplibre-gl-worker.mjs?worker&url'

// MapLibre 6 resuelve su worker relativo a su propio módulo; con el pre-bundling de Vite
// esa ruta no existe. Vite empaqueta el worker y aquí se le indica a MapLibre dónde está.
setWorkerUrl(workerUrl)
