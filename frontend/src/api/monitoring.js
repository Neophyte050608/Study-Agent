import { httpGet } from './http'

export async function loadModelRoutingStats() {
  return httpGet('/api/model-routing/stats')
}
