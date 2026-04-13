package flightbattlesimulator;

import org.lwjgl.opengl.GL11;

public class Map {
    private float size = 20000.0f;  // Size of the map (2000x2000 square)
    private float y = 0.0f;        // Height of the ground plane
    private ModelLoader islandModel;
    private boolean islandLoaded = false;

    private static final float ISLAND_WORLD_SCALE = 42.0f;
    private static final float ISLAND_Y_OFFSET = -18.0f;
    
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
        // For a flat plane, always return the same height
        return y;
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
    }
}
