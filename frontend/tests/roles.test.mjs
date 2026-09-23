import assert from 'node:assert/strict';
import test from 'node:test';
import { canAccessAdministration, roleLabel } from '../src/utils/roles.mjs';

test('only an authenticated system administrator can enter administration', () => {
  assert.equal(canAccessAdministration({ isLogin: true, isAdmin: true, role: 'ADMIN' }), true);
  for (const role of ['USER', 'GUEST', 'SUPER_ADMIN', 'TENANT_ADMIN', null]) {
    assert.equal(canAccessAdministration({ isLogin: true, isAdmin: true, role }), false);
  }
  assert.equal(canAccessAdministration({ isLogin: false, isAdmin: true, role: 'ADMIN' }), false);
  assert.equal(canAccessAdministration({ isLogin: true, isAdmin: false, role: 'ADMIN' }), false);
  assert.equal(canAccessAdministration(null), false);
});

test('the three supported roles use consistent labels', () => {
  assert.deepEqual(['ADMIN', 'USER', 'GUEST'].map(roleLabel), ['系统管理员', '普通用户', '访客']);
  assert.equal(roleLabel('TENANT_ADMIN'), '未知角色');
});
