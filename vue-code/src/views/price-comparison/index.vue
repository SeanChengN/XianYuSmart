<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { getAccountList } from '@/api/account'
import {
  addOpportunitiesToSupply,
  getSellerPublicProfile,
  searchOpportunities,
  type OpportunityCandidate,
  type SellerPublicProfile
} from '@/api/merchant'
import type { Account } from '@/types'
import { toast } from '@/utils/toast'
import '@/styles/merchant-workbench.css'
import CompetitorSkuPanel from '@/components/CompetitorSkuPanel.vue'
import { displayPriceCents, displayPriceSummary, formatMoneyCents } from '@/utils/competitor-price'
import ProductReferenceStats from '@/components/ProductReferenceStats.vue'
import { searchCache, type SearchResultState } from '@/utils/search-cache'
import { getAuthUsername } from '@/utils/request'
import { filterSearchResults, validWantMinimum, type ResultSort } from '@/utils/search-result-filter'
import { hasPermission } from '@/utils/permission'

const minWantCount = ref<number | ''>('')
const addingSupply = ref(false)
const canAddSupply = computed(() => hasPermission('action:operations-write'))


const accounts = ref<Account[]>([])
const accountId = ref<number>()
const keyword = ref('')
const minPrice = ref<number | ''>('')
const maxPrice = ref<number | ''>('')
const sortMode = ref<ResultSort>('relevance')
const loading = ref(false)
const searched = ref(false)
const results = ref<OpportunityCandidate[]>([])
const platformTotal = ref(0)
const fetchedAt = ref('')
const cacheWarning = ref(false)
const owner = getAuthUsername()
let mounted = true
let restoring = false
let savedSearch: SearchResultState | undefined
const sellerProfiles = ref<Record<string, SellerPublicProfile>>({})
const sellerProfileLoading = ref(new Set<string>())
const sellerProfileErrors = ref(new Set<string>())
const selectedSellerItem = ref<OpportunityCandidate>()

let searchGeneration = 0
watch(accountId, () => {
  restoring = true
  searchGeneration++
  results.value = []
  searched.value = false
  loading.value = false
  sellerProfiles.value = {}
  sellerProfileLoading.value = new Set()
  sellerProfileErrors.value = new Set()
  selectedSellerItem.value = undefined
  keyword.value = ''
  minPrice.value = ''
  maxPrice.value = ''
  sortMode.value = 'relevance'
  minWantCount.value = ''
  platformTotal.value = 0
  fetchedAt.value = ''
  savedSearch = undefined
  if (accountId.value && accounts.value.some(account => String(account.id) === String(accountId.value))) {
    cacheWarning.value = !searchCache.selectAccount(owner, 'comparison', accountId.value, accounts.value.map(account => account.id))
    savedSearch = searchCache.read(owner, 'comparison', accountId.value)
    if (savedSearch) {
      minWantCount.value = savedSearch.minWantCount ?? ''
      keyword.value = savedSearch.keyword
      minPrice.value = savedSearch.minPrice ?? ''
      maxPrice.value = savedSearch.maxPrice ?? ''
      sortMode.value = savedSearch.sortMode || 'relevance'
      results.value = savedSearch.results
      platformTotal.value = savedSearch.total
      fetchedAt.value = savedSearch.fetchedAt
      searched.value = true
    }
  }
  restoring = false
}, { flush: 'sync' })
const persistResults = () => {
  if (restoring || !mounted || owner !== getAuthUsername() || !accountId.value || !savedSearch) return
  cacheWarning.value = !searchCache.save(owner, 'comparison', accountId.value, {
    ...savedSearch, results: results.value, minPrice: minPrice.value, maxPrice: maxPrice.value, minWantCount: validWantMinimum(minWantCount.value) ? minWantCount.value : '', sortMode: sortMode.value
  })
}
watch(results, persistResults, { deep: true })
watch([minPrice, maxPrice, minWantCount, sortMode], persistResults, { flush: 'sync' })
watch(keyword, () => { if (!restoring) { searchGeneration++; loading.value = false } }, { flush: 'sync' })
const clearResults = () => {
  searchGeneration++
  loading.value = false
  results.value = []
  searched.value = false
  fetchedAt.value = ''
  platformTotal.value = 0
  savedSearch = undefined
  sellerProfiles.value = {}
  sellerProfileLoading.value = new Set()
  sellerProfileErrors.value = new Set()
  selectedSellerItem.value = undefined
  if (accountId.value) cacheWarning.value = !searchCache.remove(owner, 'comparison', accountId.value)
}
onBeforeUnmount(() => { mounted = false; searchGeneration++ })
const priceNumber = (value?: string | number) => displayPriceCents(value) ?? 0

const filteredResults = computed(() => {
  const minimum = minPrice.value === '' ? undefined : displayPriceCents(minPrice.value)
  const maximum = maxPrice.value === '' ? undefined : displayPriceCents(maxPrice.value)
  const list = results.value.filter(item => {
    const price = priceNumber(item.price)
    if (minimum != null && price < minimum) return false
    if (maximum != null && price > maximum) return false
    return true
  })
  return filterSearchResults(list, minWantCount.value, sortMode.value)
})

const priceSummary = computed(() => displayPriceSummary(filteredResults.value.map(item => item.price)))

const profileKey = (item: OpportunityCandidate) => item.sellerId || item.itemId
const profileFor = (item: OpportunityCandidate): SellerPublicProfile => sellerProfiles.value[profileKey(item)] || {
  itemId: item.itemId,
  sellerId: item.sellerId,
  sellerNick: item.sellerNick,
  sellerAvatar: item.sellerAvatar,
  sellerProfileUrl: item.sellerId ? `https://www.goofish.com/personal?userId=${item.sellerId}` : undefined,
  sellerCredit: item.sellerCredit,
  sellerPositiveCount: item.sellerPositiveCount,
  sellerNeutralCount: item.sellerNeutralCount,
  sellerNegativeCount: item.sellerNegativeCount
}
const hasValue = (value?: string | number) => value != null && value !== ''
const selectedSellerProfile = computed(() => selectedSellerItem.value ? profileFor(selectedSellerItem.value) : undefined)

const loadSellerProfile = async (item: OpportunityCandidate) => {
  if (!accountId.value) return
  const key = profileKey(item)
  if (sellerProfiles.value[key] || sellerProfileLoading.value.has(key)) return
  const current = searchGeneration
  sellerProfileLoading.value = new Set([...sellerProfileLoading.value, key])
  sellerProfileErrors.value.delete(key)
  try {
    const response = await getSellerPublicProfile({ itemId: item.itemId, xianyuAccountId: accountId.value })
    if (current !== searchGeneration) return
    if (!response.data) throw new Error(response.msg || '该商品暂未返回卖家口碑')
    const profile = { ...profileFor(item), ...response.data }
    const profiles = { ...sellerProfiles.value, [key]: profile }
    if (profile.sellerId) profiles[profile.sellerId] = profile
    sellerProfiles.value = profiles
  } catch {
    if (current !== searchGeneration) return
    sellerProfileErrors.value = new Set([...sellerProfileErrors.value, key])
  } finally {
    if (current !== searchGeneration) return
    const loadingKeys = new Set(sellerProfileLoading.value)
    loadingKeys.delete(key)
    sellerProfileLoading.value = loadingKeys
  }
}

const showSellerProfile = (item: OpportunityCandidate) => {
  selectedSellerItem.value = item
  void loadSellerProfile(item)
}

const loadAccounts = async () => {
  const response = await getAccountList()
  if (!mounted || owner !== getAuthUsername()) return
  accounts.value = response.data?.accounts || []
  cacheWarning.value = !searchCache.pruneAccounts(owner, accounts.value.map(account => account.id))
  const last = searchCache.lastAccount(owner, 'comparison')
  accountId.value = accounts.value.find(account => String(account.id) === last)?.id || accounts.value[0]?.id
}

const search = async () => {
  if (!accountId.value) return toast.warning('请选择用于比价的账号')
  if (!keyword.value.trim()) return toast.warning('请输入需要比价的商品关键词')
  if (minPrice.value !== '' && maxPrice.value !== '' && minPrice.value > maxPrice.value) {
    return toast.warning('最低价不能高于最高价')
  }
  const current = ++searchGeneration
  const queryKeyword = keyword.value
  loading.value = true
  try {
    const response = await searchOpportunities({
      xianyuAccountId: accountId.value,
      keyword: keyword.value,
      pageNumber: 1,
      limit: 50
    })
    if (current !== searchGeneration || !mounted || owner !== getAuthUsername()) return
    results.value = response.data?.items || []
    platformTotal.value = Number(response.data?.total || results.value.length)
    sellerProfiles.value = {}
    sellerProfileLoading.value = new Set()
    sellerProfileErrors.value = new Set()
    selectedSellerItem.value = undefined
    searched.value = true
    fetchedAt.value = new Date().toISOString()
    savedSearch = { keyword: queryKeyword, results: results.value, pageNumber: 1,
      hasMore: Boolean(response.data?.hasMore), total: platformTotal.value, fetchedAt: fetchedAt.value }
    persistResults()
  } catch { /* Keep the previous successful results when the platform search fails. */ }
  finally {
    if (current === searchGeneration) loading.value = false
  }
}

const addSupply = async (item: OpportunityCandidate) => {
  if (addingSupply.value || !accountId.value || !canAddSupply.value) return
  const current = searchGeneration, selectedAccount = accountId.value
  addingSupply.value = true
  try {
    const response = await addOpportunitiesToSupply({ candidates: [item], xianyuAccountId: selectedAccount })
    if (mounted && current === searchGeneration && selectedAccount === accountId.value && owner === getAuthUsername() && response.data)
      toast.success(`新增 ${response.data.addedCount} 件，已有 ${response.data.existingCount} 件`)
  } catch { /* Request utility displays failures; preserve the result list. */ }
  finally { addingSupply.value = false }
}

const resetFilters = () => {
  minWantCount.value = ''
  minPrice.value = ''
  maxPrice.value = ''
  sortMode.value = 'relevance'
}

onMounted(loadAccounts)
</script>

<template>
  <section class="workbench comparison">
    <header class="workbench__header">
      <div>
        <h1>全站比价</h1>
        <p>查看搜索展示价和逐 SKU 价格。搜索汇总尚未按地区、币种和面值对齐，不代表同规格比价。</p>
      </div>
    </header>

    <div class="workbench__card comparison__search">
      <select v-model="accountId" class="workbench__select">
        <option v-for="account in accounts" :key="account.id" :value="account.id">{{ account.accountNote || account.unb }}</option>
      </select>
      <input v-model="keyword" class="workbench__input" placeholder="输入商品关键词，例如：iPhone 15 256G" @keyup.enter="search">
      <button class="workbench__btn workbench__btn--primary" :disabled="loading" @click="search">{{ loading ? '比价中' : '开始比价' }}</button>
    </div>

    <div class="workbench__card comparison__filters">
      <label class="workbench__field">最低价<input v-model.number="minPrice" class="workbench__input" type="number" min="0" placeholder="不限"></label>
      <label class="workbench__field">最高价<input v-model.number="maxPrice" class="workbench__input" type="number" min="0" placeholder="不限"></label>
      <label class="workbench__field">最低想要人数<input v-model.number="minWantCount" class="workbench__input" type="number" min="0" step="1" placeholder="不限"></label>
      <label class="workbench__field">排序
        <select v-model="sortMode" class="workbench__select">
          <option value="relevance">综合匹配</option>
          <option value="price-asc">价格从低到高</option>
          <option value="price-desc">价格从高到低</option>
          <option value="want-asc">想要人数从少到多</option>
          <option value="want-desc">想要人数从多到少</option>
        </select>
      </label>
      <button class="workbench__btn" @click="resetFilters">重置筛选</button>
    </div>

    <div v-if="searched" class="comparison__result-meta">
      <span>结果获取时间 {{ new Date(fetchedAt).toLocaleString('zh-CN') }}</span>
      <button class="workbench__btn" @click="clearResults">清空搜索结果</button>
    </div>
    <p class="search-filter-note">筛选和排序仅作用于已加载结果，不会自动搜索或加载下一页。</p>
    <p v-if="!validWantMinimum(minWantCount)" class="search-cache-warning">最低想要人数请输入非负整数。</p>
    <p v-if="cacheWarning" class="search-cache-warning">浏览器无法保存搜索结果，当前页面仍会保留；刷新后可能丢失。</p>
    <div v-if="searched" class="comparison__metrics">
      <div class="workbench__card"><span>当前结果</span><strong>{{ filteredResults.length }}</strong><small>平台匹配 {{ platformTotal || results.length }} 件</small></div>
      <div class="workbench__card"><span>搜索展示最低价</span><strong>¥ {{ formatMoneyCents(priceSummary.lowest) }}</strong><small>当前筛选范围</small></div>
      <div class="workbench__card"><span>搜索展示中位价</span><strong>¥ {{ formatMoneyCents(priceSummary.median) }}</strong><small>包含不同规格，仅供搜索参考</small></div>
      <div class="workbench__card"><span>搜索展示最高价</span><strong>¥ {{ formatMoneyCents(priceSummary.highest) }}</strong><small>当前筛选范围</small></div>
    </div>

    <div class="comparison__list workbench__section">
      <article v-for="item in filteredResults" :key="item.itemId" class="workbench__card comparison__item">
        <img v-if="item.images?.[0]" :src="item.images[0]" alt="">
        <div v-else class="comparison__image-empty">暂无图片</div>
        <div class="comparison__content">
          <h2>{{ item.title }}</h2>
          <ProductReferenceStats :sold-count-text="item.soldCountText" :want-count-text="item.wantCountText" />
          <div class="comparison__seller">
            <strong>{{ item.sellerNick || '平台卖家' }}</strong>
            <span v-if="hasValue(profileFor(item)?.sellerCredit)">信用 {{ profileFor(item)?.sellerCredit }}</span>
          </div>
          <div class="comparison__reviews">
            <span v-if="hasValue(profileFor(item)?.sellerPositiveCount)" class="comparison__positive">历史好评 {{ profileFor(item)?.sellerPositiveCount }}</span>
            <span v-if="hasValue(profileFor(item)?.sellerNeutralCount)">一般评价 {{ profileFor(item)?.sellerNeutralCount }}</span>
            <span v-if="hasValue(profileFor(item)?.sellerNegativeCount)" class="comparison__negative">历史差评 {{ profileFor(item)?.sellerNegativeCount }}</span>
            <button class="comparison__review-link" @click="showSellerProfile(item)">查看卖家口碑</button>
          </div>
          <small>信用与评价均来自该卖家的平台公开历史统计，不使用商品评价或推测数据。</small>
          <CompetitorSkuPanel :item-id="item.itemId" :account-id="accountId" :initial-snapshot="item.competitorSnapshot"
            @loaded="detail => item.competitorSnapshot = detail.competitorSnapshot" />
        </div>
        <div class="comparison__action">
          <strong>¥ {{ formatMoneyCents(displayPriceCents(item.price)) }}</strong>
          <small>搜索展示价</small>
          <a class="workbench__btn" :href="item.sourceUrl" target="_blank" rel="noopener noreferrer">查看原商品</a>
          <button v-if="canAddSupply" class="workbench__btn" :disabled="addingSupply" @click="addSupply(item)">{{ addingSupply ? '入库中…' : '加入货源库' }}</button>
        </div>
      </article>
      <div v-if="searched && !filteredResults.length" class="workbench__card workbench__empty">当前筛选条件下没有结果，可调整筛选条件后查看。</div>
      <div v-else-if="!searched" class="workbench__card workbench__empty">输入关键词后开始全站比价。</div>
    </div>

    <div v-if="selectedSellerItem" class="comparison__dialog-mask" role="presentation" @click.self="selectedSellerItem = undefined">
      <article class="workbench__card comparison__dialog" role="dialog" aria-modal="true" aria-label="卖家口碑">
        <header>
          <div>
            <h2>卖家口碑</h2>
            <p>{{ selectedSellerProfile?.sellerNick || selectedSellerItem.sellerNick || '平台卖家' }}</p>
          </div>
          <button class="comparison__dialog-close" aria-label="关闭" @click="selectedSellerItem = undefined">×</button>
        </header>
        <div v-if="sellerProfileLoading.has(profileKey(selectedSellerItem))" class="workbench__empty">正在读取卖家公开数据…</div>
        <template v-else-if="selectedSellerProfile">
          <div v-if="hasValue(selectedSellerProfile.sellerCredit)" class="comparison__credit">
            <span>卖家信用</span><strong>{{ selectedSellerProfile.sellerCredit }}</strong>
          </div>
          <div class="comparison__dialog-metrics">
            <div v-if="hasValue(selectedSellerProfile.sellerPositiveCount)"><span>历史好评</span><strong>{{ selectedSellerProfile.sellerPositiveCount }}</strong></div>
            <div v-if="hasValue(selectedSellerProfile.sellerNeutralCount)"><span>一般评价</span><strong>{{ selectedSellerProfile.sellerNeutralCount }}</strong></div>
            <div v-if="hasValue(selectedSellerProfile.sellerNegativeCount)"><span>历史差评</span><strong>{{ selectedSellerProfile.sellerNegativeCount }}</strong></div>
          </div>
          <p v-if="!hasValue(selectedSellerProfile.sellerCredit)
            && !hasValue(selectedSellerProfile.sellerPositiveCount)
            && !hasValue(selectedSellerProfile.sellerNeutralCount)
            && !hasValue(selectedSellerProfile.sellerNegativeCount)" class="comparison__dialog-note">
            {{ sellerProfileErrors.has(profileKey(selectedSellerItem))
              ? '当前账号搜索正常；本次商品详情未开放卖家统计，不代表账号异常。'
              : '该商品公开数据中没有返回卖家信用或口碑统计。' }}
          </p>
          <p class="comparison__dialog-note">只展示平台返回的卖家历史统计；评价文字与图片在卖家公开主页查看。</p>
          <a v-if="selectedSellerProfile.sellerProfileUrl" class="workbench__btn workbench__btn--primary"
            :href="selectedSellerProfile.sellerProfileUrl" target="_blank" rel="noopener noreferrer">查看好评 / 差评明细</a>
        </template>
        <div v-else class="workbench__empty">该商品暂未返回卖家公开统计，可直接查看卖家主页。</div>
      </article>
    </div>
  </section>
</template>

<style scoped>
.search-filter-note { color: #667085; font-size: 12px; }
.comparison__search { display: grid; grid-template-columns: 190px minmax(0, 1fr) auto; gap: 10px; }
.comparison__result-meta { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 8px; margin-top: 12px; color: #667085; font-size: 12px; }
.search-cache-warning { color: #b54708; font-size: 12px; }
.comparison__filters { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)) auto; align-items: end; gap: 12px; margin-top: 12px; }
.comparison__metrics { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 12px; margin-top: 12px; }
.comparison__metrics span, .comparison__metrics small { display: block; color: #667085; font-size: 12px; }
.comparison__metrics strong { display: block; margin: 7px 0 3px; font-size: 22px; }
.comparison__list { display: flex; flex-direction: column; gap: 10px; }
.comparison__item { display: grid; grid-template-columns: 92px minmax(0, 1fr) 130px; align-items: center; gap: 14px; }
.comparison__item > img, .comparison__image-empty { width: 92px; height: 92px; border-radius: 8px; object-fit: cover; background: #f2f4f7; }
.comparison__image-empty { display: grid; place-items: center; color: #98a2b3; font-size: 11px; }
.comparison__content { min-width: 0; }
.comparison__content h2 { margin: 0 0 9px; overflow: hidden; font-size: 15px; line-height: 1.5; text-overflow: ellipsis; white-space: nowrap; }
.comparison__seller, .comparison__reviews { display: flex; flex-wrap: wrap; gap: 7px 14px; color: #667085; font-size: 12px; }
.comparison__reviews { margin-top: 8px; }
.comparison__positive { color: #067647; }
.comparison__negative { color: #b42318; }
.comparison__review-link { padding: 0; border: 0; color: #155eef; background: transparent; cursor: pointer; }
.comparison__content small { display: block; margin-top: 8px; color: #98a2b3; }
.comparison__action { display: flex; align-items: stretch; flex-direction: column; gap: 10px; }
.comparison__action > strong { color: #d92d20; font-size: 22px; text-align: right; white-space: nowrap; }
.comparison__dialog-mask { position: fixed; z-index: 1000; inset: 0; display: grid; padding: 20px; place-items: center; background: rgb(15 23 42 / 42%); }
.comparison__dialog { width: min(520px, 100%); max-height: calc(100dvh - 40px); overflow-y: auto; }
.comparison__dialog header { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; }
.comparison__dialog h2, .comparison__dialog p { margin: 0; }
.comparison__dialog header p { margin-top: 4px; color: #667085; }
.comparison__dialog-close { border: 0; color: #667085; background: transparent; font-size: 26px; line-height: 1; cursor: pointer; }
.comparison__credit { display: flex; align-items: center; justify-content: space-between; margin-top: 20px; padding: 12px 14px; border-radius: 8px; background: #f5f8ff; }
.comparison__dialog-metrics { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 10px; margin-top: 12px; }
.comparison__dialog-metrics > div { padding: 13px; border: 1px solid #eaecf0; border-radius: 8px; }
.comparison__dialog-metrics span, .comparison__dialog-metrics strong { display: block; }
.comparison__dialog-metrics span { color: #667085; font-size: 12px; }
.comparison__dialog-metrics strong { margin-top: 6px; font-size: 20px; }
.comparison__dialog-note { margin: 14px 0 !important; color: #667085; font-size: 13px; line-height: 1.7; }
@media (max-width: 900px) {
  .comparison__filters, .comparison__metrics { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}
@media (max-width: 767px) {
  .comparison__search, .comparison__filters, .comparison__metrics { grid-template-columns: 1fr; }
  .comparison__item { grid-template-columns: 72px minmax(0, 1fr); align-items: start; }
  .comparison__item > img, .comparison__image-empty { width: 72px; height: 72px; }
  .comparison__content h2 { display: -webkit-box; white-space: normal; -webkit-box-orient: vertical; -webkit-line-clamp: 2; }
  .comparison__action { grid-column: 1 / -1; align-items: stretch; flex-direction: column; }
  .comparison__action > strong { text-align: left; }
  .comparison__dialog-mask { align-items: end; padding: 0; }
  .comparison__dialog { width: 100%; max-height: calc(100dvh - 24px); border-radius: 14px 14px 0 0; padding-bottom: max(18px, env(safe-area-inset-bottom)); }
  .comparison__dialog-metrics { grid-template-columns: 1fr; }
}
</style>
