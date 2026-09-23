import type { App } from 'vue'
import fileRoutes from '~pages'
import { setupLayouts } from 'virtual:meta-layouts'
import { createRouter, createWebHistory } from 'vue-router'
import useStorageConfigStore from '~/stores/storage-config'
import { canAccessAdministration } from '~/utils/roles.mjs'

fileRoutes.push({
  path: '/',
  meta: {
    layout: 'file'
  },
  component: () => import('~/pages/file.vue')
}, {
  path: '/:storageKey/:fullpath(.*)*',
  meta: {
    layout: 'file'
  },
  props: true,
  component: () => import('~/pages/file.vue')
});

let routes = setupLayouts(fileRoutes);
routes.unshift({
  path: '/login/:entry',
  meta: {
    layout: 'default'
  },
  component: () => import('~/pages/login.vue')
});
routes.push({
  path: '/admin',
  redirect: '/admin/storage-list'
})
export const router = createRouter({
  routes: routes,
  history: createWebHistory(),
})

router.beforeEach(async to => {
  if (to.path === '/admin' || to.path.startsWith('/admin/')) {
    try {
      // Load HTTP only after the application has installed Pinia.
      const { checkLoginReq } = await import('~/api/home/user');
      const response = await checkLoginReq();
      useStorageConfigStore().updateLoginInfo(response.data);
      return canAccessAdministration(response.data) ? true : '/';
    } catch {
      return '/';
    }
  }
  return true;
});

export default (app: App) => app.use(router)
