import axios from "~/http/request";

export const getTenantListReq = () => axios({
  url: "/admin/tenants",
  method: "get"
});

export const saveTenantReq = (data) => axios({
  url: "/admin/tenant",
  method: "post",
  data
});

