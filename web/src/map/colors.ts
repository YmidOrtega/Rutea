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
    fg: token('--fg'),
    fgMuted: token('--fg-muted'),
    ok: token('--ok'),
    warn: token('--warn'),
    crit: token('--crit'),
    sodium: token('--sodium'),
    stream: {
      RECYCLABLE: token('--stream-recyclable'),
      ORGANIC: token('--stream-organic'),
      NON_RECYCLABLE: token('--stream-non-recyclable'),
    } satisfies Record<WasteStream, RGB>,
    nonRecyclableEdge: token('--stream-non-recyclable-edge'),
  }
}

export type Palette = ReturnType<typeof readPalette>

/** El negro oficial no se ve sobre el mapa oscuro: su trazo usa el borde claro. */
export function strokeColor(palette: Palette, stream: WasteStream): RGB {
  return stream === 'NON_RECYCLABLE' ? palette.nonRecyclableEdge : palette.stream[stream]
}
