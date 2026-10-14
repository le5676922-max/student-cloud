// Request-scoped cancellation only: no cached permissions, passwords or file data.
export function createLatestRequest() {
	let controller
	return {
		begin() {
			controller?.abort()
			const current = new AbortController()
			controller = current
			return {
				signal: current.signal,
				isCurrent: () => controller === current && !current.signal.aborted,
			}
		},
		cancel() {
			controller?.abort()
			controller = undefined
		},
	}
}

// Render the list as soon as it arrives; configuration can finish independently.
// Both callbacks are guarded so responses from a previous folder are discarded.
export async function loadDirectoryInParallel({
	loadFiles,
	loadConfig,
	isCurrent,
	onFiles,
	onConfig,
}) {
	const configuration = loadConfig().then(
		(response) => ({ response }),
		(error) => ({ error })
	)
	try {
		const files = await loadFiles()
		if (!isCurrent()) return
		onFiles(files)
		const result = await configuration
		if (!isCurrent()) return
		if (result.error) throw result.error
		onConfig(result.response)
	} catch (error) {
		if (isCurrent()) throw error
	}
}
