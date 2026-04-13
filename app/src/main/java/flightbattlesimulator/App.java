package flightbattlesimulator;

import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.Callbacks;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.stb.STBEasyFont;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.system.MemoryStack.stackPush;

public class App {
    private static final int WINDOW_WIDTH = 1280;
    private static final int WINDOW_HEIGHT = 800;
    private static final int NET_PORT = 5000;

    private enum ScreenState {
        MENU,
        WAITING,
        COUNTDOWN,
        PLAYING,
        PAUSED,
        GAME_OVER
    }

    private enum Role {
        NONE,
        PILOT,
        TURRET
    }

    private long window;
    private Map map;

    private ScreenState screenState = ScreenState.MENU;
    private Role localRole = Role.NONE;
    private NetworkPeer networkPeer;

    private final ByteBuffer textVertexBuffer = BufferUtils.createByteBuffer(64 * 1024);
    private final GameState gameState = new GameState();

    private String statusText = "Press H to Host (Pilot) or J to Join (Turret)";
    private String gameOverText = "";
    private float countdownTimer = 3.0f;
    private ScreenState pausedReturnState = ScreenState.PLAYING;

    private boolean cursorCaptured = false;
    private boolean firstMouseSample = true;
    private double lastMouseX;
    private double lastMouseY;

    private boolean prevLeftMouse = false;
    private boolean prevUiLeftMouse = false;
    private float localFireCooldown = 0.0f;
    private float lastTargetAiPhase = 0.0f;

    private final List<BulletTrace> bulletTraces = new ArrayList<>();

    // Turret state sent from client to host
    private float remoteTurretYaw = 180.0f;
    private float remoteTurretPitch = -5.0f;
    private boolean remoteTurretFired = false;

    // Local turret camera state when playing as client
    private float localTurretYaw = 180.0f;
    private float localTurretPitch = -5.0f;

    private int currentFps = 0;
    private int fpsFrameCounter = 0;
    private double fpsTimer = 0.0;

    public static void main(String[] args) {
        new App().run();
    }

    public void run() {
        if (!init()) {
            return;
        }

        loop();
        cleanup();
    }

    private boolean init() {
        GLFWErrorCallback.createPrint(System.err).set();
        if (!glfwInit()) {
            throw new IllegalStateException("Unable to initialize GLFW");
        }

        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 0);
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);

        window = glfwCreateWindow(WINDOW_WIDTH, WINDOW_HEIGHT, "Plane Fight Battle", 0, 0);
        if (window == 0) {
            throw new RuntimeException("Failed to create GLFW window");
        }

        glfwSetKeyCallback(window, this::onKey);

        glfwMakeContextCurrent(window);
        glfwSwapInterval(1);
        glfwShowWindow(window);

        GLCapabilities caps = GL.createCapabilities();
        if (!caps.OpenGL11) {
            System.err.println("OpenGL 1.1 is not supported.");
            return false;
        }

        glEnable(GL_DEPTH_TEST);
        glEnable(GL_LIGHTING);
        glEnable(GL_LIGHT0);
        glEnable(GL_COLOR_MATERIAL);

        try (MemoryStack stack = stackPush()) {
            FloatBuffer lightPos = stack.callocFloat(4).put(new float[]{5.0f, 15.0f, 10.0f, 0.0f});
            lightPos.flip();
            glLightfv(GL_LIGHT0, GL_POSITION, lightPos);

            FloatBuffer ambient = stack.callocFloat(4).put(new float[]{0.25f, 0.25f, 0.25f, 1.0f});
            ambient.flip();
            glLightfv(GL_LIGHT0, GL_AMBIENT, ambient);

            FloatBuffer diffuse = stack.callocFloat(4).put(new float[]{1.0f, 1.0f, 1.0f, 1.0f});
            diffuse.flip();
            glLightfv(GL_LIGHT0, GL_DIFFUSE, diffuse);
        }

        map = new Map(1400.0f);
        map.setY(-2.5f);
        resetRound();
        return true;
    }

    private void loop() {
        glClearColor(0.5f, 0.75f, 1.0f, 0.0f);
        double lastTime = glfwGetTime();

        while (!glfwWindowShouldClose(window)) {
            glfwPollEvents();

            double now = glfwGetTime();
            float dt = (float) (now - lastTime);
            lastTime = now;
            dt = clamp(dt, 1.0f / 240.0f, 1.0f / 20.0f);

            updateFps(dt);
            if (localFireCooldown > 0.0f) {
                localFireCooldown -= dt;
            }

            processNetworkMessages();
            updateState(dt);

            int width;
            int height;
            try (MemoryStack stack = stackPush()) {
                IntBuffer w = stack.mallocInt(1);
                IntBuffer h = stack.mallocInt(1);
                glfwGetFramebufferSize(window, w, h);
                width = Math.max(1, w.get(0));
                height = Math.max(1, h.get(0));
            }

            render(width, height);
            glfwSwapBuffers(window);
        }
    }

    private void updateFps(float dt) {
        fpsFrameCounter++;
        fpsTimer += dt;
        if (fpsTimer >= 1.0) {
            currentFps = fpsFrameCounter;
            fpsFrameCounter = 0;
            fpsTimer = 0.0;
        }
    }

    private void updateState(float dt) {
        updateCursorCapture();
        updateBulletTraces(dt);

        if (screenState == ScreenState.PAUSED) {
            updatePauseMenuInput();
            return;
        }

        if (screenState == ScreenState.WAITING) {
            if (networkPeer != null && networkPeer.isConnected()) {
                if (localRole == Role.PILOT) {
                    startCountdown();
                }
            }
            return;
        }

        if (screenState == ScreenState.COUNTDOWN) {
            if (localRole == Role.PILOT) {
                countdownTimer -= dt;
                if (networkPeer != null) {
                    networkPeer.send("COUNTDOWN|" + countdownTimer);
                }
                if (countdownTimer <= 0.0f) {
                    screenState = ScreenState.PLAYING;
                    if (networkPeer != null) {
                        networkPeer.send("START");
                    }
                }
            }
            return;
        }

        if (screenState == ScreenState.PLAYING) {
            if (localRole == Role.PILOT) {
                updatePilotGameplay(dt);
                if (remoteTurretFired) {
                    handleTurretShot(remoteTurretYaw, remoteTurretPitch);
                    remoteTurretFired = false;
                }

                if (gameState.planeHp <= 0.0f) {
                    endGame("Turret Wins! Plane destroyed.", "TURRET");
                } else if (gameState.destroyedTargetCount() == gameState.targets.size()) {
                    endGame("Pilot Wins! All targets destroyed.", "PILOT");
                }

                sendSnapshotToClient();
            } else if (localRole == Role.TURRET) {
                updateTurretControls(dt);
            }
        }
    }

    private void updatePilotGameplay(float dt) {
        Vector2 mouseDelta = sampleMouseDelta();
        float sensitivity = 0.1f;

        gameState.planeYaw -= mouseDelta.x * sensitivity;
        gameState.planePitch -= mouseDelta.y * sensitivity;
        gameState.planePitch = clamp(gameState.planePitch, -35.0f, 35.0f);

        Vector3 forward = directionFromYawPitch(gameState.planeYaw, gameState.planePitch);
        gameState.planePosition.add(forward.mul(gameState.planeSpeed * dt));

        gameState.planePosition.y = clamp(gameState.planePosition.y, 12.0f, 120.0f);
        gameState.planePosition.x = clamp(gameState.planePosition.x, -620.0f, 620.0f);
        gameState.planePosition.z = clamp(gameState.planePosition.z, -620.0f, 620.0f);

        collectTargetsByContact();
        updateLastTargetEvasion(dt);
    }

    private void updateTurretControls(float dt) {
        Vector2 mouseDelta = sampleMouseDelta();
        float sensitivity = 0.12f;

        localTurretYaw -= mouseDelta.x * sensitivity;
        localTurretPitch -= mouseDelta.y * sensitivity;
        localTurretPitch = clamp(localTurretPitch, -70.0f, 35.0f);

        if (networkPeer != null && networkPeer.isConnected()) {
            networkPeer.send("AIM|" + localTurretYaw + "|" + localTurretPitch);
        }

        boolean leftPressed = glfwGetMouseButton(window, GLFW_MOUSE_BUTTON_LEFT) == GLFW_PRESS;
        boolean justPressed = leftPressed && !prevLeftMouse;
        prevLeftMouse = leftPressed;

        if (justPressed && localFireCooldown <= 0.0f) {
            localFireCooldown = 0.15f;
            addBulletTrace(
                gameState.turretPosition.copy(),
                gameState.turretPosition.copy().add(directionFromYawPitch(localTurretYaw, localTurretPitch).mul(700.0f)),
                1.0f,
                0.9f,
                0.1f
            );
            if (networkPeer != null && networkPeer.isConnected()) {
                networkPeer.send("FIRE_TURRET");
            }
        }
    }

    private void collectTargetsByContact() {
        float captureRadius = 8.0f;
        float captureRadiusSq = captureRadius * captureRadius;

        for (int i = 0; i < gameState.targets.size(); i++) {
            if (gameState.destroyedTargets[i]) {
                continue;
            }

            Vector3 offset = gameState.targets.get(i).copy().sub(gameState.planePosition);
            if (offset.lengthSquared() <= captureRadiusSq) {
                gameState.destroyedTargets[i] = true;
            }
        }
    }

    private void updateLastTargetEvasion(float dt) {
        if (gameState.destroyedTargetCount() != gameState.targets.size() - 1) {
            lastTargetAiPhase = 0.0f;
            return;
        }

        int targetIndex = findLastRemainingTargetIndex();
        if (targetIndex < 0) {
            return;
        }

        Vector3 targetPos = gameState.targets.get(targetIndex);
        Vector3 awayFromPlane = targetPos.copy().sub(gameState.planePosition);
        float distance = awayFromPlane.length();
        float detectRadius = 230.0f;

        if (distance <= 0.001f || distance > detectRadius) {
            return;
        }

        awayFromPlane.normalize();
        lastTargetAiPhase += dt * 2.3f;

        Vector3 lateral = new Vector3(-awayFromPlane.z, 0.0f, awayFromPlane.x).normalize();
        lateral.mul((float) Math.sin(lastTargetAiPhase) * 0.55f);

        float urgency = 1.0f - (distance / detectRadius);
        float speed = 10.0f + urgency * 16.0f;

        Vector3 movement = awayFromPlane.add(lateral).normalize().mul(speed * dt);
        targetPos.add(movement);

        targetPos.y = clamp(targetPos.y + (float) Math.sin(lastTargetAiPhase * 1.9f) * 1.4f * dt, 14.0f, 32.0f);
        targetPos.x = clamp(targetPos.x, -560.0f, 560.0f);
        targetPos.z = clamp(targetPos.z, -560.0f, 560.0f);
    }

    private void handleTurretShot(float yaw, float pitch) {
        Vector3 turretOrigin = gameState.turretPosition.copy();
        Vector3 dir = directionFromYawPitch(yaw, pitch);
        addBulletTrace(turretOrigin.copy(), turretOrigin.copy().add(dir.copy().mul(700.0f)), 1.0f, 0.9f, 0.1f);
        if (raySphereHit(turretOrigin, dir, gameState.planePosition, 5.5f)) {
            gameState.planeHp -= 10.0f;
            gameState.planeHp = Math.max(0.0f, gameState.planeHp);
        }
    }

    private void sendSnapshotToClient() {
        if (networkPeer == null || !networkPeer.isConnected()) {
            return;
        }

        int lastTargetIndex = findLastRemainingTargetIndex();
        float lastTargetX = 0.0f;
        float lastTargetY = 0.0f;
        float lastTargetZ = 0.0f;
        if (lastTargetIndex >= 0) {
            Vector3 target = gameState.targets.get(lastTargetIndex);
            lastTargetX = target.x;
            lastTargetY = target.y;
            lastTargetZ = target.z;
        }

        String message = "SNAP|"
            + gameState.planePosition.x + "|"
            + gameState.planePosition.y + "|"
            + gameState.planePosition.z + "|"
            + gameState.planeYaw + "|"
            + gameState.planePitch + "|"
            + gameState.planeHp + "|"
            + targetMask() + "|"
            + lastTargetX + "|"
            + lastTargetY + "|"
            + lastTargetZ;

        networkPeer.send(message);
    }

    private int findLastRemainingTargetIndex() {
        for (int i = 0; i < gameState.destroyedTargets.length; i++) {
            if (!gameState.destroyedTargets[i]) {
                return i;
            }
        }
        return -1;
    }

    private int targetMask() {
        int mask = 0;
        for (int i = 0; i < gameState.destroyedTargets.length; i++) {
            if (gameState.destroyedTargets[i]) {
                mask |= (1 << i);
            }
        }
        return mask;
    }

    private void applyTargetMask(int mask) {
        for (int i = 0; i < gameState.destroyedTargets.length; i++) {
            gameState.destroyedTargets[i] = (mask & (1 << i)) != 0;
        }
    }

    private void startCountdown() {
        screenState = ScreenState.COUNTDOWN;
        countdownTimer = 3.0f;
        statusText = "Player connected. Starting...";
        if (networkPeer != null) {
            networkPeer.send("COUNTDOWN|" + countdownTimer);
        }
    }

    private void endGame(String text, String winnerTag) {
        screenState = ScreenState.GAME_OVER;
        gameOverText = text;
        if (localRole == Role.PILOT && networkPeer != null && networkPeer.isConnected()) {
            networkPeer.send("END|" + winnerTag);
        }
    }

    private void processNetworkMessages() {
        if (networkPeer == null) {
            return;
        }

        List<String> messages = networkPeer.drainMessages();
        for (String line : messages) {
            if (line == null || line.isBlank()) {
                continue;
            }

            String[] parts = line.split("\\|");
            if (parts.length == 0) {
                continue;
            }

            if (localRole == Role.PILOT) {
                if ("AIM".equals(parts[0]) && parts.length >= 3) {
                    remoteTurretYaw = parseFloatSafe(parts[1], remoteTurretYaw);
                    remoteTurretPitch = parseFloatSafe(parts[2], remoteTurretPitch);
                } else if ("FIRE_TURRET".equals(parts[0])) {
                    remoteTurretFired = true;
                }
            } else if (localRole == Role.TURRET) {
                if ("COUNTDOWN".equals(parts[0]) && parts.length >= 2) {
                    countdownTimer = parseFloatSafe(parts[1], 3.0f);
                    screenState = ScreenState.COUNTDOWN;
                } else if ("START".equals(parts[0])) {
                    screenState = ScreenState.PLAYING;
                } else if ("SNAP".equals(parts[0]) && parts.length >= 8) {
                    gameState.planePosition.set(
                        parseFloatSafe(parts[1], gameState.planePosition.x),
                        parseFloatSafe(parts[2], gameState.planePosition.y),
                        parseFloatSafe(parts[3], gameState.planePosition.z)
                    );
                    gameState.planeYaw = parseFloatSafe(parts[4], gameState.planeYaw);
                    gameState.planePitch = parseFloatSafe(parts[5], gameState.planePitch);
                    gameState.planeHp = parseFloatSafe(parts[6], gameState.planeHp);
                    applyTargetMask((int) parseFloatSafe(parts[7], targetMask()));

                    if (parts.length >= 11) {
                        int lastTargetIndex = findLastRemainingTargetIndex();
                        if (lastTargetIndex >= 0) {
                            gameState.targets.get(lastTargetIndex).set(
                                parseFloatSafe(parts[8], gameState.targets.get(lastTargetIndex).x),
                                parseFloatSafe(parts[9], gameState.targets.get(lastTargetIndex).y),
                                parseFloatSafe(parts[10], gameState.targets.get(lastTargetIndex).z)
                            );
                        }
                    }
                } else if ("END".equals(parts[0]) && parts.length >= 2) {
                    screenState = ScreenState.GAME_OVER;
                    gameOverText = "PILOT".equals(parts[1])
                        ? "Pilot Wins! All targets destroyed."
                        : "Turret Wins! Plane destroyed.";
                }
            }
        }
    }

    private void render(int width, int height) {
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

        setPerspective(width, height);
        setupCameraForCurrentState();

        map.render();
        renderTurret();
        renderPlane();
        renderTargets();
        renderBulletTraces();

        renderOverlay(width, height);
    }

    private void setupCameraForCurrentState() {
        glMatrixMode(GL_MODELVIEW);
        glLoadIdentity();

        if (screenState == ScreenState.MENU || screenState == ScreenState.WAITING || screenState == ScreenState.GAME_OVER) {
            lookAt(0.0f, 55.0f, 145.0f, 0.0f, 15.0f, 0.0f, 0.0f, 1.0f, 0.0f);
            return;
        }

        if (localRole == Role.PILOT) {
            Vector3 forward = directionFromYawPitch(gameState.planeYaw, gameState.planePitch);
            Vector3 cameraPos = gameState.planePosition.copy().sub(forward.copy().mul(18.0f));
            cameraPos.y += 6.0f;

            Vector3 lookTarget = gameState.planePosition.copy().add(forward.copy().mul(15.0f));
            lookAt(cameraPos.x, cameraPos.y, cameraPos.z, lookTarget.x, lookTarget.y, lookTarget.z, 0.0f, 1.0f, 0.0f);
        } else {
            float yaw = (screenState == ScreenState.PLAYING) ? localTurretYaw : 180.0f;
            float pitch = (screenState == ScreenState.PLAYING) ? localTurretPitch : -5.0f;

            Vector3 origin = gameState.turretPosition;
            Vector3 dir = directionFromYawPitch(yaw, pitch);
            Vector3 target = origin.copy().add(dir.mul(100.0f));

            lookAt(origin.x, origin.y, origin.z, target.x, target.y, target.z, 0.0f, 1.0f, 0.0f);
        }
    }

    private void setPerspective(int width, int height) {
        glViewport(0, 0, width, height);
        glMatrixMode(GL_PROJECTION);
        glLoadIdentity();

        float aspect = (float) width / (float) height;
        float fov = 65.0f;
        float near = 0.1f;
        float far = 3000.0f;

        float top = (float) Math.tan(Math.toRadians(fov * 0.5f)) * near;
        float bottom = -top;
        float right = top * aspect;
        float left = -right;

        glFrustum(left, right, bottom, top, near, far);
    }

    private void renderPlane() {
        glPushMatrix();
        glTranslatef(gameState.planePosition.x, gameState.planePosition.y, gameState.planePosition.z);
        glRotatef(gameState.planeYaw, 0.0f, 1.0f, 0.0f);
        glRotatef(gameState.planePitch, 1.0f, 0.0f, 0.0f);

        glColor3f(0.15f, 0.2f, 0.9f);
        glBegin(GL_TRIANGLES);
        glVertex3f(0.0f, 0.0f, -6.0f);
        glVertex3f(-1.2f, -0.6f, 3.0f);
        glVertex3f(1.2f, -0.6f, 3.0f);

        glVertex3f(0.0f, 1.0f, -2.0f);
        glVertex3f(-1.0f, -0.2f, 3.0f);
        glVertex3f(1.0f, -0.2f, 3.0f);
        glEnd();

        glColor3f(0.1f, 0.1f, 0.6f);
        glBegin(GL_QUADS);
        glVertex3f(-8.0f, 0.0f, 0.5f);
        glVertex3f(8.0f, 0.0f, 0.5f);
        glVertex3f(8.0f, 0.0f, 2.0f);
        glVertex3f(-8.0f, 0.0f, 2.0f);
        glEnd();

        glPopMatrix();
    }

    private void renderTurret() {
        Vector3 pos = gameState.turretPosition;
        glPushMatrix();
        glTranslatef(pos.x, map.getY() + 2.0f, pos.z);

        glColor3f(0.18f, 0.18f, 0.18f);
        drawCube(5.0f, 2.0f, 5.0f);

        glTranslatef(0.0f, 2.0f, 0.0f);
        glRotatef(localRole == Role.PILOT ? remoteTurretYaw : localTurretYaw, 0.0f, 1.0f, 0.0f);
        glRotatef(localRole == Role.PILOT ? remoteTurretPitch : localTurretPitch, 1.0f, 0.0f, 0.0f);
        glColor3f(0.35f, 0.35f, 0.35f);
        drawCube(1.0f, 1.0f, 6.0f);

        glPopMatrix();
    }

    private void renderTargets() {
        for (int i = 0; i < gameState.targets.size(); i++) {
            if (gameState.destroyedTargets[i]) {
                continue;
            }

            Vector3 p = gameState.targets.get(i);
            glPushMatrix();
            glTranslatef(p.x, p.y, p.z);

            glColor3f(0.9f, 0.1f, 0.1f);
            drawCube(4.0f, 4.0f, 4.0f);

            glDisable(GL_LIGHTING);
            glColor3f(1.0f, 1.0f, 1.0f);
            drawRingXZ(3.0f, 32);
            glTranslatef(0.0f, 1.5f, 0.0f);
            drawRingXZ(2.0f, 32);
            glEnable(GL_LIGHTING);

            glPopMatrix();
        }
    }

    private void drawRingXZ(float radius, int segments) {
        glBegin(GL_LINE_LOOP);
        for (int i = 0; i < segments; i++) {
            float t = (float) (i * 2.0 * Math.PI / segments);
            glVertex3f((float) Math.cos(t) * radius, 0.0f, (float) Math.sin(t) * radius);
        }
        glEnd();
    }

    private void drawCube(float width, float height, float depth) {
        float hx = width * 0.5f;
        float hy = height * 0.5f;
        float hz = depth * 0.5f;

        glBegin(GL_QUADS);
        glNormal3f(0, 1, 0);
        glVertex3f(-hx, hy, -hz);
        glVertex3f(hx, hy, -hz);
        glVertex3f(hx, hy, hz);
        glVertex3f(-hx, hy, hz);

        glNormal3f(0, -1, 0);
        glVertex3f(-hx, -hy, hz);
        glVertex3f(hx, -hy, hz);
        glVertex3f(hx, -hy, -hz);
        glVertex3f(-hx, -hy, -hz);

        glNormal3f(0, 0, 1);
        glVertex3f(-hx, -hy, hz);
        glVertex3f(-hx, hy, hz);
        glVertex3f(hx, hy, hz);
        glVertex3f(hx, -hy, hz);

        glNormal3f(0, 0, -1);
        glVertex3f(hx, -hy, -hz);
        glVertex3f(hx, hy, -hz);
        glVertex3f(-hx, hy, -hz);
        glVertex3f(-hx, -hy, -hz);

        glNormal3f(-1, 0, 0);
        glVertex3f(-hx, -hy, -hz);
        glVertex3f(-hx, hy, -hz);
        glVertex3f(-hx, hy, hz);
        glVertex3f(-hx, -hy, hz);

        glNormal3f(1, 0, 0);
        glVertex3f(hx, -hy, hz);
        glVertex3f(hx, hy, hz);
        glVertex3f(hx, hy, -hz);
        glVertex3f(hx, -hy, -hz);
        glEnd();
    }

    private void renderOverlay(int width, int height) {
        glMatrixMode(GL_PROJECTION);
        glPushMatrix();
        glLoadIdentity();
        glOrtho(0.0, width, height, 0.0, -1.0, 1.0);

        glMatrixMode(GL_MODELVIEW);
        glPushMatrix();
        glLoadIdentity();

        glDisable(GL_LIGHTING);
        glDisable(GL_DEPTH_TEST);
        glColor3f(0.05f, 0.05f, 0.05f);

        renderText(20.0f, 25.0f, "FPS: " + currentFps);

        if (screenState == ScreenState.MENU) {
            renderText(20.0f, 70.0f, "PLANE FIGHT BATTLE");
            renderText(20.0f, 95.0f, "H: Host game (you are Pilot)");
            renderText(20.0f, 115.0f, "J: Join localhost game (you are Turret)");
            renderText(20.0f, 145.0f, "Pilot: mouse controls plane and shoots targets");
            renderText(20.0f, 165.0f, "Turret: first-person gun, shoot the plane");
            renderText(20.0f, 195.0f, statusText);
        } else if (screenState == ScreenState.WAITING) {
            renderText(20.0f, 70.0f, "Waiting for other player to join...");
            if (networkPeer != null) {
                renderText(20.0f, 95.0f, networkPeer.connectionInfo());
            }
        } else if (screenState == ScreenState.COUNTDOWN) {
            renderText(20.0f, 70.0f, "Match starts in: " + Math.max(0, (int) Math.ceil(countdownTimer)));
        } else if (screenState == ScreenState.PLAYING) {
            renderText(20.0f, 70.0f, "Role: " + localRole);
            renderText(20.0f, 90.0f, "Plane HP: " + (int) gameState.planeHp);
            renderText(20.0f, 110.0f, "Targets destroyed: " + gameState.destroyedTargetCount() + "/" + gameState.targets.size());

            if (localRole == Role.PILOT) {
                renderText(20.0f, 130.0f, "Mouse to fly into targets (no plane shooting)");
            } else {
                renderText(20.0f, 130.0f, "Mouse to aim turret + left click to fire");
            }

            renderText(20.0f, 150.0f, "Esc: pause + free mouse");

            drawCrosshair(width * 0.5f, height * 0.5f, 10.0f);
        } else if (screenState == ScreenState.PAUSED) {
            float buttonW = 220.0f;
            float buttonH = 50.0f;
            float buttonX = width * 0.5f - buttonW * 0.5f;
            float buttonY = height * 0.5f - buttonH * 0.5f;

            renderText(20.0f, 70.0f, "Paused");
            renderText(20.0f, 95.0f, "Mouse is free. Click Resume or press Esc.");

            glColor3f(0.75f, 0.75f, 0.75f);
            drawRect2D(buttonX, buttonY, buttonW, buttonH);
            glColor3f(0.05f, 0.05f, 0.05f);
            renderText(buttonX + 70.0f, buttonY + 30.0f, "RESUME");
        } else if (screenState == ScreenState.GAME_OVER) {
            renderText(20.0f, 70.0f, "Game Over");
            renderText(20.0f, 95.0f, gameOverText);
            renderText(20.0f, 120.0f, "Press R for main menu");
        }

        glEnable(GL_DEPTH_TEST);
        glEnable(GL_LIGHTING);

        glPopMatrix();
        glMatrixMode(GL_PROJECTION);
        glPopMatrix();
        glMatrixMode(GL_MODELVIEW);
    }

    private void drawCrosshair(float x, float y, float size) {
        glBegin(GL_LINES);
        glVertex2f(x - size, y);
        glVertex2f(x + size, y);
        glVertex2f(x, y - size);
        glVertex2f(x, y + size);
        glEnd();
    }

    private void drawRect2D(float x, float y, float width, float height) {
        glBegin(GL_QUADS);
        glVertex2f(x, y);
        glVertex2f(x + width, y);
        glVertex2f(x + width, y + height);
        glVertex2f(x, y + height);
        glEnd();
    }

    private void renderText(float x, float y, String text) {
        ByteBuffer textBuffer = MemoryUtil.memASCII(text, true);
        textVertexBuffer.clear();
        int quads = STBEasyFont.stb_easy_font_print(0.0f, 0.0f, textBuffer, null, textVertexBuffer);
        textVertexBuffer.flip();
        MemoryUtil.memFree(textBuffer);

        glPushMatrix();
        glTranslatef(x, y, 0.0f);
        glEnableClientState(GL_VERTEX_ARRAY);
        glVertexPointer(2, GL_FLOAT, 16, textVertexBuffer);
        glDrawArrays(GL_QUADS, 0, quads * 4);
        glDisableClientState(GL_VERTEX_ARRAY);
        glPopMatrix();
    }

    private void onKey(long win, int key, int scancode, int action, int mods) {
        if (action != GLFW_RELEASE) {
            return;
        }

        if (key == GLFW_KEY_ESCAPE) {
            if (screenState == ScreenState.PLAYING || screenState == ScreenState.COUNTDOWN) {
                pauseGame();
            } else if (screenState == ScreenState.PAUSED) {
                resumeGame();
            } else {
                glfwSetWindowShouldClose(win, true);
            }
            return;
        }

        if (screenState == ScreenState.MENU) {
            if (key == GLFW_KEY_H) {
                hostGame();
            } else if (key == GLFW_KEY_J) {
                joinGame("127.0.0.1", NET_PORT);
            }
            return;
        }

        if (screenState == ScreenState.GAME_OVER && key == GLFW_KEY_R) {
            backToMenu();
        }
    }

    private void hostGame() {
        closeNetwork();
        resetRound();

        try {
            networkPeer = new HostPeer(NET_PORT);
            localRole = Role.PILOT;
            screenState = ScreenState.WAITING;
            statusText = "Hosting on port " + NET_PORT;
        } catch (IOException ex) {
            statusText = "Host failed: " + ex.getMessage();
            screenState = ScreenState.MENU;
            localRole = Role.NONE;
        }
    }

    private void joinGame(String host, int port) {
        closeNetwork();
        resetRound();

        try {
            networkPeer = new ClientPeer(host, port);
            localRole = Role.TURRET;
            screenState = ScreenState.WAITING;
            statusText = "Connected. Waiting for host countdown...";
        } catch (IOException ex) {
            statusText = "Join failed: " + ex.getMessage();
            screenState = ScreenState.MENU;
            localRole = Role.NONE;
        }
    }

    private void backToMenu() {
        closeNetwork();
        resetRound();
        localRole = Role.NONE;
        screenState = ScreenState.MENU;
        gameOverText = "";
        statusText = "Press H to Host (Pilot) or J to Join (Turret)";
    }

    private void resetRound() {
        gameState.reset();
        remoteTurretYaw = 180.0f;
        remoteTurretPitch = -5.0f;
        localTurretYaw = 180.0f;
        localTurretPitch = -5.0f;
        countdownTimer = 3.0f;
        localFireCooldown = 0.0f;
        prevLeftMouse = false;
        prevUiLeftMouse = false;
        lastTargetAiPhase = 0.0f;
        bulletTraces.clear();
    }

    private void updateCursorCapture() {
        boolean shouldCapture = (screenState == ScreenState.PLAYING);
        if (shouldCapture == cursorCaptured) {
            return;
        }

        glfwSetInputMode(window, GLFW_CURSOR, shouldCapture ? GLFW_CURSOR_DISABLED : GLFW_CURSOR_NORMAL);
        cursorCaptured = shouldCapture;
        firstMouseSample = true;
    }

    private Vector2 sampleMouseDelta() {
        if (!cursorCaptured) {
            return new Vector2(0.0f, 0.0f);
        }

        double[] x = new double[1];
        double[] y = new double[1];
        glfwGetCursorPos(window, x, y);

        if (firstMouseSample) {
            lastMouseX = x[0];
            lastMouseY = y[0];
            firstMouseSample = false;
            return new Vector2(0.0f, 0.0f);
        }

        float dx = (float) (x[0] - lastMouseX);
        float dy = (float) (y[0] - lastMouseY);
        lastMouseX = x[0];
        lastMouseY = y[0];
        return new Vector2(dx, dy);
    }

    private void updateBulletTraces(float dt) {
        for (int i = bulletTraces.size() - 1; i >= 0; i--) {
            BulletTrace trace = bulletTraces.get(i);
            trace.ttl -= dt;
            if (trace.ttl <= 0.0f) {
                bulletTraces.remove(i);
            }
        }
    }

    private void addBulletTrace(Vector3 start, Vector3 end, float r, float g, float b) {
        bulletTraces.add(new BulletTrace(start, end, r, g, b, 0.18f));
    }

    private void renderBulletTraces() {
        if (bulletTraces.isEmpty()) {
            return;
        }

        glDisable(GL_LIGHTING);
        glLineWidth(3.0f);
        glBegin(GL_LINES);
        for (BulletTrace trace : bulletTraces) {
            float t = clamp(trace.ttl / trace.maxTtl, 0.0f, 1.0f);
            glColor3f(trace.r * t, trace.g * t, trace.b * t);
            glVertex3f(trace.start.x, trace.start.y, trace.start.z);
            glVertex3f(trace.end.x, trace.end.y, trace.end.z);
        }
        glEnd();
        glLineWidth(1.0f);
        glEnable(GL_LIGHTING);
    }

    private void pauseGame() {
        pausedReturnState = screenState;
        screenState = ScreenState.PAUSED;
        prevLeftMouse = false;
        prevUiLeftMouse = false;
    }

    private void resumeGame() {
        screenState = pausedReturnState;
        prevLeftMouse = false;
        prevUiLeftMouse = false;
        firstMouseSample = true;
    }

    private void updatePauseMenuInput() {
        boolean leftPressed = glfwGetMouseButton(window, GLFW_MOUSE_BUTTON_LEFT) == GLFW_PRESS;
        boolean justPressed = leftPressed && !prevUiLeftMouse;
        prevUiLeftMouse = leftPressed;

        if (!justPressed) {
            return;
        }

        double[] cx = new double[1];
        double[] cy = new double[1];
        glfwGetCursorPos(window, cx, cy);

        int width;
        int height;
        try (MemoryStack stack = stackPush()) {
            IntBuffer w = stack.mallocInt(1);
            IntBuffer h = stack.mallocInt(1);
            glfwGetWindowSize(window, w, h);
            width = Math.max(1, w.get(0));
            height = Math.max(1, h.get(0));
        }

        float buttonW = 220.0f;
        float buttonH = 50.0f;
        float buttonX = width * 0.5f - buttonW * 0.5f;
        float buttonY = height * 0.5f - buttonH * 0.5f;

        if (cx[0] >= buttonX && cx[0] <= buttonX + buttonW && cy[0] >= buttonY && cy[0] <= buttonY + buttonH) {
            resumeGame();
        }
    }

    private Vector3 directionFromYawPitch(float yawDeg, float pitchDeg) {
        float yaw = (float) Math.toRadians(yawDeg);
        float pitch = (float) Math.toRadians(pitchDeg);

        float x = (float) (Math.sin(yaw) * Math.cos(pitch));
        float y = (float) Math.sin(pitch);
        float z = (float) (Math.cos(yaw) * Math.cos(pitch));
        return new Vector3(x, y, z).normalize();
    }

    private boolean raySphereHit(Vector3 origin, Vector3 dir, Vector3 center, float radius) {
        Vector3 oc = origin.copy().sub(center);
        float b = Vector3.dot(oc, dir);
        float c = Vector3.dot(oc, oc) - radius * radius;
        float h = b * b - c;
        if (h < 0.0f) {
            return false;
        }
        float t = -b - (float) Math.sqrt(h);
        return t > 0.0f;
    }

    private float parseFloatSafe(String value, float fallback) {
        try {
            return Float.parseFloat(value);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private void cleanup() {
        closeNetwork();

        Callbacks.glfwFreeCallbacks(window);
        glfwDestroyWindow(window);
        glfwTerminate();

        GLFWErrorCallback cb = glfwSetErrorCallback(null);
        if (cb != null) {
            cb.free();
        }
    }

    private void closeNetwork() {
        if (networkPeer != null) {
            networkPeer.close();
            networkPeer = null;
        }
    }

    private void lookAt(
        float eyeX,
        float eyeY,
        float eyeZ,
        float targetX,
        float targetY,
        float targetZ,
        float upX,
        float upY,
        float upZ
    ) {
        try (MemoryStack stack = stackPush()) {
            FloatBuffer fb = stack.mallocFloat(16);
            gluLookAt(eyeX, eyeY, eyeZ, targetX, targetY, targetZ, upX, upY, upZ, fb);
            glMultMatrixf(fb);
        }
    }

    private static void gluLookAt(
        float eyeX,
        float eyeY,
        float eyeZ,
        float centerX,
        float centerY,
        float centerZ,
        float upX,
        float upY,
        float upZ,
        FloatBuffer matrix
    ) {
        float[] forward = new float[]{centerX - eyeX, centerY - eyeY, centerZ - eyeZ};
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
        float len = (float) Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        if (len > 0.00001f) {
            v[0] /= len;
            v[1] /= len;
            v[2] /= len;
        }
    }

    private static void cross(float[] a, float[] b, float[] result) {
        result[0] = a[1] * b[2] - a[2] * b[1];
        result[1] = a[2] * b[0] - a[0] * b[2];
        result[2] = a[0] * b[1] - a[1] * b[0];
    }

    private static float dot(float[] a, float[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    private static final class Vector2 {
        final float x;
        final float y;

        Vector2(float x, float y) {
            this.x = x;
            this.y = y;
        }
    }

    private static final class BulletTrace {
        final Vector3 start;
        final Vector3 end;
        final float r;
        final float g;
        final float b;
        final float maxTtl;
        float ttl;

        BulletTrace(Vector3 start, Vector3 end, float r, float g, float b, float ttl) {
            this.start = start;
            this.end = end;
            this.r = r;
            this.g = g;
            this.b = b;
            this.maxTtl = ttl;
            this.ttl = ttl;
        }
    }

    private static final class GameState {
        final Vector3 planePosition = new Vector3();
        float planeYaw;
        float planePitch;
        float planeSpeed;
        float planeHp;

        final Vector3 turretPosition = new Vector3(0.0f, 5.0f, 0.0f);
        final List<Vector3> targets = new ArrayList<>();
        boolean[] destroyedTargets = new boolean[0];

        void reset() {
            planePosition.set(0.0f, 26.0f, 170.0f);
            planeYaw = 180.0f;
            planePitch = 0.0f;
            planeSpeed = 48.0f;
            planeHp = 100.0f;

            targets.clear();
            targets.add(new Vector3(-130.0f, 20.0f, -80.0f));
            targets.add(new Vector3(-40.0f, 25.0f, -130.0f));
            targets.add(new Vector3(70.0f, 18.0f, -110.0f));
            targets.add(new Vector3(130.0f, 22.0f, -30.0f));
            targets.add(new Vector3(60.0f, 16.0f, 90.0f));
            targets.add(new Vector3(-90.0f, 24.0f, 120.0f));
            destroyedTargets = new boolean[targets.size()];
        }

        int destroyedTargetCount() {
            int count = 0;
            for (boolean destroyed : destroyedTargets) {
                if (destroyed) {
                    count++;
                }
            }
            return count;
        }
    }

    private interface NetworkPeer {
        boolean isConnected();

        void send(String message);

        List<String> drainMessages();

        String connectionInfo();

        void close();
    }

    private static final class HostPeer implements NetworkPeer {
        private final ServerSocket serverSocket;
        private final ConcurrentLinkedQueue<String> incoming = new ConcurrentLinkedQueue<>();

        private volatile Socket clientSocket;
        private volatile PrintWriter writer;
        private volatile boolean closed = false;

        HostPeer(int port) throws IOException {
            serverSocket = new ServerSocket(port);
            serverSocket.setSoTimeout(250);

            Thread acceptThread = new Thread(() -> {
                while (!closed && clientSocket == null) {
                    try {
                        Socket socket = serverSocket.accept();
                        clientSocket = socket;
                        writer = new PrintWriter(socket.getOutputStream(), true);
                        incoming.add("SYS_CONNECTED");
                        startReader(socket, incoming);
                    } catch (SocketTimeoutException ignored) {
                    } catch (IOException ex) {
                        if (!closed) {
                            incoming.add("SYS_ERR|" + ex.getMessage());
                        }
                    }
                }
            }, "host-accept-thread");
            acceptThread.setDaemon(true);
            acceptThread.start();
        }

        @Override
        public boolean isConnected() {
            return clientSocket != null && clientSocket.isConnected() && !clientSocket.isClosed();
        }

        @Override
        public void send(String message) {
            if (writer != null) {
                writer.println(message);
            }
        }

        @Override
        public List<String> drainMessages() {
            List<String> out = new ArrayList<>();
            while (true) {
                String next = incoming.poll();
                if (next == null) {
                    break;
                }
                out.add(next);
            }
            return out;
        }

        @Override
        public String connectionInfo() {
            return "Hosting on localhost:" + serverSocket.getLocalPort();
        }

        @Override
        public void close() {
            closed = true;
            tryClose(clientSocket);
            tryClose(serverSocket);
        }
    }

    private static final class ClientPeer implements NetworkPeer {
        private final Socket socket;
        private final PrintWriter writer;
        private final ConcurrentLinkedQueue<String> incoming = new ConcurrentLinkedQueue<>();

        ClientPeer(String host, int port) throws IOException {
            socket = new Socket(host, port);
            socket.setTcpNoDelay(true);
            writer = new PrintWriter(socket.getOutputStream(), true);
            startReader(socket, incoming);
        }

        @Override
        public boolean isConnected() {
            return socket.isConnected() && !socket.isClosed();
        }

        @Override
        public void send(String message) {
            writer.println(message);
        }

        @Override
        public List<String> drainMessages() {
            List<String> out = new ArrayList<>();
            while (true) {
                String next = incoming.poll();
                if (next == null) {
                    break;
                }
                out.add(next);
            }
            return out;
        }

        @Override
        public String connectionInfo() {
            return "Joined " + socket.getInetAddress().getHostAddress() + ":" + socket.getPort();
        }

        @Override
        public void close() {
            tryClose(socket);
        }
    }

    private static void startReader(Socket socket, ConcurrentLinkedQueue<String> incoming) {
        Thread readerThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    incoming.add(line);
                }
            } catch (IOException ignored) {
            }
        }, "net-reader-thread");
        readerThread.setDaemon(true);
        readerThread.start();
    }

    private static void tryClose(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception ignored) {
        }
    }
}
