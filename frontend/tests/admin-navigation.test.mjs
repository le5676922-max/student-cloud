import test from 'node:test'
import assert from 'node:assert/strict'
import {
	adminPrimaryNavigation,
	adminNavigationGroups,
	activeAdminNavigationGroup,
	adminNavigationExpansionForPath,
	toggleAdminNavigationGroup,
	isAdminNavigationActive,
	workspaceManagerPreset,
	managedWorkspaceGrantDefaults,
} from '../src/utils/admin-navigation.mjs'
test('global console keeps daily entries including support and groups infrequent tools', () => {
	assert.deepEqual(
		adminPrimaryNavigation.map((item) => item.href),
		[
			'/admin/overview',
			'/admin/tenant-list',
			'/admin/user-list',
			'/admin/storage-list',
			'/admin/support',
		]
	)
	assert.equal(adminNavigationGroups.length, 3)
	assert.deepEqual(
		adminNavigationGroups.map((group) => group.name),
		['分享与链接', '站点设置', '日志诊断']
	)
	assert.equal(
		adminNavigationGroups
			.flatMap((group) => group.items)
			.some((item) => item.href === '/workspace-admin'),
		false
	)
})

test('new manager defaults authorize only the enabled managed drive of the selected space', () => {
	const preset = { role: 'TENANT_ADMIN', tenantId: 12 }
	const source = {
		tenantId: 12,
		key: 'workspace-12',
		remark: 'managed-workspace-shared-storage:v1',
		enable: true,
	}
	const grant = managedWorkspaceGrantDefaults(preset, source)
	assert.equal(grant.enable, true)
	assert.ok(grant.permissions.includes('upload'))
	assert.equal(grant.permissions.includes('ADMIN'), false)
	for (const changed of [
		{ tenantId: 13 },
		{ key: 'custom' },
		{ remark: '' },
		{ enable: false },
	])
		assert.deepEqual(
			managedWorkspaceGrantDefaults(preset, { ...source, ...changed }),
			{ enable: false, permissions: [] }
		)
	assert.deepEqual(managedWorkspaceGrantDefaults(null, source), {
		enable: false,
		permissions: [],
	})
})
test('exact advanced route expands only its own group', () => {
	assert.equal(activeAdminNavigationGroup('/admin/download-link'), 'resources')
	assert.equal(
		activeAdminNavigationGroup('/admin/download-link/setting'),
		'resources'
	)
	assert.equal(activeAdminNavigationGroup('/admin/download-link/log'), 'logs')
	assert.equal(
		activeAdminNavigationGroup('/admin/security-setting'),
		'security'
	)
	assert.equal(activeAdminNavigationGroup('/admin/overview'), undefined)
	assert.equal(activeAdminNavigationGroup('/admin/user-edit/2'), undefined)
	assert.equal(activeAdminNavigationGroup('/admin/user-edit/1'), 'security')
	assert.equal(activeAdminNavigationGroup('/admin/storage-edit/12'), undefined)
})

test('account-specific management routes remain reachable while the global default template entry is retired', () => {
	const entries = [
		...adminPrimaryNavigation,
		...adminNavigationGroups.flatMap((group) => group.items),
	]
	assert.equal(new Set(entries.map((item) => item.id)).size, entries.length)
	assert.deepEqual(
		entries
			.filter((item) => item.href)
			.map((item) => item.href)
			.sort(),
		[
			'/admin/overview',
			'/admin/tenant-list',
			'/admin/user-list',
			'/admin/storage-list',
			'/admin/support',
			'/admin/share-link',
			'/admin/download-link',
			'/admin/download-link/setting',
			'/admin/site-setting',
			'/admin/view-setting',
			'/admin/security-setting',
			'/admin/access',
			'/admin/sso',
			'/admin/user-edit/1',
			'/admin/login-log',
			'/admin/download-link/log',
			'/admin/ai-feedback',
		].sort()
	)
	assert.equal(
		entries.filter((item) => item.action === 'download-log').length,
		1
	)
	assert.equal(
		entries.some((item) => item.href === '/admin/user-edit/0'),
		false
	)
})

test('route expansion opens only its exact section and common pages start with more management closed', () => {
	assert.deepEqual(adminNavigationExpansionForPath('/admin/security-setting'), [
		'more',
		'security',
	])
	assert.deepEqual(
		adminNavigationExpansionForPath('/admin/download-link/log'),
		['more', 'logs']
	)
	assert.deepEqual(
		adminNavigationExpansionForPath('/admin/download-link/setting'),
		['more', 'resources']
	)
	for (const path of [
		'/admin/overview',
		'/admin/tenant-list',
		'/admin/user-edit/12',
		'/admin/storage-edit/12',
		'/unknown',
	])
		assert.deepEqual(adminNavigationExpansionForPath(path), [])
})

test('more management is an in-memory accordion with one inner group and no mutation of previous state', () => {
	const closed = []
	const settings = adminNavigationExpansionForPath('/admin/security-setting')
	assert.deepEqual(toggleAdminNavigationGroup(closed, 'more'), ['more'])
	assert.deepEqual(toggleAdminNavigationGroup(settings, 'logs'), [
		'more',
		'logs',
	])
	assert.deepEqual(toggleAdminNavigationGroup(settings, 'security'), ['more'])
	assert.deepEqual(toggleAdminNavigationGroup(settings, 'more'), [])
	assert.deepEqual(toggleAdminNavigationGroup(settings, 'unknown'), settings)
	assert.deepEqual(settings, ['more', 'security'])
	assert.deepEqual(closed, [])
})
test('user editor belongs to space personnel, action buttons are not links', () => {
	assert.equal(
		isAdminNavigationActive(adminPrimaryNavigation[2], '/admin/user-edit/0'),
		false
	)
	assert.equal(
		isAdminNavigationActive(adminPrimaryNavigation[2], '/admin/user-edit/12'),
		true
	)
	assert.equal(
		adminNavigationGroups
			.flatMap((group) => group.items)
			.filter((item) => item.action)
			.every((item) => !item.href),
		true
	)
})
test('new manager preset accepts only a positive exact tenant id and never an existing account', () => {
	assert.deepEqual(
		workspaceManagerPreset({ role: 'TENANT_ADMIN', tenantId: '12' }),
		{ role: 'TENANT_ADMIN', tenantId: 12 }
	)
	for (const tenantId of [
		'0',
		'-1',
		'1abc',
		'1.5',
		'2147483648',
		['1'],
		undefined,
	])
		assert.equal(
			workspaceManagerPreset({ role: 'TENANT_ADMIN', tenantId }),
			null
		)
	assert.equal(workspaceManagerPreset({ role: 'ADMIN', tenantId: '1' }), null)
	assert.equal(
		workspaceManagerPreset({ role: 'TENANT_ADMIN', tenantId: '1' }, '7'),
		null
	)
})
