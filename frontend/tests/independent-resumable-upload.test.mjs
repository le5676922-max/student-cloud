import test from 'node:test'
import assert from 'node:assert/strict'
import { createHash, webcrypto } from 'node:crypto'
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
test('independent branch rejects a legacy protocol and files over its hard 1GiB budget',async()=>{
 await assert.rejects(tryIndependentResumableUpload({file:file(),uploadType:'PROXY',request:async()=>({data:{...caps,protocol:undefined}})}),/独立上传/)
 await assert.rejects(tryIndependentResumableUpload({file:{size:1024**3+1},uploadType:'PROXY',request:()=>{throw Error('must not send')}}),/1 GiB/)
})

for (const size of [51*1024*1024,128*1024*1024,1024**3]) {
 test(`independent dynamic capability hashes ${size} bytes in bounded 4MiB slices`,async()=>{
  const bytes=new Uint8Array(chunk), slices=[], calls=[]
  const item={size,name:'large.bin',arrayBuffer(){assert.fail('whole file must never be read')},slice(start,end){
   assert.ok(end-start<=chunk);assert.ok(start>=0&&end<=size);slices.push([start,end]);return new Blob([bytes.subarray(0,end-start)])
  }}
  const count=Math.ceil(size/chunk)
  const fullHash=createHash('sha256').update(bytes).digest('hex')
  const expected=Array.from({length:count},(_,index)=>index===count-1&&size%chunk?createHash('sha256').update(bytes.subarray(0,size%chunk)).digest('hex'):fullHash)
  const result=await tryIndependentResumableUpload({file:item,uploadType:'PROXY',storageKey:'local-drive',path:'/',cryptoApi:webcrypto,request:async options=>{
   calls.push(options)
   if(options.url.endsWith('/capabilities'))return {data:{...caps,maxFileSize:size}}
   assert.ok(options.url.endsWith('/init'))
   assert.equal(options.data.chunkHashes.length,count)
   assert.deepEqual(options.data.chunkHashes,expected)
   assert.equal(options.data.manifestHash,createHash('sha256').update(expected.join('\n')).digest('hex'))
   return {data:view(size,Array.from({length:count},(_,index)=>index),'COMPLETED')}
  }})
  assert.equal(result.handled,true);assert.equal(result.session.state,'COMPLETED');assert.equal(calls.length,2)
  assert.equal(slices.length,count);assert.deepEqual(slices[0],[0,chunk]);assert.deepEqual(slices.at(-1),[(count-1)*chunk,size])
  for(let index=0;index<count;index++)assert.deepEqual(slices[index],[index*chunk,Math.min(size,(index+1)*chunk)])
 })
}

test('independent lower target limits and invalid capabilities reject before hashing or initialization',async()=>{
 for (const maxFileSize of [50*1024*1024,1024**3+1,0,Infinity,'1073741824',1.5]) {
  let calls=0
  await assert.rejects(tryIndependentResumableUpload({file:{size:51*1024*1024,name:'large.bin',slice(){assert.fail('must reject before hashing')}},uploadType:'PROXY',request:async()=>{calls++;return {data:{...caps,maxFileSize}}}}))
  assert.equal(calls,1)
 }
})

test('independent disabled and non-proxy paths never allow large multipart fallback',async()=>{
 for (const uploadType of ['PROXY','DIRECT']) {
  let calls=0
  await assert.rejects(tryIndependentResumableUpload({file:{size:51*1024*1024},uploadType,request:async()=>{calls++;return {data:{enabled:false,protocol:'independent-v1'}}}}),/50 MiB|续传/)
  assert.equal(calls,uploadType==='PROXY'?1:0)
 }
 assert.deepEqual(await tryIndependentResumableUpload({file:{size:8*1024*1024},uploadType:'PROXY',request:async()=>({data:{enabled:false,protocol:'independent-v1'}})}),{handled:false})
})
test('status requests remain header authenticated and completion uncertainty is never replayed',async()=>{
 const item=file(),calls=[]
 await assert.rejects(tryIndependentResumableUpload({file:item,uploadType:'PROXY',cryptoApi:webcrypto,request:async options=>{calls.push(options);return {data:options.url.endsWith('/capabilities')?caps:view(item.size,[0,1],'COMPLETING')}}}),error=>error.name==='ResumableCompletionPendingError')
 assert.equal(calls.length,2)
 const state=await readIndependentResumableStatus({id,size:item.size,request:async options=>{assert.equal(options.method,'GET');return {data:view(item.size,[0,1],'COMPLETED')}}})
 assert.equal(state.state,'COMPLETED')
})
