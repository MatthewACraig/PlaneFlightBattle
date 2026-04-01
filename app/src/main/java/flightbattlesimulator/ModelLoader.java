package flightbattlesimulator;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.IntBuffer;
import org.lwjgl.assimp.*;
import org.lwjgl.system.MemoryUtil;

/**
 * Simple model loader for FBX and other 3D model formats using Assimp
 */
public class ModelLoader {
    private static final float TARGET_SIZE = 30.0f;
    private static final int ASSIMP_LOAD_FLAGS = 
        Assimp.aiProcess_Triangulate |
        Assimp.aiProcess_FlipUVs |
        Assimp.aiProcess_CalcTangentSpace |
        Assimp.aiProcess_GenSmoothNormals;
    
    private AIScene scene;
    private String loadedPath;
    private float minX, maxX, minY, maxY, minZ, maxZ;
    private float scaleFactor = 1.0f;
    private float scaledWidth = 1.0f;
    private float scaledHeight = 1.0f;
    private float scaledDepth = 1.0f;
    private boolean boundsCalculated = false;
    
    /**
     * Load a 3D model from the resources directory
     * @param relativePath Path relative to resources directory (e.g., "plane/planedone.fbx")
     * @return true if successfully loaded, false otherwise
     */
    public boolean loadModel(String relativePath) {
        try {
            // Try multiple possible paths
            Path[] possiblePaths = new Path[] {
                Paths.get("app/src/main/resources").resolve(relativePath),
                Paths.get("src/main/resources").resolve(relativePath),
                Paths.get("resources").resolve(relativePath),
                Paths.get(relativePath)
            };
            
            Path resourcePath = null;
            for (Path p : possiblePaths) {
                System.out.println("Checking: " + p.toAbsolutePath());
                if (Files.exists(p)) {
                    resourcePath = p;
                    System.out.println("Found model at: " + p.toAbsolutePath());
                    break;
                }
            }
            
            if (resourcePath == null) {
                System.err.println("Model file not found in any location for: " + relativePath);
                return false;
            }
            
            // Load the model using Assimp
            String absolutePath = resourcePath.toAbsolutePath().toString();
            System.out.println("Loading model from: " + absolutePath);
            this.scene = Assimp.aiImportFile(absolutePath, ASSIMP_LOAD_FLAGS);
            
            if (this.scene == null) {
                System.err.println("Failed to load model: " + relativePath);
                System.err.println("Assimp error: " + Assimp.aiGetErrorString());
                return false;
            }
            
            int flags = this.scene.mFlags();
            System.out.println("Scene flags: " + flags + ", AI_SCENE_FLAGS_INCOMPLETE: " + Assimp.AI_SCENE_FLAGS_INCOMPLETE);
            
            if ((flags & Assimp.AI_SCENE_FLAGS_INCOMPLETE) != 0) {
                System.err.println("Warning: Scene is incomplete");
            }
            
            this.loadedPath = relativePath;
            System.out.println("Successfully loaded model: " + relativePath);
            System.out.println("Meshes: " + this.scene.mNumMeshes() + ", Materials: " + this.scene.mNumMaterials());
            
            // Calculate bounds for proper scaling
            calculateBounds();
            
            return true;
        } catch (Exception e) {
            System.err.println("Error loading model: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }
    
    /**
     * Render the loaded model
     */
    public void render() {
        if (this.scene == null) {
            System.err.println("No model loaded!");
            return;
        }
        
        // Render all meshes in the scene
        for (int i = 0; i < this.scene.mNumMeshes(); i++) {
            AIMesh mesh = AIMesh.create(this.scene.mMeshes().get(i));
            if (mesh != null) {
                renderMesh(mesh);
            }
        }
    }
    
    /**
     * Calculate and print model bounding box for debugging scale issues
     */
    public void printModelBounds() {
        if (this.scene == null) return;
        
        calculateBounds();
        
        float width = maxX - minX;
        float height = maxY - minY;
        float depth = maxZ - minZ;
        
        System.out.println("Model Bounds:");
        System.out.println("  X: [" + minX + ", " + maxX + "] (width: " + width + ")");
        System.out.println("  Y: [" + minY + ", " + maxY + "] (height: " + height + ")");
        System.out.println("  Z: [" + minZ + ", " + maxZ + "] (depth: " + depth + ")");
        System.out.println("  Scaled dimensions -> width: " + scaledWidth + ", height: " + scaledHeight + ", depth: " + scaledDepth);
    }
    
    /**
     * Calculate model bounds
     */
    private void calculateBounds() {
        if (boundsCalculated) return;
        
        minX = Float.MAX_VALUE;
        maxX = -Float.MAX_VALUE;
        minY = Float.MAX_VALUE;
        maxY = -Float.MAX_VALUE;
        minZ = Float.MAX_VALUE;
        maxZ = -Float.MAX_VALUE;
        
        for (int i = 0; i < this.scene.mNumMeshes(); i++) {
            AIMesh mesh = AIMesh.create(this.scene.mMeshes().get(i));
            if (mesh != null) {
                AIVector3D.Buffer vertices = mesh.mVertices();
                if (vertices != null) {
                    for (int v = 0; v < mesh.mNumVertices(); v++) {
                        AIVector3D vertex = vertices.get(v);
                        minX = Math.min(minX, vertex.x());
                        maxX = Math.max(maxX, vertex.x());
                        minY = Math.min(minY, vertex.y());
                        maxY = Math.max(maxY, vertex.y());
                        minZ = Math.min(minZ, vertex.z());
                        maxZ = Math.max(maxZ, vertex.z());
                    }
                }
            }
        }

        float width = maxX - minX;
        float height = maxY - minY;
        float depth = maxZ - minZ;
        float maxDim = Math.max(width, Math.max(height, depth));

        if (maxDim > 0.0001f) {
            scaleFactor = TARGET_SIZE / maxDim;
        } else {
            scaleFactor = 1.0f;
        }

        scaledWidth = width * scaleFactor;
        scaledHeight = height * scaleFactor;
        scaledDepth = depth * scaleFactor;
        
        boundsCalculated = true;
    }
    
    /**
     * Recursively render nodes in the scene (currently not used - we render all meshes directly)
     */
    private void renderNode(AINode node, AIScene scene) {
        if (node == null) {
            return;
        }
        
        // Process meshes in this node
        IntBuffer meshIndices = node.mMeshes();
        if (meshIndices != null) {
            for (int i = 0; i < node.mNumMeshes(); i++) {
                int meshIndex = meshIndices.get(i);
                if (meshIndex >= 0 && meshIndex < scene.mNumMeshes()) {
                    AIMesh mesh = AIMesh.create(scene.mMeshes().get(meshIndex));
                    if (mesh != null) {
                        renderMesh(mesh);
                    }
                }
            }
        }
        
        // Note: Child node processing skipped for simplicity
        // This will just render all meshes at root level
    }
    
    /**
     * Render a single mesh
     */
    private void renderMesh(AIMesh mesh) {
        try {
            org.lwjgl.opengl.GL11.glBegin(org.lwjgl.opengl.GL11.GL_TRIANGLES);
            
            // Get mesh data
            AIVector3D.Buffer vertices = mesh.mVertices();
            AIVector3D.Buffer normals = mesh.mNormals();
            AIFace.Buffer faces = mesh.mFaces();
            
            if (vertices == null || faces == null) {
                System.err.println("Mesh missing vertices or faces!");
                org.lwjgl.opengl.GL11.glEnd();
                return;
            }
            
            // Calculate scaling factors based on model dimensions
            // Target size: longest dimension = 30.0 units (adjusted to match scene scale)
            float baseScale = scaleFactor;
            
            // Scale each axis individually to preserve proportions
            float scaleX = baseScale;
            float scaleY = baseScale;
            float scaleZ = baseScale;
            
            // Center the model at origin
            float centerX = (minX + maxX) / 2.0f;
            float centerY = (minY + maxY) / 2.0f;
            float centerZ = (minZ + maxZ) / 2.0f;
            
            // Render each face
            for (int i = 0; i < mesh.mNumFaces(); i++) {
                AIFace face = faces.get(i);
                if (face.mNumIndices() < 3) continue;  // Skip degenerate faces
                
                // Render as triangles (triangulate if needed)
                for (int j = 0; j < face.mNumIndices() && j < 3; j++) {
                    int index = face.mIndices().get(j);
                    
                    // Set normal if available
                    if (normals != null && index < normals.capacity()) {
                        AIVector3D normal = normals.get(index);
                        org.lwjgl.opengl.GL11.glNormal3f(normal.x(), normal.y(), normal.z());
                    }
                    
                    // Set vertex position with proper scaling and centering
                    AIVector3D vertex = vertices.get(index);
                    float x = (vertex.x() - centerX) * scaleX;
                    float y = (vertex.y() - centerY) * scaleY;
                    float z = (vertex.z() - centerZ) * scaleZ;
                    org.lwjgl.opengl.GL11.glVertex3f(x, y, z);
                }
            }
            
            org.lwjgl.opengl.GL11.glEnd();
        } catch (Exception e) {
            System.err.println("Error rendering mesh: " + e.getMessage());
        }
    }
    
    /**
     * Clean up resources
     */
    public void cleanup() {
        if (this.scene != null) {
            Assimp.aiReleaseImport(this.scene);
            this.scene = null;
        }
    }
    
    public boolean isLoaded() {
        return this.scene != null;
    }
    
    public String getLoadedPath() {
        return this.loadedPath;
    }

    public float getScaledHeight() {
        if (!boundsCalculated) {
            calculateBounds();
        }
        return scaledHeight;
    }

    public float getScaledDepth() {
        if (!boundsCalculated) {
            calculateBounds();
        }
        return scaledDepth;
    }
}
