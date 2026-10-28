import assert from 'node:assert/strict'
import test from 'node:test'
import {
	MODEL_PREVIEW_LIMITS,
	parsePrivateModel,
} from '../src/utils/private-model-preview.mjs'

const bytes = (text) => new TextEncoder().encode(text).buffer
const triangle = 'v 0 0 0\nv 2 0 0\nv 0 2 0\nf 1 2 3\n'
test('OBJ geometry is centered and normalized, with stable winding and normals', () => {
	const result = parsePrivateModel(bytes(triangle), 'obj')
	assert.equal(result.triangleCount, 1)
	assert.equal(result.vertexCount, 3)
	assert.deepEqual([...result.positions], [-1, -1, 0, 1, -1, 0, -1, 1, 0])
	assert.deepEqual([...result.normals], [0, 0, 1, 0, 0, 1, 0, 0, 1])
})
test('OBJ negative vertex/texture/normal references and a convex quad triangulate correctly', () => {
	const result = parsePrivateModel(
		bytes(
			'v 0 0 0\nv 2 0 0\nv 2 2 0\nv 0 2 0\nvt 0 0\nvn 0 0 1\nf -4/-1/-1 -3/-1/-1 -2/-1/-1 -1/-1/-1\n'
		),
		'obj'
	)
	assert.equal(result.triangleCount, 2)
	assert.equal(result.vertexCount, 4)
	assert.equal(result.positions.length, 18)
	for (let i = 2; i < result.normals.length; i += 3)
		assert.equal(result.normals[i], 1)
})
test('OBJ rejects external materials, URL references and executable/unsupported records', () => {
	for (const line of [
		'mtllib sample.mtl',
		'usemtl private',
		'map_Kd http://example.invalid/a',
		'call mesh.obj',
		'csh echo unsafe',
		'o https://example.invalid/model',
	]) {
		assert.throws(() => parsePrivateModel(bytes(triangle + line + '\n'), 'obj'))
	}
})
test('OBJ rejects invalid numeric/reference forms, invalid UTF-8 and non-planar or concave quads', () => {
	for (const invalid of [
		'v NaN 0 0\nv 1 0 0\nv 0 1 0\nf 1 2 3',
		'v 1e10 0 0\nv 1 0 0\nv 0 1 0\nf 1 2 3',
		triangle.replace('f 1 2 3', 'f 0 2 3'),
		triangle.replace('f 1 2 3', 'f 1 2 9'),
		triangle.replace('f 1 2 3', 'f 1/1 2/1 3/1'),
		'v 0 0 0\nv 2 0 0\nv 0.5 0.5 0\nv 0 2 0\nf 1 2 3 4',
		'v 0 0 0\nv 2 0 0\nv 2 2 1\nv 0 2 0\nf 1 2 3 4',
		'v 0 0 0\nv 1 1 1\nv 2 2 2\nf 1 2 3',
	])
		assert.throws(() => parsePrivateModel(bytes(invalid), 'obj'))
	assert.throws(() => parsePrivateModel(new Uint8Array([0xff]).buffer, 'obj'))
})
function binaryStl(header = 'solid binary header') {
	const buffer = new ArrayBuffer(134),
		view = new DataView(buffer)
	new Uint8Array(buffer).set(new TextEncoder().encode(header))
	view.setUint32(80, 1, true)
	view.setFloat32(84 + 8, 1, true)
	const values = [0, 0, 0, 1, 0, 0, 0, 1, 0]
	values.forEach((value, index) => view.setFloat32(96 + index * 4, value, true))
	return buffer
}
test('STL binary exact layout wins over a misleading solid header', () => {
	const result = parsePrivateModel(binaryStl(), 'stl')
	assert.equal(result.triangleCount, 1)
	assert.equal(result.vertexCount, 3)
	assert.deepEqual([...result.positions], [-1, -1, 0, 1, -1, 0, -1, 1, 0])
})
const asciiStl =
	'solid example\nfacet normal 0 0 1\nouter loop\nvertex 0 0 0\nvertex 1 0 0\nvertex 0 1 0\nendloop\nendfacet\nendsolid example\n'
test('STL ASCII requires complete facet grammar and finite non-degenerate geometry', () => {
	assert.equal(parsePrivateModel(bytes(asciiStl), 'stl').triangleCount, 1)
	for (const invalid of [
		asciiStl.replace('endfacet', ''),
		asciiStl.replace('vertex 0 1 0', 'vertex 0 0 0'),
		asciiStl.replace('normal 0 0 1', 'normal Infinity 0 0'),
		asciiStl + 'facet normal 0 0 1',
	]) {
		assert.throws(() => parsePrivateModel(bytes(invalid), 'stl'))
	}
	const invalid = binaryStl()
	new DataView(invalid).setFloat32(96, Infinity, true)
	assert.throws(() => parsePrivateModel(invalid, 'stl'))
	assert.throws(() => parsePrivateModel(binaryStl().slice(0, 133), 'stl'))
})
test('hard byte, vertex and face budgets apply before unbounded output allocation', () => {
	assert.throws(() =>
		parsePrivateModel(new ArrayBuffer(MODEL_PREVIEW_LIMITS.bytes + 1), 'obj')
	)
	assert.throws(() =>
		parsePrivateModel(
			bytes('v 0 0 0\n'.repeat(MODEL_PREVIEW_LIMITS.vertices + 1)),
			'obj'
		)
	)
	assert.throws(() =>
		parsePrivateModel(
			bytes(triangle + 'f 1 2 3\n'.repeat(MODEL_PREVIEW_LIMITS.triangles)),
			'obj'
		)
	)
	const oversized = new ArrayBuffer(84)
	new DataView(oversized).setUint32(80, 100001, true)
	assert.throws(() => parsePrivateModel(oversized, 'stl'))
	assert.throws(() => parsePrivateModel(bytes(triangle), 'fbx'))
})
