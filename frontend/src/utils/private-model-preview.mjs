export const MODEL_PREVIEW_LIMITS = Object.freeze({
	bytes: 10 * 1024 * 1024,
	vertices: 100_000,
	triangles: 100_000,
})

const invalid = () =>
	new Error('模型格式无效，或包含不支持的材质、外部资源及复杂面；请下载后查看')
const budget = () => new Error('模型超过预览预算，请精简模型后重试')
const number = (value) => {
	if (!/^[+-]?(?:\d+(?:\.\d*)?|\.\d+)(?:e[+-]?\d+)?$/i.test(value))
		throw invalid()
	const result = Number(value)
	if (!Number.isFinite(result) || Math.abs(result) > 1e9) throw invalid()
	return result
}
const subtract = (a, b) => a.map((value, i) => value - b[i])
const cross = (a, b) => [
	a[1] * b[2] - a[2] * b[1],
	a[2] * b[0] - a[0] * b[2],
	a[0] * b[1] - a[1] * b[0],
]
const dot = (a, b) => a.reduce((sum, value, i) => sum + value * b[i], 0)
const normal = (a, b, c) => {
	const result = cross(subtract(b, a), subtract(c, a))
	const length = Math.hypot(...result)
	if (!(length > 0) || !Number.isFinite(length)) throw invalid()
	return result.map((value) => value / length)
}

function* lines(text) {
	let start = 0,
		count = 0
	while (start < text.length) {
		if (++count > 500_000) throw budget()
		let end = text.indexOf('\n', start)
		if (end < 0) end = text.length
		if (end - start > 4096) throw budget()
		const value = text.slice(start, end).trim()
		start = end + 1
		if (value && !value.startsWith('#')) yield value
	}
}
function textOf(buffer) {
	try {
		const value = new TextDecoder('utf-8', { fatal: true }).decode(buffer)
		if (value.includes('\0')) throw invalid()
		return value
	} catch {
		throw invalid()
	}
}
function geometry() {
	const positions = [],
		normals = []
	let triangles = 0
	return {
		add(a, b, c) {
			if (++triangles > MODEL_PREVIEW_LIMITS.triangles) throw budget()
			const faceNormal = normal(a, b, c)
			positions.push(...a, ...b, ...c)
			normals.push(...faceNormal, ...faceNormal, ...faceNormal)
		},
		finish(vertexCount) {
			if (!triangles) throw invalid()
			const min = [Infinity, Infinity, Infinity],
				max = [-Infinity, -Infinity, -Infinity]
			for (let i = 0; i < positions.length; i++) {
				const axis = i % 3
				min[axis] = Math.min(min[axis], positions[i])
				max[axis] = Math.max(max[axis], positions[i])
			}
			const span = Math.max(...max.map((value, i) => value - min[i]))
			if (!(span > 0) || !Number.isFinite(span)) throw invalid()
			for (let i = 0; i < positions.length; i++)
				positions[i] =
					((positions[i] - (min[i % 3] + max[i % 3]) / 2) / span) * 2
			return {
				positions: new Float32Array(positions),
				normals: new Float32Array(normals),
				triangleCount: triangles,
				vertexCount,
			}
		},
	}
}
function index(value, length) {
	if (!/^-?[1-9]\d*$/.test(value)) throw invalid()
	const parsed = Number(value),
		position = parsed > 0 ? parsed - 1 : length + parsed
	if (!Number.isSafeInteger(parsed) || position < 0 || position >= length)
		throw invalid()
	return position
}
function obj(buffer) {
	const vertices = [],
		uv = [],
		normals = [],
		output = geometry()
	for (const line of lines(textOf(buffer))) {
		if (/(?:https?:|data:|file:|\\|\/\/\/)/i.test(line)) throw invalid()
		const [command, ...values] = line.split(/\s+/)
		if (command === 'v' || command === 'vn' || command === 'vt') {
			if (
				command === 'vt'
					? values.length < 1 || values.length > 3
					: values.length !== 3
			)
				throw invalid()
			const target =
				command === 'v' ? vertices : command === 'vn' ? normals : uv
			if (target.length >= MODEL_PREVIEW_LIMITS.vertices) throw budget()
			target.push(values.map(number))
		} else if (command === 'f') {
			if (values.length !== 3 && values.length !== 4) throw invalid()
			const points = values.map((value) => {
				const parts = value.split('/')
				if (
					parts.length > 3 ||
					!parts[0] ||
					(parts.length === 2 && !parts[1]) ||
					(parts.length === 3 && !parts[2])
				)
					throw invalid()
				if (parts[1]) index(parts[1], uv.length)
				if (parts[2]) index(parts[2], normals.length)
				return vertices[index(parts[0], vertices.length)]
			})
			if (points.length === 4) {
				const plane = cross(
					subtract(points[1], points[0]),
					subtract(points[2], points[0])
				)
				const length = Math.hypot(...plane)
				const scale = Math.max(
					...[0, 1, 2].map(
						(axis) =>
							Math.max(...points.map((p) => p[axis])) -
							Math.min(...points.map((p) => p[axis]))
					)
				)
				if (
					!(length > 0) ||
					Math.abs(dot(subtract(points[3], points[0]), plane)) >
						scale * length * 1e-6
				)
					throw invalid()
				for (let i = 0; i < 4; i++) {
					const turn = cross(
						subtract(points[(i + 1) % 4], points[i]),
						subtract(points[(i + 2) % 4], points[(i + 1) % 4])
					)
					if (dot(turn, plane) <= scale * scale * length * 1e-12)
						throw invalid()
				}
			}
			output.add(...points.slice(0, 3))
			if (points.length === 4) output.add(points[0], points[2], points[3])
		} else if (!['o', 'g', 's'].includes(command)) throw invalid()
	}
	return output.finish(vertices.length)
}
function stl(buffer) {
	const output = geometry()
	if (buffer.byteLength >= 84) {
		const view = new DataView(buffer),
			count = view.getUint32(80, true)
		if (84 + 50 * count === buffer.byteLength) {
			if (!count || count * 3 > MODEL_PREVIEW_LIMITS.vertices) throw budget()
			for (let i = 0; i < count; i++) {
				const base = 84 + i * 50,
					numbers = []
				for (let j = 0; j < 12; j++) {
					const value = view.getFloat32(base + j * 4, true)
					if (!Number.isFinite(value) || Math.abs(value) > 1e9) throw invalid()
					numbers.push(value)
				}
				output.add(
					numbers.slice(3, 6),
					numbers.slice(6, 9),
					numbers.slice(9, 12)
				)
			}
			return output.finish(count * 3)
		}
	}
	const iterator = lines(textOf(buffer))[Symbol.iterator]()
	const next = () => iterator.next().value
	if (!/^solid(?:\s|$)/.test(next() || '')) throw invalid()
	let count = 0,
		ended = false
	for (let line = next(); line !== undefined; line = next()) {
		if (/^endsolid(?:\s|$)/.test(line)) {
			ended = true
			break
		}
		const facet = line.split(/\s+/)
		if (facet.length !== 5 || facet[0] !== 'facet' || facet[1] !== 'normal')
			throw invalid()
		facet.slice(2).forEach(number)
		if (next() !== 'outer loop') throw invalid()
		const points = []
		for (let i = 0; i < 3; i++) {
			const vertex = (next() || '').split(/\s+/)
			if (vertex.length !== 4 || vertex[0] !== 'vertex') throw invalid()
			if (++count > MODEL_PREVIEW_LIMITS.vertices) throw budget()
			points.push(vertex.slice(1).map(number))
		}
		if (next() !== 'endloop' || next() !== 'endfacet') throw invalid()
		output.add(...points)
	}
	if (!ended || next() !== undefined) throw invalid()
	return output.finish(count)
}

/** Parse a bounded geometry-only model. Never resolves resources or executes model metadata. */
export function parsePrivateModel(buffer, format) {
	if (!(buffer instanceof ArrayBuffer) || !buffer.byteLength) throw invalid()
	if (buffer.byteLength > MODEL_PREVIEW_LIMITS.bytes) throw budget()
	if (format === 'obj') return obj(buffer)
	if (format === 'stl') return stl(buffer)
	throw invalid()
}
