import test from 'node:test'
import assert from 'node:assert/strict'
import { webcrypto } from 'node:crypto'
import { tryIndependentResumableUpload, readIndependentResumableStatus } from '../src/utils/independent-resumable-upload.mjs'
const chunk=4*1024*1024
const id='12345678-1234-1234-1234-123456789abc'
const file=()=>new File([new Uint8Array(chunk*2)],'one.bin')
const caps={enabled:true,protocol:'independent-v1',chunkSize:chunk,maxFileSize:50*1024*1024,expiresInSeconds:86400}
const view=(size,receivedChunks=[],state='OPEN')=>({id,state,size,chunkSize:chunk,expiresAt:Date.now()+86400000,receivedChunks})
test('independent protocol resumes only missing chunks and sends no directory password',async()=>{
 const item=file(),puts=[],received=[0]
 const result=await tryIndependentResumableUpload({file:item,uploadType:'PROXY',storageKey:'drive',path:'/',cryptoApi:webcrypto,request:async options=>{
  assert.equal(options.headers?.['X-Upload-Directory-Password'],undefined)
  if(options.url.endsWith('/capabilities'))return {data:caps}
  if(options.method==='PUT'){puts.push(options.url);received.push(1)}
  return {data:view(item.size,[...received],options.url.endsWith('/complete')?'COMPLETED':'OPEN')}
 }})
 assert.equal(result.session.state,'COMPLETED');assert.equal(puts.length,1);assert.ok(puts[0].endsWith('/1'))
})
test('independent branch rejects a legacy protocol and files over its 50MiB budget',async()=>{
 await assert.rejects(tryIndependentResumableUpload({file:file(),uploadType:'PROXY',request:async()=>({data:{...caps,protocol:undefined}})}),/独立上传/)
 await assert.rejects(tryIndependentResumableUpload({file:{size:51*1024*1024},uploadType:'PROXY',request:()=>{throw Error('must not send')}}),/50 MiB/)
})
test('status requests remain header authenticated and completion uncertainty is never replayed',async()=>{
 const item=file(),calls=[]
 await assert.rejects(tryIndependentResumableUpload({file:item,uploadType:'PROXY',cryptoApi:webcrypto,request:async options=>{calls.push(options);return {data:options.url.endsWith('/capabilities')?caps:view(item.size,[0,1],'COMPLETING')}}}),error=>error.name==='ResumableCompletionPendingError')
 assert.equal(calls.length,2)
 const state=await readIndependentResumableStatus({id,size:item.size,request:async options=>{assert.equal(options.method,'GET');return {data:view(item.size,[0,1],'COMPLETED')}}})
 assert.equal(state.state,'COMPLETED')
})
