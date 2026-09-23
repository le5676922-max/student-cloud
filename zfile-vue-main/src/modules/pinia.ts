import type { App } from 'vue'

const pinia = createPinia()
setActivePinia(pinia)

export default (app: App) => app.use(pinia)
