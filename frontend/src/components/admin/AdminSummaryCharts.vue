<template>
	<section class="overview-charts" aria-label="全局数据图表">
		<header>
			<div>
				<h2>数据看板</h2>
				<p>点击图例查看占比，或切换到数据明细。</p>
			</div>
			<button
				type="button"
				:aria-pressed="tableMode"
				@click="tableMode = !tableMode"
			>
				{{ tableMode ? '查看图表' : '数据明细' }}
			</button>
		</header>
		<div class="chart-grid">
			<article
				v-for="chart in distributions"
				:key="chart.id"
				class="chart-card"
			>
				<h3>{{ chart.title }}</h3>
				<p v-if="chart.total === null" class="chart-empty" role="status">
					统计未返回，请刷新重试。
				</p>
				<template v-else>
					<div v-if="!tableMode" class="ring-layout">
						<div class="ring-wrapper">
							<svg
								viewBox="0 0 120 120"
								role="img"
								:aria-label="chartDescription(chart)"
							>
								<circle
									cx="60"
									cy="60"
									r="42"
									fill="none"
									stroke="#efedf3"
									stroke-width="13"
								/>
								<circle
									v-for="(segment, index) in ringSegments(chart.items)"
									:key="segment.label"
									cx="60"
									cy="60"
									r="42"
									fill="none"
									:stroke="segment.color"
									stroke-width="13"
									pathLength="100"
									:stroke-dasharray="segment.dash"
									:stroke-dashoffset="segment.offset"
									transform="rotate(-90 60 60)"
									:opacity="
										selected[chart.id] === undefined ||
										selected[chart.id] === index
											? 1
											: 0.3
									"
								>
									<title>
										{{ segment.label }}：{{ segment.value }}，占
										{{ segment.percent }}%
									</title>
								</circle>
							</svg>
							<div class="ring-center">
								<strong>{{ chart.total.toLocaleString() }}</strong
								><span>{{ chart.total === 0 ? '暂无数据' : '总计' }}</span>
							</div>
						</div>
						<ul class="chart-legend">
							<li v-for="(item, index) in chart.items" :key="item.label">
								<button
									type="button"
									:aria-pressed="selected[chart.id] === index"
									@click="
										selected[chart.id] =
											selected[chart.id] === index ? undefined : index
									"
								>
									<i :style="{ background: item.color }" /><span>{{
										item.label
									}}</span
									><strong>{{ item.value.toLocaleString() }}</strong>
								</button>
							</li>
						</ul>
					</div>
					<table v-else>
						<caption>
							{{
								chart.title
							}}明细
						</caption>
						<thead>
							<tr>
								<th>类别</th>
								<th>数量</th>
							</tr>
						</thead>
						<tbody>
							<tr v-for="item in chart.items" :key="item.label">
								<td>{{ item.label }}</td>
								<td>{{ item.value.toLocaleString() }}</td>
							</tr>
						</tbody>
					</table>
				</template>
				<p class="chart-note">{{ chart.note }}</p>
			</article>
			<article class="chart-card trend-card">
				<h3>最近 7 天登录趋势</h3>
				<p v-if="overview.loginLoggingEnabled !== true" class="chart-empty">
					{{
						overview.loginLoggingEnabled === false
							? '登录日志未启用，当前图表不代表实际登录量。'
							: '日志开关状态未返回。'
					}}
				</p>
				<p v-else-if="!trend" class="chart-empty" role="status">
					趋势数据未返回，请刷新重试。
				</p>
				<template v-else>
					<svg
						v-if="!tableMode"
						viewBox="0 0 440 160"
						role="img"
						:aria-label="trendDescription"
					>
						<g stroke="#ece9f0" stroke-dasharray="3 5">
							<line
								v-for="y in [28, 80, 132]"
								:key="y"
								x1="22"
								x2="418"
								:y1="y"
								:y2="y"
							/>
						</g>
						<g v-for="line in trend.series" :key="line.key">
							<path
								:d="line.path"
								fill="none"
								:stroke="line.color"
								stroke-width="2.5"
							/>
							<circle
								v-for="point in line.points"
								:key="point.date"
								:cx="point.x"
								:cy="point.y"
								r="3.5"
								:fill="line.color"
							>
								<title>
									{{ point.date }} {{ line.label }}：{{ point.value }}
								</title>
							</circle>
						</g>
						<text
							v-for="(day, index) in trend.days"
							:key="day.date"
							:x="22 + index * (396 / Math.max(1, trend.days.length - 1))"
							y="154"
							text-anchor="middle"
						>
							{{ day.date.slice(5) }}
						</text>
					</svg>
					<table v-else>
						<caption>
							最近 7 天登录明细
						</caption>
						<thead>
							<tr>
								<th>日期</th>
								<th>成功</th>
								<th>失败</th>
							</tr>
						</thead>
						<tbody>
							<tr v-for="day in trend.days" :key="day.date">
								<td>{{ day.date }}</td>
								<td>{{ day.successes }}</td>
								<td>{{ day.failures }}</td>
							</tr>
						</tbody>
					</table>
					<p class="chart-note">
						成功 / 失败次数，统计时区：{{
							overview.timeZone || 'Asia/Shanghai'
						}}
					</p>
				</template>
			</article>
		</div>
	</section>
</template>
<script setup>
import { computed, reactive, ref } from 'vue'
import {
	overviewDistributions,
	overviewTrendSeries,
	ringSegments,
} from '~/utils/admin-overview-charts.mjs'
const props = defineProps({ overview: { type: Object, required: true } })
const tableMode = ref(false)
const selected = reactive({})
const distributions = computed(() => overviewDistributions(props.overview))
const trend = computed(() => overviewTrendSeries(props.overview.dailyLogins))
const chartDescription = (chart) =>
	chart.items.map((item) => `${item.label} ${item.value}`).join('，')
const trendDescription = computed(
	() =>
		trend.value?.days
			.map((day) => `${day.date} 成功${day.successes}次，失败${day.failures}次`)
			.join('；') || '趋势数据未返回'
)
</script>
<style scoped>
.overview-charts {
	margin: 20px 0;
}
.overview-charts header {
	display: flex;
	align-items: center;
	justify-content: space-between;
	gap: 12px;
	margin-bottom: 14px;
}
h2,
h3,
p {
	margin: 0;
}
h2 {
	font-size: 18px;
}
h3 {
	font-size: 14px;
	font-weight: 650;
}
header p,
.chart-note {
	font-size: 12px;
	color: #686375;
	line-height: 1.7;
}
button {
	cursor: pointer;
	border: 1px solid #ddd9e4;
	border-radius: 9px;
	padding: 8px 12px;
	background: #fff;
	color: #35313e;
	font-size: 12px;
}
button:focus-visible {
	outline: 2px solid #7551df;
	outline-offset: 2px;
}
.chart-grid {
	display: grid;
	grid-template-columns: repeat(3, minmax(0, 1fr));
	gap: 16px;
}
.chart-card {
	padding: 20px;
	border: 1px solid #e4e0e9;
	border-radius: 16px;
	background: var(--cloud-surface, #fff);
	min-width: 0;
}
.ring-layout {
	display: flex;
	align-items: center;
	gap: 12px;
	margin: 16px 0;
}
.ring-wrapper {
	position: relative;
	flex: 0 0 126px;
	width: 126px;
	height: 126px;
}
.ring-wrapper svg {
	width: 100%;
	height: 100%;
}
.ring-center {
	position: absolute;
	inset: 0;
	display: flex;
	align-items: center;
	justify-content: center;
	flex-direction: column;
	pointer-events: none;
}
.ring-center strong {
	font-size: 23px;
	font-variant-numeric: tabular-nums;
}
.ring-center span {
	font-size: 11px;
	color: #777180;
}
.chart-legend {
	list-style: none;
	margin: 0;
	padding: 0;
	flex: 1;
	min-width: 0;
}
.chart-legend button {
	display: flex;
	align-items: center;
	gap: 7px;
	width: 100%;
	border-color: transparent;
	padding: 9px 2px;
	text-align: left;
}
.chart-legend button[aria-pressed='true'] {
	background: #f3eefc;
	border-color: #e4daf9;
}
.chart-legend i {
	width: 8px;
	height: 8px;
	flex-shrink: 0;
	border-radius: 3px;
}
.chart-legend span {
	font-size: 12px;
}
.chart-legend strong {
	margin-left: auto;
	font-size: 13px;
}
.trend-card {
	grid-column: 1/-1;
}
.trend-card svg {
	display: block;
	max-height: 230px;
	width: 100%;
	margin: 12px 0;
}
.trend-card text {
	fill: #777180;
	font-size: 11px;
}
.chart-empty {
	display: flex;
	align-items: center;
	justify-content: center;
	min-height: 156px;
	font-size: 13px;
	color: #777180;
	text-align: center;
}
table {
	width: 100%;
	border-collapse: collapse;
	margin: 14px 0;
	font-size: 12px;
	text-align: left;
}
caption {
	position: absolute;
	width: 1px;
	height: 1px;
	overflow: hidden;
	clip-path: inset(100%);
}
th,
td {
	padding: 9px;
	border-bottom: 1px solid #efedf3;
}
th {
	color: #777180;
}
@media (max-width: 1100px) {
	.chart-grid {
		grid-template-columns: repeat(2, minmax(0, 1fr));
	}
	.chart-card:nth-child(3) {
		grid-column: 1/-1;
	}
}
@media (max-width: 640px) {
	.chart-grid {
		grid-template-columns: 1fr;
	}
	.chart-card:nth-child(3) {
		grid-column: auto;
	}
	.chart-card {
		padding: 16px;
	}
	.ring-layout {
		justify-content: center;
	}
	.chart-legend {
		max-width: 210px;
	}
}
</style>
