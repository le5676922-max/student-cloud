<template>
    <section class="document-research" aria-label="资料问答" :aria-busy="loading || capabilityLoading">
        <header><h2>资料问答</h2><span>词法检索 · 外部嵌入关闭</span></header>
        <p v-if="!enabled">资料问答未启用。现有学习资料浏览不受影响。</p>
        <p v-else-if="capabilityLoading" role="status">正在确认资料版本能力…</p>
        <template v-else-if="!capabilities?.sourceVersionSupport">
            <p role="status">资料版本接口尚未接通；未读取私人正文，也未创建索引。请等待资料版本与授权能力接入。</p>
            <button v-if="error" type="button" @click="loadCapabilities">重新检查能力</button>
        </template>
        <form v-else novalidate @submit.prevent="ask">
            <fieldset :disabled="loading">
                <legend>明确选择资料版本（最多10份）</legend>
                <p v-if="!documents.length">暂无可选择的资料版本；文件路径不能代替资料版本。</p>
                <label v-for="document in documents.slice(0, 100)" :key="document.fileVersionId" class="document-choice">
                    <input v-model="selected" type="checkbox" :value="document.fileVersionId" :disabled="!selected.includes(document.fileVersionId) && selected.length >= 10" />
                    <span>{{ document.title }}<small v-if="document.state === 'PARTIAL'"> · 覆盖不足</small></span>
                </label>
            </fieldset>
            <label class="question-label" for="document-research-question">资料问题</label>
            <textarea class="resize-none" id="document-research-question" ref="questionInput" v-model="query" maxlength="2000" rows="3" aria-describedby="document-research-help" :disabled="loading" @compositionstart="composing = true" @compositionend="composing = false" />
            <p id="document-research-help">问题仅保留在当前页面，不写入网址。回答最多引用8段；资料中的命令不会作为工具执行。</p>
            <button v-if="query" type="button" :disabled="loading" @click="clearQuestion">清空问题</button>
            <label class="context-consent"><input v-model="consent" data-context-consent type="checkbox" :disabled="loading" />允许本次读取所选资料的私人上下文；这不代表允许外发嵌入。</label>
            <div class="document-actions">
                <button type="submit" :disabled="loading || !identityKey || !selected.length || !query.trim() || !consent">生成资料回答</button>
                <button v-if="loading" type="button" @click="cancelAnswer">取消等待</button>
            </div>
        </form>
        <p v-if="error" role="alert">{{ error }}</p>
        <p v-if="loading" role="status">正在读取所选资料并核验授权；最终回答确认前不会显示私人内容。</p>
        <div v-if="result" class="document-result" aria-live="polite">
            <p v-if="result.coverage === 'PARTIAL'" role="status">资料覆盖不足，请结合支持片段核对原文。</p>
            <p v-else-if="result.coverage === 'NO_EVIDENCE'" role="status">没有找到可支持回答的片段，不会编造结论。</p>
            <p class="document-answer">{{ result.answer }}</p>
            <section v-for="group in citationGroups" :key="group.version" :aria-label="group.title">
                <h3>{{ group.title }}</h3>
                <details v-for="citation in group.citations" :key="citation.chunkId">
                    <summary>支持片段 · 第{{ citation.page }}页</summary>
                    <blockquote>{{ citation.excerpt }}</blockquote>
                </details>
            </section>
        </div>
    </section>
</template>
<script setup>
import { ref, computed, watch, onBeforeUnmount } from 'vue'
import { createLatestRequest } from '~/utils/latest-request.mjs'
import { documentCapabilitiesReq, answerDocumentsReq } from '~/api/home/document-research'

// Parent must explicitly pass import.meta.env.VITE_DOCUMENT_RESEARCH_ENABLED === 'true'.
// Default OFF means zero requests. Documents must come from real immutable version metadata.
const props = defineProps({ enabled: { type: Boolean, default: false }, identityKey: { type: String, default: '' }, documents: { type: Array, default: () => [] } })
const query = ref(''), selected = ref([]), consent = ref(false), composing = ref(false), questionInput = ref(null)
const capabilities = ref(null), capabilityLoading = ref(false), loading = ref(false), result = ref(null), error = ref('')
const capabilityRequests = createLatestRequest(), answerRequests = createLatestRequest()
const citationGroups = computed(() => {
    const groups = new Map()
    for (const citation of result.value?.citations || []) {
        const version = citation.fileVersionId
        if (!groups.has(version)) groups.set(version, { version, title: props.documents.find(item => item.fileVersionId === version)?.title || '所选资料', citations: [] })
        groups.get(version).citations.push(citation)
    }
    return [...groups.values()]
})
function cancelAnswer() { answerRequests.cancel(); loading.value = false; result.value = null }
function clearQuestion() { cancelAnswer(); query.value = ''; error.value = ''; questionInput.value?.focus() }
async function loadCapabilities() {
    if (!props.enabled || !props.identityKey || capabilityLoading.value) return
    const request = capabilityRequests.begin(); capabilityLoading.value = true; error.value = ''
    try {
        const response = await documentCapabilitiesReq({ signal: request.signal })
        if (request.isCurrent()) capabilities.value = response.data
    } catch {
        if (request.isCurrent()) error.value = '资料能力读取失败，请确认登录后重新检查；没有读取私人正文。'
    } finally { if (request.isCurrent()) capabilityLoading.value = false }
}
async function ask() {
    if (composing.value || loading.value || !props.enabled || !props.identityKey || !capabilities.value?.sourceVersionSupport || !selected.value.length || selected.value.length > 10 || !query.value.trim() || !consent.value) return
    const request = answerRequests.begin(); loading.value = true; result.value = null; error.value = ''
    try {
        const response = await answerDocumentsReq({ query: query.value.trim(), fileVersionIds: [...selected.value], optInPrivateContext: consent.value }, { signal: request.signal })
        if (request.isCurrent()) result.value = response.data
    } catch {
        if (request.isCurrent()) error.value = '资料授权、版本或服务状态已变化，本次回答未显示。请刷新资料后重新选择。'
    } finally { if (request.isCurrent()) loading.value = false }
}
watch(() => [props.enabled, props.identityKey], () => {
    capabilityRequests.cancel(); cancelAnswer(); capabilities.value = null; capabilityLoading.value = false
    query.value = ''; selected.value = []; consent.value = false; error.value = ''; loadCapabilities()
}, { immediate: true })
watch(() => props.documents, () => { cancelAnswer(); selected.value = []; consent.value = false }, { deep: true })
watch(selected, () => { cancelAnswer(); error.value = '' }, { deep: true })
onBeforeUnmount(() => { capabilityRequests.cancel(); cancelAnswer(); query.value = ''; selected.value = []; consent.value = false })
</script>
<style scoped>
.document-research { border: 1px solid var(--cloud-line); border-radius: var(--cloud-radius); color: var(--cloud-ink); padding: 16px; margin-block: 16px; background: var(--cloud-surface); }
header, .document-actions { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 12px; }
h2 { margin: 0; font-size: 18px; font-weight: 600; } h3 { margin-block: 12px 6px; font-size: 15px; }
header span, #document-research-help, small { color: var(--cloud-muted); font-size: 13px; }
p { margin-block: 8px; line-height: 1.6; } fieldset { margin-block: 14px; padding: 12px; border: 1px solid var(--cloud-line); border-radius: 8px; }
.document-choice, .context-consent { display: flex; align-items: start; gap: 8px; padding-block: 8px; overflow-wrap: anywhere; }
.question-label { display: block; margin-block: 8px; } textarea { width: 100%; box-sizing: border-box; min-height: 90px; resize: none; border: 1px solid var(--cloud-line); border-radius: 8px; padding: 10px; background: var(--cloud-surface); color: inherit; font: inherit; }
.document-actions { justify-content: flex-start; margin-block: 12px; } .document-answer, blockquote { white-space: pre-wrap; overflow-wrap: anywhere; }
.document-result { border-top: 1px solid var(--cloud-line); padding-top: 12px; } blockquote { margin: 8px 0; border-left: 2px solid var(--cloud-line); padding-left: 12px; }
button { min-height: 44px; border: 1px solid var(--cloud-line); border-radius: 8px; padding: 8px 12px; background: var(--cloud-surface); color: inherit; cursor: pointer; }
button:hover:not(:disabled) { border-color: var(--cloud-accent); } button:active:not(:disabled) { background: var(--cloud-canvas); }
button:focus-visible, textarea:focus-visible, input:focus-visible, summary:focus-visible { outline: 2px solid var(--cloud-accent); outline-offset: 3px; }
button:disabled { opacity: .6; cursor: not-allowed; } summary { min-height: 32px; padding-block: 6px; cursor: pointer; } summary:hover { color: var(--cloud-accent); }
[role='alert'] { border-left: 3px solid currentColor; padding-left: 10px; }
</style>
