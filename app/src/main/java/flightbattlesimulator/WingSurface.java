package flightbattlesimulator;

public class WingSurface {
    private static final float AIR_DENSITY_SEA_LEVEL = 1.225f;

    private final Vector3 centerOfPressure;
    private final Vector3 normal;
    private final float area;
    private final float aspectRatio;
    private final float flapRatio;
    private final float liftSlope;
    private final float baseDrag;
    private final float efficiencyFactor;

    private float controlInput = 0.0f;

    public WingSurface(
        Vector3 centerOfPressure,
        Vector3 normal,
        float span,
        float chord,
        float flapRatio,
        float liftSlope,
        float baseDrag
    ) {
        this.centerOfPressure = centerOfPressure;
        this.normal = normal.copy().normalize();
        this.area = span * chord;
        this.aspectRatio = (span * span) / Math.max(0.001f, area);
        this.flapRatio = flapRatio;
        this.liftSlope = liftSlope;
        this.baseDrag = baseDrag;
        this.efficiencyFactor = 0.85f;
    }

    public void setControlInput(float input) {
        controlInput = clamp(input, -1.0f, 1.0f);
    }

    public void applyForces(RigidBody body) {
        Vector3 localVelocity = body.getPointVelocity(centerOfPressure);
        float speed = localVelocity.length();
        if (speed <= 1.0f) {
            return;
        }

        Vector3 dragDirection = localVelocity.copy().mul(-1.0f / speed);

        Vector3 liftDirection = Vector3.cross(Vector3.cross(dragDirection, normal), dragDirection);
        if (liftDirection.lengthSquared() <= 0.00001f) {
            return;
        }
        liftDirection.normalize();

        float alpha = (float) Math.asin(clamp(Vector3.dot(dragDirection, normal), -1.0f, 1.0f));

        float liftCoeff = liftSlope * alpha;
        if (flapRatio > 0.0f) {
            liftCoeff += (float) (Math.sqrt(flapRatio) * controlInput * 0.8f);
        }

        float dragCoeff = baseDrag + (liftCoeff * liftCoeff) /
            ((float) Math.PI * Math.max(0.1f, aspectRatio) * efficiencyFactor);

        float altitude = Math.max(0.0f, body.getY());
        float airDensity = AIR_DENSITY_SEA_LEVEL * (float) Math.exp(-altitude / 9000.0f);
        float dynamicPressure = 0.5f * speed * speed * airDensity * area;

        Vector3 lift = liftDirection.mul(liftCoeff * dynamicPressure);
        Vector3 drag = dragDirection.mul(dragCoeff * dynamicPressure);

        body.addForceAtPoint(lift.add(drag), centerOfPressure);
    }

    private float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
