/**
 * Cifras como se escriben en Colombia: punto de miles y coma decimal. En español, Intl no agrupa los números de
 * cuatro cifras (1612); `always` los deja como 1.612, igual que el resto de la interfaz y que ymid.me.
 */
const formats = new Map<number, Intl.NumberFormat>()

export function decimal(value: number, digits = 0): string {
  let format = formats.get(digits)
  if (!format) {
    format = new Intl.NumberFormat('es-CO', {
      minimumFractionDigits: digits,
      maximumFractionDigits: digits,
      useGrouping: 'always',
    })
    formats.set(digits, format)
  }
  return format.format(value)
}

export const km = (meters: number) => decimal(meters / 1000, 1)
export const tonnes = (kg: number) => decimal(kg / 1000, 1)
