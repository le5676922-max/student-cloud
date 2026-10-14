<template>
	<!-- All content passes through DOMPurify; raw HTML is not accepted. -->
	<!-- eslint-disable-next-line vue/no-v-html -->
	<div v-html="sanitized"></div>
</template>

<script setup>
import { sanitizeRichHtml } from '~/utils/safe-html.mjs'
const props = defineProps({ html: { type: String, default: '' } })
const sanitized = computed(() => sanitizeRichHtml(props.html))
</script>
