import test from 'node:test'
import assert from 'node:assert/strict'
import {
	adminPrimaryNavigation,
	adminNavigationGroups,
	activeAdminNavigationGroup,
	isAdminNavigationActive,
	workspaceManagerPreset,
	managedWorkspaceGrantDefaults,
} from '../src/utils/admin-navigation.mjs'
test('global console keeps only three primary governance entries', () => {
	assert.deepEqual(
		adminPrimaryNavigation.map((item) => item.href),
		['/admin/overview', '/admin/tenant-list', '/admin/user-list']
	)
	assert.equal(adminNavigationGroups.length, 3)
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
	assert.equal(
		activeAdminNavigationGroup('/admin/storage-edit/12'),
		'resources'
	)
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
