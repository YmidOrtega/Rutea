import type { StyleSpecification } from 'maplibre-gl'
import type { Palette, RGB } from './colors'
import { BASEMAP_STYLE } from './view'

/**
 * Dark Matter es gris y azul pizarra. Rutea es de noche en Bogotá: cada color del estilo se vuelve a pintar por su
 * luminancia, del fondo hacia el sodio, como una ciudad vista desde arriba bajo sus luminarias. El fondo del estilo
 * queda igual al de la interfaz y las calles más claras, las avenidas, son las que más brillan.
 */
export async function sodiumBasemap(palette: Palette): Promise<StyleSpecification | string> {
  try {
    const response = await fetch(BASEMAP_STYLE)
    if (!response.ok) return BASEMAP_STYLE
    const style = (await response.json()) as StyleSpecification
    for (const layer of style.layers) {
      if (!('paint' in layer) || !layer.paint) continue
      // El agua no lleva luminarias: más oscura que una calle con la misma luminancia
      const gain = layer.id.startsWith('water') ? 0.35 : 0.9
      const paint = layer.paint as Record<string, unknown>
      for (const key of Object.keys(paint)) {
        if (key.endsWith('-color')) paint[key] = recolor(paint[key], palette.bg, palette.sodium, gain)
      }
    }
    return style
  } catch {
    // Sin teñir se ve igual de bien que antes: no vale la pena un mapa en blanco
    return BASEMAP_STYLE
  }
}

/** Recorre expresiones y paradas de zoom: solo cambian las hojas que son colores. */
function recolor(value: unknown, bg: RGB, light: RGB, gain: number): unknown {
  if (typeof value === 'string') return tint(value, bg, light, gain) ?? value
  if (Array.isArray(value)) return value.map((item) => recolor(item, bg, light, gain))
  if (value && typeof value === 'object') {
    return Object.fromEntries(Object.entries(value).map(([k, v]) => [k, recolor(v, bg, light, gain)]))
  }
  return value
}

const BASE_LUMINANCE = 0.055

function tint(color: string, bg: RGB, light: RGB, gain: number): string | null {
  const parsed = parse(color)
  if (!parsed) return null
  const [r, g, b, a] = parsed
  const luminance = (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255
  const t = Math.min(1, Math.max(0, luminance - BASE_LUMINANCE) * gain)
  const [x, y, z] = bg.map((channel, i) => Math.round(channel + (light[i] - channel) * t))
  return `rgba(${x}, ${y}, ${z}, ${a})`
}

function parse(color: string): [number, number, number, number] | null {
  const hex = /^#([0-9a-f]{3}|[0-9a-f]{6})$/i.exec(color)
  if (hex) {
    const digits = hex[1].length === 3 ? [...hex[1]].map((d) => d + d).join('') : hex[1]
    const n = Number.parseInt(digits, 16)
    return [(n >> 16) & 255, (n >> 8) & 255, n & 255, 1]
  }
  const rgba = /^rgba?\(\s*([\d.]+)\s*,\s*([\d.]+)\s*,\s*([\d.]+)\s*(?:,\s*([\d.]+)\s*)?\)$/i.exec(color)
  if (rgba) return [Number(rgba[1]), Number(rgba[2]), Number(rgba[3]), rgba[4] === undefined ? 1 : Number(rgba[4])]
  return null
}
