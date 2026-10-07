import type { OpportunityCandidate } from '../api/merchant'

export type SearchPage = 'opportunities' | 'comparison'
export interface SearchResultState {
  keyword: string
  sourceMode?: 'keyword' | 'shop'
  shopUrl?: string
  minPrice?: number | ''
  maxPrice?: number | ''
  minWantCount?: number | ''
  sortMode?: 'relevance' | 'price-asc' | 'price-desc' | 'want-asc' | 'want-desc'
  results: OpportunityCandidate[]
  pageNumber: number
  hasMore: boolean
  total: number
  fetchedAt: string
}

type StorageLike = Pick<Storage, 'getItem' | 'setItem' | 'removeItem'>
interface CacheData {
  version: 1
  owner: string
  entries: Record<string, SearchResultState>
  lastAccounts: Partial<Record<SearchPage, string>>
}
export const SEARCH_CACHE_KEY = 'xianyu_search_results_v1'
const copy = <T>(value: T): T => JSON.parse(JSON.stringify(value))
const keyFor = (page: SearchPage, accountId: string | number) => `${page}:${accountId}`

function validState(value: unknown): value is SearchResultState {
  if (!value || typeof value !== 'object') return false
  const state = value as SearchResultState
  return typeof state.keyword === 'string'
    && (state.sourceMode == null || ['keyword', 'shop'].includes(state.sourceMode))
    && (state.shopUrl == null || typeof state.shopUrl === 'string')
    && [state.minPrice, state.maxPrice].every(v => v == null || v === '' || (typeof v === 'number' && Number.isFinite(v)))
    && (state.sortMode == null || ['relevance', 'price-asc', 'price-desc', 'want-asc', 'want-desc'].includes(state.sortMode))
    && (state.minWantCount == null || state.minWantCount === '' || (Number.isSafeInteger(state.minWantCount) && Number(state.minWantCount) >= 0))
    && Array.isArray(state.results)
    && state.results.every(item => item && typeof item.itemId === 'string' && typeof item.title === 'string'
      && [item.soldCountText, item.wantCountText].every(v => v == null || typeof v === 'string')
      && (item.images == null || (Array.isArray(item.images) && item.images.every(v => typeof v === 'string')))
      && (!item.competitorSnapshot || (Array.isArray(item.competitorSnapshot.skus)
        && item.competitorSnapshot.skus.every(sku => sku && Array.isArray(sku.properties)
          && sku.properties.every(property => property && typeof property.name === 'string' && typeof property.value === 'string')))))
    && Number.isInteger(state.pageNumber) && state.pageNumber >= 1
    && typeof state.hasMore === 'boolean' && Number.isFinite(state.total) && state.total >= 0
    && typeof state.fetchedAt === 'string' && Number.isFinite(Date.parse(state.fetchedAt))
}

// Explicit allowlist: wizard state, selections and publishing parameters never enter storage.
function searchFacts(state: SearchResultState): SearchResultState {
  return copy({ keyword: state.keyword, sourceMode: state.sourceMode, shopUrl: state.shopUrl,
    minPrice: state.minPrice, maxPrice: state.maxPrice, minWantCount: state.minWantCount, sortMode: state.sortMode,
    results: state.results, pageNumber: state.pageNumber, hasMore: state.hasMore,
    total: state.total, fetchedAt: state.fetchedAt })
}

export function createSearchCache(storage: () => StorageLike | undefined) {
  let data: CacheData | undefined
  let persistent = true
  const flush = () => {
    try {
      const target = storage()
      if (!target) throw new Error('Storage unavailable')
      if (data) target.setItem(SEARCH_CACHE_KEY, JSON.stringify(data))
      else target.removeItem(SEARCH_CACHE_KEY)
      persistent = true
    } catch { persistent = false }
    return persistent
  }
  const clear = () => { data = undefined; return flush() }
  const activate = (owner: string | null) => {
    if (!owner) { clear(); return false }
    if (data?.owner === owner) return true
    if (!data) {
      try {
        const stored = JSON.parse(storage()?.getItem(SEARCH_CACHE_KEY) || 'null') as CacheData | null
        if (stored?.version === 1 && stored.owner === owner && stored.entries && stored.lastAccounts
          && Object.entries(stored.entries).every(([key, value]) => /^(opportunities|comparison):[^:]+$/.test(key) && validState(value))
          && Object.entries(stored.lastAccounts).every(([page, id]) => ['opportunities', 'comparison'].includes(page) && typeof id === 'string')) {
          data = stored
          return true
        }
      } catch { /* Corrupt or inaccessible storage is ignored. */ }
    }
    data = { version: 1, owner, entries: {}, lastAccounts: {} }
    flush()
    return true
  }
  const prune = (accessible: Array<string | number>) => {
    for (const key of Object.keys(data!.entries)) {
      if (!accessible.some(id => key.endsWith(`:${id}`))) delete data!.entries[key]
    }
    for (const page of ['opportunities', 'comparison'] as const) {
      if (!accessible.some(id => String(id) === data!.lastAccounts[page])) delete data!.lastAccounts[page]
    }
  }
  return {
    clear, activate,
    pruneAccounts(owner: string | null, accessible: Array<string | number>) {
      if (!activate(owner)) return false
      prune(accessible)
      return flush()
    },
    isPersistent: () => persistent,
    read(owner: string | null, page: SearchPage, accountId: string | number) {
      if (!activate(owner)) return undefined
      const state = data!.entries[keyFor(page, accountId)]
      return state ? searchFacts(state) : undefined
    },
    lastAccount(owner: string | null, page: SearchPage) {
      return activate(owner) ? data!.lastAccounts[page] : undefined
    },
    selectAccount(owner: string | null, page: SearchPage, accountId: string | number, accessible: Array<string | number>) {
      if (!activate(owner)) return false
      // Drop revoked account records before any restoration.
      prune(accessible)
      data!.lastAccounts[page] = String(accountId)
      return flush()
    },
    save(owner: string | null, page: SearchPage, accountId: string | number, state: SearchResultState) {
      if (!activate(owner)) return false
      data!.entries[keyFor(page, accountId)] = searchFacts(state)
      return flush()
    },
    remove(owner: string | null, page: SearchPage, accountId: string | number) {
      if (!activate(owner)) return false
      delete data!.entries[keyFor(page, accountId)]
      return flush()
    }
  }
}

export const searchCache = createSearchCache(() => window.sessionStorage)
