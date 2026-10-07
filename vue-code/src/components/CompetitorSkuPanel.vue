<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { getCompetitorDetail, type CompetitorDetail, type CompetitorSkuSnapshot } from '@/api/merchant'
import { formatMoneyCents } from '@/utils/competitor-price'

const props = defineProps<{
  itemId?: string
  accountId?: number
  initialSnapshot?: CompetitorSkuSnapshot
}>()
const emit = defineEmits<{ loaded: [detail: CompetitorDetail] }>()
const snapshot = ref<CompetitorSkuSnapshot>()
const loading = ref(false)
let generation = 0
let controller: AbortController | undefined

watch(() => [props.itemId, props.accountId], () => {
  generation++
  controller?.abort()
  loading.value = false
  snapshot.value = props.initialSnapshot
}, { immediate: true, flush: 'sync' })
watch(() => props.initialSnapshot, value => { snapshot.value = value })
onBeforeUnmount(() => { generation++; controller?.abort() })

const requiresVerification = () => ['USER_VALIDATE', 'CAPTCHA'].includes(snapshot.value?.reason || '')
const statusLabel = (status?: string) => ({
  AVAILABLE: '规格价格已获取', PARTIAL: '部分资料可用', NO_SKU: '平台返回空规格',
  MISSING: '平台未提供规格', BLOCKED: requiresVerification() ? '等待人工验证' : '平台访问受限', FAILED: '获取失败'
}[status || ''] || '尚未获取规格')

const load = async (forceRefresh = false) => {
  if (!props.itemId || !props.accountId || loading.value) return
  const current = ++generation
  controller?.abort()
  controller = new AbortController()
  loading.value = true
  try {
    const response = await getCompetitorDetail({
      itemId: props.itemId, xianyuAccountId: props.accountId, forceRefresh
    }, controller.signal)
    if (current !== generation || !response.data) return
    const detail = response.data
    const incoming = detail.competitorSnapshot
    // Failed preview requests retain facts already loaded in this panel.
    if (incoming && !incoming.capturedAt && snapshot.value?.capturedAt) {
      detail.competitorSnapshot = { ...snapshot.value, ...incoming,
        capturedAt: snapshot.value.capturedAt, skus: snapshot.value.skus, stale: true }
    }
    snapshot.value = detail.competitorSnapshot
    emit('loaded', detail)
  } catch (error) {
    if (current !== generation) return
    snapshot.value = {
      ...snapshot.value, itemId: props.itemId, status: 'FAILED',
      skus: snapshot.value?.skus || [], stale: Boolean(snapshot.value?.capturedAt),
      message: error instanceof Error ? error.message : '规格获取失败'
    }
  } finally {
    if (current === generation) loading.value = false
  }
}

const priceLabel = (sku: CompetitorSkuSnapshot['skus'][number]) => {
  if (sku.priceCents != null) return `¥ ${formatMoneyCents(sku.priceCents)}`
  if (sku.priceStatus === 'UNIT_UNCONFIRMED') return `单位待确认（原值 ${sku.rawPrice}）`
  return sku.priceStatus === 'INVALID' ? '价格字段无效' : '价格未提供'
}
</script>

<template>
  <section class="competitor-sku" aria-label="竞品规格价格" aria-live="polite">
    <div class="competitor-sku__header">
      <strong>{{ statusLabel(snapshot?.status) }}</strong>
      <button type="button" :disabled="loading || !itemId || !accountId" @click="load(Boolean(snapshot))">
        {{ loading ? '获取中…' : snapshot ? '刷新规格' : '查看规格' }}
      </button>
    </div>
    <p v-if="snapshot?.message">{{ snapshot.message }}</p>
    <p v-if="snapshot?.status === 'BLOCKED'">
      <RouterLink to="/connection">前往连接管理</RouterLink>
      · <a :href="`https://www.goofish.com/item?id=${encodeURIComponent(itemId || '')}`" target="_blank" rel="noopener noreferrer">打开闲鱼商品页</a>
      <span>。仅在官方页面出现验证时处理；当前采集已暂停，请勿连续刷新。</span>
    </p>
    <p v-if="snapshot?.stale" class="competitor-sku__warning">本次更新失败，下方保留上次成功资料，不代表当前售价。</p>
    <small v-if="snapshot?.capturedAt">来源：{{ snapshot.source === 'ORDER_RENDER_API' ? '闲鱼下单确认页' : '闲鱼商品详情' }} · 采集时间 {{ new Date(snapshot.capturedAt).toLocaleString('zh-CN') }}</small>
    <div v-if="snapshot?.skus.length" class="competitor-sku__scroll" tabindex="0" aria-label="可横向滚动的规格表">
      <table>
        <thead><tr><th>规格组合</th><th>SKU 售价</th><th>库存</th></tr></thead>
        <tbody>
          <tr v-for="sku in snapshot.skus" :key="sku.skuId">
            <td><span v-if="sku.properties.length">{{ sku.properties.map(p => `${p.name}：${p.value}`).join(' / ') }}</span><span v-else>规格资料缺失</span><small>SKU {{ sku.skuId }}</small></td>
            <td>{{ priceLabel(sku) }}</td><td>{{ sku.quantity ?? '未提供' }}</td>
          </tr>
        </tbody>
      </table>
    </div>
    <p v-if="!snapshot">搜索展示价可能是起售价。点击查看规格后获取逐 SKU 价格。</p>
  </section>
</template>

<style scoped>
.competitor-sku { min-width: 0; padding: 14px; border: 1px solid #e4e7ec; border-radius: 8px; background: #fff; color: #344054; }
.competitor-sku__header { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.competitor-sku button { flex-shrink: 0; border: 1px solid #d0d5dd; border-radius: 6px; padding: 7px 10px; background: #fff; color: #155eef; cursor: pointer; }
.competitor-sku button:disabled { opacity: .5; cursor: default; }
.competitor-sku p, .competitor-sku small { font-size: 12px; line-height: 1.6; overflow-wrap: anywhere; }
.competitor-sku small { display: block; color: #667085; }
.competitor-sku__warning { color: #b54708; }
.competitor-sku__scroll { max-width: 100%; overflow-x: auto; margin-top: 12px; }
.competitor-sku table { width: 100%; min-width: 360px; border-collapse: collapse; font-size: 13px; }
.competitor-sku th, .competitor-sku td { padding: 10px; text-align: left; border-bottom: 1px solid #eaecf0; }
.competitor-sku th { background: #f9fafb; white-space: nowrap; }
</style>
