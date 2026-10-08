<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { downloadProductImage } from '@/utils/product-image-download'
import { toast } from '@/utils/toast'

const props = withDefaults(defineProps<{
  images?: unknown
  itemId?: string | number
  title?: string
  initialIndex?: number
}>(), { title: '商品图片', initialIndex: 0 })
const images = computed(() => Array.isArray(props.images) ? props.images.filter((url): url is string => {
  if (typeof url !== 'string' || !url.trim()) return false
  try { return ['https:', 'http:'].includes(new URL(url.startsWith('//') ? `https:${url}` : url, window.location.origin).protocol) }
  catch { return false }
}).map(url => url.startsWith('//') ? `https:${url}` : url) : [])
const thumbnail = computed(() => images.value[Math.min(props.initialIndex, Math.max(0, images.value.length - 1))])
const open = ref(false)
const index = ref(0)
const current = computed(() => images.value[index.value])
const thumbnailFailed = ref(false)
const imageFailed = ref(false)
const imageLoading = ref(true)
const downloading = ref(false)
const dialog = ref<HTMLElement>()
const closeButton = ref<HTMLButtonElement>()
let previousFocus: HTMLElement | null = null
let downloadController: AbortController | undefined

watch(thumbnail, () => { thumbnailFailed.value = false })
watch(current, () => { imageFailed.value = false; imageLoading.value = true })
watch(() => props.images, () => { if (open.value) close() })

async function show() {
  if (!images.value.length) return
  previousFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null
  index.value = Math.min(props.initialIndex, images.value.length - 1)
  imageFailed.value = false
  imageLoading.value = true
  open.value = true
  await nextTick()
  closeButton.value?.focus()
}
function close() {
  downloadController?.abort()
  open.value = false
  if (previousFocus?.isConnected) previousFocus.focus()
}
function move(offset: number) {
  if (!downloading.value) index.value = (index.value + offset + images.value.length) % images.value.length
}
function onKey(event: KeyboardEvent) {
  event.stopPropagation()
  if (event.key === 'Escape') { event.preventDefault(); close() }
  if (event.key === 'ArrowLeft') { event.preventDefault(); move(-1) }
  if (event.key === 'ArrowRight') { event.preventDefault(); move(1) }
  if (event.key === 'Tab') {
    const controls = dialog.value?.querySelectorAll<HTMLElement>('button:not(:disabled), a[href]')
    if (!controls?.length) return
    const first = controls[0], last = controls[controls.length - 1]
    if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last?.focus() }
    else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus() }
  }
}
async function download() {
  const url = current.value
  if (!url || downloading.value) return
  const controller = new AbortController()
  downloadController = controller
  downloading.value = true
  try {
    await downloadProductImage(url, props.itemId, index.value, controller.signal)
  } catch (error) {
    if (!controller.signal.aborted) toast.error(error instanceof Error ? error.message : '图片下载失败')
  } finally {
    downloading.value = false
  }
}
onBeforeUnmount(() => downloadController?.abort())
</script>

<template>
  <div class="product-image">
    <button type="button" class="product-image__trigger" :disabled="!images.length" :aria-label="`放大查看 ${title}`" title="点击放大查看和下载" @click.stop="show" @keydown.stop>
      <img v-if="thumbnail && !thumbnailFailed" :src="thumbnail" :alt="title" @error="thumbnailFailed = true">
      <span v-else class="product-image__placeholder">{{ images.length ? '图片加载失败' : '暂无图片' }}</span>
    </button>
    <Teleport to="body">
      <div v-if="open" class="product-image-viewer" @click.self="close" @keydown="onKey">
        <section ref="dialog" class="product-image-viewer__dialog" role="dialog" aria-modal="true" aria-label="商品图片预览">
          <header class="product-image-viewer__header">
            <span>{{ title }} · {{ index + 1 }} / {{ images.length }}</span>
            <button ref="closeButton" type="button" aria-label="关闭图片预览" @click="close">关闭</button>
          </header>
          <div class="product-image-viewer__stage">
            <img v-if="current && !imageFailed" :key="current" :src="current" :alt="`${title} 第 ${index + 1} 张`" @load="imageLoading = false" @error="imageFailed = true; imageLoading = false">
            <span v-if="imageLoading" class="product-image-viewer__status" role="status">图片加载中…</span>
            <span v-if="imageFailed" class="product-image-viewer__status" role="status">图片加载失败，可尝试打开图片或下载。</span>
          </div>
          <footer class="product-image-viewer__actions">
            <button v-if="images.length > 1" type="button" :disabled="downloading" @click="move(-1)">上一张</button>
            <button v-if="images.length > 1" type="button" :disabled="downloading" @click="move(1)">下一张</button>
            <button type="button" :disabled="downloading || !current" @click="download">{{ downloading ? '下载中…' : '下载当前图片' }}</button>
            <a :href="current" target="_blank" rel="noopener noreferrer">打开图片</a>
          </footer>
        </section>
      </div>
    </Teleport>
  </div>
</template>

<style scoped>
.product-image { width: 56px; height: 56px; min-width: 0; overflow: hidden; border-radius: 7px; background: #f2f4f7; }
.product-image__trigger { display: block; width: 100%; height: 100%; padding: 0; border: 0; border-radius: inherit; background: transparent; cursor: zoom-in; }
.product-image__trigger:disabled { cursor: default; }
.product-image__trigger:focus-visible { outline: 2px solid #155eef; outline-offset: -2px; }
.product-image .product-image__trigger img { display: block; width: 100%; height: 100%; object-fit: cover; }
.product-image__placeholder { display: grid; width: 100%; height: 100%; place-items: center; color: #667085; font-size: 11px; }
.product-image-viewer { position: fixed; inset: 0; z-index: 2000; display: grid; place-items: center; padding: 20px; background: rgba(16, 24, 40, .75); }
.product-image-viewer__dialog { box-sizing: border-box; display: flex; flex-direction: column; width: min(100%, 1060px); max-height: 92vh; max-height: 92dvh; min-width: 0; padding: 16px; border-radius: 12px; background: #fff; color: #101828; box-shadow: 0 12px 48px rgba(0, 0, 0, .3); }
.product-image-viewer__header { display: flex; justify-content: space-between; align-items: center; gap: 12px; margin-bottom: 12px; }
.product-image-viewer__header span { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.product-image-viewer__stage { position: relative; display: grid; place-items: center; min-height: 160px; overflow: auto; flex: 1; background: #f2f4f7; border-radius: 8px; }
.product-image-viewer__stage img { display: block; max-width: 100%; max-height: 68vh; max-height: 68dvh; object-fit: contain; }
.product-image-viewer__status { position: absolute; padding: 10px; background: rgba(255, 255, 255, .9); color: #475467; font-size: 13px; }
.product-image-viewer__actions { display: flex; justify-content: center; flex-wrap: wrap; gap: 8px; margin-top: 12px; }
.product-image-viewer button, .product-image-viewer a { box-sizing: border-box; display: inline-flex; align-items: center; justify-content: center; min-height: 40px; padding: 8px 12px; border: 1px solid #d0d5dd; border-radius: 7px; color: #344054; background: #fff; font: inherit; font-size: 14px; text-decoration: none; cursor: pointer; }
.product-image-viewer button:disabled { opacity: .5; cursor: wait; }
@media (max-width: 600px) { .product-image-viewer { padding: 10px; } .product-image-viewer__dialog { padding: 12px; } .product-image-viewer__stage img { max-height: 60vh; max-height: 60dvh; } }
</style>
