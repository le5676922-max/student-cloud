<template>
	<nav class="global-navigation" aria-label="全局管理导航">
		<div class="navigation-context">
			<p class="navigation-context-title">全局管理</p>
			<p class="navigation-caption">
				管理工作空间与站点。日常文件操作，请返回云盘。
			</p>
		</div>
		<p class="navigation-section-label">工作空间</p>
		<ul class="navigation-list">
			<li v-for="item in adminPrimaryNavigation" :key="item.id">
				<router-link
					:to="item.href"
					class="navigation-item"
					:class="{ active: active(item) }"
					:aria-current="active(item) ? 'page' : undefined"
					><component :is="icons[item.icon]" aria-hidden="true" /><span>{{
						item.name
					}}</span
					><span
						v-if="active(item)"
						class="navigation-active-mark"
						aria-hidden="true"
				/></router-link>
			</li>
		</ul>
		<section
			v-for="group in adminNavigationGroups"
			:key="group.id"
			class="navigation-group"
		>
			<button
				type="button"
				class="group-toggle"
				:class="{ 'group-has-active': group.items.some(active) }"
				:aria-expanded="expanded.includes(group.id)"
				:aria-controls="`${instanceId}-${group.id}`"
				@click="$emit('toggle', group.id)"
			>
				{{ group.name
				}}<ChevronDownIcon
					:class="{ expanded: expanded.includes(group.id) }"
					aria-hidden="true"
				/>
			</button>
			<ul
				v-show="expanded.includes(group.id)"
				:id="`${instanceId}-${group.id}`"
				class="navigation-list"
			>
				<li v-for="item in group.items" :key="item.id">
					<router-link
						v-if="item.href"
						:to="item.href"
						class="navigation-item"
						:class="{ active: active(item) }"
						:aria-current="active(item) ? 'page' : undefined"
						><component :is="icons[item.icon]" aria-hidden="true" /><span>{{
							item.name
						}}</span
						><span
							v-if="active(item)"
							class="navigation-active-mark"
							aria-hidden="true"
					/></router-link>
					<button
						v-else
						type="button"
						class="navigation-item"
						@click="$emit('action', item.action)"
					>
						<component :is="icons[item.icon]" aria-hidden="true" />{{
							item.name
						}}
					</button>
				</li>
			</ul>
		</section>
		<div class="account-actions">
			<router-link to="/" class="navigation-item"
				><ArrowUturnLeftIcon aria-hidden="true" />返回云盘</router-link
			>
			<button
				type="button"
				class="navigation-item"
				:disabled="loggingOut"
				@click="$emit('action', 'logout')"
			>
				<ArrowLeftOnRectangleIcon aria-hidden="true" />{{
					loggingOut ? '正在退出…' : '退出登录'
				}}
			</button>
		</div>
	</nav>
</template>
<script setup>
import {
	Squares2X2Icon,
	BuildingOffice2Icon,
	UsersIcon,
	CircleStackIcon,
	TableCellsIcon,
	Cog8ToothIcon,
	EyeIcon,
	ShieldCheckIcon,
	DocumentTextIcon,
	DocumentArrowDownIcon,
	ChevronDownIcon,
	ArrowLeftOnRectangleIcon,
	ArrowUturnLeftIcon,
} from '@heroicons/vue/24/outline'
import {
	adminPrimaryNavigation,
	adminNavigationGroups,
	isAdminNavigationActive,
} from '~/utils/admin-navigation.mjs'
const props = defineProps({
	path: { type: String, required: true },
	expanded: { type: Array, default: () => [] },
	instanceId: { type: String, required: true },
	loggingOut: { type: Boolean, default: false },
})
defineEmits(['toggle', 'action'])
const icons = {
	overview: Squares2X2Icon,
	workspace: BuildingOffice2Icon,
	members: UsersIcon,
	storage: CircleStackIcon,
	table: TableCellsIcon,
	setting: Cog8ToothIcon,
	view: EyeIcon,
	shield: ShieldCheckIcon,
	document: DocumentTextIcon,
	download: DocumentArrowDownIcon,
}
const active = (item) => isAdminNavigationActive(item, props.path)
</script>
<style scoped>
.global-navigation {
	display: flex;
	flex-direction: column;
	flex: 1;
	gap: 6px;
	padding-bottom: 8px;
}
.navigation-context {
	margin: 0 2px 14px;
	padding: 12px 13px;
	border: 1px solid var(--cloud-line);
	border-radius: 10px;
	background: var(--cloud-surface);
}
.navigation-context-title {
	margin: 0 0 4px;
	color: var(--cloud-ink);
	font-size: 13px;
	font-weight: 650;
}
.navigation-caption {
	margin: 0;
	font-size: 12px;
	line-height: 1.6;
	color: var(--cloud-muted);
}
.navigation-section-label {
	margin: 0;
	padding: 0 12px 5px;
	font-size: 11px;
	color: var(--cloud-muted);
}
.navigation-list {
	display: grid;
	gap: 3px;
	margin: 0;
	padding: 0;
	list-style: none;
}
.navigation-item {
	display: flex;
	align-items: center;
	gap: 10px;
	width: 100%;
	min-height: 40px;
	padding: 9px 12px;
	border-radius: 8px;
	color: var(--cloud-ink);
	font-size: 13px;
	text-align: left;
	line-height: 1.5;
	text-decoration: none;
	transition: background-color 150ms ease, color 150ms ease;
}
.navigation-item svg {
	width: 18px;
	height: 18px;
	flex-shrink: 0;
	color: var(--cloud-muted);
}
.navigation-item:hover {
	color: var(--cloud-primary);
	background: #eeefea;
}
.navigation-item.active {
	color: var(--cloud-primary);
	background: #e8f1ec;
	font-weight: 650;
	box-shadow: inset 2px 0 0 var(--cloud-primary);
}
.navigation-item.active svg {
	color: var(--cloud-primary);
}
.navigation-active-mark {
	width: 5px;
	height: 5px;
	margin-left: auto;
	border-radius: 50%;
	background: currentColor;
}
.navigation-group {
	padding-top: 8px;
}
.group-toggle {
	display: flex;
	align-items: center;
	justify-content: space-between;
	width: 100%;
	padding: 8px 12px;
	border-radius: 7px;
	font-size: 12px;
	font-weight: 600;
	color: var(--cloud-muted);
}
.group-toggle:hover,
.group-toggle.group-has-active {
	color: var(--cloud-primary);
}
.group-toggle svg {
	width: 16px;
	height: 16px;
	transition: transform 0.15s;
}
.group-toggle svg.expanded {
	transform: rotate(180deg);
}
.account-actions {
	margin-top: auto;
	padding-top: 16px;
	border-top: 1px solid var(--cloud-line);
}
button:focus-visible,
a:focus-visible {
	outline: 2px solid var(--cloud-primary);
	outline-offset: 2px;
}
button:disabled {
	opacity: 0.6;
	cursor: wait;
}
@media (prefers-reduced-motion: reduce) {
	.group-toggle svg,
	.navigation-item {
		transition: none;
	}
}
</style>
