package flightbattlesimulator;

public class Vector3 {
    public float x;
    public float y;
    public float z;

    public Vector3() {
        this(0.0f, 0.0f, 0.0f);
    }

    public Vector3(float x, float y, float z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public Vector3 set(float nx, float ny, float nz) {
        x = nx;
        y = ny;
        z = nz;
        return this;
    }

    public Vector3 set(Vector3 other) {
        x = other.x;
        y = other.y;
        z = other.z;
        return this;
    }

    public Vector3 copy() {
        return new Vector3(x, y, z);
    }

    public Vector3 add(Vector3 other) {
        x += other.x;
        y += other.y;
        z += other.z;
        return this;
    }

    public Vector3 sub(Vector3 other) {
        x -= other.x;
        y -= other.y;
        z -= other.z;
        return this;
    }

    public Vector3 mul(float scalar) {
        x *= scalar;
        y *= scalar;
        z *= scalar;
        return this;
    }

    public Vector3 div(float scalar) {
        if (Math.abs(scalar) > 0.000001f) {
            x /= scalar;
            y /= scalar;
            z /= scalar;
        }
        return this;
    }

    public float length() {
        return (float) Math.sqrt(x * x + y * y + z * z);
    }

    public float lengthSquared() {
        return x * x + y * y + z * z;
    }

    public Vector3 normalize() {
        float len = length();
        if (len > 0.000001f) {
            div(len);
        }
        return this;
    }

    public static float dot(Vector3 a, Vector3 b) {
        return a.x * b.x + a.y * b.y + a.z * b.z;
    }

    public static Vector3 cross(Vector3 a, Vector3 b) {
        return new Vector3(
            a.y * b.z - a.z * b.y,
            a.z * b.x - a.x * b.z,
            a.x * b.y - a.y * b.x
        );
    }
}
