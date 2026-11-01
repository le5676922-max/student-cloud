import axios from '~/http/request'

// No owner, role, storage URL or host path is accepted by these document contracts.
export const documentCapabilitiesReq = config => axios({
    url: '/api/ai/documents/capabilities', method: 'get',
    config: { showDefaultMsg: false, timeout: 15000, ...config },
})
export const answerDocumentsReq = (input, config) => axios({
    url: '/api/ai/documents/answer', method: 'post',
    data: { query: input.query, fileVersionIds: input.fileVersionIds, optInPrivateContext: input.optInPrivateContext === true },
    config: { showDefaultMsg: false, timeout: 60000, ...config },
})
export const searchDocumentsReq = (input, config) => axios({
    url: '/api/ai/documents/search', method: 'post',
    data: { query: input.query, fileVersionIds: input.fileVersionIds, optInPrivateContext: input.optInPrivateContext === true },
    config: { showDefaultMsg: false, timeout: 15000, ...config },
})
