package flightbattlesimulator;

import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.Callbacks;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.stb.STBEasyFont;
import org.lwjgl.stb.STBImage;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
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
    private ModelLoader planeModel;
    private ModelLoader targetModel;
    private int targetTextureId = 0;
    private AudioEngine audioEngine;
    private int plane1RumbleSource = -1;
    private int plane2RumbleSource = -1;
    private int plane1FreeBirdSource = -1;
    private int plane2FreeBirdSource = -1;
    private int targetGrabbedSource = -1;
    private int turretShootSource = -1;
    private static final float PLANE_RUMBLE_BASE_GAIN = 0.18f;
    private static final float FREE_BIRD_BASE_GAIN = 0.36f;
    private static final float PILOT_RUMBLE_GAIN = 0.34f;
    private static final float BASE_PLANE_SPEED = 80.64f;
    private static final float BOOST_MULTIPLIER = 1.5f;
    private static final float BOOST_DRAIN_PER_SECOND = 1.0f / 2.0f;
    private static final float BOOST_REGEN_PER_SECOND = 1.0f / 20.0f;
    private final float planeModelPitchOffset = -90.0f;
    private final float planeModelRollOffset = 180.0f;
    private final float targetModelPitchOffset = -90.0f;
    private final float targetModelScale = 0.14f;
    private final Random random = new Random();

    private ScreenState screenState = ScreenState.MENU;
    private Role localRole = Role.NONE;
    private NetworkPeer networkPeer;

    private final ByteBuffer textVertexBuffer = BufferUtils.createByteBuffer(64 * 1024);
    private final GameState gameState = new GameState();

    private String statusText = "Press H to Host (Plane 1) or J to Join (Plane 2)";
    private String gameOverText = "";
    private float countdownTimer = 3.0f;
    private ScreenState pausedReturnState = ScreenState.PLAYING;
    private boolean musicMuted = false;

    private boolean cursorCaptured = false;
    private boolean firstMouseSample = true;
    private double lastMouseX;
    private double lastMouseY;

    private boolean prevLeftMouse = false;
    private boolean prevUiLeftMouse = false;
    private float localFireCooldown = 0.0f;
    private float targetAiPhase = 0.0f;
    private float planeBank = 0.0f;

    private final List<BulletTrace> bulletTraces = new ArrayList<>();

    private float plane2Bank = 0.0f;
    private float planeBoostEnergy = 1.0f;
    private boolean planeBoostLockedOut = false;

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

        planeModel = new ModelLoader();
        if (!planeModel.loadModel("plane/planedone.fbx")) {
            System.err.println("Failed to load plane/planedone.fbx, falling back to simple plane mesh.");
            planeModel = null;
        }

        targetModel = new ModelLoader();
        if (!targetModel.loadModel("cube.obj", "target.jpg")) {
            System.err.println("Failed to load cube.obj with target.jpg, falling back to red cube targets.");
            targetModel = null;
        }

        targetTextureId = loadTextureFromResources("target.jpg");

        initAudio();

        resetRound();
        return true;
    }

    private void initAudio() {
        audioEngine = new AudioEngine();
        if (!audioEngine.init()) {
            System.err.println("Audio initialization failed. Continuing without sound.");
            audioEngine = null;
            return;
        }

        plane1RumbleSource = audioEngine.createMp3Source(
            "sounds/plane_rumble.mp3",
            true,
            PLANE_RUMBLE_BASE_GAIN,
            1.0f,
            32.0f,
            900.0f,
            1.15f
        );
        plane2RumbleSource = audioEngine.createMp3Source(
            "sounds/plane_rumble.mp3",
            true,
            PLANE_RUMBLE_BASE_GAIN,
            1.0f,
            32.0f,
            900.0f,
            1.15f
        );
        plane1FreeBirdSource = audioEngine.createMp3Source(
            "sounds/Free Bird.mp3",
            true,
            FREE_BIRD_BASE_GAIN,
            1.0f,
            55.0f,
            1700.0f,
            0.45f
        );
        plane2FreeBirdSource = audioEngine.createMp3Source(
            "sounds/Free Bird.mp3",
            true,
            FREE_BIRD_BASE_GAIN,
            1.0f,
            55.0f,
            1700.0f,
            0.45f
        );
        targetGrabbedSource = audioEngine.createMp3Source(
            "sounds/target_grabbed.mp3",
            false,
            0.95f,
            1.0f,
            20.0f,
            650.0f,
            1.2f
        );
        turretShootSource = audioEngine.createMp3Source(
            "sounds/turret_shoot.mp3",
            false,
            0.9f,
            1.0f,
            28.0f,
            700.0f,
            1.25f
        );
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

                if (gameState.planeHp <= 0.0f) {
                    endGame("Plane 2 Wins! Plane 1 destroyed.", "PLANE2");
                } else if (gameState.destroyedTargetCount() == gameState.targets.size()) {
                    endGame("Plane 1 Wins! All targets destroyed.", "PLANE1");
                }

                sendSnapshotToClient();
            } else if (localRole == Role.TURRET) {
                updatePlane2Gameplay(dt);
            }
        }
    }

    private void updatePilotGameplay(float dt) {
        Vector2 mouseDelta = sampleMouseDelta();
        float sensitivity = 0.1f;

        gameState.planeYaw -= mouseDelta.x * sensitivity;
        gameState.planePitch -= mouseDelta.y * sensitivity;
        gameState.planePitch = clamp(gameState.planePitch, -35.0f, 35.0f);

        float targetBank = clamp(mouseDelta.x * 1.25f, -32.0f, 32.0f);
        float bankLerp = clamp(dt * 8.5f, 0.0f, 1.0f);
        planeBank += (targetBank - planeBank) * bankLerp;
        gameState.planeRoll = planeBank;

        if (planeBoostLockedOut && planeBoostEnergy >= 1.0f) {
            planeBoostLockedOut = false;
        }

        boolean boostHeld = glfwGetKey(window, GLFW_KEY_SPACE) == GLFW_PRESS;
        boolean boosting = boostHeld && !planeBoostLockedOut && planeBoostEnergy > 0.0f;
        if (boosting) {
            planeBoostEnergy = Math.max(0.0f, planeBoostEnergy - BOOST_DRAIN_PER_SECOND * dt);
            if (planeBoostEnergy <= 0.0f) {
                planeBoostLockedOut = true;
            }
        } else {
            planeBoostEnergy = Math.min(1.0f, planeBoostEnergy + BOOST_REGEN_PER_SECOND * dt);
        }

        Vector3 forward = directionFromYawPitch(gameState.planeYaw, gameState.planePitch);
        float speedMultiplier = boosting ? BOOST_MULTIPLIER : 1.0f;
        gameState.planePosition.add(forward.mul(gameState.planeSpeed * speedMultiplier * dt));

        float terrainFloor = map != null ? map.getHeightAt(gameState.planePosition.x, gameState.planePosition.z) : 0.0f;
        float minAltitude = Math.max(-92.0f, terrainFloor);
        gameState.planePosition.y = clamp(gameState.planePosition.y, minAltitude, 120.0f);
        gameState.planePosition.x = clamp(gameState.planePosition.x, -620.0f, 620.0f);
        gameState.planePosition.z = clamp(gameState.planePosition.z, -620.0f, 620.0f);

        collectTargetsByContact();
        updateTargetEvasion(dt);
    }

    private void updatePlane2Gameplay(float dt) {
        Vector2 mouseDelta = sampleMouseDelta();
        float sensitivity = 0.1f;

        gameState.plane2Yaw -= mouseDelta.x * sensitivity;
        gameState.plane2Pitch -= mouseDelta.y * sensitivity;
        gameState.plane2Pitch = clamp(gameState.plane2Pitch, -35.0f, 35.0f);

        float targetBank = clamp(mouseDelta.x * 1.25f, -32.0f, 32.0f);
        float bankLerp = clamp(dt * 8.5f, 0.0f, 1.0f);
        plane2Bank += (targetBank - plane2Bank) * bankLerp;
        gameState.plane2Roll = plane2Bank;

        Vector3 forward = directionFromYawPitch(gameState.plane2Yaw, gameState.plane2Pitch);
        gameState.plane2Position.add(forward.mul(gameState.plane2Speed * dt));

        float terrainFloor = map != null ? map.getHeightAt(gameState.plane2Position.x, gameState.plane2Position.z) : 0.0f;
        float minAltitude = Math.max(-92.0f, terrainFloor);
        gameState.plane2Position.y = clamp(gameState.plane2Position.y, minAltitude, 120.0f);
        gameState.plane2Position.x = clamp(gameState.plane2Position.x, -620.0f, 620.0f);
        gameState.plane2Position.z = clamp(gameState.plane2Position.z, -620.0f, 620.0f);

        if (networkPeer != null && networkPeer.isConnected()) {
            networkPeer.send(
                "P2STATE|"
                    + gameState.plane2Position.x + "|"
                    + gameState.plane2Position.y + "|"
                    + gameState.plane2Position.z + "|"
                    + gameState.plane2Yaw + "|"
                    + gameState.plane2Pitch + "|"
                    + gameState.plane2Roll
            );
        }

        boolean leftPressed = glfwGetMouseButton(window, GLFW_MOUSE_BUTTON_LEFT) == GLFW_PRESS;
        boolean justPressed = leftPressed && !prevLeftMouse;
        prevLeftMouse = leftPressed;

        if (justPressed && localFireCooldown <= 0.0f) {
            localFireCooldown = 0.15f;
            Vector3 shotDir = directionFromYawPitch(gameState.plane2Yaw, gameState.plane2Pitch);
            Vector3 start = gameState.plane2Position.copy().add(shotDir.copy().mul(6.0f));
            addBulletTrace(
                start,
                start.copy().add(shotDir.mul(700.0f)),
                1.0f,
                0.9f,
                0.1f
            );
            if (audioEngine != null) {
                audioEngine.setSourcePosition(turretShootSource, gameState.plane2Position);
                audioEngine.playOneShot(turretShootSource);
            }
            if (networkPeer != null && networkPeer.isConnected()) {
                networkPeer.send(
                    "FIRE_P2|"
                        + start.x + "|"
                        + start.y + "|"
                        + start.z + "|"
                        + shotDir.x + "|"
                        + shotDir.y + "|"
                        + shotDir.z
                );
            }
        }
    }

    private void respawnTargetsAcrossMap() {
        int targetCount = 6;
        gameState.targets.clear();

        for (int i = 0; i < targetCount; i++) {
            Vector3 spawn = new Vector3();
            boolean placed = false;

            for (int attempt = 0; attempt < 80; attempt++) {
                float x = -560.0f + random.nextFloat() * 1120.0f;
                float z = -560.0f + random.nextFloat() * 1120.0f;
                float terrainHeight = map != null ? map.getHeightAt(x, z) : 0.0f;
                float heightJitter = random.nextFloat() * 36.0f;
                if (random.nextFloat() < 0.28f) {
                    heightJitter += 14.0f + random.nextFloat() * 16.0f;
                }
                float y = terrainHeight + 6.0f + heightJitter;
                y = clamp(y, -6.0f, 72.0f);

                spawn.set(x, y, z);
                if (spawn.copy().sub(gameState.planePosition).lengthSquared() < 220.0f * 220.0f) {
                    continue;
                }

                boolean overlaps = false;
                for (Vector3 existing : gameState.targets) {
                    if (existing.copy().sub(spawn).lengthSquared() < 95.0f * 95.0f) {
                        overlaps = true;
                        break;
                    }
                }

                if (!overlaps) {
                    placed = true;
                    break;
                }
            }

            if (!placed) {
                spawn.set(-220.0f + i * 90.0f, 20.0f + (i % 3) * 4.0f, -120.0f + i * 55.0f);
            }

            gameState.targets.add(spawn.copy());
        }

        gameState.destroyedTargets = new boolean[gameState.targets.size()];
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
                Vector3 collectedPos = gameState.targets.get(i);
                if (audioEngine != null) {
                    audioEngine.setSourcePosition(targetGrabbedSource, collectedPos);
                    audioEngine.playOneShot(targetGrabbedSource);
                }
                if (localRole == Role.PILOT && networkPeer != null && networkPeer.isConnected()) {
                    networkPeer.send(
                        "TARGET_COLLECT|"
                            + collectedPos.x + "|"
                            + collectedPos.y + "|"
                            + collectedPos.z
                    );
                }
            }
        }
    }

    private void updateTargetEvasion(float dt) {
        int totalTargets = gameState.targets.size();
        int destroyedCount = gameState.destroyedTargetCount();
        int remainingTargets = totalTargets - destroyedCount;
        if (remainingTargets <= 0) {
            targetAiPhase = 0.0f;
            return;
        }

        targetAiPhase += dt;

        float progress = totalTargets > 1 ? (float) destroyedCount / (float) (totalTargets - 1) : 1.0f;
        float detectRadius = 250.0f;
        float minSpeed = 8.0f + 10.0f * progress;
        float maxSpeed = 20.0f + 18.0f * progress;
        if (remainingTargets == 1) {
            maxSpeed = gameState.planeSpeed * 0.92f;
            minSpeed = Math.min(minSpeed, maxSpeed * 0.75f);
        }

        for (int i = 0; i < totalTargets; i++) {
            if (gameState.destroyedTargets[i]) {
                continue;
            }

            Vector3 targetPos = gameState.targets.get(i);
            Vector3 awayFromPlane = targetPos.copy().sub(gameState.planePosition);
            float distance = awayFromPlane.length();
            if (distance <= 0.001f || distance > detectRadius) {
                continue;
            }

            awayFromPlane.normalize();
            float phase = targetAiPhase * (2.0f + progress * 0.7f) + (i * 1.37f);

            Vector3 lateral = new Vector3(-awayFromPlane.z, 0.0f, awayFromPlane.x).normalize();
            lateral.mul((float) Math.sin(phase) * (0.25f + progress * 0.45f));

            float urgency = 1.0f - (distance / detectRadius);
            float speed = minSpeed + urgency * (maxSpeed - minSpeed);

            Vector3 movement = awayFromPlane.copy().add(lateral).normalize().mul(speed * dt);
            targetPos.add(movement);

            float bob = (float) Math.sin(phase * 1.6f) * (0.7f + progress * 1.4f);
            targetPos.y = clamp(targetPos.y + bob * dt, 14.0f, 34.0f);
            targetPos.x = clamp(targetPos.x, -560.0f, 560.0f);
            targetPos.z = clamp(targetPos.z, -560.0f, 560.0f);
        }
    }

    private void handlePlane2Shot(Vector3 shotOrigin, Vector3 dir) {
        Vector3 start = shotOrigin.copy();
        addBulletTrace(start, start.copy().add(dir.copy().mul(700.0f)), 1.0f, 0.9f, 0.1f);
        if (audioEngine != null) {
            audioEngine.setSourcePosition(turretShootSource, shotOrigin);
            audioEngine.playOneShot(turretShootSource);
        }
        if (raySphereHit(shotOrigin, dir, gameState.planePosition, 5.5f)) {
            gameState.planeHp -= 1.0f;
            gameState.planeHp = Math.max(0.0f, gameState.planeHp);
        }
    }

    private void sendSnapshotToClient() {
        if (networkPeer == null || !networkPeer.isConnected()) {
            return;
        }

        StringBuilder message = new StringBuilder("SNAP|")
            .append(gameState.planePosition.x).append('|')
            .append(gameState.planePosition.y).append('|')
            .append(gameState.planePosition.z).append('|')
            .append(gameState.planeYaw).append('|')
            .append(gameState.planePitch).append('|')
            .append(gameState.planeRoll).append('|')
            .append(gameState.planeHp).append('|')
            .append(gameState.plane2Position.x).append('|')
            .append(gameState.plane2Position.y).append('|')
            .append(gameState.plane2Position.z).append('|')
            .append(gameState.plane2Yaw).append('|')
            .append(gameState.plane2Pitch).append('|')
            .append(gameState.plane2Roll).append('|')
            .append(targetMask());

        for (int i = 0; i < gameState.targets.size(); i++) {
            Vector3 target = gameState.targets.get(i);
            message.append('|').append(target.x);
            message.append('|').append(target.y);
            message.append('|').append(target.z);
        }

        networkPeer.send(message.toString());
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
                if ("P2STATE".equals(parts[0]) && parts.length >= 7) {
                    gameState.plane2Position.set(
                        parseFloatSafe(parts[1], gameState.plane2Position.x),
                        parseFloatSafe(parts[2], gameState.plane2Position.y),
                        parseFloatSafe(parts[3], gameState.plane2Position.z)
                    );
                    gameState.plane2Yaw = parseFloatSafe(parts[4], gameState.plane2Yaw);
                    gameState.plane2Pitch = parseFloatSafe(parts[5], gameState.plane2Pitch);
                    gameState.plane2Roll = parseFloatSafe(parts[6], gameState.plane2Roll);
                } else if ("FIRE_P2".equals(parts[0]) && parts.length >= 7) {
                    Vector3 origin = new Vector3(
                        parseFloatSafe(parts[1], gameState.plane2Position.x),
                        parseFloatSafe(parts[2], gameState.plane2Position.y),
                        parseFloatSafe(parts[3], gameState.plane2Position.z)
                    );
                    Vector3 dir = new Vector3(
                        parseFloatSafe(parts[4], 0.0f),
                        parseFloatSafe(parts[5], 0.0f),
                        parseFloatSafe(parts[6], 1.0f)
                    ).normalize();
                    handlePlane2Shot(origin, dir);
                }
            } else if (localRole == Role.TURRET) {
                if ("COUNTDOWN".equals(parts[0]) && parts.length >= 2) {
                    countdownTimer = parseFloatSafe(parts[1], 3.0f);
                    screenState = ScreenState.COUNTDOWN;
                } else if ("START".equals(parts[0])) {
                    screenState = ScreenState.PLAYING;
                } else if ("SNAP".equals(parts[0]) && parts.length >= 15) {
                    gameState.planePosition.set(
                        parseFloatSafe(parts[1], gameState.planePosition.x),
                        parseFloatSafe(parts[2], gameState.planePosition.y),
                        parseFloatSafe(parts[3], gameState.planePosition.z)
                    );
                    gameState.planeYaw = parseFloatSafe(parts[4], gameState.planeYaw);
                    gameState.planePitch = parseFloatSafe(parts[5], gameState.planePitch);
                    gameState.planeRoll = parseFloatSafe(parts[6], gameState.planeRoll);
                    gameState.planeHp = parseFloatSafe(parts[7], gameState.planeHp);
                    gameState.plane2Position.set(
                        parseFloatSafe(parts[8], gameState.plane2Position.x),
                        parseFloatSafe(parts[9], gameState.plane2Position.y),
                        parseFloatSafe(parts[10], gameState.plane2Position.z)
                    );
                    gameState.plane2Yaw = parseFloatSafe(parts[11], gameState.plane2Yaw);
                    gameState.plane2Pitch = parseFloatSafe(parts[12], gameState.plane2Pitch);
                    gameState.plane2Roll = parseFloatSafe(parts[13], gameState.plane2Roll);
                    applyTargetMask((int) parseFloatSafe(parts[14], targetMask()));

                    int partIndex = 15;
                    int expectedCount = partIndex + gameState.targets.size() * 3;
                    if (parts.length >= expectedCount) {
                        for (int i = 0; i < gameState.targets.size(); i++) {
                            Vector3 target = gameState.targets.get(i);
                            target.set(
                                parseFloatSafe(parts[partIndex], target.x),
                                parseFloatSafe(parts[partIndex + 1], target.y),
                                parseFloatSafe(parts[partIndex + 2], target.z)
                            );
                            partIndex += 3;
                        }
                    }
                } else if ("END".equals(parts[0]) && parts.length >= 2) {
                    screenState = ScreenState.GAME_OVER;
                    gameOverText = "PLANE1".equals(parts[1])
                        ? "Plane 1 Wins! All targets destroyed."
                        : "Plane 2 Wins! Plane 1 destroyed.";
                } else if ("TARGET_COLLECT".equals(parts[0]) && parts.length >= 4) {
                    if (audioEngine != null) {
                        Vector3 collectedPos = new Vector3(
                            parseFloatSafe(parts[1], gameState.planePosition.x),
                            parseFloatSafe(parts[2], gameState.planePosition.y),
                            parseFloatSafe(parts[3], gameState.planePosition.z)
                        );
                        audioEngine.setSourcePosition(targetGrabbedSource, collectedPos);
                        audioEngine.playOneShot(targetGrabbedSource);
                    }
                }
            }
        }
    }

    private void render(int width, int height) {
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

        setPerspective(width, height);
        setupCameraForCurrentState();
        updateAudioScene();

        map.render();
        renderPlane();
        renderPlane2();
        renderTargets();
        renderBulletTraces();

        renderOverlay(width, height);
    }

    private void setupCameraForCurrentState() {
        glMatrixMode(GL_MODELVIEW);
        glLoadIdentity();

        if (screenState == ScreenState.MENU || screenState == ScreenState.WAITING || screenState == ScreenState.GAME_OVER) {
            setCameraAndListener(0.0f, 55.0f, 145.0f, 0.0f, 15.0f, 0.0f, 0.0f, 1.0f, 0.0f);
            return;
        }

        if (localRole == Role.PILOT) {
            Vector3 forward = directionFromYawPitch(gameState.planeYaw, gameState.planePitch);
            Vector3 cameraPos = gameState.planePosition.copy().sub(forward.copy().mul(18.0f));
            cameraPos.y += 6.0f;

            Vector3 lookTarget = gameState.planePosition.copy().add(forward.copy().mul(15.0f));
            setCameraAndListener(cameraPos.x, cameraPos.y, cameraPos.z, lookTarget.x, lookTarget.y, lookTarget.z, 0.0f, 1.0f, 0.0f);
        } else {
            Vector3 forward = directionFromYawPitch(gameState.plane2Yaw, gameState.plane2Pitch);
            Vector3 cameraPos = gameState.plane2Position.copy().sub(forward.copy().mul(18.0f));
            cameraPos.y += 6.0f;

            Vector3 lookTarget = gameState.plane2Position.copy().add(forward.copy().mul(15.0f));
            setCameraAndListener(cameraPos.x, cameraPos.y, cameraPos.z, lookTarget.x, lookTarget.y, lookTarget.z, 0.0f, 1.0f, 0.0f);
        }
    }

    private void setCameraAndListener(
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
        lookAt(eyeX, eyeY, eyeZ, targetX, targetY, targetZ, upX, upY, upZ);

        if (audioEngine != null) {
            Vector3 listenerPos = new Vector3(eyeX, eyeY, eyeZ);
            Vector3 listenerForward = new Vector3(targetX - eyeX, targetY - eyeY, targetZ - eyeZ).normalize();
            Vector3 listenerUp = new Vector3(upX, upY, upZ).normalize();
            audioEngine.setListener(listenerPos, listenerForward, listenerUp);
        }
    }

    private void updateAudioScene() {
        if (audioEngine == null) {
            return;
        }

        audioEngine.setSourcePosition(plane1RumbleSource, gameState.planePosition);
        audioEngine.setSourcePosition(plane2RumbleSource, gameState.plane2Position);
        audioEngine.setSourcePosition(plane1FreeBirdSource, gameState.planePosition);
        audioEngine.setSourcePosition(plane2FreeBirdSource, gameState.plane2Position);

        boolean inMatch = screenState == ScreenState.PLAYING;
        boolean pilotView = localRole == Role.PILOT;
        boolean shooterView = localRole == Role.TURRET;
        float distance = gameState.planePosition.copy().sub(gameState.plane2Position).length();
        float nearDistance = 45.0f;
        float farDistance = 1200.0f;
        float proximity = 1.0f - clamp((distance - nearDistance) / (farDistance - nearDistance), 0.0f, 1.0f);
        proximity = proximity * proximity * proximity;

        float minMusicGain = FREE_BIRD_BASE_GAIN * 0.05f;
        float proximityMusicGain = minMusicGain + (FREE_BIRD_BASE_GAIN - minMusicGain) * proximity;
        float audibleMusicGain = musicMuted ? 0.0f : proximityMusicGain;

        audioEngine.setSourceGain(plane1RumbleSource, pilotView ? PILOT_RUMBLE_GAIN : 0.0f);
        audioEngine.setSourceGain(plane2RumbleSource, shooterView ? PILOT_RUMBLE_GAIN : 0.0f);

        audioEngine.setSourceGain(plane1FreeBirdSource, shooterView ? audibleMusicGain : 0.0f);
        audioEngine.setSourceGain(plane2FreeBirdSource, pilotView ? audibleMusicGain : 0.0f);

        audioEngine.setLoopPlaying(plane1RumbleSource, inMatch && pilotView);
        audioEngine.setLoopPlaying(plane2RumbleSource, inMatch && shooterView);

        // Each player hears only the other player's Free Bird source.
        audioEngine.setLoopPlaying(plane1FreeBirdSource, inMatch && shooterView && !musicMuted);
        audioEngine.setLoopPlaying(plane2FreeBirdSource, inMatch && pilotView && !musicMuted);
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
        float renderRoll = (localRole == Role.PILOT) ? planeBank : gameState.planeRoll;
        glRotatef(renderRoll, 0.0f, 0.0f, 1.0f);
        glRotatef(-gameState.planePitch, 1.0f, 0.0f, 0.0f);
        glColor3f(0.15f, 0.2f, 0.9f);

        if (planeModel != null && planeModel.isLoaded()) {
            glRotatef(planeModelPitchOffset, 1.0f, 0.0f, 0.0f);
            glRotatef(planeModelRollOffset, 0.0f, 0.0f, 1.0f);
            planeModel.render();
            glColor3f(1.0f, 1.0f, 1.0f);
        } else {
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
        }

        glPopMatrix();
    }

    private void renderPlane2() {
        glPushMatrix();
        glTranslatef(gameState.plane2Position.x, gameState.plane2Position.y, gameState.plane2Position.z);
        glRotatef(gameState.plane2Yaw, 0.0f, 1.0f, 0.0f);
        glRotatef(gameState.plane2Roll, 0.0f, 0.0f, 1.0f);
        glRotatef(-gameState.plane2Pitch, 1.0f, 0.0f, 0.0f);
        glColor3f(0.85f, 0.15f, 0.15f);

        if (planeModel != null && planeModel.isLoaded()) {
            glRotatef(planeModelPitchOffset, 1.0f, 0.0f, 0.0f);
            glRotatef(planeModelRollOffset, 0.0f, 0.0f, 1.0f);
            planeModel.render();
            glColor3f(1.0f, 1.0f, 1.0f);
        } else {
            glBegin(GL_TRIANGLES);
            glVertex3f(0.0f, 0.0f, -6.0f);
            glVertex3f(-1.2f, -0.6f, 3.0f);
            glVertex3f(1.2f, -0.6f, 3.0f);

            glVertex3f(0.0f, 1.0f, -2.0f);
            glVertex3f(-1.0f, -0.2f, 3.0f);
            glVertex3f(1.0f, -0.2f, 3.0f);
            glEnd();
        }

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

            if (targetTextureId != 0) {
                glEnable(GL_TEXTURE_2D);
                glBindTexture(GL_TEXTURE_2D, targetTextureId);
                glColor3f(1.0f, 1.0f, 1.0f);
                drawTexturedCube(4.0f, 4.0f, 4.0f);
                glBindTexture(GL_TEXTURE_2D, 0);
                glDisable(GL_TEXTURE_2D);
            } else if (targetModel != null && targetModel.isLoaded()) {
                glRotatef(targetModelPitchOffset, 1.0f, 0.0f, 0.0f);
                glScalef(targetModelScale, targetModelScale, targetModelScale);
                targetModel.render();
            } else {
                glColor3f(0.9f, 0.1f, 0.1f);
                drawCube(4.0f, 4.0f, 4.0f);
            }

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

    private void drawTexturedCube(float width, float height, float depth) {
        float hx = width * 0.5f;
        float hy = height * 0.5f;
        float hz = depth * 0.5f;

        glBegin(GL_QUADS);

        glNormal3f(0, 1, 0);
        glTexCoord2f(0.0f, 0.0f);
        glVertex3f(-hx, hy, -hz);
        glTexCoord2f(1.0f, 0.0f);
        glVertex3f(hx, hy, -hz);
        glTexCoord2f(1.0f, 1.0f);
        glVertex3f(hx, hy, hz);
        glTexCoord2f(0.0f, 1.0f);
        glVertex3f(-hx, hy, hz);

        glNormal3f(0, -1, 0);
        glTexCoord2f(0.0f, 0.0f);
        glVertex3f(-hx, -hy, hz);
        glTexCoord2f(1.0f, 0.0f);
        glVertex3f(hx, -hy, hz);
        glTexCoord2f(1.0f, 1.0f);
        glVertex3f(hx, -hy, -hz);
        glTexCoord2f(0.0f, 1.0f);
        glVertex3f(-hx, -hy, -hz);

        glNormal3f(0, 0, 1);
        glTexCoord2f(0.0f, 0.0f);
        glVertex3f(-hx, -hy, hz);
        glTexCoord2f(1.0f, 0.0f);
        glVertex3f(-hx, hy, hz);
        glTexCoord2f(1.0f, 1.0f);
        glVertex3f(hx, hy, hz);
        glTexCoord2f(0.0f, 1.0f);
        glVertex3f(hx, -hy, hz);

        glNormal3f(0, 0, -1);
        glTexCoord2f(0.0f, 0.0f);
        glVertex3f(hx, -hy, -hz);
        glTexCoord2f(1.0f, 0.0f);
        glVertex3f(hx, hy, -hz);
        glTexCoord2f(1.0f, 1.0f);
        glVertex3f(-hx, hy, -hz);
        glTexCoord2f(0.0f, 1.0f);
        glVertex3f(-hx, -hy, -hz);

        glNormal3f(-1, 0, 0);
        glTexCoord2f(0.0f, 0.0f);
        glVertex3f(-hx, -hy, -hz);
        glTexCoord2f(1.0f, 0.0f);
        glVertex3f(-hx, hy, -hz);
        glTexCoord2f(1.0f, 1.0f);
        glVertex3f(-hx, hy, hz);
        glTexCoord2f(0.0f, 1.0f);
        glVertex3f(-hx, -hy, hz);

        glNormal3f(1, 0, 0);
        glTexCoord2f(0.0f, 0.0f);
        glVertex3f(hx, -hy, hz);
        glTexCoord2f(1.0f, 0.0f);
        glVertex3f(hx, hy, hz);
        glTexCoord2f(1.0f, 1.0f);
        glVertex3f(hx, hy, -hz);
        glTexCoord2f(0.0f, 1.0f);
        glVertex3f(hx, -hy, -hz);

        glEnd();
    }

    private int loadTextureFromResources(String relativePath) {
        Path texturePath = resolveResourcePath(relativePath);
        if (texturePath == null) {
            return 0;
        }

        ByteBuffer imageData;
        try {
            imageData = MemoryUtil.memAlloc((int) Files.size(texturePath));
            imageData.put(Files.readAllBytes(texturePath)).flip();
        } catch (Exception ex) {
            System.err.println("Failed to read texture " + relativePath + ": " + ex.getMessage());
            return 0;
        }

        int textureId = 0;
        try (MemoryStack stack = stackPush()) {
            IntBuffer w = stack.mallocInt(1);
            IntBuffer h = stack.mallocInt(1);
            IntBuffer channels = stack.mallocInt(1);

            STBImage.stbi_set_flip_vertically_on_load(true);
            ByteBuffer pixels = STBImage.stbi_load_from_memory(imageData, w, h, channels, 4);
            if (pixels == null) {
                return 0;
            }

            textureId = glGenTextures();
            glBindTexture(GL_TEXTURE_2D, textureId);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_REPEAT);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, w.get(0), h.get(0), 0, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
            glBindTexture(GL_TEXTURE_2D, 0);

            STBImage.stbi_image_free(pixels);
        } catch (Exception ex) {
            if (textureId != 0) {
                glDeleteTextures(textureId);
            }
            textureId = 0;
            System.err.println("Failed to load texture " + relativePath + ": " + ex.getMessage());
        } finally {
            MemoryUtil.memFree(imageData);
        }

        return textureId;
    }

    private Path resolveResourcePath(String relativePath) {
        Path[] possiblePaths = new Path[] {
            Paths.get("app/src/main/resources").resolve(relativePath),
            Paths.get("src/main/resources").resolve(relativePath),
            Paths.get("resources").resolve(relativePath),
            Paths.get(relativePath)
        };

        for (Path path : possiblePaths) {
            if (Files.exists(path)) {
                return path;
            }
        }
        return null;
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
            renderText(20.0f, 95.0f, "H: Host game (you are Plane 1)");
            renderText(20.0f, 115.0f, "J: Join localhost game (you are Plane 2)");
            renderText(20.0f, 145.0f, "Plane 1: fly through targets");
            renderText(20.0f, 165.0f, "Plane 2: fly and left-click to shoot Plane 1");
            renderText(20.0f, 195.0f, statusText);
        } else if (screenState == ScreenState.WAITING) {
            renderText(20.0f, 70.0f, "Waiting for other player to join...");
            if (networkPeer != null) {
                renderText(20.0f, 95.0f, networkPeer.connectionInfo());
            }
        } else if (screenState == ScreenState.COUNTDOWN) {
            renderText(20.0f, 70.0f, "Match starts in: " + Math.max(0, (int) Math.ceil(countdownTimer)));
        } else if (screenState == ScreenState.PLAYING) {
            renderText(20.0f, 70.0f, "Role: " + (localRole == Role.PILOT ? "Plane 1" : "Plane 2"));
            renderText(20.0f, 90.0f, "Plane HP: " + (int) gameState.planeHp);
            renderText(20.0f, 110.0f, "Targets destroyed: " + gameState.destroyedTargetCount() + "/" + gameState.targets.size());

            if (localRole == Role.PILOT) {
                renderText(20.0f, 130.0f, "Mouse to fly Plane 1 into targets");

                renderText(20.0f, 150.0f, "Boost (hold Space):");
                float barX = 180.0f;
                float barY = 142.0f;
                float barW = 220.0f;
                float barH = 18.0f;

                glColor3f(0.20f, 0.20f, 0.20f);
                drawRect2D(barX, barY, barW, barH);

                glColor3f(0.95f, 0.85f, 0.20f);
                drawRect2D(barX + 2.0f, barY + 2.0f, (barW - 4.0f) * planeBoostEnergy, barH - 4.0f);

                glColor3f(0.05f, 0.05f, 0.05f);
                renderText(barX + barW + 12.0f, 155.0f, (int) (planeBoostEnergy * 100.0f) + "%");
            } else {
                renderText(20.0f, 130.0f, "Mouse to fly Plane 2 + left click to fire");
            }

            renderText(20.0f, 190.0f, "Esc: pause + free mouse");

            drawCrosshair(width * 0.5f, height * 0.5f, 10.0f);
        } else if (screenState == ScreenState.PAUSED) {
            float buttonW = 220.0f;
            float buttonH = 50.0f;
            float buttonX = width * 0.5f - buttonW * 0.5f;
            float resumeY = height * 0.5f - 58.0f;
            float musicY = resumeY + buttonH + 18.0f;

            renderText(20.0f, 70.0f, "Paused");
            renderText(20.0f, 95.0f, "Mouse is free. Click Resume or press Esc.");

            glColor3f(0.75f, 0.75f, 0.75f);
            drawRect2D(buttonX, resumeY, buttonW, buttonH);
            glColor3f(0.05f, 0.05f, 0.05f);
            renderText(buttonX + 70.0f, resumeY + 30.0f, "RESUME");

            glColor3f(0.75f, 0.75f, 0.75f);
            drawRect2D(buttonX, musicY, buttonW, buttonH);
            glColor3f(0.05f, 0.05f, 0.05f);
            renderText(buttonX + 34.0f, musicY + 30.0f, musicMuted ? "UNMUTE MUSIC" : "MUTE MUSIC");
        } else if (screenState == ScreenState.GAME_OVER) {
            glColor3f(0.95f, 0.95f, 0.95f);
            renderCenteredTextScaled(width * 0.5f, height * 0.45f, gameOverText, 2.8f);
            glColor3f(0.05f, 0.05f, 0.05f);
            renderCenteredTextScaled(width * 0.5f, height * 0.60f, "Press R for main menu", 1.35f);
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

    private void renderCenteredTextScaled(float centerX, float y, String text, float scale) {
        float charWidth = 8.0f;
        float textWidth = text.length() * charWidth * scale;
        float x = centerX - textWidth * 0.5f;

        glPushMatrix();
        glTranslatef(x, y, 0.0f);
        glScalef(scale, scale, 1.0f);
        renderText(0.0f, 0.0f, text);
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
            return;
        }

    }

    private void hostGame() {
        closeNetwork();
        resetRound();

        try {
            networkPeer = new HostPeer(NET_PORT);
            localRole = Role.PILOT;
            screenState = ScreenState.WAITING;
            statusText = "Hosting Plane 1 on port " + NET_PORT;
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
            statusText = "Connected as Plane 2. Waiting for host countdown...";
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
        statusText = "Press H to Host (Plane 1) or J to Join (Plane 2)";
    }

    private void resetRound() {
        gameState.reset();
        respawnTargetsAcrossMap();
        planeBoostEnergy = 1.0f;
        planeBoostLockedOut = false;
        countdownTimer = 3.0f;
        localFireCooldown = 0.0f;
        prevLeftMouse = false;
        prevUiLeftMouse = false;
        targetAiPhase = 0.0f;
        planeBank = 0.0f;
        plane2Bank = 0.0f;
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
        glDisable(GL_DEPTH_TEST);
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
        glEnable(GL_DEPTH_TEST);
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
        float resumeY = height * 0.5f - 58.0f;
        float musicY = resumeY + buttonH + 18.0f;

        if (cx[0] >= buttonX && cx[0] <= buttonX + buttonW && cy[0] >= resumeY && cy[0] <= resumeY + buttonH) {
            resumeGame();
        } else if (cx[0] >= buttonX && cx[0] <= buttonX + buttonW && cy[0] >= musicY && cy[0] <= musicY + buttonH) {
            musicMuted = !musicMuted;
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

        if (planeModel != null) {
            planeModel.cleanup();
            planeModel = null;
        }

        if (targetModel != null) {
            targetModel.cleanup();
            targetModel = null;
        }

        if (targetTextureId != 0) {
            glDeleteTextures(targetTextureId);
            targetTextureId = 0;
        }

        if (map != null) {
            map.cleanup();
            map = null;
        }

        if (audioEngine != null) {
            audioEngine.cleanup();
            audioEngine = null;
        }

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
        float planeRoll;
        float planeSpeed;
        float planeHp;

        final Vector3 plane2Position = new Vector3();
        float plane2Yaw;
        float plane2Pitch;
        float plane2Roll;
        float plane2Speed;

        final List<Vector3> targets = new ArrayList<>();
        boolean[] destroyedTargets = new boolean[0];

        void reset() {
            planePosition.set(0.0f, 26.0f, 170.0f);
            planeYaw = 180.0f;
            planePitch = 0.0f;
            planeRoll = 0.0f;
            planeSpeed = BASE_PLANE_SPEED;
            planeHp = 3.0f;

            plane2Position.set(0.0f, 26.0f, -170.0f);
            plane2Yaw = 0.0f;
            plane2Pitch = 0.0f;
            plane2Roll = 0.0f;
            plane2Speed = BASE_PLANE_SPEED;

            targets.clear();
            destroyedTargets = new boolean[0];
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
