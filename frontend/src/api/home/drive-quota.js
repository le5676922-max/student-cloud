import axios from '~/http/request'
export const getDriveQuota = (key, signal) => axios({method:'get',url:`/api/drives/${encodeURIComponent(key)}/quota`,config:{signal,showDefaultMsg:false,timeout:15000}})
export const reconcileDriveQuota = (key, signal) => axios({method:'post',url:`/api/drives/${encodeURIComponent(key)}/quota/reconcile`,config:{signal,showDefaultMsg:false,timeout:30000}})
