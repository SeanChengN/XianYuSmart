import assert from 'node:assert/strict'
import { test } from 'node:test'
import { createSearchCache, SEARCH_CACHE_KEY, type SearchResultState } from '../src/utils/search-cache.ts'

const sample = (): SearchResultState => ({ keyword: '苹果礼品', sourceMode: 'keyword', shopUrl: '',
  results: [{ itemId: '123', title: '参考商品', sourceUrl: '', opportunityScore: 90, riskLevel: 'LOW', matchReason: '',
    wantCountText: '879人想要', competitorSnapshot: { itemId: '123', status: 'AVAILABLE', skus: [] } }],
  fetchedAt: '2026-10-07T00:00:00Z', pageNumber: 2, hasMore: true, total: 99 })
function setup() {
  const values = new Map<string, string>()
  let blocked = false
  const storage = { getItem: (key: string) => values.get(key) ?? null,
    setItem: (key: string, value: string) => { if (blocked) throw Error('quota'); values.set(key, value) },
    removeItem: (key: string) => { if (blocked) throw Error('denied'); values.delete(key) } }
  return { values, cache: createSearchCache(() => storage), storage, block: () => { blocked = true } }
}

test('isolates page, account and owner, remembers last accessible account', () => {
  const { cache } = setup()
  cache.save('alice', 'opportunities', 2, sample())
  cache.selectAccount('alice', 'opportunities', 2, [2, 3])
  assert.equal(cache.lastAccount('alice', 'opportunities'), '2')
  assert.equal(cache.read('alice', 'opportunities', '2')?.results[0]?.wantCountText, '879人想要')
  assert.equal(cache.read('alice', 'comparison', 2), undefined)
  assert.equal(cache.read('alice', 'opportunities', 3), undefined)
  assert.equal(cache.read('bob', 'opportunities', 2), undefined)
  assert.equal(cache.read('alice', 'opportunities', 2), undefined)
})
test('refresh restores paging, query, filters and snapshots, excludes wizard state', () => {
  const { cache, storage } = setup()
  cache.save('alice', 'comparison', 2, { ...sample(), minPrice: 10, maxPrice: 100, sortMode: 'price-desc',
    draft: { amount: 90 }, step: 3, active: '123', selectedIds: ['123'] } as SearchResultState)
  const fresh = createSearchCache(() => storage).read('alice', 'comparison', 2)!
  assert.equal(fresh.pageNumber, 2)
  assert.equal(fresh.sortMode, 'price-desc')
  assert.equal(fresh.results[0]?.competitorSnapshot?.status, 'AVAILABLE')
  for (const key of ['draft', 'step', 'active', 'selectedIds']) assert.equal(key in fresh, false)
  fresh.results.splice(0)
  assert.equal(cache.read('alice', 'comparison', 2)?.results.length, 1)
})
test('revoked accounts are removed including the last selected account', () => {
  const { cache } = setup()
  cache.save('alice', 'opportunities', 2, sample())
  cache.selectAccount('alice', 'opportunities', 2, [2])
  cache.pruneAccounts('alice', [])
  assert.equal(cache.read('alice', 'opportunities', 2), undefined)
  assert.equal(cache.lastAccount('alice', 'opportunities'), undefined)
})
test('corrupt cache and malformed results are ignored', () => {
  const { storage, values } = setup()
  for (const text of ['broken', JSON.stringify({ version: 1, owner: 'alice', entries: { 'comparison:2': { ...sample(), results: [null] } }, lastAccounts: {} }),
    JSON.stringify({ version: 1, owner: 'alice', entries: { 'comparison:2': { ...sample(), results: [{ itemId: '123', title: 'bad', competitorSnapshot: { skus: null } }] } }, lastAccounts: {} }),
    JSON.stringify({ version: 1, owner: 'alice', entries: { 'comparison:2': { ...sample(), results: [{ itemId: '123', title: 'bad', competitorSnapshot: { skus: [{ properties: [null] }] } }] } }, lastAccounts: {} })]) {
    values.set(SEARCH_CACHE_KEY, text)
    assert.equal(createSearchCache(() => storage).read('alice', 'comparison', 2), undefined)
  }
})
test('quota errors keep in-memory results until clear', () => {
  const { cache, block } = setup()
  cache.activate('alice')
  block()
  assert.equal(cache.save('alice', 'opportunities', 2, sample()), false)
  assert.equal(cache.isPersistent(), false)
  assert.equal(cache.read('alice', 'opportunities', 2)?.results.length, 1)
  cache.clear()
  assert.equal(cache.read('alice', 'opportunities', 2), undefined)
})
test('manual clear and logout discard memory and session results', () => {
  const { cache, storage } = setup()
  cache.save('alice', 'opportunities', 2, sample())
  cache.save('alice', 'comparison', 2, sample())
  cache.remove('alice', 'comparison', 2)
  assert.equal(cache.read('alice', 'comparison', 2), undefined)
  assert.equal(cache.read('alice', 'opportunities', 2)?.results.length, 1)
  cache.activate(null)
  assert.equal(storage.getItem(SEARCH_CACHE_KEY), null)
  assert.equal(cache.read('alice', 'opportunities', 2), undefined)
})
