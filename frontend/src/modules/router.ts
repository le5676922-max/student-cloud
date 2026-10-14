import type { App } from 'vue'
import type { AxiosRequestConfig } from 'axios'
import fileRoutes from '~pages'
import { setupLayouts } from 'virtual:meta-layouts'
import { createRouter, createWebHistory } from 'vue-router'
import useStorageConfigStore from '~/stores/storage-config'
import { createAuthenticationGuard } from '~/utils/auth-navigation.mjs'
import NProgress from 'nprogress'
import { ElMessage } from 'element-plus'

fileRoutes.push(
	{
		path: '/',
		meta: {
			layout: 'file',
		},
		component: () => import('~/pages/file.vue'),
	},
	{
		path: '/:storageKey/:fullpath(.*)*',
		meta: {
			layout: 'file',
		},
		props: true,
		component: () => import('~/pages/file.vue'),
	}
)

let routes = setupLayouts(fileRoutes)
routes.unshift({
	path: '/login/:entry',
	meta: {
		layout: 'default',
	},
	component: () => import('~/pages/login.vue'),
})
routes.push({
	path: '/admin',
	redirect: '/admin/overview',
})
export const router = createRouter({
	routes: routes,
	history: createWebHistory(),
})

NProgress.configure({ showSpinner: false })
router.beforeEach(() => {
	NProgress.start()
})
router.afterEach(() => NProgress.done())
router.onError(() => {
	NProgress.done()
	ElMessage.error('页面加载失败，请刷新页面后重试。')
})

router.beforeEach(
	createAuthenticationGuard({
		async loadLoginInfo(config: AxiosRequestConfig = {}) {
			// Load HTTP only after the application has installed Pinia.
			const { checkLoginReq } = await import('~/api/home/user')
			return (await checkLoginReq({ ...config, showDefaultMsg: false })).data
		},
		updateLoginInfo: (info: unknown) =>
			useStorageConfigStore().updateLoginInfo(info),
		clearLoginInfo: () => useStorageConfigStore().clearLoginInfo(),
	})
)

export default (app: App) => app.use(router)
