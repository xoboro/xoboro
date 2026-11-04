import { render, screen } from '@testing-library/svelte'
import { describe, expect, it } from 'vitest'
import SeriesActions from '../src/reader/SeriesActions.svelte'

describe('series entry actions', () => {
  const first = { id: 'chapter-1', title: 'Synthetic first chapter', progress: null }

  it('offers continue when the first chapter is partly read', () => {
    const started = { ...first, progress: { page: 6, completed: false } }
    render(SeriesActions, { first: started, resume: started })
    expect(screen.getByTestId('series-continue')).toHaveAttribute('href', '#/read/chapter-1')
    expect(screen.getByTestId('series-from-start')).toHaveAttribute('href', '#/read/chapter-1/start')
  })

  it('keeps restart distinct from continuing a later chapter', () => {
    render(SeriesActions, { first, resume: { id: 'chapter-2', title: 'Synthetic next chapter' } })
    expect(screen.getByTestId('series-continue')).toHaveAttribute('href', '#/read/chapter-2')
    expect(screen.getByTestId('series-from-start')).toHaveAttribute('href', '#/read/chapter-1/start')
  })

  it('does not offer duplicate continue for a chapter with no progress', () => {
    render(SeriesActions, { first, resume: first })
    expect(screen.queryByTestId('series-continue')).toBeNull()
    expect(screen.getByTestId('series-from-start')).toBeInTheDocument()
  })

  it('has no entry actions for an empty series', () => {
    render(SeriesActions, { first: null, resume: null })
    expect(screen.queryByTestId('series-continue')).toBeNull()
    expect(screen.queryByTestId('series-from-start')).toBeNull()
  })

  it('does not expose a stale resume destination during progress revalidation', () => {
    const started = { ...first, progress: { page: 6, completed: false } }
    render(SeriesActions, { first: started, resume: started, busy: true })
    expect(screen.getByTestId('series-continue')).toHaveAttribute('aria-disabled', 'true')
    expect(screen.getByTestId('series-continue')).not.toHaveAttribute('href')
    expect(screen.getByTestId('series-from-start')).toHaveAttribute('href', '#/read/chapter-1/start')
  })
})
