import { isTopmostModal } from './dialog'

describe('dialog focus boundary', () => {
  it('recognizes only the last active modal', () => {
    const background = document.createElement('aside')
    background.setAttribute('role', 'dialog')
    background.setAttribute('aria-modal', 'true')
    const foreground = background.cloneNode() as HTMLElement
    document.body.append(background, foreground)

    expect(isTopmostModal(background)).toBe(false)
    expect(isTopmostModal(foreground)).toBe(true)
    expect(isTopmostModal(null)).toBe(false)

    background.remove()
    foreground.remove()
  })
})
