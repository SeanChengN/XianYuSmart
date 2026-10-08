import { clearAuthToken, getAuthToken } from './request'

export async function downloadProductImage(url: string, itemId: string | number | undefined, index: number, signal: AbortSignal) {
  const token = getAuthToken()
  const response = await fetch(`/api/merchant/images/download?${new URLSearchParams({ url })}`, {
    headers: token ? { Authorization: `Bearer ${token}` } : {},
    signal,
    cache: 'no-store'
  })
  const type = response.headers.get('Content-Type')?.split(';')[0]?.trim() || ''
  if (!response.ok || !type.startsWith('image/')) {
    const error = await response.json().catch(() => null)
    if (response.status === 401 || error?.code === 401) {
      clearAuthToken()
      window.location.assign('/login')
    }
    throw new Error(error?.msg || '图片下载失败，可打开图片后保存')
  }
  const extensions: Record<string, string> = { 'image/jpeg': 'jpg', 'image/png': 'png', 'image/webp': 'webp', 'image/gif': 'gif' }
  const extension = extensions[type]
  if (!extension) throw new Error('不支持该图片格式，请打开图片后保存')
  const blob = await response.blob()
  if (signal.aborted) return
  const objectUrl = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  const safeId = String(itemId || 'reference').replace(/[^a-zA-Z0-9_-]/g, '_').slice(0, 64)
  anchor.href = objectUrl
  anchor.download = `${safeId}-${index + 1}.${extension}`
  document.body.appendChild(anchor)
  anchor.click()
  anchor.remove()
  window.setTimeout(() => URL.revokeObjectURL(objectUrl), 30000)
}
