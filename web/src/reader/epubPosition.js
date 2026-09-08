function clamp(value, minimum, maximum) {
  return Math.min(maximum, Math.max(minimum, value))
}

function boundedProgression(value, fallback = 0) {
  const progression = Number(value)
  return Number.isFinite(progression) ? clamp(progression, 0, 1) : fallback
}

function resourcePath(href) {
  const value = String(href ?? '')
  const suffix = value.search(/[?#]/)
  return suffix < 0 ? value : value.slice(0, suffix)
}

function indexForPage(page, positionCount, pageCount) {
  const pages = Math.max(1, Number(pageCount) || 1)
  const boundedPage = clamp(Number(page) || 1, 1, pages)
  return clamp(Math.floor(((boundedPage - 1) * positionCount) / pages), 0, positionCount - 1)
}

export function epubResume(progress, positions, pageCount) {
  if (positions.length === 0 || progress?.completed) return { index: 0, progression: 0 }

  const locator = progress?.locator
  const locatorPosition = Number(locator?.locations?.position)
  let index = Number.isFinite(locatorPosition)
    ? positions.findIndex((entry) => entry.position === locatorPosition)
    : -1

  if (index < 0 && locator?.href) {
    const progression = boundedProgression(locator.locations?.progression)
    const locatorPath = resourcePath(locator.href)
    positions.forEach((entry, candidate) => {
      if (
        resourcePath(entry.href) === locatorPath &&
        boundedProgression(entry.progression) <= progression
      ) {
        index = candidate
      }
    })
  }

  if (index < 0) index = indexForPage(progress?.page, positions.length, pageCount)
  return {
    index,
    progression: locator
      ? boundedProgression(locator.locations?.progression, boundedProgression(positions[index].progression))
      : boundedProgression(positions[index].progression),
  }
}

export function epubProgressFor(positions, href, progression, pageCount, atBottom) {
  const bounded = boundedProgression(progression)
  const currentPath = resourcePath(href)
  let index = positions.findIndex((entry) => resourcePath(entry.href) === currentPath)
  positions.forEach((entry, candidate) => {
    if (
      resourcePath(entry.href) === currentPath &&
      boundedProgression(entry.progression) <= bounded
    ) {
      index = candidate
    }
  })
  if (index < 0) index = 0

  const entry = positions[index]
  const next = positions[index + 1]
  const start = boundedProgression(entry?.progression)
  const end = resourcePath(next?.href) === currentPath ? boundedProgression(next.progression, 1) : 1
  const fraction = end > start ? clamp((bounded - start) / (end - start), 0, 1) : 0
  const totalProgression = positions.length
    ? clamp((index + fraction) / positions.length, 0, 1)
    : 0
  const pages = Math.max(1, Number(pageCount) || 1)
  const position = Number(entry?.position) || 1
  const page = clamp(Math.floor(((position - 1) * pages) / Math.max(1, positions.length)) + 1, 1, pages)

  return {
    page,
    locator: {
      href: entry?.href ?? href,
      locations: { progression: bounded, totalProgression, position },
    },
    completed: Boolean(atBottom && index === positions.length - 1),
    index,
  }
}
