import { reactive } from 'vue'
import useStorageConfigStore from '~/stores/storage-config'
import { loadGlobalSiteConfigReq } from '~/api/home/home'
import { constant } from '~/constant'
import { createSiteConfigBootstrap } from '~/utils/site-config-bootstrap.mjs'

const applications = new WeakMap()
const suffixKinds = ['Audio', 'Image', 'Text', 'Video', 'Office']
const defaultSuffixes = Object.fromEntries(
	suffixKinds.map((kind) => [
		kind,
		[...constant.fileTypeMap[kind.toLowerCase()]],
	])
)
export default function useSiteConfigBootstrap() {
	const store = useStorageConfigStore()
	if (!applications.has(store))
		applications.set(
			store,
			createSiteConfigBootstrap({
				state: reactive({}),
				load: loadGlobalSiteConfigReq,
				apply(data) {
					store.updateGlobalConfig(data)
					for (const kind of suffixKinds) {
						const suffixes = data[`custom${kind}Suffix`]
						constant.fileTypeMap[kind.toLowerCase()] =
							typeof suffixes === 'string' && suffixes
								? suffixes.split(',')
								: [...defaultSuffixes[kind]]
					}
				},
			})
		)
	return applications.get(store)
}
