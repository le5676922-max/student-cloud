// 你自定义的 css
import './styles/main.css'
import './styles/tailwind/index.scss'

import App from './App.vue'
import axios from 'axios'
const app = createApp(App)

import useGlobalConfigStore from '~/stores/global-config'
const globalConfigStore = useGlobalConfigStore()

import config from '../package.json'
console.log(
	'\n %c 学生云盘 ' + config.version + ' %c 个人云存储项目 \n\n',
	'background: #4f46e5; padding: 1px; border-radius: 3px 0 0 3px; color: #fff',
	'background: #34d399; padding: 1px; border-radius: 0 3px 3px 0; color: #fff'
)

axios
	.get('/student-cloud.config.json', { timeout: 5000 })
	.then((res) => {
		globalConfigStore.updateStudentCloudConfig(res.data)
	})
	.catch(() => {
		// Default same-site configuration still lets the user reach login and retry.
		console.warn('本地站点配置未能读取，已使用默认配置。')
	})
	.finally(() => {
		app.mount('#app')
	})
