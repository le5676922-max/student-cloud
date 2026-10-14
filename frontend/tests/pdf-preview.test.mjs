import test from 'node:test'
import assert from 'node:assert/strict'
import {
	clampPdfPage,
	clampPdfZoom,
	pdfLoadingProgress,
	pdfKeyboardAction,
	createPdfSessionGuard,
} from '../src/utils/pdf-preview.mjs'

test('PDF pages are always one positive bounded page, never the all-pages null value', () => {
	assert.equal(clampPdfPage(0, 100), 1)
	assert.equal(clampPdfPage(-3, 100), 1)
	assert.equal(clampPdfPage(101, 100), 100)
	assert.equal(clampPdfPage('17.9', 100), 17)
	assert.equal(clampPdfPage('', 100, 12), 12)
	assert.equal(clampPdfPage('invalid', 100, 12), 12)
	assert.equal(clampPdfPage(Infinity, 100, 12), 12)
	assert.equal(clampPdfPage(20, 0), 1)
	assert.equal(clampPdfPage(null, 100), 1)
})

test('PDF zoom cannot shrink below 50 percent or exceed 200 percent', () => {
	assert.equal(clampPdfZoom(-20), 50)
	assert.equal(clampPdfZoom(220), 200)
	assert.equal(clampPdfZoom(115), 115)
	assert.equal(clampPdfZoom(NaN), 100)
	assert.equal(clampPdfZoom(Infinity), 100)
})

test('PDF progress handles missing Content-Length, invalid data and totals corrected in transit', () => {
	assert.equal(pdfLoadingProgress({ loaded: 10, total: 0 }), null)
	assert.equal(pdfLoadingProgress({ loaded: 10 }), null)
	assert.equal(pdfLoadingProgress({ loaded: -1, total: 100 }), null)
	assert.equal(pdfLoadingProgress({ loaded: Infinity, total: 100 }), null)
	assert.equal(pdfLoadingProgress(undefined), null)
	assert.equal(pdfLoadingProgress({ loaded: 1, total: 3 }), 33)
	assert.equal(pdfLoadingProgress({ loaded: 101, total: 100 }), 100)
})

test('PDF shortcuts preserve editing, browser shortcuts and composition', () => {
	assert.equal(pdfKeyboardAction({ key: 'ArrowLeft' }), 'previous')
	assert.equal(pdfKeyboardAction({ key: 'ArrowRight' }), 'next')
	assert.equal(pdfKeyboardAction({ key: '+' }), 'zoom-in')
	assert.equal(pdfKeyboardAction({ key: '-' }), 'zoom-out')
	for (const target of [
		{ tagName: 'INPUT' },
		{ tagName: 'TEXTAREA' },
		{ tagName: 'SELECT' },
		{ isContentEditable: true },
	]) {
		assert.equal(pdfKeyboardAction({ key: 'ArrowLeft', target }), null)
	}
	for (const flag of [
		'ctrlKey',
		'metaKey',
		'altKey',
		'isComposing',
		'defaultPrevented',
	]) {
		assert.equal(pdfKeyboardAction({ key: '+', [flag]: true }), null)
	}
	assert.equal(pdfKeyboardAction({ key: 'Escape' }), null)
})

test('old PDF progress, success and failure callbacks cannot mutate a new or unmounted preview', () => {
	const sessions = createPdfSessionGuard()
	const events = []
	const first = sessions.begin()
	const oldCallback = first.guard((event) => events.push(event))
	oldCallback('first started')
	const second = sessions.begin()
	const currentCallback = second.guard((event) => events.push(event))
	oldCallback('stale success')
	oldCallback('stale error')
	currentCallback('second loaded')
	sessions.invalidate()
	currentCallback('late callback after close')
	assert.ok(second.id > first.id)
	assert.deepEqual(events, ['first started', 'second loaded'])
})
