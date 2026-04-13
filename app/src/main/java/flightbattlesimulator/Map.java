package flightbattlesimulator;

import org.lwjgl.opengl.GL11;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

public class Map {
    private float size = 20000.0f;  // Size of the map (2000x2000 square)
    private float y = 0.0f;        // Height of the ground plane
    private ModelLoader islandModel;
    private boolean islandLoaded = false;

    private static final float ISLAND_WORLD_SCALE = 42.0f;
    private static final float ISLAND_Y_OFFSET = -18.0f;
    private static final float MODEL_TARGET_SIZE = 30.0f;
    private static final float COLLISION_CELL_SIZE = 8.0f;
    private static final float BASE_FLOOR_OFFSET = -52.0f;
    private static final float EMPTY_HEIGHT = -10_000.0f;

    private float collisionMinX = 0.0f;
    private float collisionMaxX = 0.0f;
    private float collisionMinZ = 0.0f;
    private float collisionMaxZ = 0.0f;
    private int collisionGridWidth = 0;
    private int collisionGridHeight = 0;
    private float[] collisionHeights = new float[0];
    private boolean collisionGridReady = false;
    
    public Map() {
        tryLoadIsland();
    }
    
    public Map(float size) {
        this.size = size;
        tryLoadIsland();
    }

    private void tryLoadIsland() {
        islandModel = new ModelLoader();
        islandLoaded = islandModel.loadModel("Twin Islands/OBJ/Twin Islands.obj");
        if (!islandLoaded) {
            System.err.println("Failed to load Twin Islands map model; using flat map fallback.");
            islandModel = null;
            return;
        }
        islandModel.printModelBounds();

        if (!buildCollisionGridFromIslandObj()) {
            System.err.println("Failed to build island collision grid; falling back to base floor only.");
        }
    }
    
    /**
     * Renders a flat gray square ground plane
     */
    public void render() {
        GL11.glPushMatrix();

        if (islandLoaded && islandModel != null && islandModel.isLoaded()) {
            GL11.glTranslatef(0.0f, y + ISLAND_Y_OFFSET, 0.0f);
            GL11.glScalef(ISLAND_WORLD_SCALE, ISLAND_WORLD_SCALE, ISLAND_WORLD_SCALE);
            islandModel.render();
        } else {
            GL11.glColor3f(0.5f, 0.5f, 0.5f);

            GL11.glBegin(GL11.GL_QUADS);
            GL11.glNormal3f(0, 1, 0);

            float half = size / 2.0f;
            GL11.glVertex3f(-half, y, -half);
            GL11.glVertex3f(half, y, -half);
            GL11.glVertex3f(half, y, half);
            GL11.glVertex3f(-half, y, half);

            GL11.glEnd();
        }
        
        GL11.glPopMatrix();
    }
    
    /**
     * Get the height of the ground at a specific position
     */
    public float getHeightAt(float x, float z) {
        float baseFloor = y + BASE_FLOOR_OFFSET;
        if (!collisionGridReady || collisionHeights.length == 0) {
            return baseFloor;
        }

        if (x < collisionMinX || x > collisionMaxX || z < collisionMinZ || z > collisionMaxZ) {
            return baseFloor;
        }

        float gx = (x - collisionMinX) / COLLISION_CELL_SIZE;
        float gz = (z - collisionMinZ) / COLLISION_CELL_SIZE;

        int x0 = clampInt((int) Math.floor(gx), 0, collisionGridWidth - 1);
        int z0 = clampInt((int) Math.floor(gz), 0, collisionGridHeight - 1);
        int x1 = clampInt(x0 + 1, 0, collisionGridWidth - 1);
        int z1 = clampInt(z0 + 1, 0, collisionGridHeight - 1);

        float tx = gx - x0;
        float tz = gz - z0;

        float h00 = sampleCellHeight(x0, z0, baseFloor);
        float h10 = sampleCellHeight(x1, z0, baseFloor);
        float h01 = sampleCellHeight(x0, z1, baseFloor);
        float h11 = sampleCellHeight(x1, z1, baseFloor);

        float hx0 = h00 + (h10 - h00) * tx;
        float hx1 = h01 + (h11 - h01) * tx;
        float terrainHeight = hx0 + (hx1 - hx0) * tz;

        return Math.max(baseFloor, terrainHeight);
    }

    private float sampleCellHeight(int gridX, int gridZ, float fallback) {
        float h = collisionHeights[gridZ * collisionGridWidth + gridX];
        return h <= EMPTY_HEIGHT * 0.5f ? fallback : h;
    }

    private int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private boolean buildCollisionGridFromIslandObj() {
        Path objPath = resolveIslandObjPath();
        if (objPath == null) {
            return false;
        }

        List<float[]> modelVertices = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(objPath)) {
                String trimmed = line.trim();
                if (!trimmed.startsWith("v ")) {
                    continue;
                }

                String[] parts = trimmed.split("\\s+");
                if (parts.length < 4) {
                    continue;
                }

                float vx = parseFloatSafe(parts[1]);
                float vy = parseFloatSafe(parts[2]);
                float vz = parseFloatSafe(parts[3]);
                modelVertices.add(new float[] {vx, vy, vz});
            }
        } catch (IOException ex) {
            return false;
        }

        if (modelVertices.isEmpty()) {
            return false;
        }

        float minX = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE;
        float minY = Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;
        float minZ = Float.MAX_VALUE;
        float maxZ = -Float.MAX_VALUE;

        for (float[] v : modelVertices) {
            minX = Math.min(minX, v[0]);
            maxX = Math.max(maxX, v[0]);
            minY = Math.min(minY, v[1]);
            maxY = Math.max(maxY, v[1]);
            minZ = Math.min(minZ, v[2]);
            maxZ = Math.max(maxZ, v[2]);
        }

        float centerX = (minX + maxX) * 0.5f;
        float centerY = (minY + maxY) * 0.5f;
        float centerZ = (minZ + maxZ) * 0.5f;
        float width = maxX - minX;
        float height = maxY - minY;
        float depth = maxZ - minZ;
        float maxDim = Math.max(width, Math.max(height, depth));
        float modelScale = maxDim > 0.0001f ? (MODEL_TARGET_SIZE / maxDim) : 1.0f;
        float worldScale = modelScale * ISLAND_WORLD_SCALE;

        collisionMinX = Float.MAX_VALUE;
        collisionMaxX = -Float.MAX_VALUE;
        collisionMinZ = Float.MAX_VALUE;
        collisionMaxZ = -Float.MAX_VALUE;

        List<float[]> worldVertices = new ArrayList<>(modelVertices.size());
        for (float[] v : modelVertices) {
            float wx = (v[0] - centerX) * worldScale;
            float wy = (v[1] - centerY) * worldScale + y + ISLAND_Y_OFFSET;
            float wz = (v[2] - centerZ) * worldScale;

            worldVertices.add(new float[] {wx, wy, wz});
            collisionMinX = Math.min(collisionMinX, wx);
            collisionMaxX = Math.max(collisionMaxX, wx);
            collisionMinZ = Math.min(collisionMinZ, wz);
            collisionMaxZ = Math.max(collisionMaxZ, wz);
        }

        collisionGridWidth = Math.max(2, (int) Math.ceil((collisionMaxX - collisionMinX) / COLLISION_CELL_SIZE) + 1);
        collisionGridHeight = Math.max(2, (int) Math.ceil((collisionMaxZ - collisionMinZ) / COLLISION_CELL_SIZE) + 1);
        collisionHeights = new float[collisionGridWidth * collisionGridHeight];
        for (int i = 0; i < collisionHeights.length; i++) {
            collisionHeights[i] = EMPTY_HEIGHT;
        }

        for (float[] v : worldVertices) {
            int gx = clampInt((int) Math.floor((v[0] - collisionMinX) / COLLISION_CELL_SIZE), 0, collisionGridWidth - 1);
            int gz = clampInt((int) Math.floor((v[2] - collisionMinZ) / COLLISION_CELL_SIZE), 0, collisionGridHeight - 1);
            int idx = gz * collisionGridWidth + gx;
            collisionHeights[idx] = Math.max(collisionHeights[idx], v[1]);

            for (int nz = Math.max(0, gz - 1); nz <= Math.min(collisionGridHeight - 1, gz + 1); nz++) {
                for (int nx = Math.max(0, gx - 1); nx <= Math.min(collisionGridWidth - 1, gx + 1); nx++) {
                    int nIdx = nz * collisionGridWidth + nx;
                    float neighborHeight = v[1] - 1.0f;
                    collisionHeights[nIdx] = Math.max(collisionHeights[nIdx], neighborHeight);
                }
            }
        }

        smoothCollisionGrid(2);
        collisionGridReady = true;
        return true;
    }

    private void smoothCollisionGrid(int passes) {
        if (collisionHeights.length == 0) {
            return;
        }

        for (int pass = 0; pass < passes; pass++) {
            float[] next = collisionHeights.clone();
            for (int z = 0; z < collisionGridHeight; z++) {
                for (int x = 0; x < collisionGridWidth; x++) {
                    int idx = z * collisionGridWidth + x;
                    float current = collisionHeights[idx];
                    if (current > EMPTY_HEIGHT * 0.5f) {
                        continue;
                    }

                    float sum = 0.0f;
                    int count = 0;
                    for (int nz = Math.max(0, z - 1); nz <= Math.min(collisionGridHeight - 1, z + 1); nz++) {
                        for (int nx = Math.max(0, x - 1); nx <= Math.min(collisionGridWidth - 1, x + 1); nx++) {
                            float sample = collisionHeights[nz * collisionGridWidth + nx];
                            if (sample <= EMPTY_HEIGHT * 0.5f) {
                                continue;
                            }
                            sum += sample;
                            count++;
                        }
                    }

                    if (count > 0) {
                        next[idx] = sum / count;
                    }
                }
            }
            collisionHeights = next;
        }
    }

    private Path resolveIslandObjPath() {
        Path[] candidates = new Path[] {
            Paths.get("app/src/main/resources/Twin Islands/OBJ/Twin Islands.obj"),
            Paths.get("src/main/resources/Twin Islands/OBJ/Twin Islands.obj"),
            Paths.get("resources/Twin Islands/OBJ/Twin Islands.obj"),
            Paths.get("Twin Islands/OBJ/Twin Islands.obj")
        };

        for (Path p : candidates) {
            if (Files.exists(p)) {
                return p;
            }
        }
        return null;
    }

    private float parseFloatSafe(String value) {
        try {
            return Float.parseFloat(value);
        } catch (Exception ignored) {
            return 0.0f;
        }
    }
    
    public float getSize() {
        return size;
    }
    
    public void setSize(float size) {
        this.size = size;
    }
    
    public float getY() {
        return y;
    }
    
    public void setY(float y) {
        this.y = y;
    }

    public void cleanup() {
        if (islandModel != null) {
            islandModel.cleanup();
            islandModel = null;
        }
        islandLoaded = false;
        collisionGridReady = false;
        collisionHeights = new float[0];
        collisionGridWidth = 0;
        collisionGridHeight = 0;
    }
}
