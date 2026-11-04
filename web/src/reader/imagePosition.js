import { indexOfPage } from './views.js'

/** A viewport-centre anchor survives image resizing and stays inside a long webtoon panel. */
export function imageScrollProgression(rect, centre) {
  const height = rect?.bottom - rect?.top
  if (!Number.isFinite(height) || height <= 0 || !Number.isFinite(centre)) return null
  return Number(Math.max(0, Math.min(1, (centre - rect.top) / height)).toFixed(6))
}

export function imageLocator(view, progression = null) {
  const scroll = Number.isFinite(progression)
  return {
    href: `pages/${view.page}`,
    locations: { position: view.page, ...(scroll ? { progression } : {}) },
    xoboro: { reader: 'image', half: view.half, ...(scroll ? { anchor: 'viewport-centre' } : {}) },
  }
}

export function imageResumeView(progress, views, page) {
  const fallback = { index: indexOfPage(views, page), progression: null }
  if (progress?.completed) return fallback
  const locator = progress?.locator
  if (locator?.xoboro?.reader !== 'image' || locator.locations?.position !== page ||
      locator.href !== `pages/${page}`) return fallback
  const half = locator.xoboro.half
  const matching = views.findIndex((view) => view.page === page && view.half === half)
  const index = matching < 0 ? fallback.index : matching
  const value = locator.locations.progression
  const progression = locator.xoboro.anchor === 'viewport-centre' &&
    Number.isFinite(value) && value >= 0 && value <= 1 ? value : null
  return { index, progression }
}
