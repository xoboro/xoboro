function bounded(value, minimum, maximum, fallback) {
  const number = Number(value)
  return Number.isFinite(number) ? Math.min(maximum, Math.max(minimum, number)) : fallback
}

function stylesheet(settings = {}) {
  const fontSize = bounded(settings.fontSize, 75, 200, 100)
  const lineHeight = bounded(settings.lineHeight, 1, 3, 1.6)
  const margin = bounded(settings.margin, 0, 128, 32)
  const width = settings.width === 'full' ? 'none' : `${bounded(settings.width, 20, 80, 42)}rem`
  const theme = settings.theme === 'light' ? 'light' : 'dark'
  return `
:root { color-scheme: ${theme}; background: Canvas; color: CanvasText; }
html { font-size: ${fontSize}%; line-height: ${lineHeight}; background: Canvas; color: CanvasText; }
body { box-sizing: border-box; width: 100%; max-width: ${width}; margin: 0 auto; padding: ${margin}px; }
body, body * { line-height: inherit; }
`
}

export function bindEpubFrame(node, initialOptions) {
  let options = initialOptions
  let boundDocument = null
  let scrolling = null
  let restoreKey = null

  function detachDocument() {
    boundDocument?.removeEventListener('scroll', reportProgress, true)
    boundDocument?.removeEventListener('click', toggleChrome)
    boundDocument?.removeEventListener('keydown', forwardKeydown)
    boundDocument = null
    scrolling = null
  }

  function applyStyle() {
    if (!boundDocument) return
    let style = boundDocument.getElementById('xoboro-epub-style')
    if (!style) {
      style = boundDocument.createElement('style')
      style.id = 'xoboro-epub-style'
      ;(boundDocument.head ?? boundDocument.documentElement).append(style)
    }
    style.textContent = stylesheet(options.styles)
  }

  function reportProgress() {
    if (!scrolling) return
    const range = Math.max(0, scrolling.scrollHeight - scrolling.clientHeight)
    const progression = range > 0 ? Math.min(1, Math.max(0, scrolling.scrollTop / range)) : 1
    options.onProgress?.({ progression, atBottom: range === 0 || scrolling.scrollTop >= range - 1 })
  }

  function restore() {
    if (!scrolling) return
    const progression = bounded(options.progression, 0, 1, 0)
    const range = Math.max(0, scrolling.scrollHeight - scrolling.clientHeight)
    scrolling.scrollTop = progression * range
    restoreKey = options.restoreKey
    reportProgress()
  }

  function toggleChrome(event) {
    options.onToggleChrome?.(event)
  }

  function forwardKeydown(event) {
    options.onKeydown?.(event)
  }

  function bindDocument() {
    detachDocument()
    boundDocument = node.contentDocument
    if (!boundDocument) return
    scrolling = boundDocument.scrollingElement ?? boundDocument.documentElement
    applyStyle()
    boundDocument.addEventListener('scroll', reportProgress, true)
    boundDocument.addEventListener('click', toggleChrome)
    boundDocument.addEventListener('keydown', forwardKeydown)
    restore()
  }

  node.addEventListener('load', bindDocument)

  return {
    update(nextOptions) {
      options = nextOptions
      applyStyle()
      if (boundDocument && options.restoreKey !== restoreKey) restore()
    },
    destroy() {
      node.removeEventListener('load', bindDocument)
      detachDocument()
    },
  }
}
