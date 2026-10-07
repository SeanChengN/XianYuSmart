/** Search display prices only; no inferred SKU units or cross-specification matching. */
export function displayPriceCents(value?: string | number): number | null {
  const text = String(value ?? '').trim().replace(/^[¥￥]\s*/, '')
  if (!/^\d+(?:\.\d{1,2})?$/.test(text)) return null
  const [yuan = '0', fraction = ''] = text.split('.')
  const cents = Number(yuan) * 100 + Number(fraction.padEnd(2, '0'))
  return Number.isSafeInteger(cents) ? cents : null
}

export function formatMoneyCents(cents: number | string | null | undefined): string {
  if (cents == null || (typeof cents === 'string' && !/^\d+$/.test(cents))) return '--'
  // Jackson serializes Long fields as strings. This contract is already in cents.
  const value = Number(cents)
  if (!Number.isSafeInteger(value) || value < 0) return '--'
  return `${Math.floor(value / 100)}.${String(value % 100).padStart(2, '0')}`
}

export function displayPriceSummary(values: Array<string | number | undefined>) {
  const prices = values.map(displayPriceCents).filter((price): price is number => price != null && price > 0).sort((a, b) => a - b)
  if (!prices.length) return { lowest: null, median: null, highest: null }
  const middle = Math.floor(prices.length / 2)
  // Preserve exact half-cent arithmetic, then round half up for two-decimal display.
  const lower = prices[middle - 1] || 0
  const upper = prices[middle]!
  const median = prices.length % 2 ? upper : lower + Math.floor((upper - lower + 1) / 2)
  return { lowest: prices[0]!, median, highest: prices[prices.length - 1]! }
}
