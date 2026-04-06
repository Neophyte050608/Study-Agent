import { 
  loadIngestConfig, 
  saveIngestConfig, 
  runIngest, 
  uploadResumePdf, 
  loadIngestStats,
  getKnowledgeBases,
  getDocuments,
  getChunks,
  setDocumentEnabled,
  removeDocument,
  rechunkDocument,
  setChunkEnabled
} from '../api/notes'

export const knowledgeService = {
  async getConfig() {
    const res = await loadIngestConfig()
    return {
      paths: res?.paths || '',
      imagePath: res?.imagePath || '',
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
        fileName: r.fileName || r.sourceType,
        status: r.status === 'SUCCESS' ? 'success' : 'failed',
        message: r.errorMessage || '任务处理完毕',
        timestamp: r.startedAt || Date.now()
      }))
    }
  },
  async getKnowledgeBases() {
    return await getKnowledgeBases()
  },
  async getDocuments(kbId, params) {
    return await getDocuments(kbId, params)
  },
  async getChunks(docId, params) {
    return await getChunks(docId, params)
  },
  async setDocumentEnabled(docId, enabled) {
    return await setDocumentEnabled(docId, enabled)
  },
  async deleteDocument(docId) {
    return await removeDocument(docId)
  },
  async rechunkDocument(docId) {
    return await rechunkDocument(docId)
  },
  async setChunkEnabled(docId, chunkId, enabled) {
    return await setChunkEnabled(docId, chunkId, enabled)
  }
}
