import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import { workspaceMemberPermissionLink } from '../src/utils/admin-navigation.mjs'
import {
	canManageWorkspaceAdministrator,
	createWorkspaceUserRequestGate,
	normalizeWorkspaceUserQuery,
	validWorkspaceDirectoryPage,
	validWorkspaceUserPage,
	workspaceAdministratorCreateLink,
	workspaceDirectorySearchQuery,
	workspaceUserCreatedAt,
	workspaceUserSequence,
	workspaceUserStatus,
	workspaceUserTenantId,
} from '../src/utils/workspace-users.mjs'

const administrator = {
	id: 3,
	tenantId: 9,
	role: 'TENANT_ADMIN',
	membershipStatus: 'ACTIVE',
	enable: true,
}
const member = {
	id: 4,
	tenantId: 9,
	role: 'USER',
	membershipStatus: 'ACTIVE',
	enable: true,
}

test('directory searches release initial deep-link IDs and reset pagination', () => {
	const initial = { workspaceId: 9, page: 4, pageSize: 10, keyword: 'old' }
	const searched = workspaceDirectorySearchQuery('  Beta_%  ', initial)
	assert.equal(searched.keyword, 'Beta_%')
	assert.equal(searched.page, 1)
	assert.equal(searched.pageSize, 10)
	assert.equal(Object.hasOwn(searched, 'workspaceId'), false)
	const cleared = workspaceDirectorySearchQuery('', initial)
	assert.equal(cleared.keyword, '')
	assert.equal(Object.hasOwn(cleared, 'workspaceId'), false)
	assert.equal(initial.workspaceId, 9)
})

test('workspace navigation IDs accept only positive safe integers', () => {
	assert.equal(workspaceUserTenantId('9'), 9)
	assert.equal(workspaceUserTenantId(9), 9)
	for (const input of [
		null,
		undefined,
		'',
		0,
		-1,
		Infinity,
		1.5,
		[],
		['9'],
		'wrong',
		Number.MAX_VALUE,
		true,
		false,
		'1e3',
	])
		assert.equal(workspaceUserTenantId(input), null)
})

test('member page query has bounded pagination, literal keyword and explicit role partition', () => {
	assert.deepEqual(normalizeWorkspaceUserQuery(), {
		keyword: '',
		page: 1,
		pageSize: 10,
		role: 'TENANT_ADMIN',
	})
	assert.deepEqual(
		normalizeWorkspaceUserQuery(
			{ keyword: '  name_%  ', page: '2', pageSize: 20, enabled: false },
			'USER'
		),
		{ keyword: 'name_%', page: 2, pageSize: 20, role: 'USER' }
	)
	const bounded = normalizeWorkspaceUserQuery(
		{ keyword: '中'.repeat(101), page: 10001, pageSize: 1000 },
		'ADMIN'
	)
	assert.equal(bounded.keyword.length, 100)
	assert.equal(bounded.page, 10000)
	assert.equal(bounded.pageSize, 100)
	assert.equal(bounded.role, 'TENANT_ADMIN')
	assert.equal(Object.hasOwn(bounded, 'enabled'), false)
})

test('member pending/rejected status takes precedence over a stale enable flag', () => {
	assert.deepEqual(
		workspaceUserStatus({ membershipStatus: 'PENDING', enable: true }),
		{ label: '待审核', type: 'warning' }
	)
	assert.deepEqual(
		workspaceUserStatus({ membershipStatus: 'REJECTED', enable: true }),
		{ label: '已拒绝', type: 'danger' }
	)
	assert.deepEqual(workspaceUserStatus(member), {
		label: '已启用',
		type: 'success',
	})
	assert.deepEqual(workspaceUserStatus({ ...member, enable: false }), {
		label: '已停用',
		type: 'info',
	})
	assert.deepEqual(workspaceUserStatus(null), {
		label: '状态未获取',
		type: 'info',
	})
})

test('global directory only offers account operations for admitted administrators of the selected space', () => {
	assert.equal(canManageWorkspaceAdministrator(administrator, 9), true)
	assert.equal(canManageWorkspaceAdministrator(administrator, '9'), true)
	for (const row of [
		member,
		{ ...administrator, role: 'ADMIN' },
		{ ...administrator, id: 0 },
		{ ...administrator, id: 1 },
		{ ...administrator, id: 2 },
		{ ...administrator, tenantId: 10 },
		{ ...administrator, membershipStatus: 'PENDING' },
		{ ...administrator, membershipStatus: 'REJECTED' },
		{ ...administrator, enable: undefined },
	])
		assert.equal(canManageWorkspaceAdministrator(row, 9), false)
	assert.equal(canManageWorkspaceAdministrator(administrator, null), false)
})

test('page validation rejects mixed space or role records and built-in templates', () => {
	const page = { records: [administrator], total: 1, page: 1, pageSize: 10 }
	assert.equal(validWorkspaceUserPage(page, 9, 'TENANT_ADMIN'), true)
	assert.equal(
		validWorkspaceUserPage({ ...page, records: [], total: 0 }, 9, 'USER'),
		true
	)
	assert.equal(validWorkspaceUserPage(page, 10, 'TENANT_ADMIN'), false)
	assert.equal(validWorkspaceUserPage(page, 9, 'USER'), false)
	assert.equal(
		validWorkspaceUserPage(
			{ ...page, records: [{ ...administrator, id: 0 }] },
			9,
			'TENANT_ADMIN'
		),
		false
	)
	assert.equal(
		validWorkspaceUserPage({ ...page, records: [null] }, 9, 'TENANT_ADMIN'),
		false
	)
	assert.equal(
		validWorkspaceUserPage({ ...page, total: undefined }, 9, 'TENANT_ADMIN'),
		false
	)
	assert.equal(
		validWorkspaceUserPage({ ...page, page: 10001 }, 9, 'TENANT_ADMIN'),
		false
	)
	assert.equal(
		validWorkspaceUserPage({ ...page, pageSize: 101 }, 9, 'TENANT_ADMIN'),
		false
	)
	assert.equal(
		validWorkspaceUserPage(
			{ ...page, records: Array(11).fill(administrator) },
			9,
			'TENANT_ADMIN'
		),
		false
	)
	assert.equal(validWorkspaceUserPage(null, 9, 'TENANT_ADMIN'), false)
})

test('space navigation validates directory pagination rather than inventing empty healthy data', () => {
	const page = {
		records: [{ id: 9, name: 'Alpha' }],
		total: 1,
		page: 1,
		pageSize: 10,
	}
	assert.equal(validWorkspaceDirectoryPage(page), true)
	assert.equal(
		validWorkspaceDirectoryPage({ ...page, records: [], total: 0 }),
		true
	)
	assert.equal(validWorkspaceDirectoryPage({ ...page, records: [null] }), false)
	assert.equal(
		validWorkspaceDirectoryPage({ ...page, records: [{ id: 9 }] }),
		false
	)
	assert.equal(validWorkspaceDirectoryPage({ ...page, page: 0 }), false)
	assert.equal(validWorkspaceDirectoryPage({ ...page, total: null }), false)
	assert.equal(validWorkspaceDirectoryPage(null), false)
})

test('administrator create links carry exact role and tenant, with no member create shortcut', () => {
	assert.deepEqual(workspaceAdministratorCreateLink(9), {
		path: '/admin/user-edit',
		query: { role: 'TENANT_ADMIN', tenantId: '9' },
	})
	assert.equal(workspaceAdministratorCreateLink(null), null)
	assert.equal(workspaceAdministratorCreateLink('wrong'), null)
})

test('display helpers preserve pagination sequence and unknown timestamps', () => {
	assert.equal(workspaceUserSequence({ page: 2, pageSize: 10 }, 0), '11')
	assert.equal(workspaceUserSequence({ page: 1, pageSize: 20 }, 3), '4')
	assert.equal(workspaceUserSequence(null, 0), '—')
	assert.equal(workspaceUserSequence({ page: 1 }, -1), '—')
	assert.equal(workspaceUserCreatedAt(null), '—')
	assert.match(workspaceUserCreatedAt(Date.UTC(2026, 9, 2, 16)), /2026\/10\/03/)
})

test('switching spaces or collapsing members cancels late replies and allows later reload', async () => {
	const gate = createWorkspaceUserRequestGate()
	let resolve
	let displayed = 'new space'
	const request = gate.begin()
	const delayed = new Promise((done) => {
		resolve = done
	}).then((data) => {
		if (gate.isCurrent(request)) displayed = data
	})
	gate.cancel()
	assert.equal(request.signal.aborted, true)
	assert.equal(gate.isCurrent(request), false)
	resolve('private old space rows')
	await delayed
	assert.equal(displayed, 'new space')
	const reopened = gate.begin()
	assert.equal(gate.isCurrent(reopened), true)
	gate.finish(request)
	assert.equal(gate.isCurrent(reopened), true)
	gate.dispose()
	assert.equal(reopened.signal.aborted, true)
	assert.equal(gate.isCurrent(reopened), false)
	assert.equal(gate.begin(), null)
})

test('latest filter/page request is the only reply eligible to display', () => {
	const gate = createWorkspaceUserRequestGate()
	const first = gate.begin()
	const second = gate.begin()
	assert.equal(first.signal.aborted, true)
	assert.equal(gate.isCurrent(first), false)
	assert.equal(gate.isCurrent(second), true)
	gate.finish(second)
	gate.dispose()
	assert.equal(second.signal.aborted, false)
})

test('global page uses bounded workspace directory and a lazy member overview without permission editing', async () => {
	const page = await readFile(
		new URL('../src/pages/admin/user-list/index.vue', import.meta.url),
		'utf8'
	)
	assert.doesNotMatch(
		page,
		/getUserListReq|userStorageSourceList|deleteUserReq|openCopyUserDialog/
	)
	assert.match(page, /const membersOpen = ref\(false\)/)
	assert.match(page, /if \(!tenantId \|\| !membersOpen\.value\) return/)
	assert.match(page, /membersGate\.cancel\(\)/)
	assert.match(page, /role: 'TENANT_ADMIN'|normalizeWorkspaceUserQuery\(\)/)
	assert.match(page, /getWorkspaceUsersReq/)
	const template = page.slice(0, page.indexOf('<script setup>'))
	const memberSection = template.slice(template.indexOf('members-panel'))
	assert.doesNotMatch(
		memberSection,
		/updateUserEnableReq|deleteUser|copyUser|el-switch/
	)
	assert.doesNotMatch(
		memberSection,
		/workspaceMemberPermissionLink|设置权限|label="权限"/
	)
	assert.match(memberSection, /全局管理员在这里总览空间人员，不设置成员权限/)
})

test('member permission links require the matching workspace administrator and exclude global administrators', () => {
	const login = {
		isLogin: true,
		isAdmin: false,
		role: 'TENANT_ADMIN',
		tenantId: 9,
	}
	const row = {
		id: 21,
		role: 'USER',
		tenantId: 9,
		membershipStatus: 'ACTIVE',
		enable: false,
	}
	assert.equal(
		workspaceMemberPermissionLink(row, 9, login),
		'/workspace-admin?memberId=21'
	)
	for (const changed of [
		{ id: 0 },
		{ id: 1 },
		{ id: 2 },
		{ id: '21' },
		{ tenantId: 10 },
		{ role: 'ADMIN' },
		{ role: 'TENANT_ADMIN' },
		{ membershipStatus: 'PENDING' },
		{ membershipStatus: 'REJECTED' },
		{ membershipStatus: undefined },
	])
		assert.equal(
			workspaceMemberPermissionLink({ ...row, ...changed }, 9, login),
			null
		)
	for (const changed of [
		{ isLogin: false },
		{ tenantId: 10 },
		{ role: 'USER' },
		{ role: 'ADMIN', isAdmin: true },
	])
		assert.equal(
			workspaceMemberPermissionLink(row, 9, { ...login, ...changed }),
			null
		)
	assert.equal(workspaceMemberPermissionLink(row, null, login), null)
})
