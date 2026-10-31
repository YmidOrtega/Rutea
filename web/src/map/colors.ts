import type { WasteStream } from '../demo/plan'

export type RGB = [number, number, number]

/**
 * deck.gl no lee variables CSS: los colores se leen de tokens.css al arrancar para que los tokens
 * sigan siendo la única fuente de verdad.
 */
function token(name: string): RGB {
  const value = getComputedStyle(document.documentElement).getPropertyValue(name).trim()
  const match = /^#([0-9a-f]{6})$/i.exec(value)
  if (!match) {
    throw new Error(`El token ${name} debe ser #rrggbb en tokens.css; vale "${value}"`)
  }
  const n = Number.parseInt(match[1], 16)
  return [(n >> 16) & 255, (n >> 8) & 255, n & 255]
}

export function readPalette() {
  return {
    bg: token('--bg'),
    mapBg: token('--map-bg'),
    mapStreet: token('--map-street'),
    fg: token('--fg'),
    fgMuted: token('--fg-muted'),
    ok: token('--ok'),
    warn: token('--warn'),
    crit: token('--crit'),
    sodium: token('--sodium'),
    clean: token('--clean'),
    stream: {
      RECYCLABLE: token('--stream-recyclable'),
      ORGANIC: token('--stream-organic'),
      NON_RECYCLABLE: token('--stream-non-recyclable'),
    } satisfies Record<WasteStream, RGB>,
    nonRecyclableEdge: token('--stream-non-recyclable-edge'),
    /** Por número de ASE: `operator[1]` es el del ASE 1. */
    operator: [null, ...[1, 2, 3, 4, 5].map((area) => token(`--operator-${area}`))] as (RGB | null)[],
  }
}

export type Palette = ReturnType<typeof readPalette>

export function operatorColor(palette: Palette, area: number): RGB {
  const color = palette.operator[area]
  if (!color) throw new Error(`No hay color para el ASE ${area} en tokens.css`)
  return color
}

/** El negro oficial no se ve sobre el mapa oscuro: su trazo usa el borde claro. */
export function strokeColor(palette: Palette, stream: WasteStream): RGB {
  return stream === 'NON_RECYCLABLE' ? palette.nonRecyclableEdge : palette.stream[stream]
}
