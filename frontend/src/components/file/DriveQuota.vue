<template>
  <section v-if="!unavailable" class="drive-quota" aria-label="本次授权范围容量" :aria-busy="busy">
    <div class="drive-quota-copy">
      <span class="drive-quota-title">空间容量</span>
      <p v-if="error" role="alert">{{ error }}</p>
      <p v-else role="status">{{ busy && !view ? '正在读取容量…' : presentation.detail }}</p>
      <div v-if="presentation.percent !== null && !error" class="drive-quota-track" role="progressbar" :aria-valuenow="Math.round(presentation.percent)" aria-valuemin="0" aria-valuemax="100" aria-label="已用与预留容量">
        <span :style="{width: presentation.percent + '%'}" />
      </div>
      <small v-if="view?.reservedBytes > 0 || view?.pendingWrites > 0">存在待确认操作；不会因超时自动释放。校准需等待操作核查完成。</small>
    </div>
    <div class="drive-quota-actions">
      <el-button :disabled="busy" @click="read(false)">刷新容量</el-button>
      <el-button v-if="view && presentation.canReconcile" :disabled="busy" @click="read(true)">校准占用</el-button>
    </div>
  </section>
</template>
<script setup>
import { ref, computed, watch, onBeforeUnmount } from 'vue'
import { getDriveQuota, reconcileDriveQuota } from '~/api/home/drive-quota'
import { quotaPresentation } from '~/utils/quota-presentation.mjs'
const props = defineProps({driveKey:{type:String,required:true},identityKey:{type:String,required:true}})
const view=ref(null), busy=ref(false), error=ref(''), unavailable=ref(false)
const presentation=computed(()=>quotaPresentation(view.value))
let generation=0, controller
async function read(reconcile=false) {
  controller?.abort(); controller=new AbortController()
  const mine=++generation; busy.value=true; error.value=''
  try {
    const result=await (reconcile?reconcileDriveQuota:getDriveQuota)(props.driveKey,controller.signal)
    if(mine===generation) view.value=result.data
  } catch(failure) {
    if(mine!==generation)return
    view.value=null
    if(['QUOTA_UNAVAILABLE','DRIVE_ENGINE_UNAVAILABLE'].includes(failure?.response?.data?.code)) unavailable.value=true
    else error.value=failure?.response?.data?.code==='QUOTA_PENDING' ? '存在待核查写入，暂不能校准；预留容量仍保留。' : '容量暂时无法读取，请刷新；这不表示占用为零。'
  } finally {if(mine===generation)busy.value=false}
}
watch(()=>[props.driveKey,props.identityKey],()=>{++generation;controller?.abort();view.value=null;error.value='';unavailable.value=false;if(props.driveKey&&props.identityKey)read()}, {immediate:true})
onBeforeUnmount(()=>{++generation;controller?.abort();view.value=null})
</script>
<style scoped>
.drive-quota{display:flex;align-items:center;justify-content:space-between;gap:16px;padding:14px 18px;margin:0 0 16px;border:1px solid var(--cloud-line);border-radius:var(--el-border-radius-base);background:var(--cloud-surface);color:var(--cloud-ink)}
.drive-quota-copy{min-width:0;flex:1}.drive-quota-title{font-size:14px;font-weight:600}.drive-quota p,.drive-quota small{font-size:13px;color:var(--cloud-muted);margin:4px 0;overflow-wrap:anywhere}
.drive-quota-track{height:5px;max-width:360px;background:var(--cloud-canvas);border-radius:8px;overflow:hidden;margin-top:8px}.drive-quota-track span{display:block;height:100%;background:var(--cloud-accent)}.drive-quota-actions{display:flex;flex-wrap:wrap;gap:8px}.drive-quota-actions :deep(.el-button){margin:0}
@media(max-width:600px){.drive-quota{align-items:stretch;flex-direction:column}.drive-quota-actions :deep(.el-button){min-height:44px}}
</style>
