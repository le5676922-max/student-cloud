// Memory-only dirty checking: never serialize a draft into browser storage or a URL.
export function createUnsavedFormGuard({
	readSnapshot,
	readScope,
	confirmLeave,
}) {
	let baseline, baselineScope, pending
	return {
		accept() {
			baseline = readSnapshot()
			baselineScope = readScope()
		},
		hasChanges() {
			return (
				baseline !== undefined &&
				baselineScope === readScope() &&
				baseline !== readSnapshot()
			)
		},
		async mayLeave() {
			if (!this.hasChanges()) return true
			if (pending) return pending
			const scope = readScope()
			pending = Promise.resolve()
				.then(confirmLeave)
				.then((allowed) => allowed === true && scope === readScope())
				.catch(() => false)
				.finally(() => {
					pending = undefined
				})
			return pending
		},
	}
}
