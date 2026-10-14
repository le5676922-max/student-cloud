import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

const page = await readFile(
	new URL('../src/pages/login.vue', import.meta.url),
	'utf8'
)
const styles = await readFile(
	new URL('../src/styles/login.css', import.meta.url),
	'utf8'
)

test('login renders directly without the opening overlay, replay control or entrance animation', () => {
	assert.doesNotMatch(
		page,
		/\b(?:LoginIntro|introActive|introShown|introRevealing|replayIntro|completeIntro)\b/
	)
	assert.doesNotMatch(
		page,
		/login-intro\.mjs|career-login-replay|重播开场|data-login-intro/
	)
	assert.doesNotMatch(
		styles,
		/career-login-enter|career-login-page--(?:intro|revealing)|career-login-replay/
	)
})

test('direct entry retains the interactive characters and existing registration and login hooks', () => {
	assert.match(page, /<LoginCharacters\b/)
	assert.match(page, /:is-typing="isTyping"/)
	assert.match(page, /:show-password="passwordVisible"/)
	assert.match(page, /:password-length="formData\.password\.length"/)
	assert.match(page, /:paused="motionPaused"/)
	assert.doesNotMatch(page, /<LoginCharacters\b[^>]*\bvariant=/)
	assert.match(page, /<RegistrationDialog\b/)
	assert.match(page, /@submit\.prevent="submitForm"/)
	assert.match(page, /validateLoginEntryReq/)
})

test('password visibility and animation pause controls remain accessible on direct entry', () => {
	assert.match(page, /:type="passwordVisible \? 'text' : 'password'"/)
	assert.match(page, /:aria-pressed="passwordVisible"/)
	assert.match(page, /:aria-pressed="motionPaused"/)
	assert.match(page, /@click="motionPaused = !motionPaused"/)
	assert.match(page, /@focus="isTyping = true"/)
	assert.match(page, /@blur="isTyping = false"/)
	assert.doesNotMatch(page, /v-model\.trim="formData\.password"/)
})
