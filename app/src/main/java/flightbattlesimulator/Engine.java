package flightbattlesimulator;

public class Engine {
    private float throttle = 0.0f;   // 0..1
    private final float maxThrust;

    public Engine(float maxThrust) {
        this.maxThrust = maxThrust;
    }

    public float getThrottle() {
        return throttle;
    }

    public void increaseThrottle(float step) {
        throttle = clamp(throttle + step, 0.0f, 1.0f);
    }

    public void decreaseThrottle(float step) {
        throttle = clamp(throttle - step, 0.0f, 1.0f);
    }

    public void applyForce(RigidBody rigidBody) {
        rigidBody.addRelativeForce(new Vector3(0.0f, 0.0f, -throttle * maxThrust));
    }

    private float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
