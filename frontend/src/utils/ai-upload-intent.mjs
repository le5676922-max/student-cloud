/** Only intercept an explicit upload command; questions about uploading remain normal chat. */
export function isUploadCommand(message) {
	const text = String(message || '').trim()
	if (!text || /[？?]|^(怎么|如何|能否|可以|为什么|介绍|说明)/.test(text))
		return false
	if (
		/(排查|诊断|解释|分析|修复|解决|失败|报错|错误|异常|不了|不能|不成功|卡住|没反应|无法)/.test(
			text
		)
	)
		return false
	return /(上传|存入云盘|放到云盘|保存到云盘)/.test(text)
}

/** A path is suggested only when the user wrote an explicit slash-prefixed path. */
export function explicitUploadPath(message) {
	const match = String(message || '').match(
		/(?:^|[\s“"：:])\/(?:[^\s，。；;“”"?？#]+(?:\/[^\s，。；;“”"?？#]+)*)/
	)
	return match ? match[0].trim().replace(/^[“"：:]/, '') : null
}

export function existingUploadNames(files, directoryItems) {
	const names = new Set((directoryItems || []).map((item) => item.name))
	return (files || [])
		.filter((file) => names.has(file.name))
		.map((file) => file.name)
}
