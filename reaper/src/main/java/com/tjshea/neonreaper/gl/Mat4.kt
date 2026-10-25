package com.tjshea.neonreaper.gl

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** Column-major 4×4 matrix helpers (OpenGL convention), allocation-free where it matters. */
object Mat4 {
    fun identity(m: FloatArray = FloatArray(16)): FloatArray {
        m.fill(0f); m[0] = 1f; m[5] = 1f; m[10] = 1f; m[15] = 1f; return m
    }

    fun perspective(m: FloatArray, fovYDeg: Float, aspect: Float, near: Float, far: Float): FloatArray {
        val f = 1f / tan(Math.toRadians(fovYDeg / 2.0)).toFloat()
        m.fill(0f)
        m[0] = f / aspect; m[5] = f
        m[10] = (far + near) / (near - far); m[11] = -1f
        m[14] = 2f * far * near / (near - far)
        return m
    }

    fun lookAt(m: FloatArray, ex: Float, ey: Float, ez: Float, cx: Float, cy: Float, cz: Float, ux: Float = 0f, uy: Float = 1f, uz: Float = 0f): FloatArray {
        var fx = cx - ex; var fy = cy - ey; var fz = cz - ez
        val fl = sqrt(fx * fx + fy * fy + fz * fz); fx /= fl; fy /= fl; fz /= fl
        var sx = fy * uz - fz * uy; var sy = fz * ux - fx * uz; var sz = fx * uy - fy * ux
        val sl = sqrt(sx * sx + sy * sy + sz * sz); sx /= sl; sy /= sl; sz /= sl
        val vx = sy * fz - sz * fy; val vy = sz * fx - sx * fz; val vz = sx * fy - sy * fx
        m[0] = sx; m[4] = sy; m[8] = sz
        m[1] = vx; m[5] = vy; m[9] = vz
        m[2] = -fx; m[6] = -fy; m[10] = -fz
        m[3] = 0f; m[7] = 0f; m[11] = 0f
        m[12] = -(sx * ex + sy * ey + sz * ez)
        m[13] = -(vx * ex + vy * ey + vz * ez)
        m[14] = fx * ex + fy * ey + fz * ez
        m[15] = 1f
        return m
    }

    /** out = a × b (out may not alias a or b). */
    fun mul(out: FloatArray, a: FloatArray, b: FloatArray): FloatArray {
        for (c in 0 until 4) for (r in 0 until 4) {
            out[c * 4 + r] = a[r] * b[c * 4] + a[4 + r] * b[c * 4 + 1] + a[8 + r] * b[c * 4 + 2] + a[12 + r] * b[c * 4 + 3]
        }
        return out
    }

    private val t1 = FloatArray(16)
    private val t2 = FloatArray(16)

    /** m = m × T(x,y,z) */
    fun translate(m: FloatArray, x: Float, y: Float, z: Float): FloatArray {
        for (i in 0 until 4) m[12 + i] += m[i] * x + m[4 + i] * y + m[8 + i] * z
        return m
    }

    fun scale(m: FloatArray, x: Float, y: Float, z: Float): FloatArray {
        for (i in 0 until 4) { m[i] *= x; m[4 + i] *= y; m[8 + i] *= z }
        return m
    }

    /** m = m × R(axis, deg) */
    fun rotate(m: FloatArray, deg: Float, ax: Float, ay: Float, az: Float): FloatArray {
        synchronized(t1) {
            val r = Math.toRadians(deg.toDouble())
            val c = cos(r).toFloat(); val s = sin(r).toFloat(); val k = 1 - c
            val l = sqrt(ax * ax + ay * ay + az * az); val x = ax / l; val y = ay / l; val z = az / l
            identity(t1)
            t1[0] = x * x * k + c; t1[1] = y * x * k + z * s; t1[2] = x * z * k - y * s
            t1[4] = x * y * k - z * s; t1[5] = y * y * k + c; t1[6] = y * z * k + x * s
            t1[8] = x * z * k + y * s; t1[9] = y * z * k - x * s; t1[10] = z * z * k + c
            mul(t2, m, t1); t2.copyInto(m)
        }
        return m
    }

    /** Normal matrix = inverse-transpose of the model's upper 3×3 (handles non-uniform scale). */
    fun normalMatrix(out: FloatArray, m: FloatArray): FloatArray {
        val a = m[0]; val b = m[4]; val c = m[8]
        val d = m[1]; val e = m[5]; val f = m[9]
        val g = m[2]; val h = m[6]; val i = m[10]
        val A = e * i - f * h; val B = -(d * i - f * g); val C = d * h - e * g
        val det = a * A + b * B + c * C
        val inv = if (det == 0f) 0f else 1f / det
        // inverse-transpose == cofactor matrix / det; out is column-major: out[col*3+row] = C[row][col]
        out[0] = A * inv; out[1] = -(b * i - c * h) * inv; out[2] = (b * f - c * e) * inv
        out[3] = B * inv; out[4] = (a * i - c * g) * inv; out[5] = -(a * f - c * d) * inv
        out[6] = C * inv; out[7] = -(a * h - b * g) * inv; out[8] = (a * e - b * d) * inv
        return out
    }

    /** Transforms a point by m (w-divide), writing x,y,z,w into out. */
    fun transform(out: FloatArray, m: FloatArray, x: Float, y: Float, z: Float): FloatArray {
        out[0] = m[0] * x + m[4] * y + m[8] * z + m[12]
        out[1] = m[1] * x + m[5] * y + m[9] * z + m[13]
        out[2] = m[2] * x + m[6] * y + m[10] * z + m[14]
        out[3] = m[3] * x + m[7] * y + m[11] * z + m[15]
        return out
    }
}
