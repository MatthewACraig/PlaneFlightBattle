package flightbattlesimulator;

import org.lwjgl.*;
import org.lwjgl.glfw.*;
import org.lwjgl.opengl.*;
import org.lwjgl.stb.STBEasyFont;
import org.lwjgl.system.*;

import java.nio.*;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.system.MemoryStack.*;

public class App {
    private long window;
    private Plane plane;
    private Map map;
    private float cameraDistance = 22.0f;
    private float cameraHeight = 6.0f;
    private float cameraLookAhead = 8.0f;
    private float cameraLookDown = 2.0f;
    
    // Camera modes
    private enum CameraMode { THIRD_PERSON, COCKPIT }
    private CameraMode cameraMode = CameraMode.THIRD_PERSON;

    // Overlay / diagnostics
    private boolean showFps = false;
    private int currentFps = 0;
    private int fpsFrameCounter = 0;
    private double fpsTimer = 0.0;
    private final ByteBuffer fpsVertexBuffer = BufferUtils.createByteBuffer(64 * 1024);
    
    public static void main(String[] args) {
        new App().run();
    }
    
    public void run() {
        System.out.println("Initializing Flight Battle Simulator...");
        
        if (!init()) {
            System.err.println("Failed to initialize application");
            return;
        }
        
        loop();
        cleanup();
        System.out.println("Application closed");
    }
    
    private boolean init() {
        // Setup an error callback
        GLFWErrorCallback.createPrint(System.err).set();
        
        // Initialize GLFW
        if (!glfwInit()) {
            throw new IllegalStateException("Unable to initialize GLFW");
        }
        
        // Configure GLFW
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 0);
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);
        
        // Create window
        int width = 1200;
        int height = 800;
        window = glfwCreateWindow(width, height, "Flight Battle Simulator", 0, 0);
        if (window == 0) {
            throw new RuntimeException("Failed to create GLFW window");
        }
        
        // Setup a key callback
        glfwSetKeyCallback(window, (w, key, scancode, action, mods) -> {
            if (key == GLFW_KEY_ESCAPE && action == GLFW_RELEASE) {
                glfwSetWindowShouldClose(w, true);
            }
            if (key == GLFW_KEY_SPACE && action == GLFW_RELEASE) {
                // Toggle camera mode
                cameraMode = (cameraMode == CameraMode.THIRD_PERSON) ? CameraMode.COCKPIT : CameraMode.THIRD_PERSON;
                System.out.println("Camera mode: " + cameraMode);
            }
            if (key == GLFW_KEY_0 && action == GLFW_RELEASE) {
                showFps = !showFps;
                System.out.println("FPS overlay: " + (showFps ? "ON" : "OFF"));
            }
        });
        
        // Make the OpenGL context current
        glfwMakeContextCurrent(window);
        
        // Enable v-sync
        glfwSwapInterval(1);
        
        // Make the window visible
        glfwShowWindow(window);
        
        // This line is critical for LWJGL's interop with GLFW
        GLCapabilities cap = GL.createCapabilities();
        if (!cap.OpenGL11) {
            System.err.println("OpenGL 1.1 is not supported!");
            return false;
        }

        String gpuVendor = glGetString(GL_VENDOR);
        String gpuRenderer = glGetString(GL_RENDERER);
        String gpuVersion = glGetString(GL_VERSION);
        System.out.println("OpenGL Vendor: " + gpuVendor);
        System.out.println("OpenGL Renderer: " + gpuRenderer);
        System.out.println("OpenGL Version: " + gpuVersion);
        
        // Setup OpenGL
        glEnable(GL_DEPTH_TEST);
        glEnable(GL_LIGHTING);
        glEnable(GL_LIGHT0);
        glEnable(GL_COLOR_MATERIAL);
        
        // Setup lighting
        try (MemoryStack stack = stackPush()) {
            FloatBuffer light_position = stack.callocFloat(4).put(new float[]{5.0f, 10.0f, 5.0f, 0.0f});
            light_position.flip();
            GL11.glLightfv(GL11.GL_LIGHT0, GL11.GL_POSITION, light_position);
            
            FloatBuffer light_ambient = stack.callocFloat(4).put(new float[]{0.2f, 0.2f, 0.2f, 1.0f});
            light_ambient.flip();
            GL11.glLightfv(GL11.GL_LIGHT0, GL11.GL_AMBIENT, light_ambient);
            
            FloatBuffer light_diffuse = stack.callocFloat(4).put(new float[]{1.0f, 1.0f, 1.0f, 1.0f});
            light_diffuse.flip();
            GL11.glLightfv(GL11.GL_LIGHT0, GL11.GL_DIFFUSE, light_diffuse);
        }
        
        map = new Map(200.0f);
        map.setY(-5.0f);

        // Initialize game objects
        plane = new Plane();
        plane.setPosition(0, 0, 0);
        plane.loadModel("plane/planedone.fbx");  // Load the 3D aircraft model
        plane.snapToGround(map);
        
        return true;
    }
    
    private void loop() {
        // Set the clear color
        glClearColor(0.6f, 0.8f, 1.0f, 0.0f);  // Light blue sky
        double lastFrameTime = glfwGetTime();
        
        while (!glfwWindowShouldClose(window)) {
            // Poll events
            glfwPollEvents();

            double currentTime = glfwGetTime();
            float dt = (float) (currentTime - lastFrameTime);
            lastFrameTime = currentTime;

            fpsFrameCounter++;
            fpsTimer += dt;
            if (fpsTimer >= 1.0) {
                currentFps = fpsFrameCounter;
                fpsFrameCounter = 0;
                fpsTimer = 0.0;
            }
            
            // Handle keyboard input for plane control (axes)
            float rollInput = 0.0f;
            float pitchInput = 0.0f;
            float yawInput = 0.0f;

            if (glfwGetKey(window, GLFW_KEY_LEFT) == GLFW_PRESS) {
                rollInput = 1.0f;
            }
            if (glfwGetKey(window, GLFW_KEY_RIGHT) == GLFW_PRESS) {
                rollInput = -1.0f;
            }
            if (glfwGetKey(window, GLFW_KEY_UP) == GLFW_PRESS) {
                pitchInput = 1.0f;
            }
            if (glfwGetKey(window, GLFW_KEY_DOWN) == GLFW_PRESS) {
                pitchInput = -1.0f;
            }

            // Rudder (yaw)
            if (glfwGetKey(window, GLFW_KEY_A) == GLFW_PRESS) {
                yawInput = 1.0f;
            }
            if (glfwGetKey(window, GLFW_KEY_D) == GLFW_PRESS) {
                yawInput = -1.0f;
            }

            plane.setRollInput(rollInput);
            plane.setPitchInput(pitchInput);
            plane.setYawInput(yawInput);

            // Throttle control
            if (glfwGetKey(window, GLFW_KEY_W) == GLFW_PRESS) {
                plane.increaseThrottle();
            }
            if (glfwGetKey(window, GLFW_KEY_S) == GLFW_PRESS) {
                plane.decreaseThrottle();
            }
            
            // Update
            // plane.accelerate();  // Disabled - plane sits still by default
            plane.update(map, dt);
            
            // Clear the framebuffer
            glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
            
            // Set up projection matrix
            int width;
            int height;
            try (MemoryStack stack = stackPush()) {
                IntBuffer pWidth = stack.mallocInt(1);
                IntBuffer pHeight = stack.mallocInt(1);
                glfwGetFramebufferSize(window, pWidth, pHeight);
                width = pWidth.get(0);
                height = pHeight.get(0);
            }

            glViewport(0, 0, width, height);
            glMatrixMode(GL_PROJECTION);
            glLoadIdentity();
            float aspect = (float) width / (float) height;
            float fov = 45.0f;
            float f = (float) (1.0 / Math.tan(Math.toRadians(fov / 2.0)));
            float near = 0.1f;
            float far = 1000.0f;
            
            glMatrixMode(GL_PROJECTION);
            glLoadIdentity();
            glFrustum(-aspect * near * f / f, aspect * near * f / f, -near / f, near / f, near, far);
            
            // Set up modelview matrix
            glMatrixMode(GL_MODELVIEW);
            glLoadIdentity();
            
            // Set up camera based on current mode
            if (cameraMode == CameraMode.THIRD_PERSON) {
                // Chase camera: behind the aircraft based on yaw/pitch, with height offset.
                float yawRad = (float) Math.toRadians(plane.getYaw());
                float pitchRad = (float) Math.toRadians(plane.getPitch());
                float rollRad = (float) Math.toRadians(plane.getRoll());

                // Plane forward vector from yaw/pitch.
                float forwardX = (float) (Math.sin(yawRad) * Math.cos(pitchRad));
                float forwardY = (float) Math.sin(pitchRad);
                float forwardZ = (float) (Math.cos(yawRad) * Math.cos(pitchRad));
                float forwardLen = (float) Math.sqrt(forwardX * forwardX + forwardY * forwardY + forwardZ * forwardZ);
                if (forwardLen > 0) {
                    forwardX /= forwardLen;
                    forwardY /= forwardLen;
                    forwardZ /= forwardLen;
                }

                // Camera alignment tweak: flip horizontal heading so chase view tracks tail/rudder side.
                float chaseForwardX = -forwardX;
                float chaseForwardY = forwardY;
                float chaseForwardZ = -forwardZ;

                // Up vector from roll so banking/turning is visible in camera orientation.
                float upX = (float) (-Math.sin(rollRad) * Math.cos(yawRad));
                float upY = (float) Math.cos(rollRad);
                float upZ = (float) (Math.sin(rollRad) * Math.sin(yawRad));

                // Camera position: a few units behind, slightly above plane.
                float camX = plane.getX() - chaseForwardX * cameraDistance;
                float camY = plane.getY() - chaseForwardY * cameraDistance + cameraHeight;
                float camZ = plane.getZ() - chaseForwardZ * cameraDistance;

                // Look slightly ahead of the plane to better show climb/dive and turn direction.
                float targetX = plane.getX() + chaseForwardX * cameraLookAhead;
                float targetY = plane.getY() + chaseForwardY * cameraLookAhead - cameraLookDown;
                float targetZ = plane.getZ() + chaseForwardZ * cameraLookAhead;

                lookAt(camX, camY, camZ, targetX, targetY, targetZ, upX, upY, upZ);
            } else {
                // Cockpit view: camera at plane position, looking forward
                float camX = plane.getX();
                float camY = plane.getY();
                float camZ = plane.getZ();
                
                // Calculate forward direction based on yaw and pitch
                float yawRad = (float) Math.toRadians(plane.getYaw());
                float pitchRad = (float) Math.toRadians(plane.getPitch());
                
                float forwardX = (float) (Math.sin(yawRad) * Math.cos(pitchRad));
                float forwardY = (float) Math.sin(pitchRad);
                float forwardZ = (float) (Math.cos(yawRad) * Math.cos(pitchRad));
                
                // Normalize forward direction
                float forwardLen = (float) Math.sqrt(forwardX*forwardX + forwardY*forwardY + forwardZ*forwardZ);
                forwardX /= forwardLen;
                forwardY /= forwardLen;
                forwardZ /= forwardLen;
                
                // Target is in front of the plane
                float targetX = camX + forwardX * 100;
                float targetY = camY + forwardY * 100;
                float targetZ = camZ + forwardZ * 100;
                
                // Calculate up vector (accounting for roll)
                float rollRad = (float) Math.toRadians(plane.getRoll());
                float upX = (float) (-Math.sin(rollRad) * Math.cos(yawRad));
                float upY = (float) Math.cos(rollRad);
                float upZ = (float) (Math.sin(rollRad) * Math.sin(yawRad));
                
                lookAt(camX, camY, camZ, targetX, targetY, targetZ, upX, upY, upZ);
            }
            
            // Render scene
            map.render();
            plane.render();

            if (showFps) {
                renderFpsOverlay(width, height);
            }
            
            // Display FPS and plane info
            displayInfo();
            
            // Swap buffers
            glfwSwapBuffers(window);
        }
    }
    
    private void cleanup() {
        if (plane != null) {
            plane.cleanup();
        }
        
        Callbacks.glfwFreeCallbacks(window);
        glfwDestroyWindow(window);
        glfwTerminate();
        glfwSetErrorCallback(null).free();
    }
    
    private void displayInfo() {
        // Keep console output quiet during runtime.
    }

    private void renderFpsOverlay(int width, int height) {
        String text = "FPS: " + currentFps;
        ByteBuffer textBuffer = MemoryUtil.memASCII(text, true);

        fpsVertexBuffer.clear();
        int numQuads = STBEasyFont.stb_easy_font_print(0.0f, 0.0f, textBuffer, null, fpsVertexBuffer);
        fpsVertexBuffer.flip();
        MemoryUtil.memFree(textBuffer);

        float textWidth = text.length() * 8.0f;
        float x = Math.max(8.0f, width - textWidth - 12.0f);
        float y = 12.0f;

        glMatrixMode(GL_PROJECTION);
        glPushMatrix();
        glLoadIdentity();
        glOrtho(0.0, width, height, 0.0, -1.0, 1.0);

        glMatrixMode(GL_MODELVIEW);
        glPushMatrix();
        glLoadIdentity();
        glTranslatef(x, y, 0.0f);

        glDisable(GL_LIGHTING);
        glDisable(GL_DEPTH_TEST);
        glColor3f(0.05f, 0.05f, 0.05f);

        glEnableClientState(GL_VERTEX_ARRAY);
        glVertexPointer(2, GL_FLOAT, 16, fpsVertexBuffer);
        glDrawArrays(GL_QUADS, 0, numQuads * 4);
        glDisableClientState(GL_VERTEX_ARRAY);

        glEnable(GL_DEPTH_TEST);
        glEnable(GL_LIGHTING);

        glPopMatrix();
        glMatrixMode(GL_PROJECTION);
        glPopMatrix();
        glMatrixMode(GL_MODELVIEW);
    }
    
    /**
     * Simple camera look at function
     */
    private void lookAt(float eyeX, float eyeY, float eyeZ,
                       float targetX, float targetY, float targetZ,
                       float upX, float upY, float upZ) {
        try (MemoryStack stack = stackPush()) {
            FloatBuffer fb = stack.mallocFloat(16);
            gluLookAt(eyeX, eyeY, eyeZ, targetX, targetY, targetZ, upX, upY, upZ, fb);
            glMultMatrixf(fb);
        }
    }
    
    /**
     * Simple gluLookAt implementation
     */
    private static void gluLookAt(float eyeX, float eyeY, float eyeZ,
                                 float centerX, float centerY, float centerZ,
                                 float upX, float upY, float upZ,
                                 FloatBuffer matrix) {
        float[] forward = new float[3];
        forward[0] = centerX - eyeX;
        forward[1] = centerY - eyeY;
        forward[2] = centerZ - eyeZ;
        normalize(forward);
        
        float[] right = new float[3];
        cross(forward, new float[]{upX, upY, upZ}, right);
        normalize(right);
        
        float[] up = new float[3];
        cross(right, forward, up);
        normalize(up);
        
        matrix.put(new float[]{
            right[0], up[0], -forward[0], 0,
            right[1], up[1], -forward[1], 0,
            right[2], up[2], -forward[2], 0,
            -dot(right, new float[]{eyeX, eyeY, eyeZ}),
            -dot(up, new float[]{eyeX, eyeY, eyeZ}),
            dot(forward, new float[]{eyeX, eyeY, eyeZ}),
            1
        });
        matrix.flip();
    }
    
    private static void normalize(float[] v) {
        float len = (float) Math.sqrt(v[0]*v[0] + v[1]*v[1] + v[2]*v[2]);
        if (len > 0) {
            v[0] /= len;
            v[1] /= len;
            v[2] /= len;
        }
    }
    
    private static void cross(float[] a, float[] b, float[] result) {
        result[0] = a[1]*b[2] - a[2]*b[1];
        result[1] = a[2]*b[0] - a[0]*b[2];
        result[2] = a[0]*b[1] - a[1]*b[0];
    }
    
    private static float dot(float[] a, float[] b) {
        return a[0]*b[0] + a[1]*b[1] + a[2]*b[2];
    }
}
