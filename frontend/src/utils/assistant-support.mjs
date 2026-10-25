// Routing intent only: these helpers never create a support conversation or send chat history.
const supportNoun =
	/人工(?:客服|服务|支持|帮助)|真人客服|human\s*(?:support|agent)|live\s*agent/i
const declineSupport =
	/(?:不要|不用|不需要|不想|无需|暂不|取消|别)(?:.{0,8})(?:人工|客服)|(?:do\s*not|don't|no)\s+(?:want\s+)?(?:human|live\s*agent)/i

export const declinesHumanSupport = (value) =>
	declineSupport.test(String(value || ''))

export function requestsHumanSupport(value) {
	const text = String(value || '').trim()
	if (!text || declineSupport.test(text)) return false
	return (
		supportNoun.test(text) ||
		/(?:转接?|接入|找|联系|换成|切换到|接通)(?:一下|个|一位|到|下|为|我)?\s*人工(?:[，。！？!?\s]|$)/.test(
			text
		)
	)
}

export function isUnresolvedFeedback(value) {
	const text = String(value || '').trim()
	if (declineSupport.test(text)) return false
	return (
		/^(?:还是|仍然|依然)?(?:不行|没用|没解决|没有解决|解决不了|没帮助|没有帮助|答非所问|回答不对|回答有问题|你没回答|你没有回答)(?:我的问题|问题|这个问题)?[，。！？!?\s]*$/.test(
			text
		) ||
		/(?:你的|这个|这条|刚才的)(?:回答|回复|答案)(?:.{0,6})(?:没用|没有帮助|没帮助|不对|有问题|没解决|没有解决)/.test(
			text
		)
	)
}

export function supportIssueFromMessages(messages, proposed = '') {
	const text = String(proposed || '').trim()
	if (text && !requestsHumanSupport(text) && !isUnresolvedFeedback(text))
		return text.slice(0, 2000)
	// Only the last question is proposed for explicit review. Assistant replies,
	// attachments, files, tools and private memories are never copied.
	for (const message of [...messages].reverse()) {
		if (message.role !== 'user') continue
		const content = String(message.content || '').trim()
		if (
			content &&
			!requestsHumanSupport(content) &&
			!isUnresolvedFeedback(content)
		)
			return content.slice(0, 2000)
	}
	return ''
}

export function supportSuggestionReason(suggestion) {
	return typeof suggestion?.reason === 'string'
		? suggestion.reason.trim().slice(0, 300)
		: ''
}
