import { displayPriceCents } from './competitor-price.ts'

export type ResultSort = 'relevance' | 'price-asc' | 'price-desc' | 'want-asc' | 'want-desc'
export function parseWantCount(label?: string | null): number | null {
  if (typeof label !== 'string') return null
  const match = /^(?:约)?(\d+(?:\.\d+)?)(万|亿)?\+?人想要$/.exec(label.trim())
  if (!match || (!match[2] && match[1]!.includes('.'))) return null
  const count = Math.round(Number(match[1]) * (match[2] === '亿' ? 100000000 : match[2] === '万' ? 10000 : 1))
  return Number.isSafeInteger(count) && count >= 0 ? count : null
}
export function validWantMinimum(value: unknown): value is number | '' {
  return value === '' || (typeof value === 'number' && Number.isSafeInteger(value) && value >= 0)
}
export function filterSearchResults<T extends { wantCountText?: string | null; price?: string | number }>(
  results: T[], minimum: number | '', sort: ResultSort
): T[] {
  const list = results.filter(item => {
    if (minimum === '') return true
    const count = parseWantCount(item.wantCountText)
    return validWantMinimum(minimum) && count != null && count >= minimum
  })
  if (sort === 'relevance') return list
  return list.map((item, index) => ({ item, index })).sort((a, b) => {
    if (sort === 'want-asc' || sort === 'want-desc') {
      const av = parseWantCount(a.item.wantCountText), bv = parseWantCount(b.item.wantCountText)
      if (av == null || bv == null) return av == null && bv == null ? a.index - b.index : av == null ? 1 : -1
      return (sort === 'want-asc' ? av - bv : bv - av) || a.index - b.index
    }
    const av = displayPriceCents(a.item.price) ?? 0, bv = displayPriceCents(b.item.price) ?? 0
    return (sort === 'price-asc' ? av - bv : bv - av) || a.index - b.index
  }).map(entry => entry.item)
}
