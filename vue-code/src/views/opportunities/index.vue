<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { getAccountList } from '@/api/account'
import { addOpportunitiesToSupply, createPublishPlan, crawlShopOpportunities, generateOpportunityImage, importOpportunities, polishOpportunity, searchOpportunities, type OpportunityCandidate } from '@/api/merchant'
import PublishAddressFields from '@/components/PublishAddressFields.vue'
import type { PublishAddress } from '@/data/publish-address'
import type { Account } from '@/types'
import { toast } from '@/utils/toast'
import '@/styles/merchant-workbench.css'
import CompetitorSkuPanel from '@/components/CompetitorSkuPanel.vue'
import { displayPriceCents, formatMoneyCents } from '@/utils/competitor-price'
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
const sourceMode = ref<'keyword' | 'shop'>('keyword')
const keyword = ref('')
const shopUrl = ref('')
const loading = ref(false)
const loadingMore = ref(false)
const results = ref<OpportunityCandidate[]>([])
const selectedIds = ref<string[]>([])
const active = ref<OpportunityCandidate>()
const step = ref(1)
const maxStep = ref(1)
const searched = ref(false)
const pageNumber = ref(1)
const hasMore = ref(false)
const total = ref(0)
const fetchedAt = ref('')
const cacheWarning = ref(false)
const owner = getAuthUsername()
let mounted = true
let restoring = false
let savedSearch: SearchResultState | undefined
const emptyDraft = () => ({
  name: '',
  description: '',
  amount: '' as number | '',
  stock: '' as number | '',
  category: '虚拟商品',
  province: '北京市',
  city: '北京市',
  district: '',
  divisionId: '',
  gps: '',
  poiId: '',
  poiName: '',
  deliveryMethod: '线上交付',
  images: [] as string[]
})
const draft = reactive(emptyDraft())

const sortMode = ref<ResultSort>('relevance')
const filteredResults = computed(() => filterSearchResults(results.value, minWantCount.value, sortMode.value))
watch(filteredResults, visible => {
  const ids = new Set(visible.map(item => item.itemId))
  selectedIds.value = selectedIds.value.filter(id => ids.has(id))
  if (step.value === 1 && active.value && !ids.has(active.value.itemId)) active.value = undefined
}, { flush: 'sync' })
const selectedCandidates = computed(() => filteredResults.value.filter(item => selectedIds.value.includes(item.itemId)))
const publishAddress = computed<PublishAddress>({
  get: () => ({
    province: draft.province,
    city: draft.city,
    district: draft.district,
    divisionId: draft.divisionId,
    gps: draft.gps,
    poiId: draft.poiId,
    poiName: draft.poiName
  }),
  set: value => Object.assign(draft, value)
})

const loadAccounts = async () => {
  const response = await getAccountList()
  if (!mounted || owner !== getAuthUsername()) return
  accounts.value = response.data?.accounts || []
  cacheWarning.value = !searchCache.pruneAccounts(owner, accounts.value.map(account => account.id))
  const last = searchCache.lastAccount(owner, 'opportunities')
  accountId.value = accounts.value.find(account => String(account.id) === last)?.id || accounts.value[0]?.id
}

let searchGeneration = 0
let captureGeneration = 0
const capturing = ref(false)

const resetResults = () => {
  searchGeneration++
  captureGeneration++
  capturing.value = false
  results.value = []
  selectedIds.value = []
  active.value = undefined
  searched.value = false
  pageNumber.value = 1
  hasMore.value = false
  total.value = 0
  fetchedAt.value = ''
  savedSearch = undefined
}

watch(accountId, () => {
  restoring = true
  resetResults()
  loading.value = false
  loadingMore.value = false
  step.value = 1
  maxStep.value = 1
  Object.assign(draft, emptyDraft())
  sourceMode.value = 'keyword'
  keyword.value = ''
  shopUrl.value = ''
  minWantCount.value = ''
  sortMode.value = 'relevance'
  if (accountId.value && accounts.value.some(account => String(account.id) === String(accountId.value))) {
    cacheWarning.value = !searchCache.selectAccount(owner, 'opportunities', accountId.value, accounts.value.map(account => account.id))
    savedSearch = searchCache.read(owner, 'opportunities', accountId.value)
    if (savedSearch) {
      sortMode.value = savedSearch.sortMode || 'relevance'
      sourceMode.value = savedSearch.sourceMode || 'keyword'
      minWantCount.value = savedSearch.minWantCount ?? ''
      keyword.value = savedSearch.keyword
      shopUrl.value = savedSearch.shopUrl || ''
      results.value = savedSearch.results
      pageNumber.value = savedSearch.pageNumber
      hasMore.value = savedSearch.hasMore
      total.value = savedSearch.total
      fetchedAt.value = savedSearch.fetchedAt
      searched.value = true
    }
  }
  restoring = false
}, { flush: 'sync' })

const persistResults = () => {
  if (restoring || !mounted || owner !== getAuthUsername() || !accountId.value || !savedSearch) return
  cacheWarning.value = !searchCache.save(owner, 'opportunities', accountId.value, { ...savedSearch, results: results.value, minWantCount: validWantMinimum(minWantCount.value) ? minWantCount.value : '', sortMode: sortMode.value })
}
watch([sourceMode, keyword, shopUrl], () => {
  if (restoring) return
  searchGeneration++
  loading.value = false
  loadingMore.value = false
}, { flush: 'sync' })
watch(results, persistResults, { deep: true })
watch([minWantCount, sortMode], persistResults, { flush: 'sync' })
const clearResults = () => {
  resetResults()
  loading.value = false
  loadingMore.value = false
  if (accountId.value) cacheWarning.value = !searchCache.remove(owner, 'opportunities', accountId.value)
}
onBeforeUnmount(() => { mounted = false; searchGeneration++; captureGeneration++ })

const search = async (append = false) => {
  if (sourceMode.value === 'keyword' && !keyword.value.trim()) return toast.error('请输入商品关键词')
  if (sourceMode.value === 'shop' && !shopUrl.value.trim()) return toast.error('请输入闲鱼店铺链接')
  if (!accountId.value) return toast.error('请选择搜索账号')
  if (append && (!savedSearch || savedSearch.sourceMode !== sourceMode.value
    || savedSearch.keyword !== keyword.value || savedSearch.shopUrl !== shopUrl.value)) {
    return toast.warning('搜索条件已改变，请先开始新的搜索')
  }
  const current = ++searchGeneration
  const query = { sourceMode: sourceMode.value, keyword: keyword.value, shopUrl: shopUrl.value }
  if (append) loadingMore.value = true
  else loading.value = true
  try {
    const targetPage = append ? pageNumber.value + 1 : 1
    const common = { xianyuAccountId: accountId.value, pageNumber: targetPage, limit: 30 }
    const response = sourceMode.value === 'keyword'
      ? await searchOpportunities({ ...common, keyword: keyword.value })
      : await crawlShopOpportunities({ ...common, shopUrl: shopUrl.value })
    if (current !== searchGeneration || !mounted || owner !== getAuthUsername()) return
    const page = response.data
    const pageItems = page?.items || []
    results.value = append
      ? [...results.value, ...pageItems.filter(item => !results.value.some(current => current.itemId === item.itemId))]
      : pageItems
    pageNumber.value = page?.pageNumber || targetPage
    hasMore.value = Boolean(page?.hasMore)
    total.value = Number(page?.total || results.value.length)
    searched.value = true
    fetchedAt.value = new Date().toISOString()
    savedSearch = { ...query, results: results.value, pageNumber: pageNumber.value,
      hasMore: hasMore.value, total: total.value, fetchedAt: fetchedAt.value }
    persistResults()
    if (!append) {
      selectedIds.value = []
      active.value = filteredResults.value[0]
    }
  } catch { /* Request utility displays the error; keep the last successful results. */ }
  finally {
    if (current === searchGeneration) {
      loading.value = false
      loadingMore.value = false
    }
  }
}

const toggle = (item: OpportunityCandidate) => {
  active.value = item
  selectedIds.value = selectedIds.value.includes(item.itemId)
    ? selectedIds.value.filter(id => id !== item.itemId)
    : [...selectedIds.value, item.itemId]
}

const addSupply = async () => {
  if (addingSupply.value || capturing.value || !accountId.value || !canAddSupply.value) return
  const candidates = [...selectedCandidates.value]
  if (!candidates.length || candidates.length > 50) return toast.warning('请选择 1 至 50 件商品')
  const current = searchGeneration, selectedAccount = accountId.value
  addingSupply.value = true
  try {
    const response = await addOpportunitiesToSupply({ candidates, xianyuAccountId: selectedAccount })
    if (mounted && current === searchGeneration && selectedAccount === accountId.value && owner === getAuthUsername() && response.data)
      toast.success(`新增 ${response.data.addedCount} 件，已有 ${response.data.existingCount} 件`)
  } catch { /* Request utility displays failures; keep selections and results. */ }
  finally { addingSupply.value = false }
}

const capture = async () => {
  if (capturing.value || addingSupply.value || !accountId.value) return
  if (!selectedCandidates.value.length) return toast.error('至少选择一个候选商品')
  const candidates = [...selectedCandidates.value]
  const item = candidates[0]!
  const current = ++captureGeneration
  capturing.value = true
  try {
    const response = await importOpportunities({ candidates, xianyuAccountId: accountId.value })
    if (current !== captureGeneration || !mounted || owner !== getAuthUsername()) return
    const collected: Record<string, any> = response.data?.[0]?.data || item
    if (collected.competitorSnapshot) item.competitorSnapshot = collected.competitorSnapshot
    active.value = item
    draft.name = String(collected.title || item.title)
    draft.description = String(collected.description || collected.title || item.title)
    draft.amount = ''
    draft.stock = ''
    draft.images = collected.images || item.images || []
    if (collected.detailStatus === 'SEARCH_FALLBACK') {
      toast.warning('本次详情获取失败，已保留已有资料；请查看规格状态，平台验证完成后手动刷新')
    }
    step.value = 2
    maxStep.value = 2
  } finally {
    if (current === captureGeneration) capturing.value = false
  }
}

const next = () => {
  if (step.value === 2 && (!draft.name.trim() || !draft.description.trim())) return toast.error('标题和详情不能为空')
  if (step.value === 3 && (!(Number(draft.amount) > 0) || !Number.isInteger(Number(draft.stock)) || Number(draft.stock) < 1 || !draft.images.length || !draft.divisionId || !draft.gps)) return toast.error('请填写自己的售价、正整数库存、图片和完整发布位置')
  step.value = Math.min(4, step.value + 1)
  maxStep.value = Math.max(maxStep.value, step.value)
}

const goStep = (target: number) => {
  if (target <= maxStep.value) step.value = target
}

const publish = async (dryRun = false) => {
  if (!accountId.value) return toast.error('请选择发布账号')
  if (!(Number(draft.amount) > 0) || !Number.isInteger(Number(draft.stock)) || Number(draft.stock) < 1) return toast.error('请确认自己的售价和库存')
  loading.value = true
  try {
    const response = await createPublishPlan({ xianyuAccountId: accountId.value, ...draft, amount: Number(draft.amount), stock: Number(draft.stock), dryRun })
    if (response.data?.valid === false) {
      return toast.error(String(response.data.error || '商品发布失败'))
    }
    const itemId = response.data?.platform?.itemId
    toast.success(dryRun
      ? '平台校验通过，发布配置可用'
      : `平台已确认发布成功${itemId ? `，商品 ID：${itemId}` : ''}`)
  } finally {
    loading.value = false
  }
}

const polish = async () => {
  if (!draft.name.trim()) return toast.error('请先选择并整理商品')
  loading.value = true
  try {
    const response = await polishOpportunity({ title: draft.name, description: draft.description })
    if (response.data) {
      draft.name = response.data.title
      draft.description = response.data.description
      toast.success('AI文案改写完成')
    }
  } finally {
    loading.value = false
  }
}

const generateImage = async () => {
  if (!accountId.value) return toast.error('请选择图片上传账号')
  if (!draft.name.trim()) return toast.error('请先填写商品标题')
  loading.value = true
  try {
    const response = await generateOpportunityImage({
      xianyuAccountId: accountId.value,
      prompt: `生成一张简洁真实的闲鱼商品主图，不添加水印、二维码和虚假参数。商品：${draft.name}。详情：${draft.description}`
    })
    if (response.data?.url) {
      draft.images = [...new Set([...draft.images, response.data.url])].slice(0, 9)
      toast.success('AI商品图已生成并上传到闲鱼图片服务')
    }
  } finally {
    loading.value = false
  }
}

onMounted(loadAccounts)
</script>

<template>
  <section class="workbench opportunity">
    <header class="workbench__header">
      <div><h1>商机发掘</h1><p>按关键词搜索商品，或采集指定店铺，再完成详情整理、AI 润色与发布。</p></div>
    </header>

    <div class="workbench__steps">
      <button v-for="(label, index) in ['1 捕获', '2 改写', '3 配置', '4 发布']" :key="label" class="workbench__step" :class="{ 'workbench__step--active': step === index + 1 }" :disabled="index + 1 > maxStep" @click="goStep(index + 1)">{{ label }}</button>
    </div>

    <div v-if="step === 1" class="opportunity__layout workbench__section">
      <div class="opportunity__search-pane">
        <div class="workbench__card workbench__toolbar">
          <select v-model="accountId" class="workbench__select opportunity__account">
            <option v-for="account in accounts" :key="account.id" :value="account.id">{{ account.accountNote || account.unb }}</option>
          </select>
          <select v-model="sourceMode" class="workbench__select opportunity__mode">
            <option value="keyword">商品搜索</option>
            <option value="shop">店铺采集</option>
          </select>
          <input v-if="sourceMode === 'keyword'" v-model="keyword" class="workbench__input" placeholder="输入商品关键词，例如：华为 Mate 80" @keyup.enter="search(false)">
          <input v-else v-model="shopUrl" class="workbench__input" placeholder="粘贴闲鱼网页版店铺主页完整链接" @keyup.enter="search(false)">
          <button class="workbench__btn workbench__btn--primary" :disabled="loading" @click="search(false)">{{ loading ? '搜索中' : '开始搜索' }}</button>
        </div>
        <div class="workbench__card opportunity__filters">
          <label class="workbench__field">最低想要人数<input v-model.number="minWantCount" class="workbench__input" type="number" min="0" step="1" placeholder="不限"></label>
          <label class="workbench__field">排序<select v-model="sortMode" class="workbench__select">
            <option value="relevance">综合匹配</option><option value="price-asc">价格从低到高</option><option value="price-desc">价格从高到低</option>
            <option value="want-asc">想要人数从少到多</option><option value="want-desc">想要人数从多到少</option>
          </select></label>
          <button class="workbench__btn" @click="minWantCount = ''; sortMode = 'relevance'">重置筛选</button>
        </div>
        <div v-if="searched" class="opportunity__result-meta">
          <span>{{ total > 0 ? `平台共匹配 ${total} 件，` : '' }}当前已加载 {{ results.length }} 件，筛选后 {{ filteredResults.length }} 件 · 结果获取时间 {{ new Date(fetchedAt).toLocaleString('zh-CN') }}</span>
          <button class="workbench__btn" @click="clearResults">清空搜索结果</button>
        </div>
        <p class="search-filter-note">筛选和排序仅作用于已加载结果，不会自动搜索或加载下一页。</p>
        <p v-if="!validWantMinimum(minWantCount)" class="search-cache-warning">最低想要人数请输入非负整数。</p>
        <p v-if="cacheWarning" class="search-cache-warning">浏览器无法保存搜索结果，当前页面仍会保留；刷新后可能丢失。</p>
        <div class="workbench__list workbench__section">
          <article v-for="item in filteredResults" :key="item.itemId" class="workbench__item opportunity__result" :class="{ 'opportunity__result--active': active?.itemId === item.itemId }" tabindex="0" @click="toggle(item)" @keydown.enter="toggle(item)">
            <input type="checkbox" :checked="selectedIds.includes(item.itemId)" @click.stop="toggle(item)">
            <img :src="item.images?.[0]" alt="">
            <div class="opportunity__result-copy">
              <h3>{{ item.title }}</h3>
              <div class="workbench__tags">
                <span class="workbench__tag workbench__tag--good">机会分 {{ item.opportunityScore }}</span>
                <span class="workbench__tag" :class="{ 'workbench__tag--warn': item.riskLevel !== 'LOW' }">{{ item.riskLevel === 'LOW' ? '资料完整' : '建议复核' }}</span>
                <span class="workbench__tag">{{ item.matchReason }}</span>
              </div>
              <ProductReferenceStats :sold-count-text="item.soldCountText" :want-count-text="item.wantCountText" />
            </div>
            <strong>¥ {{ formatMoneyCents(displayPriceCents(item.price)) }}<small>搜索展示价</small></strong>
          </article>
          <div v-if="!filteredResults.length" class="workbench__empty">{{ results.length ? '当前筛选条件下没有结果，请调整筛选。' : searched ? '平台未返回可用商品，请检查输入内容、账号状态或平台验证。' : '选择商品搜索或店铺采集后开始发现候选商品。' }}</div>
          <button v-if="hasMore" class="workbench__btn opportunity__more" :disabled="loadingMore" @click="search(true)">{{ loadingMore ? '加载中' : '加载更多平台商品' }}</button>
        </div>
      </div>
      <aside class="workbench__card opportunity__preview">
        <template v-if="active">
          <img :src="active.images?.[0]" alt="">
          <h2>{{ active.title }}</h2>
          <strong>搜索展示价 ¥ {{ formatMoneyCents(displayPriceCents(active.price)) }}</strong>
          <p>{{ active.matchReason }}</p>
          <CompetitorSkuPanel :item-id="active.itemId" :account-id="accountId" :initial-snapshot="active.competitorSnapshot"
            @loaded="detail => { if (active?.itemId === detail.itemId) active.competitorSnapshot = detail.competitorSnapshot }" />
        </template>
        <div v-else class="workbench__empty">选择商品后查看预览</div>
        <p>已选择 {{ selectedIds.length }} 件（单次最多 50 件）</p>
        <button v-if="canAddSupply" class="workbench__btn" :disabled="!selectedIds.length || selectedIds.length > 50 || addingSupply || capturing" @click="addSupply">{{ addingSupply ? '入库中…' : '加入货源库' }}</button>
        <button class="workbench__btn workbench__btn--primary" :disabled="!selectedIds.length || selectedIds.length > 50 || capturing || addingSupply" @click="capture">{{ capturing ? '采集中…' : '下一步：整理商品' }}</button>
      </aside>
    </div>

    <div v-else class="workbench__card opportunity__wizard workbench__section">
      <p class="opportunity__sku-note">竞品规格仅供参考。本次发布为单规格，请填写自己的售价和库存。</p>
      <CompetitorSkuPanel v-if="active" :item-id="active.itemId" :account-id="accountId" :initial-snapshot="active.competitorSnapshot"
        @loaded="detail => { if (active?.itemId === detail.itemId) active.competitorSnapshot = detail.competitorSnapshot }" />
      <template v-if="step === 2">
        <div class="opportunity__title-row">
          <h2>整理商品内容</h2>
          <div class="workbench__actions">
            <button class="workbench__btn" :disabled="loading" @click="polish">{{ loading ? '处理中' : 'AI 搬运润色' }}</button>
            <button class="workbench__btn" :disabled="loading || draft.images.length >= 9" @click="generateImage">AI 生成商品图</button>
          </div>
        </div>
        <label class="workbench__field">商品标题<input v-model="draft.name" class="workbench__input" maxlength="120"><small>{{ draft.name.length }} / 120</small></label>
        <label class="workbench__field">商品详情<textarea v-model="draft.description" class="workbench__textarea" maxlength="3000"></textarea><small>{{ draft.description.length }} / 3000</small></label>
        <div class="opportunity__images">
          <article v-for="(image, index) in draft.images" :key="image">
            <img :src="image" alt="">
            <button type="button" @click="draft.images.splice(index, 1)">移除</button>
          </article>
          <div v-if="!draft.images.length" class="workbench__empty">暂无商品图，可保留采集图片或使用 AI 生成。</div>
        </div>
      </template>
      <template v-else-if="step === 3">
        <h2>配置发布参数</h2>
        <div class="workbench__grid workbench__grid--two">
          <label class="workbench__field">价格<input v-model.number="draft.amount" class="workbench__input" type="number" min="0.01" step="0.01"></label>
          <label class="workbench__field">库存<input v-model.number="draft.stock" class="workbench__input" type="number" min="1"></label>
          <label class="workbench__field">素材分类<input v-model="draft.category" class="workbench__input"><small>仅用于站内整理，真实类目由闲鱼发布接口识别。</small></label>
          <label class="workbench__field">交付方式<select v-model="draft.deliveryMethod" class="workbench__select"><option>线上交付</option><option>快递发货</option><option>当面交易</option></select></label>
          <PublishAddressFields v-model="publishAddress" />
          <label class="workbench__field">图片地址（每行一张）<textarea :value="draft.images.join('\n')" class="workbench__textarea" @input="draft.images = ($event.target as HTMLTextAreaElement).value.split('\n').map(v => v.trim()).filter(Boolean)"></textarea></label>
        </div>
      </template>
      <template v-else>
        <h2>发布前确认</h2>
        <div class="opportunity__summary">
          <img :src="draft.images[0]" alt="">
          <div><h3>{{ draft.name }}</h3><p>{{ draft.description }}</p><strong>¥ {{ formatMoneyCents(displayPriceCents(draft.amount === '' ? undefined : draft.amount)) }} · 库存 {{ draft.stock }}</strong><small>{{ draft.province }} {{ draft.city }} {{ draft.district }} · {{ draft.deliveryMethod }}</small></div>
        </div>
      </template>
      <div class="workbench__actions opportunity__footer">
        <button class="workbench__btn" :disabled="step <= 1" @click="step--">上一步</button>
        <button v-if="step < 4" class="workbench__btn workbench__btn--primary" @click="next">下一步</button>
        <template v-else>
          <button class="workbench__btn" :disabled="loading" @click="publish(true)">仅校验</button>
          <button class="workbench__btn workbench__btn--primary" :disabled="loading" @click="publish(false)">立即发布</button>
        </template>
      </div>
    </div>
  </section>
</template>

<style scoped>
.opportunity__filters { display: flex; flex-wrap: wrap; align-items: end; gap: 12px; margin-top: 12px; }
.opportunity__filters .workbench__field { flex: 1; min-width: 180px; }
.search-filter-note { color: #667085; font-size: 12px; }
.opportunity__preview > button + button { margin-top: 8px; }
.opportunity__layout { display: grid; grid-template-columns: minmax(0, 1fr) 300px; gap: 14px; }
.opportunity__account { max-width: 180px; }
.opportunity__mode { max-width: 130px; }
.opportunity__search-pane { min-width: 0; }
.opportunity__result-meta { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 8px; margin: 12px 0 -4px; color: #667085; font-size: 12px; }
.search-cache-warning { color: #b54708; font-size: 12px; }
.opportunity__result { width: 100%; min-width: 0; grid-template-columns: auto 56px minmax(0, 1fr) auto; color: inherit; text-align: left; cursor: pointer; }
.opportunity__result-copy { min-width: 0; }
.opportunity__result > strong { white-space: nowrap; }
.opportunity__result--active { border-color: #84adff; background: #f5f8ff; }
.opportunity__preview { position: sticky; top: 16px; align-self: start; }
.opportunity__preview > img { width: 100%; aspect-ratio: 4 / 3; border-radius: 8px; object-fit: cover; }
.opportunity__preview h2 { display: -webkit-box; overflow: hidden; font-size: 15px; line-height: 1.5; overflow-wrap: anywhere; -webkit-box-orient: vertical; -webkit-line-clamp: 3; }
.opportunity__preview > strong { color: #d92d20; font-size: 22px; }
.opportunity__preview > p { color: #667085; font-size: 12px; }
.opportunity__preview > button { width: 100%; }
.opportunity__more { width: 100%; justify-content: center; }
.opportunity__wizard { max-width: 980px; margin-right: auto; margin-left: auto; }
.opportunity__wizard h2 { margin-top: 0; }
.opportunity__title-row { display: flex; align-items: center; justify-content: space-between; }
.opportunity__images { display: grid; grid-template-columns: repeat(auto-fill, minmax(120px, 1fr)); gap: 10px; margin-top: 14px; }
.opportunity__images article { overflow: hidden; border: 1px solid #e4e7ec; border-radius: 8px; background: #fff; }
.opportunity__images img { display: block; width: 100%; aspect-ratio: 1; object-fit: cover; }
.opportunity__images button { width: 100%; border: 0; border-top: 1px solid #e4e7ec; padding: 7px; color: #b42318; background: #fff; cursor: pointer; }
.opportunity__wizard > .workbench__field { margin-bottom: 14px; }
.opportunity__footer { justify-content: flex-end; margin-top: 18px; }
.opportunity__summary { display: grid; grid-template-columns: 180px 1fr; gap: 18px; }
.opportunity__summary img { width: 180px; height: 180px; border-radius: 8px; object-fit: cover; background: #f2f4f7; }
.opportunity__summary p { color: #667085; white-space: pre-wrap; }
.opportunity__summary strong, .opportunity__summary small { display: block; margin-top: 10px; }
@media (max-width: 900px) { .opportunity__layout { grid-template-columns: 1fr; } .opportunity__preview { position: static; } }
@media (max-width: 767px) {
  .opportunity__result { grid-template-columns: auto 52px minmax(0, 1fr); align-items: start; }
  .opportunity__result img { width: 52px; height: 52px; }
  .opportunity__result > strong { grid-column: 3; }
  .opportunity__summary { grid-template-columns: 1fr; }
  .opportunity__summary img { width: 100%; height: auto; aspect-ratio: 1; }
  .opportunity__preview { padding-bottom: max(16px, env(safe-area-inset-bottom)); }
}
</style>
