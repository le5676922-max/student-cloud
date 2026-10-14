import assert from 'node:assert/strict'
import test from 'node:test'
import {
	canAccessAdministration,
	canAccessWorkspaceAdministration,
	roleLabel,
} from '../src/utils/roles.mjs'

test('only an authenticated system administrator can enter administration', () => {
	assert.equal(
		canAccessAdministration({ isLogin: true, isAdmin: true, role: 'ADMIN' }),
		true
	)
	for (const role of ['USER', 'GUEST', 'SUPER_ADMIN', 'TENANT_ADMIN', null]) {
		assert.equal(
			canAccessAdministration({ isLogin: true, isAdmin: true, role }),
			false
		)
	}
	assert.equal(
		canAccessAdministration({ isLogin: false, isAdmin: true, role: 'ADMIN' }),
		false
	)
	assert.equal(
		canAccessAdministration({ isLogin: true, isAdmin: false, role: 'ADMIN' }),
		false
	)
	assert.equal(canAccessAdministration(null), false)
})

test('workspace administrators stay outside global administration', () => {
	assert.equal(
		canAccessWorkspaceAdministration({
			isLogin: true,
			role: 'TENANT_ADMIN',
			tenantId: 1,
		}),
		true
	)
	assert.equal(
		canAccessWorkspaceAdministration({
			isLogin: true,
			role: 'TENANT_ADMIN',
			tenantId: null,
		}),
		false
	)
	assert.equal(
		canAccessWorkspaceAdministration({
			isLogin: true,
			role: 'ADMIN',
			tenantId: 1,
		}),
		false
	)
	assert.equal(
		canAccessWorkspaceAdministration({
			isLogin: false,
			role: 'TENANT_ADMIN',
			tenantId: 1,
		}),
		false
	)
})

test('the three account roles use consistent labels and retired roles are unknown', () => {
	assert.deepEqual(['ADMIN', 'TENANT_ADMIN', 'USER'].map(roleLabel), [
		'全局管理员',
		'工作空间管理员',
		'普通用户',
	])
	assert.equal(roleLabel('GUEST'), '未知角色')
})
