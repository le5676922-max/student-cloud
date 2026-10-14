<template>
	<!-- All Markdown HTML is sanitized before rendering. -->
	<!-- eslint-disable-next-line vue/no-v-html -->
	<div class="markdown-body" v-html="html" />
</template>

<script setup>
import { marked } from 'marked'
import DOMPurify from 'dompurify'
import 'github-markdown-css/github-markdown.css'

const props = defineProps({ text: { type: String, default: '' } })
const html = computed(() =>
	DOMPurify.sanitize(
		marked.parse(props.text, {
			mangle: false,
			headerIds: false,
			gfm: true,
		}),
		{
			USE_PROFILES: { html: true },
			FORBID_TAGS: ['style', 'form', 'iframe'],
		}
	)
)
</script>
