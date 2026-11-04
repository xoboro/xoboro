import { describe, expect, it } from 'vitest'
import { imageLocator, imageResumeView, imageScrollProgression } from '../src/reader/imagePosition.js'
import { buildViews } from '../src/reader/views.js'

describe('image reading positions', () => {
  const pages = [{ number: 1, width: 800, height: 1200 }, { number: 2, width: 1600, height: 1200 }]

  it('records the centre inside a long image and clamps geometry outside its bounds', () => {
    expect(imageScrollProgression({ top: -5600, bottom: 2400 }, 400)).toBe(0.75)
    expect(imageScrollProgression({ top: 500, bottom: 600 }, 400)).toBe(0)
    expect(imageScrollProgression({ top: -500, bottom: 200 }, 400)).toBe(1)
    expect(imageScrollProgression({ top: 0, bottom: 0 }, 400)).toBeNull()
  })

  it.each(['ltr', 'rtl'])('restores the physical spread half in %s reading order', (direction) => {
    const views = buildViews(pages, true, direction)
    for (const half of ['L', 'R']) {
      const view = views.find((candidate) => candidate.half === half)
      const saved = { page: 2, completed: false, locator: imageLocator(view, 0.75) }
      expect(imageResumeView(saved, views, 2)).toEqual({ index: views.indexOf(view), progression: 0.75 })
    }
  })

  it('ignores completed, foreign, inconsistent, and invalid locators', () => {
    const views = buildViews(pages, false, 'ltr')
    const locator = imageLocator(views[1], 0.75)
    expect(imageResumeView({ completed: true, locator }, views, 1)).toEqual({ index: 0, progression: null })
    expect(imageResumeView({ locator }, views, 1)).toEqual({ index: 0, progression: null })
    expect(imageResumeView({ locator: { ...locator, xoboro: { reader: 'epub' } } }, views, 2))
      .toEqual({ index: 1, progression: null })
    expect(imageResumeView({ locator: { ...locator, locations: { position: 2, progression: 5 } } }, views, 2))
      .toEqual({ index: 1, progression: null })
  })
})
