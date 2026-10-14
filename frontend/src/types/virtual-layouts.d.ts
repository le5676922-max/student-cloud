// The older layout plugin's exports omit the .d.ts extension under bundler resolution.
declare module 'virtual:meta-layouts' {
	export function setupLayouts(
		routes: import('vue-router').RouteRecordRaw[]
	): import('vue-router').RouteRecordRaw[]
}
