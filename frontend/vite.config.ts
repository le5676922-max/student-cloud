import { resolve } from 'path'
import Tov from './presets/tov'
import { defineConfig } from 'vite'
import type { ClientRequest, IncomingMessage } from 'node:http'
import type { Socket } from 'node:net'
import { isSameOriginSupportUpgrade } from './src/utils/support-proxy-origin.mjs'

// Vite's newer generic EventEmitter declaration is not represented by this
// project's older Node typings. Narrow only the one event this hook consumes.
interface SupportUpgradeProxy {
	on(
		event: 'proxyReqWs',
		listener: (
			proxyReq: ClientRequest,
			req: IncomingMessage,
			socket: Socket
		) => void
	): unknown
}

const supportApiTarget = new URL(
	process.env.CLOUD_API_TARGET || 'http://127.0.0.1:8080'
)
const preserveSupportOrigin = process.env.CLOUD_BACKEND_MODE === 'microservices'

export default defineConfig({
	appType: 'spa',
	resolve: {
		alias: {
			'~/': `${resolve(__dirname, 'src')}/`,
		},
	},
	build: {
		target: 'es2022',
		cssTarget: ['chrome107', 'firefox104', 'safari16'],
		reportCompressedSize: false,
		rollupOptions: {
			output: {
				onlyExplicitManualChunks: true,
				manualChunks(id) {
					const path = id.replace(/\\/g, '/')
					if (path.includes('virtual:svg-icons-register')) return 'file-icons'
					// Preserve dependency initialization order instead of forcing Vue/UI
					// modules into separate chunks, which can create runtime cycles.
				},
			},
		},
	},
	define: {
		'process.env': {},
	},
	optimizeDeps: {
		// File-based lazy routes and auto-imported styles otherwise appear only
		// on first navigation, restarting optimization and invalidating imports.
		entries: ['index.html', 'src/**/*.{vue,js,ts}'],
		include: ['element-plus/es', 'element-plus/es/components/**/style/css'],
	},
	server: {
		host: '0.0.0.0',
		proxy: {
			'^/ws/support(?:\\?|$)': {
				target: supportApiTarget.toString(),
				changeOrigin: true,
				ws: true,
				configure(proxyServer) {
					const proxy = proxyServer as unknown as SupportUpgradeProxy
					proxy.on('proxyReqWs', (proxyReq, req, socket) => {
						if (
							!isSameOriginSupportUpgrade(
								req.headers,
								Boolean((req.socket as { encrypted?: boolean }).encrypted)
							)
						) {
							proxyReq.destroy()
							socket.end('HTTP/1.1 403 Forbidden\r\nConnection: close\r\n\r\n')
							return
						}
						// Authentication remains the backend's one-use ticket handshake.
						// New service tickets bind the browser Origin; retain legacy behavior by default.
						const upstreamOrigin = preserveSupportOrigin
							? String(req.headers.origin)
							: supportApiTarget.origin
						proxyReq.setHeader('origin', upstreamOrigin)
					})
				},
			},
			'^/(api|user|admin|file|pd|d|s|directlink|sso|onlyOffice|onedrive|onedirve|gd|s3|sharepoint|115)(/|$)':
				{
					target: process.env.CLOUD_API_TARGET || 'http://127.0.0.1:8080',
					changeOrigin: true,
					bypass(req) {
						// Management-page navigation belongs to the SPA; Ajax calls go to the API.
						if (
							req.url?.match(/^\/admin(?:\/|$)/) &&
							req.headers.accept?.includes('text/html')
						) {
							return '/index.html'
						}
					},
				},
		},
	},
	plugins: [Tov()],
})
