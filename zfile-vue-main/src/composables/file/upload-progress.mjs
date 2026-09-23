// Multipart request sizes include headers/boundaries, not just the file payload.
export const calculateUploadProgress = (fileSize, { loaded, total }) => {
	const size = Number.isFinite(fileSize) && fileSize > 0 ? fileSize : 0
	const transferred = Number.isFinite(loaded) && loaded > 0 ? loaded : 0
	const requestSize = Number.isFinite(total) && total > 0 ? total : size
	const ratio = requestSize > 0 ? Math.min(transferred / requestSize, 1) : 0
	return { loaded: Math.round(size * ratio), progress: Math.round(ratio * 100) }
}
