import { loadIngestConfig, saveIngestConfig, runIngest, uploadResumePdf, loadIngestStats } from '../api/notes'

export const knowledgeService = {
  async getConfig() {
    const res = await loadIngestConfig()
    return {
      paths: res?.paths || '',
      ignoreDirs: res?.ignoreDirs || ''
    }
  },
  async saveConfig(payload) {
    return await saveIngestConfig(payload)
  },
  async sync(payload) {
    return await runIngest(payload)
  },
  async uploadPdf(file) {
    return await uploadResumePdf(file)
  },
  async getStats() {
    const res = await loadIngestStats()
    return {
      totalScanned: res?.totalScanned || 0,
      totalIndexed: res?.totalIndexed || 0,
      successRate: res?.successRate || '0%',
      failedFiles: res?.failedFiles || 0,
      recentReports: (res?.recentReports || []).map(r => ({
        fileName: r.fileName,
        status: r.status,
        message: r.message,
        timestamp: r.timestamp || Date.now()
      }))
    }
  }
}
