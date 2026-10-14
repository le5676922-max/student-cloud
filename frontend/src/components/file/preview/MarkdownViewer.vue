<template>
	<div class="content">
		<SafeHtml class="dialog-scroll markdown-body" :html="markdownHtml" />
	</div>
</template>

<script setup>
import { marked } from 'marked'
import SafeHtml from '~/components/common/SafeHtml.vue'
import { computed, onMounted, onUpdated, ref } from 'vue'
import hljs from 'highlight.js/lib/core'
import 'highlight.js/styles/github.css'
import 'github-markdown-css'

import { getFileTextReq } from '~/api/home/common'

let props = defineProps({
	fileUrl: { type: String, default: '' },
	fileName: { type: String, default: '' },
})

const fileContent = ref('')

onMounted(() => {
	let fileUrl = props.fileUrl
	getFileTextReq(fileUrl, 'text')
		.then((res) => {
			fileContent.value = res.data
		})
		.catch(() => {
			ElMessage({
				message: `加载文本文件失败. 请检查此地址是否有效或是否限制了跨域访问.`,
				type: 'error',
				duration: 0,
				showClose: true,
				grouping: true,
			})
		})
})

let markdownHtml = computed(() => {
	// url 新窗口打开.
	let renderer = new marked.Renderer()
	renderer.link = function () {
		let link = marked.Renderer.prototype.link.apply(this, arguments)
		return link.replace('<a', "<a target='_blank'")
	}
	return marked(fileContent.value, {
		renderer,
		mangle: false,
		headerIds: false,
		highlight: function (code) {
			return hljs.highlightAuto(code).value
		},
	})
})

import CodeCopy from '~/components/file/preview/CopyCode.vue'
import { createVNode, render } from 'vue'

onUpdated(() => {
	document.querySelectorAll('pre').forEach((el) => {
		if (el.classList.contains('code-copy-added')) return
		//   https://cn.vuejs.org/v2/api/index.html#Vue-extend
		/* 使用基础 Vue 构造器，创建一个“子类”。参数是一个包含组件选项的对象 */

		let codeCopyVNode = createVNode(CodeCopy, {
			code: el.innerText,
		})
		let mountNode = document.createElement('div')
		render(codeCopyVNode, mountNode)
		el.classList.add('code-copy-added')
		el.classList.add('hljs')
		el.appendChild(mountNode)
	})
})
</script>

<style scoped>
.content {
	padding: 10px 0;
}

.content .markdown-body :deep(pre) {
	margin-right: 20px;
}

.content .markdown-body :deep(pre:hover .copy-btn) {
	opacity: 1;
}

.dialog-scroll {
	height: 80vh;
	overflow-y: auto;
	overflow-x: hidden;
	margin: 0;
}

.content :deep(.code-copy-added) {
	position: relative;
}
</style>
