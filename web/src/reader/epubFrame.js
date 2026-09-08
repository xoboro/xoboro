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
:root { color-scheme: ${theme} !important; }
html, body { background: Canvas !important; color: CanvasText !important; }
html { max-width: 100%; overflow-x: hidden !important; }
body {
  box-sizing: border-box;
  width: 100%;
  max-width: ${width};
  margin: 0 auto;
  padding: ${margin}px;
  font-size: ${fontSize}% !important;
  line-height: ${lineHeight} !important;
  overflow-wrap: anywhere;
}
body * { background-color: transparent !important; color: inherit !important; line-height: inherit !important; }
body :where(p, li, blockquote, pre, code, td, th, dd, dt, span) { font-size: inherit !important; }
body :where(img, svg, video, canvas) { max-width: 100% !important; height: auto !important; }
body :where(table, pre) { display: block !important; max-width: 100% !important; overflow-x: auto !important; }
`
}

export function bindEpubFrame(node, initialOptions) {
  let options = initialOptions
  let boundDocument = null
  let scrolling = null
  let restoreKey = null
  let suppressProgress = false

  function detachDocument() {
    boundDocument?.removeEventListener('scroll', reportProgress, true)
    boundDocument?.removeEventListener('click', handleClick)
    boundDocument?.removeEventListener('keydown', forwardKeydown)
    boundDocument = null
    scrolling = null
  }

  function applyStyle() {
    if (!boundDocument) return false
    let style = boundDocument.getElementById('xoboro-epub-style')
    if (!style) {
      style = boundDocument.createElement('style')
      style.id = 'xoboro-epub-style'
      ;(boundDocument.head ?? boundDocument.documentElement).append(style)
    }
    const nextStyle = stylesheet(options.styles)
    const changed = style.textContent !== nextStyle
    if (changed) style.textContent = nextStyle
    return changed
  }

  function reportProgress() {
    if (!scrolling || suppressProgress) return
    const range = Math.max(0, scrolling.scrollHeight - scrolling.clientHeight)
    const progression = range > 0 ? Math.min(1, Math.max(0, scrolling.scrollTop / range)) : 1
    options.onProgress?.({ progression, atBottom: range === 0 || scrolling.scrollTop >= range - 1 })
  }

  function measuredProgression() {
    if (!scrolling) return 0
    const range = Math.max(0, scrolling.scrollHeight - scrolling.clientHeight)
    return range > 0 ? Math.min(1, Math.max(0, scrolling.scrollTop / range)) : 1
  }

  function fragmentProgression(fragment) {
    if (!boundDocument || !scrolling || !fragment) return null
    const target =
      boundDocument.getElementById(fragment) ??
      Array.from(boundDocument.getElementsByName(fragment)).find(Boolean)
    if (!target) return null
    let offset = 0
    let current = target
    while (current && current !== scrolling) {
      offset += Number(current.offsetTop) || 0
      current = current.offsetParent
    }
    const range = Math.max(0, scrolling.scrollHeight - scrolling.clientHeight)
    return range > 0 ? Math.min(1, Math.max(0, offset / range)) : 1
  }

  function restoreTo(progression) {
    if (!scrolling) return
    const boundedProgression = bounded(progression, 0, 1, 0)
    // A scroll event may fire synchronously in some engines. It describes the transient
    // layout, not a reader action, so publish only once the logical location is restored.
    suppressProgress = true
    try {
      const range = Math.max(0, scrolling.scrollHeight - scrolling.clientHeight)
      scrolling.scrollTop = boundedProgression * range
    } finally {
      suppressProgress = false
    }
    reportProgress()
  }

  function restore() {
    if (!scrolling) return
    const progression =
      fragmentProgression(options.fragment) ?? bounded(options.progression, 0, 1, 0)
    restoreKey = options.restoreKey
    restoreTo(progression)
  }

  function handleClick(event) {
    const anchor = event.target?.closest?.('a[href]')
    if (!anchor) {
      options.onToggleChrome?.(event)
      return
    }
    // Chapter markup is untrusted and is never allowed to own parent navigation. A
    // validated spine destination is handed to the parent; everything else stays put.
    event.preventDefault()
    options.onNavigate?.(anchor.getAttribute('href'))
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
    boundDocument.addEventListener('click', handleClick)
    boundDocument.addEventListener('keydown', forwardKeydown)
    restore()
  }

  node.addEventListener('load', bindDocument)

  return {
    update(nextOptions) {
      const progression = measuredProgression()
      options = nextOptions
      const stylesChanged = applyStyle()
      if (boundDocument && options.restoreKey !== restoreKey) {
        restore()
      } else if (boundDocument && stylesChanged) {
        // Reading scrollHeight after the stylesheet mutation forces layout before the
        // ratio is reapplied, preserving the logical location across reflow.
        restoreTo(progression)
      }
    },
    destroy() {
      node.removeEventListener('load', bindDocument)
      detachDocument()
    },
  }
}
