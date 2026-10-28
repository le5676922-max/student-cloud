import { validPrivateGeometry } from './private-model-worker.mjs'

const vertexSource = `
attribute vec3 a_position;
attribute vec3 a_normal;
uniform mat4 u_matrix;
uniform mat3 u_rotation;
varying float v_light;
void main() {
  gl_Position = u_matrix * vec4(a_position, 1.0);
  vec3 n = normalize(u_rotation * a_normal);
  v_light = 0.35 + 0.65 * abs(dot(n, normalize(vec3(0.3, 0.5, 1.0))));
}`
const fragmentSource = `
precision mediump float;
uniform vec3 u_color;
varying float v_light;
void main() { gl_FragColor = vec4(u_color * v_light, 1.0); }
`
const finite = (value, fallback) => (Number.isFinite(value) ? value : fallback)
export function privateModelMatrices(
	{ yaw = 0.5, pitch = -0.3, zoom = 1 } = {},
	aspect = 1
) {
	const y = finite(yaw, 0.5),
		x = finite(pitch, -0.3),
		scale = (0.75 / Math.sqrt(3)) * Math.min(3, Math.max(0.35, finite(zoom, 1)))
	const ratio = Math.max(0.1, Math.min(10, finite(aspect, 1))),
		sx = scale / (ratio >= 1 ? ratio : 1),
		sy = scale * (ratio >= 1 ? 1 : ratio),
		sz = 0.25
	const cy = Math.cos(y),
		syaw = Math.sin(y),
		cx = Math.cos(x),
		sp = Math.sin(x)
	return {
		matrix: new Float32Array([
			cy * sx,
			0,
			-syaw * sz,
			0,
			syaw * sp * sx,
			cx * sy,
			cy * sp * sz,
			0,
			syaw * cx * sx,
			-sp * sy,
			cy * cx * sz,
			0,
			0,
			0,
			0,
			1,
		]),
		rotation: new Float32Array([
			cy,
			0,
			-syaw,
			syaw * sp,
			cx,
			cy * sp,
			syaw * cx,
			-sp,
			cy * cx,
		]),
	}
}

/** A geometry-only renderer: no textures, shaders from files, URL IO or animation loop. */
export function createPrivateModelRenderer(
	canvas,
	geometry,
	color = [0.35, 0.35, 0.4]
) {
	if (!validPrivateGeometry(geometry))
		throw new Error('模型几何数据无效，请下载后查看')
	const gl = canvas.getContext('webgl', {
		alpha: true,
		antialias: true,
		depth: true,
		preserveDrawingBuffer: false,
		powerPreference: 'low-power',
	})
	if (!gl) throw new Error('浏览器不支持 WebGL，请下载模型后查看')
	const shaders = [],
		buffers = []
	let program,
		disposed = false,
		width = 640,
		height = 400
	const dispose = () => {
		if (disposed) return
		disposed = true
		buffers.forEach((buffer) => gl.deleteBuffer(buffer))
		shaders.forEach((shader) => gl.deleteShader(shader))
		if (program) gl.deleteProgram(program)
		buffers.length = 0
		shaders.length = 0
		program = undefined
	}
	try {
		const compile = (type, source) => {
			const shader = gl.createShader(type)
			if (!shader) throw new Error('无法创建模型着色器，请关闭预览后重试')
			shaders.push(shader)
			gl.shaderSource(shader, source)
			gl.compileShader(shader)
			if (!gl.getShaderParameter(shader, gl.COMPILE_STATUS))
				throw new Error('浏览器无法绘制模型，请下载后查看')
			return shader
		}
		const vertex = compile(gl.VERTEX_SHADER, vertexSource),
			fragment = compile(gl.FRAGMENT_SHADER, fragmentSource)
		program = gl.createProgram()
		if (!program) throw new Error('无法创建模型画布，请关闭预览后重试')
		gl.attachShader(program, vertex)
		gl.attachShader(program, fragment)
		gl.linkProgram(program)
		if (!gl.getProgramParameter(program, gl.LINK_STATUS))
			throw new Error('浏览器无法绘制模型，请下载后查看')
		gl.useProgram(program)
		for (const [name, data] of [
			['a_position', geometry.positions],
			['a_normal', geometry.normals],
		]) {
			const buffer = gl.createBuffer(),
				location = gl.getAttribLocation(program, name)
			if (buffer) buffers.push(buffer)
			if (!buffer || location < 0)
				throw new Error('模型画布资源不足，请关闭预览后重试')
			gl.bindBuffer(gl.ARRAY_BUFFER, buffer)
			gl.bufferData(gl.ARRAY_BUFFER, data, gl.STATIC_DRAW)
			gl.enableVertexAttribArray(location)
			gl.vertexAttribPointer(location, 3, gl.FLOAT, false, 0, 0)
		}
		const matrix = gl.getUniformLocation(program, 'u_matrix'),
			rotation = gl.getUniformLocation(program, 'u_rotation'),
			material = gl.getUniformLocation(program, 'u_color')
		gl.enable(gl.DEPTH_TEST)
		gl.clearColor(0, 0, 0, 0)
		return {
			resize(logicalWidth, logicalHeight, dpr = 1) {
				if (disposed) return
				const density = Math.min(2, Math.max(1, finite(dpr, 1)))
				width = Math.min(
					1600,
					Math.max(16, Math.round(finite(logicalWidth, 640) * density))
				)
				height = Math.min(
					1200,
					Math.max(16, Math.round(finite(logicalHeight, 400) * density))
				)
				canvas.width = width
				canvas.height = height
				gl.viewport(0, 0, width, height)
			},
			draw(view) {
				if (disposed) return
				const matrices = privateModelMatrices(view, width / height)
				gl.useProgram(program)
				gl.clear(gl.COLOR_BUFFER_BIT | gl.DEPTH_BUFFER_BIT)
				gl.uniformMatrix4fv(matrix, false, matrices.matrix)
				gl.uniformMatrix3fv(rotation, false, matrices.rotation)
				gl.uniform3fv(
					material,
					new Float32Array(
						color.map((value) => Math.min(1, Math.max(0, finite(value, 0.4))))
					)
				)
				gl.drawArrays(gl.TRIANGLES, 0, geometry.positions.length / 3)
			},
			dispose,
		}
	} catch (error) {
		dispose()
		throw error
	}
}
