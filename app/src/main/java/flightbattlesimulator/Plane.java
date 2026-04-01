package flightbattlesimulator;

import java.nio.FloatBuffer;
import org.lwjgl.opengl.GL11;
import org.lwjgl.BufferUtils;

public class Plane {
        // Position
        private float x = 0, y = 0, z = 0;
        
        // Movement
        private float speed = 0;
        private float velocityY = 0;  // Vertical velocity for gravity
        private float maxSpeed = 0.3f;
        private float acceleration = 0.03f;
        private float friction = 0.95f;
        private float gravity = 0.15f;  // Gravity strength
        
        // Rotation (Pitch, Roll, Yaw)
        private float pitch = 0;    // Rotation around X-axis (nose up/down)
        private float roll = 0;     // Rotation around Z-axis (wing tilt)
        private float yaw = 0;      // Rotation around Y-axis (turning left/right)
        private float turnSpeed = 3.0f;
        
        // Health system
        private float currentHP = 100.0f;
        private float maxHP = 100.0f;
        
        // Visual
        private float colorR = 1.0f, colorG = 0.2f, colorB = 0.2f;
        
        // 3D Model
        private ModelLoader modelLoader;

        public float getX() { return x; }
        public float getY() { return y; }
        public float getZ() { return z; }
        public float getPitch() { return pitch; }
        public float getRoll() { return roll; }
        public float getYaw() { return yaw; }
        
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
                // Print model bounds for debugging
                this.modelLoader.printModelBounds();
            }
        }
        
        public void setPosition(float x, float y, float z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        public void cleanup() {
            // Clean up model resources
            if (modelLoader != null) {
                modelLoader.cleanup();
            }
        }

        public void accelerate() {
            if (speed < maxSpeed) speed += acceleration;
        }

        public void decelerate() {
            if (speed > -maxSpeed) speed -= acceleration;
        }

        public void turnLeft() {
            yaw += turnSpeed;
        }

        public void turnRight() {
            yaw -= turnSpeed;
        }
        
        public void pitchUp() {
            pitch += turnSpeed;
        }
        
        public void pitchDown() {
            pitch -= turnSpeed;
        }
        
        public void rollLeft() {
            roll += turnSpeed;
        }
        
        public void rollRight() {
            roll -= turnSpeed;
        }

        public void update(Map map) {
            // Update position based on speed and yaw
            x += speed * Math.sin(Math.toRadians(yaw));
            z += speed * Math.cos(Math.toRadians(yaw));
            
            // Apply friction to speed
            speed *= friction;
            
            // Apply gravity
            velocityY -= gravity;
            y += velocityY;
            
            // Collision with ground
            float groundHeight = map.getHeightAt(x, z) + 0.5f;  // +0.5f for plane height
            if (y < groundHeight) {
                y = groundHeight;
                velocityY = 0;  // Stop falling
            }
        }

        public void render() {
            GL11.glPushMatrix();
            
            // Translate to position
            GL11.glTranslatef(x, y, z);
            
            // Apply rotations (Yaw -> Pitch -> Roll order)
            GL11.glRotatef(yaw, 0, 1, 0);      // Yaw around Y-axis
            GL11.glRotatef(pitch, 1, 0, 0);    // Pitch around X-axis
            GL11.glRotatef(roll, 0, 0, 1);     // Roll around Z-axis
            
            // Set color
            GL11.glColor3f(colorR, colorG, colorB);
            
            // Render the 3D model if loaded, otherwise use placeholder
            if (modelLoader != null && modelLoader.isLoaded()) {
                modelLoader.render();
            } else {
                renderPlaceholder();
            }
            
            GL11.glPopMatrix();
        }
        
        private void renderPlaceholder() {
            // Aircraft-like placeholder with fuselage and wings
            GL11.glBegin(GL11.GL_TRIANGLES);
            
            // Fuselage (cylindrical body) - render as cone-like shape
            GL11.glColor3f(colorR, colorG, colorB);
            float fuseLength = 2.0f;
            float fuseRadius = 0.2f;
            
            // Nose cone
            GL11.glColor3f(colorR + 0.2f, colorG, colorB);
            GL11.glVertex3f(0, fuseRadius, fuseLength / 2);  // Nose tip (pointing forward in Z)
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
            
            // Tail
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
            
            // Wings (flat rectangles) - MUCH WIDER NOW
            GL11.glColor3f(colorR - 0.2f, colorG, colorB);
            GL11.glBegin(GL11.GL_QUADS);
            float wingSpan = 4.0f;    // Doubled from 2.0f
            float wingChord = 1.0f;   // Doubled from 0.5f
            
            // Right wing
            GL11.glNormal3f(0, 1, 0);
            GL11.glVertex3f(0, 0, -fuseLength / 4);
            GL11.glVertex3f(wingSpan / 2, 0, -fuseLength / 4);
            GL11.glVertex3f(wingSpan / 2, 0, -fuseLength / 4 + wingChord);
            GL11.glVertex3f(0, 0, -fuseLength / 4 + wingChord);
            
            // Left wing
            GL11.glVertex3f(0, 0, -fuseLength / 4);
            GL11.glVertex3f(-wingSpan / 2, 0, -fuseLength / 4);
            GL11.glVertex3f(-wingSpan / 2, 0, -fuseLength / 4 + wingChord);
            GL11.glVertex3f(0, 0, -fuseLength / 4 + wingChord);
            
            // Tail wing (vertical stabilizer)
            GL11.glNormal3f(1, 0, 0);
            GL11.glVertex3f(0, 0, -fuseLength / 2);
            GL11.glVertex3f(0, 0.6f, -fuseLength / 2);
            GL11.glVertex3f(0, 0.6f, -fuseLength / 2 + 0.6f);
            GL11.glVertex3f(0, 0, -fuseLength / 2 + 0.6f);
            
            GL11.glEnd();
        }
}

