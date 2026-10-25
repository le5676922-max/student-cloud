import assert from 'node:assert/strict'
import test from 'node:test'
import {
	requestsHumanSupport,
	isUnresolvedFeedback,
	supportIssueFromMessages,
	supportSuggestionReason,
} from '../src/utils/assistant-support.mjs'

test('direct human support intent recognizes natural requests without rerouting AI or explicit refusals', () => {
	for (const text of [
		'人工服务',
		'转人工',
		'请联系人工客服',
		'我想找真人客服',
		'human support',
	])
		assert.equal(requestsHumanSupport(text), true, text)
	for (const text of [
		'人工智能是什么',
		'不要转人工',
		'不用人工服务，继续解释',
		'取消人工客服',
		'帮我上传文件',
	])
		assert.equal(requestsHumanSupport(text), false, text)
})

test('negative answer feedback is distinguished from an ordinary description of a problem', () => {
	for (const text of [
		'还是不行',
		'没解决',
		'你的回答没有帮助',
		'这条回复有问题',
		'回答有问题',
	])
		assert.equal(isUnresolvedFeedback(text), true, text)
	for (const text of [
		'我上传文件有问题',
		'帮我解决下载问题',
		'不要人工客服',
		'文件不存在怎么办',
	])
		assert.equal(isUnresolvedFeedback(text), false, text)
})

test('handoff proposes only the most recent user question and never assistant content, attachments or feedback', () => {
	const history = [
		{ role: 'user', content: '先前的问题' },
		{ role: 'assistant', content: 'PRIVATE-ANSWER' },
		{
			role: 'user',
			content: '上传出错了怎么办？',
			attachments: [{ name: 'PRIVATE.txt' }],
		},
		{ role: 'assistant', content: 'PRIVATE-ANSWER-2' },
		{ role: 'user', content: '还是不行' },
		{ role: 'user', content: '转人工' },
	]
	assert.equal(
		supportIssueFromMessages(history, '人工服务'),
		'上传出错了怎么办？'
	)
	assert.equal(
		supportIssueFromMessages(history, '我已重新写好求助说明'),
		'我已重新写好求助说明'
	)
	assert.equal(
		supportIssueFromMessages([{ role: 'assistant', content: 'secret' }]),
		''
	)
})

test('malformed model suggestions are ignored and reason length is bounded', () => {
	for (const suggestion of [null, {}, { reason: 3 }, { reason: '  ' }])
		assert.equal(supportSuggestionReason(suggestion), '')
	assert.equal(
		supportSuggestionReason({ reason: '需要人工核实' }),
		'需要人工核实'
	)
	assert.equal(
		supportSuggestionReason({ reason: '长'.repeat(1000) }).length,
		300
	)
})
