import { httpGet } from './http'

export function loadProfileOverview() {
  return httpGet('/api/profile/overview')
}

export function loadProfileEvents(limit = 5) {
  return httpGet(`/api/observability/profile/events?limit=${limit}`)
}
