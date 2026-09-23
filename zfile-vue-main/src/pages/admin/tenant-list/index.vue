<template>
  <div>
    <admin-form-header hide-bottom-border title="工作空间管理" />

    <div class="z-admin-table-tools">
      <el-button type="primary" :icon="PlusIcon" @click="openCreateDialog">新建工作空间</el-button>
    </div>

    <el-table-plus border :data="tenantList" v-loading="loading">
      <el-table-column-plus width="80" align="center" prop="id" label="ID" />
      <el-table-column-plus min-width="220" prop="name" label="工作空间名称" />
      <el-table-column-plus width="130" align="center" prop="ownerUserId" label="负责人用户 ID">
        <template #default="scope">
          {{ scope.row.ownerUserId || "暂未分配" }}
        </template>
      </el-table-column-plus>
      <el-table-column-plus width="110" align="center" prop="enable" label="状态">
        <template #default="scope">
          <el-tag :type="scope.row.enable ? 'success' : 'info'">
            {{ scope.row.enable ? "启用" : "停用" }}
          </el-tag>
        </template>
      </el-table-column-plus>
      <el-table-column-plus width="90" align="center" label="操作">
        <template #default="scope">
          <PencilSquareIcon class="z-btn-primary" @click="openEditDialog(scope.row)" />
        </template>
      </el-table-column-plus>
    </el-table-plus>

    <el-dialog v-model="dialogVisible" :title="form.id ? '编辑工作空间' : '新建工作空间'" width="420px">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="100px">
        <el-form-item label="空间名称" prop="name">
          <el-input v-model="form.name" maxlength="100" />
        </el-form-item>
        <el-form-item label="启用状态">
          <el-switch v-model="form.enable" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { PencilSquareIcon, PlusIcon } from "@heroicons/vue/24/outline";
import { getTenantListReq, saveTenantReq } from "~/api/admin/admin-tenant";

const tenantList = ref([]);
const loading = ref(false);
const saving = ref(false);
const dialogVisible = ref(false);
const formRef = ref();
const form = reactive({ id: null, name: "", enable: true });
const rules = { name: [{ required: true, message: "请输入工作空间名称", trigger: "blur" }] };

const loadData = () => {
  loading.value = true;
  getTenantListReq()
    .then(res => tenantList.value = res.data)
    .finally(() => loading.value = false);
};

const openCreateDialog = () => {
  Object.assign(form, { id: null, name: "", enable: true });
  dialogVisible.value = true;
};

const openEditDialog = row => {
  Object.assign(form, { id: row.id, name: row.name, enable: row.enable });
  dialogVisible.value = true;
};

const save = () => {
  formRef.value.validate(valid => {
    if (!valid) return;
    saving.value = true;
    saveTenantReq(form)
      .then(() => {
        ElMessage.success("保存成功");
        dialogVisible.value = false;
        loadData();
      })
      .finally(() => saving.value = false);
  });
};

onMounted(loadData);
</script>

<route lang="yaml">
meta:
  layout: admin
  name: 工作空间管理
</route>
