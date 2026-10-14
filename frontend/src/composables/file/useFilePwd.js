import { minimatch } from 'minimatch'
import useRouterData from '~/composables/useRouterData'
import { concatPath, removeDuplicateSeparator } from '~/utils'
import MessageBox from '~/components/messageBox/messageBox'

let { storageKey, currentPath } = useRouterData()

const studentCloudPasswordCache = useStorage('student-cloud-pwd-cache', {})
const fullStudentCloudPasswordCache = useStorage(
	'student-cloud-pwd-full-cache',
	{},
	sessionStorage
)

import useStorageConfigStore from '~/stores/storage-config'
let storageConfigStore = useStorageConfigStore()

export default function useFilePwd() {
	/**
	 * 弹出目录密码输入框（可配置是否展示“记住密码”）
	 * - Promise 风格：popPassword(options?) => Promise<{ value, checkbox }>
	 * - 兼容旧式：popPassword(onConfirm, onCancel)
	 */
	const popPassword = (arg1, arg2) => {
		const isCallbackStyle =
			typeof arg1 === 'function' || typeof arg2 === 'function'
		const onConfirm = isCallbackStyle ? arg1 : null
		const onCancel = isCallbackStyle ? arg2 : null

		// 默认配置
		const opts = isCallbackStyle ? {} : arg1 || {}
		const {
			showRemember = true,
			defaultRemember = storageConfigStore.globalConfig?.defaultSavePwd,
			defaultValue = getPathPwd(null, true),
			title = '提示',
			message = '此文件夹已加密，请输入密码：',
			inputPlaceholder = '请输入',
		} = opts

		const promise = MessageBox.prompt(message, title, {
			confirmButtonText: '确定',
			cancelButtonText: '取消',
			inputType: 'password',
			inputPlaceholder,
			checkbox: !!showRemember,
			defaultChecked: !!defaultRemember,
			inputDefault: defaultValue || '',
			checkboxLabel: '记住密码',
			inputValidator(val) {
				return !!val
			},
			inputErrorMessage: '密码不能为空.',
		})

		if (isCallbackStyle) {
			promise
				.then(({ value, checkbox }) => {
					onConfirm && onConfirm(value, checkbox)
				})
				.catch(() => {
					onCancel && onCancel()
				})
			// 同步返回 undefined（与旧式保持行为，真正结果通过回调）
			return
		}

		return promise.then(({ value, checkbox }) => ({ value, checkbox }))
	}

	// 向缓存中写入当前路径密码
	let putPathPwd = (pattern, password, rememberPassword) => {
		if (pattern) {
			// 如果表达式开头没写 / ，则自动补全
			pattern = pattern.startsWith('/') ? pattern : '/' + pattern

			// 初始化存储源 key
			if (!studentCloudPasswordCache.value[storageKey.value]) {
				studentCloudPasswordCache.value[storageKey.value] = {}
			}
			if (!fullStudentCloudPasswordCache.value[storageKey.value]) {
				fullStudentCloudPasswordCache.value[storageKey.value] = {}
			}

			// 修正 glob 表达式兼容性和服务端不同的 bug
			if (pattern.endsWith('**') && !pattern.endsWith('/**')) {
				pattern = removeDuplicateSeparator(
					pattern.substring(0, pattern.length - 2) + '/**'
				)
				console.log(
					'检测到密码文件夹通配符 ** 前未写 /，自动将其修正为为：',
					pattern
				)
			}

			// 根据是否记住密码，写入不同的缓存
			if (rememberPassword) {
				studentCloudPasswordCache.value[storageKey.value][pattern] = password
			}
			fullStudentCloudPasswordCache.value[storageKey.value][pattern] = password
		}
	}

	// 获取当前路径缓存中的密码
	let getPathPwd = (path, ignoreTempPassword, targetContext) => {
		// 如果没传递 path，则使用当前路径
		let currentPathValue = path || currentPath.value
		// 用户存储源基目录
		let rootPath = targetContext?.rootPath ?? storageConfigStore.user.rootPath
		let targetStorageKey = targetContext?.storageKey ?? storageKey.value
		// 自动修正为标准路径
		currentPathValue = concatPath('/', rootPath, currentPathValue, '/')

		// 如果全量密码缓存为空，则自动初始化为当前密码缓存
		if (
			Object.keys(fullStudentCloudPasswordCache.value).length === 0 &&
			Object.keys(studentCloudPasswordCache.value).length !== 0
		) {
			fullStudentCloudPasswordCache.value = JSON.parse(
				JSON.stringify(studentCloudPasswordCache.value)
			)
		}
		// 根据是否需要获取全量密码(含未记住的密码)，选择不同的缓存
		let cache = ignoreTempPassword
			? studentCloudPasswordCache.value
			: fullStudentCloudPasswordCache.value

		for (let storageTag of Object.keys(cache)) {
			if (storageTag === targetStorageKey) {
				for (let key of Object.keys(cache[storageTag])) {
					// 如果 key 以 * 结尾，则给 currentPathValue 加上任意字符，以匹配通配符
					if (key.endsWith('*')) {
						currentPathValue = currentPathValue + '*'
					}
					if (minimatch(currentPathValue, key)) {
						return cache[storageTag][key]
					}
				}
			}
		}
		return ''
	}

	const clearPwdCache = () => {
		studentCloudPasswordCache.value = {}
		fullStudentCloudPasswordCache.value = {}
		ElMessage.success('操作成功')
	}

	return {
		putPathPwd,
		getPathPwd,
		popPassword,
		clearPwdCache,
	}
}
