package flightbattlesimulator;

import java.util.ArrayList;
import java.util.List;
import org.lwjgl.opengl.GL11;

public class Plane extends RigidBody {
    private final Engine engine;
    private final List<WingSurface> wingElements = new ArrayList<>();

    // Control inputs (-1..1)
    private float pitchInput = 0.0f;
    private float rollInput = 0.0f;
    private float yawInput = 0.0f;

    // Ground collision hitbox
    private float collisionHalfHeight = 0.5f;
    private boolean onGround = true;

    // FBX orientation correction
    private float modelPitchOffset = -90.0f;

    // Stability tuning
    private float stallSpeed = 8.0f;
    private float fullControlSpeed = 35.0f;

    // Health system
    private float currentHP = 100.0f;
    private float maxHP = 100.0f;

    // Visual
    private float colorR = 1.0f;
    private float colorG = 0.2f;
    private float colorB = 0.2f;

    // 3D Model
    private ModelLoader modelLoader;

    public Plane() {
        setMass(1200.0f);
        setInertia(1800.0f, 2600.0f, 1600.0f);

        engine = new Engine(6500.0f);

        // Main wings
        wingElements.add(new WingSurface(new Vector3(-4.0f, 0.0f, -1.5f), new Vector3(0.0f, 1.0f, 0.0f), 5.5f, 1.6f, 0.0f, 5.0f, 0.025f));
        wingElements.add(new WingSurface(new Vector3(4.0f, 0.0f, -1.5f), new Vector3(0.0f, 1.0f, 0.0f), 5.5f, 1.6f, 0.0f, 5.0f, 0.025f));

        // Ailerons
        wingElements.add(new WingSurface(new Vector3(-5.2f, 0.0f, -0.8f), new Vector3(0.0f, 1.0f, 0.0f), 2.2f, 0.9f, 0.35f, 4.5f, 0.03f));
        wingElements.add(new WingSurface(new Vector3(5.2f, 0.0f, -0.8f), new Vector3(0.0f, 1.0f, 0.0f), 2.2f, 0.9f, 0.35f, 4.5f, 0.03f));

        // Elevator and rudder
        wingElements.add(new WingSurface(new Vector3(0.0f, 0.2f, -7.0f), new Vector3(0.0f, 1.0f, 0.0f), 3.0f, 1.2f, 0.4f, 4.2f, 0.035f));
        wingElements.add(new WingSurface(new Vector3(0.0f, 1.3f, -7.1f), new Vector3(1.0f, 0.0f, 0.0f), 2.2f, 1.0f, 0.35f, 3.0f, 0.04f));
    }

    public float getThrottle() {
        return engine.getThrottle();
    }

    public float getCurrentHP() { return currentHP; }
    public float getMaxHP() { return maxHP; }
    public void setMaxHP(float hp) { this.maxHP = hp; this.currentHP = hp; }

    public void takeDamage(float damage) {
        currentHP -= damage;
        if (currentHP < 0) currentHP = 0;
    }

    public void heal(float amount) {
        currentHP += amount;
        if (currentHP > maxHP) currentHP = maxHP;
    }

    public void setColor(float r, float g, float b) {
        this.colorR = r;
        this.colorG = g;
        this.colorB = b;
    }

    public void loadModel(String modelPath) {
        this.modelLoader = new ModelLoader();
        if (!this.modelLoader.loadModel(modelPath)) {
            System.err.println("Failed to load plane model, using placeholder");
            this.modelLoader = null;
        } else {
            float modelVerticalSpan = Math.max(this.modelLoader.getScaledHeight(), this.modelLoader.getScaledDepth());
            this.collisionHalfHeight = Math.max(0.5f, modelVerticalSpan * 0.5f);
            this.modelLoader.printModelBounds();
        }
    }

    public void cleanup() {
        if (modelLoader != null) {
            modelLoader.cleanup();
        }
    }

    public void increaseThrottle() {
        engine.increaseThrottle(0.01f);
    }

    public void decreaseThrottle() {
        engine.decreaseThrottle(0.01f);
    }

    public void setPitchInput(float input) {
        pitchInput = clamp(input, -1.0f, 1.0f);
    }

    public void setRollInput(float input) {
        rollInput = clamp(input, -1.0f, 1.0f);
    }

    public void setYawInput(float input) {
        yawInput = clamp(input, -1.0f, 1.0f);
    }

    public void update(Map map) {
        update(map, 1.0f / 60.0f);
    }

    public void update(Map map, float dt) {
        dt = clamp(dt, 1.0f / 240.0f, 1.0f / 20.0f);

        Vector3 bodyVelocity = inverseTransformDirection(velocity);
        float speed = velocity.length();
        float forwardSpeed = bodyVelocity.z;

        float authority = controlAuthority(speed);
        if (onGround && speed < stallSpeed * 1.2f) {
            authority = 0.0f;
        }

        // Wing control mapping: [0,1]=main wings, [2,3]=ailerons, [4]=elevator, [5]=rudder
        wingElements.get(2).setControlInput(rollInput * authority);
        wingElements.get(3).setControlInput(-rollInput * authority);
        wingElements.get(4).setControlInput(pitchInput * authority);
        wingElements.get(5).setControlInput(yawInput * authority);

        engine.applyForce(this);
        for (WingSurface wing : wingElements) {
            wing.applyForces(this);
        }

        // Body torque inputs + angular damping
        float pitchTorque = 2200.0f * pitchInput * authority;
        float yawTorque = 1600.0f * yawInput * authority;
        float rollTorque = 2800.0f * rollInput * authority;

        addRelativeTorque(new Vector3(
            pitchTorque - angularVelocity.x * 350.0f,
            yawTorque - angularVelocity.y * 220.0f,
            rollTorque - angularVelocity.z * 320.0f
        ));

        integrate(dt);

        // Ground collision/resting behavior
        float minAllowedY = map.getHeightAt(position.x, position.z) + collisionHalfHeight;
        if (position.y < minAllowedY) {
            position.y = minAllowedY;
            if (velocity.y < 0.0f) {
                velocity.y = 0.0f;
            }
            onGround = true;
        } else {
            onGround = false;
        }

        if (onGround) {
            float friction = (float) Math.pow(0.992f, dt * 60.0f);
            velocity.x *= friction;
            velocity.z *= friction;

            float angularFriction = (float) Math.pow(0.85f, dt * 60.0f);
            angularVelocity.x *= angularFriction;
            angularVelocity.z *= angularFriction;

            if (speed < 1.0f && engine.getThrottle() < 0.02f) {
                velocity.x = 0.0f;
                velocity.z = 0.0f;
                angularVelocity.x = 0.0f;
                angularVelocity.y = 0.0f;
                angularVelocity.z = 0.0f;

                orientation.x *= 0.9f;
                orientation.z *= 0.9f;
            }

            if (forwardSpeed < 0.5f && engine.getThrottle() < 0.05f) {
                // Prevent backward rolling at idle.
                velocity.x *= 0.95f;
                velocity.z *= 0.95f;
            }
        }
    }

    public void snapToGround(Map map) {
        float minAllowedY = map.getHeightAt(position.x, position.z) + collisionHalfHeight;
        position.y = minAllowedY;
        velocity.set(0.0f, 0.0f, 0.0f);
        angularVelocity.set(0.0f, 0.0f, 0.0f);
        onGround = true;
    }

    public void render() {
        GL11.glPushMatrix();

        GL11.glTranslatef(position.x, position.y, position.z);
        GL11.glRotatef(orientation.y, 0, 1, 0);  // Yaw
        GL11.glRotatef(orientation.x, 1, 0, 0);  // Pitch
        GL11.glRotatef(orientation.z, 0, 0, 1);  // Roll

        GL11.glColor3f(colorR, colorG, colorB);

        if (modelLoader != null && modelLoader.isLoaded()) {
            GL11.glRotatef(modelPitchOffset, 1, 0, 0);
            modelLoader.render();
        } else {
            renderPlaceholder();
        }

        GL11.glPopMatrix();
    }

    private float controlAuthority(float airspeed) {
        if (airspeed <= stallSpeed) {
            return 0.0f;
        }
        float value = (airspeed - stallSpeed) / (fullControlSpeed - stallSpeed);
        return clamp(value, 0.0f, 1.0f);
    }

    private void renderPlaceholder() {
        GL11.glBegin(GL11.GL_TRIANGLES);

        GL11.glColor3f(colorR, colorG, colorB);
        float fuseLength = 2.0f;
        float fuseRadius = 0.2f;

        GL11.glColor3f(colorR + 0.2f, colorG, colorB);
        GL11.glVertex3f(0, fuseRadius, fuseLength / 2);
        GL11.glVertex3f(fuseRadius, 0, -fuseLength / 4);
        GL11.glVertex3f(0, -fuseRadius, -fuseLength / 4);

        GL11.glVertex3f(0, fuseRadius, fuseLength / 2);
        GL11.glVertex3f(0, -fuseRadius, -fuseLength / 4);
        GL11.glVertex3f(-fuseRadius, 0, -fuseLength / 4);

        GL11.glVertex3f(0, fuseRadius, fuseLength / 2);
        GL11.glVertex3f(-fuseRadius, 0, -fuseLength / 4);
        GL11.glVertex3f(0, fuseRadius, -fuseLength / 4);

        GL11.glVertex3f(0, fuseRadius, fuseLength / 2);
        GL11.glVertex3f(0, fuseRadius, -fuseLength / 4);
        GL11.glVertex3f(fuseRadius, 0, -fuseLength / 4);

        GL11.glColor3f(colorR, colorG + 0.1f, colorB);
        GL11.glVertex3f(0, 0, -fuseLength / 2);
        GL11.glVertex3f(0, -fuseRadius, -fuseLength / 4);
        GL11.glVertex3f(fuseRadius, 0, -fuseLength / 4);

        GL11.glVertex3f(0, 0, -fuseLength / 2);
        GL11.glVertex3f(-fuseRadius, 0, -fuseLength / 4);
        GL11.glVertex3f(0, -fuseRadius, -fuseLength / 4);

        GL11.glVertex3f(0, 0, -fuseLength / 2);
        GL11.glVertex3f(0, fuseRadius, -fuseLength / 4);
        GL11.glVertex3f(-fuseRadius, 0, -fuseLength / 4);

        GL11.glVertex3f(0, 0, -fuseLength / 2);
        GL11.glVertex3f(fuseRadius, 0, -fuseLength / 4);
        GL11.glVertex3f(0, fuseRadius, -fuseLength / 4);

        GL11.glEnd();

        GL11.glColor3f(colorR - 0.2f, colorG, colorB);
        GL11.glBegin(GL11.GL_QUADS);
        float wingSpan = 4.0f;
        float wingChord = 1.0f;

        GL11.glNormal3f(0, 1, 0);
        GL11.glVertex3f(0, 0, -fuseLength / 4);
        GL11.glVertex3f(wingSpan / 2, 0, -fuseLength / 4);
        GL11.glVertex3f(wingSpan / 2, 0, -fuseLength / 4 + wingChord);
        GL11.glVertex3f(0, 0, -fuseLength / 4 + wingChord);

        GL11.glVertex3f(0, 0, -fuseLength / 4);
        GL11.glVertex3f(-wingSpan / 2, 0, -fuseLength / 4);
        GL11.glVertex3f(-wingSpan / 2, 0, -fuseLength / 4 + wingChord);
        GL11.glVertex3f(0, 0, -fuseLength / 4 + wingChord);

        GL11.glNormal3f(1, 0, 0);
        GL11.glVertex3f(0, 0, -fuseLength / 2);
        GL11.glVertex3f(0, 0.6f, -fuseLength / 2);
        GL11.glVertex3f(0, 0.6f, -fuseLength / 2 + 0.6f);
        GL11.glVertex3f(0, 0, -fuseLength / 2 + 0.6f);

        GL11.glEnd();
    }

    private float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
