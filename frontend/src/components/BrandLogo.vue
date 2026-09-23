<template>
  <div class="inline-flex items-center" :class="compact ? 'gap-2' : 'flex-col gap-2'">
    <span class="relative flex items-center justify-center rounded-md bg-indigo-600 text-white shadow-sm"
          :class="compact ? 'h-8 w-8' : 'h-12 w-12'">
      <CloudArrowUpIcon :class="compact ? 'h-5 w-5' : 'h-7 w-7'" aria-hidden="true" />
      <span class="absolute -right-1 -top-1 h-3 w-3 rounded-full border-2 border-white bg-emerald-400" />
    </span>
    <span class="font-bold text-gray-900" :class="compact ? 'text-lg' : 'text-xl'">学生云盘</span>
  </div>
</template>

<script setup>
import { CloudArrowUpIcon } from '@heroicons/vue/24/outline'

defineProps({
  compact: {
    type: Boolean,
    default: false
  }
})
</script>
