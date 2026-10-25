<template>
	<nav class="global-navigation" aria-label="全局管理导航">
		<p class="navigation-context">
			<ShieldCheckIcon aria-hidden="true" />全局管理
		</p>
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
		<section class="navigation-more">
			<button
				ref="moreToggle"
				type="button"
				class="group-toggle more-toggle"
				:class="{ 'group-has-active': activeAdminNavigationGroup(path) }"
				:aria-expanded="expanded.includes('more')"
				:aria-controls="`${instanceId}-more`"
				@click="$emit('toggle', 'more')"
			>
				<span><Cog8ToothIcon aria-hidden="true" />更多管理</span>
				<ChevronDownIcon
					:class="{ expanded: expanded.includes('more') }"
					aria-hidden="true"
				/>
			</button>
			<Transition name="navigation-fold">
				<div
					v-show="expanded.includes('more')"
					:id="`${instanceId}-more`"
					:inert="expanded.includes('more') ? undefined : ''"
					class="more-sections"
					@keydown.esc.stop.prevent="closeMore"
				>
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
							{{ group.name }}
							<ChevronDownIcon
								:class="{ expanded: expanded.includes(group.id) }"
								aria-hidden="true"
							/>
						</button>
						<Transition name="navigation-fold">
							<ul
								v-show="expanded.includes(group.id)"
								:id="`${instanceId}-${group.id}`"
								:inert="expanded.includes(group.id) ? undefined : ''"
								class="navigation-list"
							>
								<li v-for="item in group.items" :key="item.id">
									<router-link
										v-if="item.href"
										:to="item.href"
										class="navigation-item"
										:class="{ active: active(item) }"
										:aria-current="active(item) ? 'page' : undefined"
										><component
											:is="icons[item.icon]"
											aria-hidden="true"
										/><span>{{ item.name }}</span>
										<span
											v-if="active(item)"
											class="navigation-active-mark"
											aria-hidden="true"
										/>
									</router-link>
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
						</Transition>
					</section>
				</div>
			</Transition>
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
import { ref } from 'vue'
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
	activeAdminNavigationGroup,
	isAdminNavigationActive,
} from '~/utils/admin-navigation.mjs'
const props = defineProps({
	path: { type: String, required: true },
	expanded: { type: Array, default: () => [] },
	instanceId: { type: String, required: true },
	loggingOut: { type: Boolean, default: false },
})
const emit = defineEmits(['toggle', 'action'])
const moreToggle = ref(null)
function closeMore() {
	if (!props.expanded.includes('more')) return
	emit('toggle', 'more')
	moreToggle.value?.focus()
}
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
	display: flex;
	align-items: center;
	gap: 7px;
	margin: 0 0 8px;
	padding: 0 12px 6px;
	font-size: 11px;
	color: var(--cloud-muted);
}
.navigation-context svg {
	width: 14px;
	height: 14px;
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
	border-radius: 12px;
	color: var(--cloud-ink);
	font-size: 13px;
	text-align: left;
	line-height: 1.5;
	text-decoration: none;
	transition: background-color 180ms ease, color 180ms ease,
		transform 200ms var(--cloud-ease), box-shadow 200ms ease;
}
.navigation-item svg {
	width: 18px;
	height: 18px;
	flex-shrink: 0;
	color: var(--cloud-muted);
}
.navigation-item:not(:disabled):hover {
	color: var(--cloud-primary);
	background: rgb(255 255 255 / 65%);
}
.navigation-item.active {
	color: #fff;
	background: var(--cloud-primary);
	font-weight: 650;
	box-shadow: 0 4px 12px rgb(28 29 32 / 12%);
}
.navigation-item.active:hover {
	color: #fff;
	background: var(--cloud-primary-dark);
}
.navigation-item.active svg {
	color: #ddd4ff;
}
.navigation-active-mark {
	width: 5px;
	height: 5px;
	margin-left: auto;
	border-radius: 50%;
	background: #c5b3ff;
}
.navigation-group {
	padding-top: 2px;
}
.navigation-more {
	margin-top: 10px;
	padding-top: 10px;
	border-top: 1px solid var(--cloud-line);
}
.more-sections {
	margin: 3px 0 0 12px;
	padding-left: 7px;
	border-left: 1px solid var(--cloud-line);
}
.more-toggle > span {
	display: flex;
	align-items: center;
	gap: 10px;
}
.more-toggle > span svg {
	width: 18px;
	height: 18px;
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
.group-toggle:hover {
	background: rgb(255 255 255 / 35%);
}
.group-toggle svg {
	width: 16px;
	height: 16px;
	transition: transform 220ms var(--cloud-ease);
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
	outline: 2px solid var(--cloud-accent);
	outline-offset: 2px;
}
button:disabled {
	opacity: 0.6;
	cursor: wait;
}
@media (hover: hover) and (pointer: fine) {
	.navigation-item:not(:disabled):hover {
		transform: translateX(3px);
	}
}
.navigation-item:not(:disabled):active {
	transform: translateX(1px) scale(0.985);
}
.navigation-fold-enter-active,
.navigation-fold-leave-active {
	transition: opacity 160ms ease, transform 180ms var(--cloud-ease);
}
.navigation-fold-enter-from,
.navigation-fold-leave-to {
	opacity: 0;
	transform: translateY(-4px);
}
@media (prefers-reduced-motion: reduce) {
	.group-toggle svg,
	.navigation-item,
	.navigation-fold-enter-active,
	.navigation-fold-leave-active {
		transition: none;
	}
	.navigation-item:not(:disabled):hover,
	.navigation-item:not(:disabled):active {
		transform: none;
	}
}
</style>
