package flightbattlesimulator;

import org.lwjgl.assimp.AIFace;
import org.lwjgl.assimp.AIFace.Buffer;
import org.lwjgl.assimp.AIMaterial;
import org.lwjgl.assimp.AIMesh;
import org.lwjgl.assimp.AIScene;
import org.lwjgl.assimp.AIString;
import org.lwjgl.assimp.AIVector3D;
import org.lwjgl.assimp.Assimp;
import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryUtil;

import java.nio.IntBuffer;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

import static org.lwjgl.opengl.GL11.GL_LINEAR;
import static org.lwjgl.opengl.GL11.GL_COMPILE;
import static org.lwjgl.opengl.GL11.GL_REPEAT;
import static org.lwjgl.opengl.GL11.GL_RGBA;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_2D;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_MAG_FILTER;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_MIN_FILTER;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_WRAP_S;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_WRAP_T;
import static org.lwjgl.opengl.GL11.GL_TRIANGLES;
import static org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.GL11.glBegin;
import static org.lwjgl.opengl.GL11.glBindTexture;
import static org.lwjgl.opengl.GL11.glColor3f;
import static org.lwjgl.opengl.GL11.glDeleteTextures;
import static org.lwjgl.opengl.GL11.glDeleteLists;
import static org.lwjgl.opengl.GL11.glDisable;
import static org.lwjgl.opengl.GL11.glEnable;
import static org.lwjgl.opengl.GL11.glEnd;
import static org.lwjgl.opengl.GL11.glEndList;
import static org.lwjgl.opengl.GL11.glCallList;
import static org.lwjgl.opengl.GL11.glGenLists;
import static org.lwjgl.opengl.GL11.glGenTextures;
import static org.lwjgl.opengl.GL11.glNewList;
import static org.lwjgl.opengl.GL11.glNormal3f;
import static org.lwjgl.opengl.GL11.glTexCoord2f;
import static org.lwjgl.opengl.GL11.glTexImage2D;
import static org.lwjgl.opengl.GL11.glTexParameteri;
import static org.lwjgl.opengl.GL11.glVertex3f;

public class ModelLoader {
    private static final float TARGET_SIZE = 30.0f;
    private static final int ASSIMP_LOAD_FLAGS =
        Assimp.aiProcess_Triangulate |
        Assimp.aiProcess_FlipUVs |
        Assimp.aiProcess_CalcTangentSpace |
        Assimp.aiProcess_GenSmoothNormals;

    private AIScene scene;
    private String loadedPath;
    private Path modelDirectory;

    private float minX;
    private float maxX;
    private float minY;
    private float maxY;
    private float minZ;
    private float maxZ;

    private float scaleFactor = 1.0f;
    private float scaledHeight = 1.0f;
    private float scaledDepth = 1.0f;
    private boolean boundsCalculated = false;

    private int textureId = 0;
    private int displayListId = 0;
    private final Map<Integer, Integer> materialTextureIds = new HashMap<>();

    public boolean loadModel(String relativePath) {
        return loadModel(relativePath, null);
    }

    public boolean loadModel(String relativePath, String textureRelativePath) {
        try {
            cleanup();
            Path modelPath = resolveModelPath(relativePath);
            if (modelPath == null) {
                System.err.println("Model file not found: " + relativePath);
                return false;
            }

            modelDirectory = modelPath.toAbsolutePath().getParent();
            scene = Assimp.aiImportFile(modelPath.toAbsolutePath().toString(), ASSIMP_LOAD_FLAGS);
            if (scene == null) {
                System.err.println("Failed to load model: " + relativePath);
                System.err.println("Assimp error: " + Assimp.aiGetErrorString());
                return false;
            }

            loadedPath = relativePath;
            boundsCalculated = false;
            calculateBounds();

            if (textureRelativePath != null && !textureRelativePath.isBlank()) {
                textureId = loadTextureFromRelativePath(textureRelativePath);
            } else {
                loadMaterialTextures();
            }

            compileDisplayList();

            return true;
        } catch (Exception ex) {
            System.err.println("Error loading model: " + ex.getMessage());
            return false;
        }
    }

    public void render() {
        if (scene == null) {
            return;
        }

        if (displayListId != 0) {
            glCallList(displayListId);
            return;
        }

        renderImmediate();
    }

    private void renderImmediate() {
        if (scene == null) {
            return;
        }

        boolean hasGlobalTexture = textureId != 0;
        if (hasGlobalTexture) {
            glEnable(GL_TEXTURE_2D);
            glBindTexture(GL_TEXTURE_2D, textureId);
            glColor3f(1.0f, 1.0f, 1.0f);
        }

        for (int i = 0; i < scene.mNumMeshes(); i++) {
            AIMesh mesh = AIMesh.create(scene.mMeshes().get(i));
            if (mesh == null) {
                continue;
            }

            int boundTexture = textureId;
            if (!hasGlobalTexture) {
                boundTexture = materialTextureIds.getOrDefault(mesh.mMaterialIndex(), 0);
                if (boundTexture != 0) {
                    glEnable(GL_TEXTURE_2D);
                    glBindTexture(GL_TEXTURE_2D, boundTexture);
                    glColor3f(1.0f, 1.0f, 1.0f);
                } else {
                    glBindTexture(GL_TEXTURE_2D, 0);
                    glDisable(GL_TEXTURE_2D);
                }
            }

            renderMesh(mesh, boundTexture != 0);
        }

        glBindTexture(GL_TEXTURE_2D, 0);
        glDisable(GL_TEXTURE_2D);
    }

    private void compileDisplayList() {
        if (scene == null) {
            return;
        }

        if (displayListId != 0) {
            glDeleteLists(displayListId, 1);
            displayListId = 0;
        }

        displayListId = glGenLists(1);
        if (displayListId == 0) {
            return;
        }

        glNewList(displayListId, GL_COMPILE);
        renderImmediate();
        glEndList();
    }

    private void renderMesh(AIMesh mesh, boolean textured) {
        AIVector3D.Buffer vertices = mesh.mVertices();
        AIVector3D.Buffer normals = mesh.mNormals();
        AIVector3D.Buffer texCoords = mesh.mTextureCoords(0);
        Buffer faces = mesh.mFaces();

        if (vertices == null || faces == null) {
            return;
        }

        float centerX = (minX + maxX) * 0.5f;
        float centerY = (minY + maxY) * 0.5f;
        float centerZ = (minZ + maxZ) * 0.5f;

        glBegin(GL_TRIANGLES);
        for (int i = 0; i < mesh.mNumFaces(); i++) {
            AIFace face = faces.get(i);
            if (face.mNumIndices() < 3) {
                continue;
            }

            for (int j = 0; j < 3; j++) {
                int index = face.mIndices().get(j);
                if (index < 0 || index >= vertices.capacity()) {
                    continue;
                }

                if (normals != null && index < normals.capacity()) {
                    AIVector3D n = normals.get(index);
                    glNormal3f(n.x(), n.y(), n.z());
                }

                if (textured && texCoords != null && index < texCoords.capacity()) {
                    AIVector3D uv = texCoords.get(index);
                    glTexCoord2f(uv.x(), uv.y());
                }

                AIVector3D v = vertices.get(index);
                glVertex3f(
                    (v.x() - centerX) * scaleFactor,
                    (v.y() - centerY) * scaleFactor,
                    (v.z() - centerZ) * scaleFactor
                );
            }
        }
        glEnd();
    }

    private void calculateBounds() {
        if (boundsCalculated || scene == null) {
            return;
        }

        minX = Float.MAX_VALUE;
        maxX = -Float.MAX_VALUE;
        minY = Float.MAX_VALUE;
        maxY = -Float.MAX_VALUE;
        minZ = Float.MAX_VALUE;
        maxZ = -Float.MAX_VALUE;

        for (int i = 0; i < scene.mNumMeshes(); i++) {
            AIMesh mesh = AIMesh.create(scene.mMeshes().get(i));
            if (mesh == null || mesh.mVertices() == null) {
                continue;
            }

            AIVector3D.Buffer vertices = mesh.mVertices();
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

        float width = maxX - minX;
        float height = maxY - minY;
        float depth = maxZ - minZ;
        float maxDimension = Math.max(width, Math.max(height, depth));

        scaleFactor = maxDimension > 0.0001f ? (TARGET_SIZE / maxDimension) : 1.0f;
        scaledHeight = height * scaleFactor;
        scaledDepth = depth * scaleFactor;
        boundsCalculated = true;
    }

    private void loadMaterialTextures() {
        materialTextureIds.clear();
        if (scene == null || scene.mMaterials() == null) {
            return;
        }

        for (int materialIndex = 0; materialIndex < scene.mNumMaterials(); materialIndex++) {
            AIMaterial material = AIMaterial.create(scene.mMaterials().get(materialIndex));
            if (material == null) {
                continue;
            }

            AIString texturePathString = AIString.calloc();
            int result = Assimp.aiGetMaterialTexture(
                material,
                Assimp.aiTextureType_DIFFUSE,
                0,
                texturePathString,
                (IntBuffer) null,
                null,
                null,
                null,
                null,
                null
            );

            if (result == Assimp.aiReturn_SUCCESS) {
                String rawTexturePath = texturePathString.dataString().replace('\\', '/');
                Path resolved = resolveTexturePath(rawTexturePath);
                if (resolved != null) {
                    int loadedTexture = loadTextureAtPath(resolved);
                    if (loadedTexture != 0) {
                        materialTextureIds.put(materialIndex, loadedTexture);
                    }
                }
            }

            texturePathString.free();
        }
    }

    private Path resolveModelPath(String relativePath) {
        Path[] candidates = new Path[] {
            Paths.get("app/src/main/resources").resolve(relativePath),
            Paths.get("src/main/resources").resolve(relativePath),
            Paths.get("resources").resolve(relativePath),
            Paths.get(relativePath)
        };

        for (Path candidate : candidates) {
            if (Files.exists(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private int loadTextureFromRelativePath(String relativePath) {
        Path[] candidates = new Path[] {
            Paths.get("app/src/main/resources").resolve(relativePath),
            Paths.get("src/main/resources").resolve(relativePath),
            Paths.get("resources").resolve(relativePath),
            Paths.get(relativePath)
        };

        for (Path candidate : candidates) {
            if (Files.exists(candidate)) {
                return loadTextureAtPath(candidate);
            }
        }
        return 0;
    }

    private Path resolveTexturePath(String rawTexturePath) {
        if (rawTexturePath == null || rawTexturePath.isBlank()) {
            return null;
        }

        Path fileName = Paths.get(rawTexturePath).getFileName();
        Path[] candidates = new Path[] {
            Paths.get(rawTexturePath),
            modelDirectory != null ? modelDirectory.resolve(rawTexturePath) : null,
            modelDirectory != null && fileName != null ? modelDirectory.resolve(fileName) : null,
            Paths.get("app/src/main/resources").resolve(rawTexturePath),
            Paths.get("src/main/resources").resolve(rawTexturePath),
            Paths.get("resources").resolve(rawTexturePath),
            fileName != null ? Paths.get("app/src/main/resources/Twin Islands/OBJ/Textures").resolve(fileName) : null,
            fileName != null ? Paths.get("src/main/resources/Twin Islands/OBJ/Textures").resolve(fileName) : null
        };

        for (Path candidate : candidates) {
            if (candidate != null && Files.exists(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private int loadTextureAtPath(Path texturePath) {
        IntBuffer width = MemoryUtil.memAllocInt(1);
        IntBuffer height = MemoryUtil.memAllocInt(1);
        IntBuffer channels = MemoryUtil.memAllocInt(1);

        try {
            STBImage.stbi_set_flip_vertically_on_load(true);
            ByteBuffer image = STBImage.stbi_load(texturePath.toAbsolutePath().toString(), width, height, channels, 4);
            if (image == null) {
                return 0;
            }

            int generatedTextureId = glGenTextures();
            glBindTexture(GL_TEXTURE_2D, generatedTextureId);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_REPEAT);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, width.get(0), height.get(0), 0, GL_RGBA, GL_UNSIGNED_BYTE, image);
            glBindTexture(GL_TEXTURE_2D, 0);

            STBImage.stbi_image_free(image);
            return generatedTextureId;
        } catch (Exception ex) {
            return 0;
        } finally {
            MemoryUtil.memFree(width);
            MemoryUtil.memFree(height);
            MemoryUtil.memFree(channels);
        }
    }

    public void cleanup() {
        if (scene != null) {
            Assimp.aiReleaseImport(scene);
            scene = null;
        }

        if (textureId != 0) {
            glDeleteTextures(textureId);
            textureId = 0;
        }

        if (displayListId != 0) {
            glDeleteLists(displayListId, 1);
            displayListId = 0;
        }

        for (int loadedTexture : materialTextureIds.values()) {
            if (loadedTexture != 0) {
                glDeleteTextures(loadedTexture);
            }
        }
        materialTextureIds.clear();
    }

    public boolean isLoaded() {
        return scene != null;
    }

    public String getLoadedPath() {
        return loadedPath;
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

    public void printModelBounds() {
        if (!boundsCalculated) {
            calculateBounds();
        }
        System.out.println("Model Bounds: X[" + minX + ", " + maxX + "] Y[" + minY + ", " + maxY + "] Z[" + minZ + ", " + maxZ + "]");
    }
}
