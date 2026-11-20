export function createLatestRequest() {
  let current: AbortController | undefined;
  function cancel() {
    current?.abort();
    current = undefined;
  }
  return {
    cancel,
    start() {
      cancel();
      current = new AbortController();
      return current.signal;
    },
  };
}
