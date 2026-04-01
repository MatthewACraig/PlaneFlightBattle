package flightbattlesimulator;

public class RigidBody {
    protected float mass = 1.0f;

    protected final Vector3 position = new Vector3();            // world space
    protected final Vector3 orientation = new Vector3();         // Euler degrees: pitch(X), yaw(Y), roll(Z)
    protected final Vector3 velocity = new Vector3();            // world space
    protected final Vector3 angularVelocity = new Vector3();     // body space, rad/s

    protected final Vector3 inertia = new Vector3(1.0f, 1.0f, 1.0f);
    protected final Vector3 inverseInertia = new Vector3(1.0f, 1.0f, 1.0f);

    protected final Vector3 accumulatedForce = new Vector3();    // world space
    protected final Vector3 accumulatedTorque = new Vector3();   // body space

    protected boolean applyGravity = true;

    public float getX() { return position.x; }
    public float getY() { return position.y; }
    public float getZ() { return position.z; }

    public float getPitch() { return orientation.x; }
    public float getYaw() { return orientation.y; }
    public float getRoll() { return orientation.z; }

    public void setPosition(float x, float y, float z) {
        position.set(x, y, z);
    }

    public void setMass(float m) {
        mass = Math.max(0.0001f, m);
    }

    public void setInertia(float ix, float iy, float iz) {
        inertia.set(Math.max(0.0001f, ix), Math.max(0.0001f, iy), Math.max(0.0001f, iz));
        inverseInertia.set(1.0f / inertia.x, 1.0f / inertia.y, 1.0f / inertia.z);
    }

    public Vector3 transformDirection(Vector3 bodyDirection) {
        Vector3 out = bodyDirection.copy();
        rotateY(out, (float) Math.toRadians(orientation.y));
        rotateX(out, (float) Math.toRadians(orientation.x));
        rotateZ(out, (float) Math.toRadians(orientation.z));
        return out;
    }

    public Vector3 inverseTransformDirection(Vector3 worldDirection) {
        Vector3 out = worldDirection.copy();
        rotateZ(out, (float) Math.toRadians(-orientation.z));
        rotateX(out, (float) Math.toRadians(-orientation.x));
        rotateY(out, (float) Math.toRadians(-orientation.y));
        return out;
    }

    // Body point and resulting velocity in body coordinates
    public Vector3 getPointVelocity(Vector3 pointBody) {
        Vector3 bodyLinearVelocity = inverseTransformDirection(velocity);
        Vector3 rotationalVelocity = Vector3.cross(angularVelocity, pointBody);
        return bodyLinearVelocity.add(rotationalVelocity);
    }

    // Force and point are body space
    public void addForceAtPoint(Vector3 forceBody, Vector3 pointBody) {
        addRelativeForce(forceBody);
        accumulatedTorque.add(Vector3.cross(pointBody, forceBody));
    }

    // Body-space force
    public void addRelativeForce(Vector3 forceBody) {
        accumulatedForce.add(transformDirection(forceBody));
    }

    // Body-space torque
    public void addRelativeTorque(Vector3 torqueBody) {
        accumulatedTorque.add(torqueBody);
    }

    public void integrate(float dt) {
        Vector3 acceleration = accumulatedForce.copy().div(mass);
        if (applyGravity) {
            acceleration.y -= 9.81f;
        }

        velocity.add(acceleration.copy().mul(dt));
        position.add(velocity.copy().mul(dt));

        Vector3 inertiaTimesOmega = new Vector3(
            inertia.x * angularVelocity.x,
            inertia.y * angularVelocity.y,
            inertia.z * angularVelocity.z
        );

        Vector3 gyroscopic = Vector3.cross(angularVelocity, inertiaTimesOmega);

        Vector3 angularAcceleration = new Vector3(
            (accumulatedTorque.x - gyroscopic.x) * inverseInertia.x,
            (accumulatedTorque.y - gyroscopic.y) * inverseInertia.y,
            (accumulatedTorque.z - gyroscopic.z) * inverseInertia.z
        );

        angularVelocity.add(angularAcceleration.mul(dt));

        orientation.x += (float) Math.toDegrees(angularVelocity.x * dt);
        orientation.y += (float) Math.toDegrees(angularVelocity.y * dt);
        orientation.z += (float) Math.toDegrees(angularVelocity.z * dt);

        clearAccumulators();
    }

    protected void clearAccumulators() {
        accumulatedForce.set(0.0f, 0.0f, 0.0f);
        accumulatedTorque.set(0.0f, 0.0f, 0.0f);
    }

    protected static void rotateX(Vector3 v, float rad) {
        float c = (float) Math.cos(rad);
        float s = (float) Math.sin(rad);
        float ny = v.y * c - v.z * s;
        float nz = v.y * s + v.z * c;
        v.y = ny;
        v.z = nz;
    }

    protected static void rotateY(Vector3 v, float rad) {
        float c = (float) Math.cos(rad);
        float s = (float) Math.sin(rad);
        float nx = v.x * c + v.z * s;
        float nz = -v.x * s + v.z * c;
        v.x = nx;
        v.z = nz;
    }

    protected static void rotateZ(Vector3 v, float rad) {
        float c = (float) Math.cos(rad);
        float s = (float) Math.sin(rad);
        float nx = v.x * c - v.y * s;
        float ny = v.x * s + v.y * c;
        v.x = nx;
        v.y = ny;
    }
}
