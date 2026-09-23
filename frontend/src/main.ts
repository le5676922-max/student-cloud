// 你自定义的 css
import './styles/main.css'
import './styles/tailwind/index.scss'
import 'babel-polyfill'

import App from './App.vue'
import axios from "axios";
const app = createApp(App)

import useGlobalConfigStore from "~/stores/global-config";
const globalConfigStore = useGlobalConfigStore();

import config from '../package.json'
console.log("\n %c 学生云盘 " + config.version + " %c 个人云存储项目 \n\n", "background: #4f46e5; padding: 1px; border-radius: 3px 0 0 3px; color: #fff", "background: #34d399; padding: 1px; border-radius: 0 3px 3px 0; color: #fff");

axios.get('/student-cloud.config.json').then((res) => {
  globalConfigStore.updateStudentCloudConfig(res.data);
}).finally(() => {
  app.mount('#app')
})