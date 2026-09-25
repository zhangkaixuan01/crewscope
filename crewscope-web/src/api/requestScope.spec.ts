import { createRequestScope } from './requestScope'

describe('request scope', () => {
  it('aborts superseded reads and ignores stale finish without releasing the current request', () => {
    const scope = createRequestScope(() => 'same')
    const old = scope.begin('list')
    const current = scope.begin('list')
    expect(old.signal.aborted).toBe(true)
    expect(old.isCurrent()).toBe(false)
    old.finish()
    expect(current.isCurrent()).toBe(true)
    current.finish()
    expect(current.isCurrent()).toBe(false)
  })
  it('checks identity coordinates even before a reactive watcher runs', () => {
    let identity = 'alice'
    const scope = createRequestScope(() => identity)
    const request = scope.begin('read')
    identity = 'bob'
    expect(request.isCurrent()).toBe(false)
  })
  it('rejects A-B-A results through a monotonic epoch, including adapters ignoring Abort', () => {
    let team = 'A'
    const scope = createRequestScope(() => team)
    const owner = scope.capture()
    const request = scope.begin('read')
    team = 'B'; scope.invalidate()
    team = 'A'; scope.invalidate()
    expect(owner.isCurrent()).toBe(false)
    expect(request.isCurrent()).toBe(false)
    expect(request.signal.aborted).toBe(true)
  })
  it('keeps independent read channels and invalidates all on disposal', () => {
    const scope = createRequestScope(() => 'team')
    const detail = scope.begin('detail')
    const list = scope.begin('list')
    expect(detail.isCurrent()).toBe(true)
    scope.dispose()
    expect(detail.signal.aborted).toBe(true)
    expect(list.isCurrent()).toBe(false)
    expect(scope.capture().isCurrent()).toBe(false)
  })
})
