/** Read cancellation is an optimization; epoch/context checks also reject adapters ignoring Abort. */
export function createRequestScope(context: () => string) {
  let epoch = 0
  let disposed = false
  const requests = new Map<string, AbortController>()

  function capture() {
    const at = epoch
    const coordinate = context()
    return { isCurrent: () => !disposed && epoch === at && context() === coordinate }
  }

  function begin(channel: string) {
    requests.get(channel)?.abort()
    const controller = new AbortController()
    const owner = capture()
    requests.set(channel, controller)
    return {
      signal: controller.signal,
      isCurrent: () => owner.isCurrent() && requests.get(channel) === controller,
      finish: () => { if (requests.get(channel) === controller) requests.delete(channel) },
    }
  }

  function invalidate() {
    epoch += 1
    requests.forEach(controller => controller.abort())
    requests.clear()
  }

  return { begin, capture, invalidate, dispose: () => { disposed = true; invalidate() } }
}
