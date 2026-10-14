import { resolve } from 'path'
import Tov from './presets/tov'
import { defineConfig } from 'vite'

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
