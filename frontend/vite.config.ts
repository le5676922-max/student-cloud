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
		target: ['es2015'],
		cssTarget: ['chrome49'],
		reportCompressedSize: false
	},
	define: {
		'process.env': {}
	},
	server:{
		host: '0.0.0.0',
		proxy: {
			'^/(api|user|admin|file|pd|d|s|sso|onlyOffice|onedrive|onedirve|gd|s3|sharepoint|115)(/|$)': {
				target: process.env.CLOUD_API_TARGET || 'http://127.0.0.1:8080',
				changeOrigin: true,
				bypass(req) {
					// Management-page navigation belongs to the SPA; Ajax calls go to the API.
					if (req.url?.match(/^\/admin(?:\/|$)/) && req.headers.accept?.includes('text/html')) {
						return '/index.html';
					}
				},
			},
		},
	},
	plugins: [Tov()],
})
