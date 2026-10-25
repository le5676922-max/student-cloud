import assert from 'node:assert/strict'
import test, { after } from 'node:test'
import { readFile } from 'node:fs/promises'
import { JSDOM } from 'jsdom'
import { parse, compileScript, compileTemplate, compileStyle } from 'vue/compiler-sfc'
import { createLatestRequest } from '../src/utils/latest-request.mjs'

const dom = new JSDOM('<!doctype html><html><body></body></html>')
for (const key of ['window','document','Document','Node','Element','HTMLElement','HTMLInputElement','HTMLSelectElement','SVGElement','Event']) globalThis[key] = dom.window[key]
const vue = await import('vue')
after(() => dom.window.close())
const flush = async () => { for(let i=0;i<14;i++) { await Promise.resolve(); await vue.nextTick() } }
const deferred = () => { let resolve; const promise = new Promise(done => { resolve=done }); return { resolve,promise } }
const transform = code => code.replace(/import\s+\{([\s\S]*?)\}\s+from\s+['"]([^'"]+)['"]/g, (_match,names,module) => `const {${names.replace(/\s+as\s+/g,': ')}} = modules[${JSON.stringify(module)}]`).replace(/import\s+(\w+)\s+from\s+['"]([^'"]+)['"]/g, (_match,name,module) => `const ${name} = modules[${JSON.stringify(module)}]`)
async function mount(file, role, api, props={}) {
	const descriptor = parse(await readFile(new URL(`../src/components/${file}`,import.meta.url),'utf8')).descriptor
	const script = compileScript(descriptor,{id:file}), template = compileTemplate({source:descriptor.template.content,filename:file,id:file,compilerOptions:{bindingMetadata:script.bindings}})
	assert.deepEqual(template.errors,[])
	for(const style of descriptor.styles) assert.deepEqual(compileStyle({source:style.content,filename:file,id:file,scoped:true}).errors,[])
	const store=vue.reactive({loginInfo:{isLogin:true,role,tenantId:9,username:'fixture'}})
	const modules={vue,'~/utils/latest-request.mjs':{createLatestRequest},'~/stores/storage-config':()=>store,...api}
	const Component=new Function('modules',transform(script.content).replace('export default','return'))(modules)
	Component.render=new Function('modules',transform(template.code).replace('export function render','return function render'))(modules)
	const host=document.createElement('div');document.body.append(host)
	const app=vue.createApp(Component,props);app.component('router-link',{props:['to'],setup:(p,{slots})=>()=>vue.h('a',{href:p.to},slots.default?.())})
	const vm=app.mount(host);await flush()
	return {host,store,api:vm.$.setupState,dispose(){app.unmount();host.remove()}}
}
const button=(app,text)=>[...app.host.querySelectorAll('button')].find(b=>b.textContent.includes(text))
const cloudApi=(values={})=>({'~/api/home/workspace-cloud':{getMemberCloudsReq:async()=>({data:[{id:31,name:'本空间云盘',granted:false,status:'NONE'}]}),getCloudApplicationsReq:async()=>({data:[]}),applyMemberCloudReq:async()=>({data:'id'}),reviewCloudApplicationReq:async()=>({data:{}}),...values}})
test('cloud access only applies on explicit click and repeats are blocked while request is pending',async()=>{
	const response=deferred(),calls=[]
	const app=await mount('auth/WorkspaceCloudAccess.vue','USER',cloudApi({applyMemberCloudReq:(...args)=>{calls.push(args);return response.promise}}))
	try{assert.equal(calls.length,0);button(app,'申请使用').click();await flush();assert.equal(calls.length,1);assert.equal(button(app,'申请使用').disabled,true);button(app,'申请使用').click();assert.equal(calls.length,1);response.resolve({data:'id'});await flush();assert.match(app.host.textContent,/已提交给空间管理员/)}finally{app.dispose()}
})
test('cloud approval requires a finite administrator-set quota and sends only selected member operations',async()=>{
	const calls=[];const app=await mount('auth/WorkspaceCloudAccess.vue','TENANT_ADMIN',cloudApi({getCloudApplicationsReq:async()=>({data:[{id:'a'.repeat(32),userId:10,displayName:'Member',storageName:'Cloud',reason:'学习'}]}),reviewCloudApplicationReq:async(...args)=>{calls.push(args);return {data:{}}}}))
	try{app.api.rows[0].quotaGB=-1;await flush();assert.equal(button(app,'同意并开通').disabled,true);app.api.rows[0].quotaGB=2.5;app.api.rows[0].allowUpload=true;await flush();button(app,'同意并开通').click();await flush();assert.equal(calls[0][1].quotaBytes,2684354560);assert.deepEqual(calls[0][1].permissions,['preview','download','upload','newFolder']);assert.equal(calls[0][1].approve,true)}finally{app.dispose()}
})
test('a changed account immediately clears private cloud requests and cancels pending mutations',async()=>{
	const response=deferred(),calls=[];const app=await mount('auth/WorkspaceCloudAccess.vue','USER',cloudApi({applyMemberCloudReq:(...args)=>{calls.push(args);return response.promise}}))
	try{button(app,'申请使用').click();await flush();app.store.loginInfo.isLogin=false;assert.equal(calls[0][2].signal.aborted,true);assert.deepEqual(app.api.rows,[]);response.resolve({data:'id'});await flush();assert.doesNotMatch(app.host.textContent,/已提交给/)}finally{app.dispose()}
})
const metrics=()=>({tenantId:null,generatedAt:Date.now(),cacheSeconds:30,storageTypes:[{key:'local',count:2}],pendingApprovals:[{key:'LOCAL_STORAGE',count:1},{key:'MEMBER_CLOUD',count:2}],feedbackStatuses:[{key:'OPEN',count:3}],knowledgeScopes:[{key:'TENANT',count:1}],courses:{total:2,active:2,members:4,materials:3,usedBytes:1024,quotaBytes:4096},courseUsageByWorkspace:[{tenantId:9,name:'课程空间A',courses:2,usedBytes:1024,quotaBytes:4096}],personalStorageUsedBytes:null})
test('business charts render actual counts, preserve unknown personal usage, and switch to readable tables',async()=>{
	let reads=0;const app=await mount('admin/AdminBusinessCharts.vue','ADMIN',{'~/api/admin/business-metrics':{getBusinessMetricsReq:async()=>{reads++;return {data:metrics()}}}})
	try{assert.equal(reads,1);assert.match(app.host.textContent,/本地云盘/);assert.match(app.host.textContent,/个人云盘总占用：暂未统一计量/);assert.ok(app.host.querySelectorAll('svg').length>2);button(app,'数据明细').click();await flush();assert.ok(app.host.querySelectorAll('table').length>=3);assert.equal(reads,1)}finally{app.dispose()}
})
test('chart workspace click filters the bounded server query rather than loading every user or file',async()=>{
	const calls=[];const app=await mount('admin/AdminBusinessCharts.vue','ADMIN',{'~/api/admin/business-metrics':{getBusinessMetricsReq:async(...args)=>{calls.push(args);return {data:metrics()}}}})
	try{button(app,'课程空间A').click();await flush();assert.equal(calls.length,2);assert.equal(calls[1][0],9);assert.equal(app.api.selectedTenant,9)}finally{app.dispose()}
})
test('invalid metrics never become zero statistics and demotion fences a late response',async()=>{
	const response=deferred();let count=0;const app=await mount('admin/AdminBusinessCharts.vue','ADMIN',{'~/api/admin/business-metrics':{getBusinessMetricsReq:async()=>{if(count++===0)return {data:{}};return response.promise}}})
	try{assert.match(app.host.textContent,/业务统计暂不可用/);button(app,'刷新').click();await flush();app.store.loginInfo.role='USER';response.resolve({data:metrics()});await flush();assert.equal(app.api.metrics,null);assert.doesNotMatch(app.host.textContent,/课程空间A/)}finally{app.dispose()}
})
