import assert from 'node:assert/strict'
import test from 'node:test'
import {
	normalizeCourseInvitation,
	courseInvitationUrl,
	courseInvitationStatus,
} from '../src/utils/course-invitations.mjs'

test('invitation accepts only bounded hexadecimal codes', () => {
	assert.equal(normalizeCourseInvitation(` ${'A'.repeat(32)} `), 'a'.repeat(32))
	for (const value of [
		null,
		['a'.repeat(32)],
		'../private',
		'a'.repeat(33),
		'g'.repeat(32),
	])
		assert.equal(normalizeCourseInvitation(value), '')
})

test('invitation URL uses only the current trusted HTTP origin and an encoded bounded token', () => {
	assert.equal(
		courseInvitationUrl('https://cloud.example.test', 'b'.repeat(32)),
		`https://cloud.example.test/courses?invite=${'b'.repeat(32)}`
	)
	for (const origin of [
		'javascript:alert(1)',
		'https://evil.test/path',
		'https://name:password@cloud.example.test',
	])
		assert.throws(() => courseInvitationUrl(origin, 'b'.repeat(32)))
	assert.throws(() => courseInvitationUrl('https://cloud.example.test', 'x'))
})

test('permanent invites remain valid and revocation takes precedence over expiry', () => {
	assert.equal(
		courseInvitationStatus({ expiresAt: null, revokedAt: 0 }, 9999999999999),
		'有效'
	)
	assert.equal(
		courseInvitationStatus({ expiresAt: null, revokedAt: 1 }, 1),
		'已撤销'
	)
	assert.equal(
		courseInvitationStatus({ expiresAt: 200, revokedAt: 0 }, 100),
		'有效'
	)
	assert.equal(
		courseInvitationStatus({ expiresAt: 200, revokedAt: 0 }, 200),
		'已过期'
	)
	assert.equal(courseInvitationStatus({ revokedAt: 0 }), '已失效')
	assert.equal(
		courseInvitationStatus({ expiresAt: null, maxMembers: 2, usedCount: 2 }),
		'人数已满'
	)
	assert.equal(
		courseInvitationStatus({ expiresAt: null, maxMembers: 2, usedCount: 1 }),
		'有效'
	)
	assert.equal(
		courseInvitationStatus({ expiresAt: 1, maxMembers: 2, usedCount: 2 }, 2),
		'已过期'
	)
	assert.equal(
		courseInvitationStatus({
			expiresAt: null,
			maxMembers: 2,
			usedCount: 2,
			revokedAt: 1,
		}),
		'已撤销'
	)
})
