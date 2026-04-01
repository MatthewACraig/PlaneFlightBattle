package flightbattlesimulator;

import org.lwjgl.opengl.GL11;

public class Map {
    private float size = 2000.0f;  // Size of the map (2000x2000 square)
    private float y = 0.0f;        // Height of the ground plane
    
    public Map() {
    }
    
    public Map(float size) {
        this.size = size;
    }
    
    /**
     * Renders a flat gray square ground plane
     */
    public void render() {
        GL11.glPushMatrix();
        
        // Set color to gray
        GL11.glColor3f(0.5f, 0.5f, 0.5f);
        
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glNormal3f(0, 1, 0);  // Normal facing up
        
        // Render a flat square in the XZ plane
        float half = size / 2.0f;
        GL11.glVertex3f(-half, y, -half);
        GL11.glVertex3f(half, y, -half);
        GL11.glVertex3f(half, y, half);
        GL11.glVertex3f(-half, y, half);
        
        GL11.glEnd();
        
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
}
