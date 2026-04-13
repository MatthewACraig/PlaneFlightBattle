package flightbattlesimulator;

import javazoom.jl.decoder.Bitstream;
import javazoom.jl.decoder.Decoder;
import javazoom.jl.decoder.Header;
import javazoom.jl.decoder.SampleBuffer;
import org.lwjgl.openal.AL;
import org.lwjgl.openal.ALC;
import org.lwjgl.openal.ALCCapabilities;
import org.lwjgl.system.MemoryUtil;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.openal.AL10.AL_BUFFER;
import static org.lwjgl.openal.AL10.AL_FORMAT_MONO16;
import static org.lwjgl.openal.AL10.AL_FORMAT_STEREO16;
import static org.lwjgl.openal.AL10.AL_GAIN;
import static org.lwjgl.openal.AL10.AL_LOOPING;
import static org.lwjgl.openal.AL10.AL_MAX_DISTANCE;
import static org.lwjgl.openal.AL10.AL_ORIENTATION;
import static org.lwjgl.openal.AL10.AL_PAUSED;
import static org.lwjgl.openal.AL10.AL_PITCH;
import static org.lwjgl.openal.AL10.AL_PLAYING;
import static org.lwjgl.openal.AL10.AL_POSITION;
import static org.lwjgl.openal.AL10.AL_REFERENCE_DISTANCE;
import static org.lwjgl.openal.AL10.AL_ROLLOFF_FACTOR;
import static org.lwjgl.openal.AL10.AL_SOURCE_STATE;
import static org.lwjgl.openal.AL10.AL_TRUE;
import static org.lwjgl.openal.AL10.AL_FALSE;
import static org.lwjgl.openal.AL10.alBufferData;
import static org.lwjgl.openal.AL10.alDeleteBuffers;
import static org.lwjgl.openal.AL10.alDeleteSources;
import static org.lwjgl.openal.AL10.alGenBuffers;
import static org.lwjgl.openal.AL10.alGenSources;
import static org.lwjgl.openal.AL10.alGetSourcei;
import static org.lwjgl.openal.AL10.alListener3f;
import static org.lwjgl.openal.AL10.alListenerfv;
import static org.lwjgl.openal.AL10.alSource3f;
import static org.lwjgl.openal.AL10.alSourcePause;
import static org.lwjgl.openal.AL10.alSourcePlay;
import static org.lwjgl.openal.AL10.alSourceRewind;
import static org.lwjgl.openal.AL10.alSourceStop;
import static org.lwjgl.openal.AL10.alSourcef;
import static org.lwjgl.openal.AL10.alSourcei;
import static org.lwjgl.openal.ALC10.alcCloseDevice;
import static org.lwjgl.openal.ALC10.alcCreateContext;
import static org.lwjgl.openal.ALC10.alcDestroyContext;
import static org.lwjgl.openal.ALC10.alcGetString;
import static org.lwjgl.openal.ALC10.alcMakeContextCurrent;
import static org.lwjgl.openal.ALC10.alcOpenDevice;
import static org.lwjgl.openal.ALC11.ALC_DEFAULT_DEVICE_SPECIFIER;

public class AudioEngine {
    private long device;
    private long context;
    private boolean initialized;

    private final List<Integer> sourceIds = new ArrayList<>();
    private final List<Integer> bufferIds = new ArrayList<>();

    public boolean init() {
        try {
            String defaultDeviceName = alcGetString(0, ALC_DEFAULT_DEVICE_SPECIFIER);
            device = alcOpenDevice(defaultDeviceName);
            if (device == MemoryUtil.NULL) {
                System.err.println("Failed to open OpenAL device.");
                return false;
            }

            context = alcCreateContext(device, (IntBuffer) null);
            if (context == MemoryUtil.NULL) {
                System.err.println("Failed to create OpenAL context.");
                alcCloseDevice(device);
                device = MemoryUtil.NULL;
                return false;
            }

            alcMakeContextCurrent(context);
            ALCCapabilities alcCaps = ALC.createCapabilities(device);
            AL.createCapabilities(alcCaps);

            initialized = true;
            return true;
        } catch (Exception ex) {
            System.err.println("Audio init error: " + ex.getMessage());
            cleanup();
            return false;
        }
    }

    public int createMp3Source(
        String relativePath,
        boolean loop,
        float gain,
        float pitch,
        float referenceDistance,
        float maxDistance,
        float rolloff
    ) {
        if (!initialized) {
            return -1;
        }

        Path path = resolveResourcePath(relativePath);
        if (path == null) {
            System.err.println("Audio file not found: " + relativePath);
            return -1;
        }

        DecodedAudio decoded = decodeMp3(path);
        if (decoded == null || decoded.pcmData == null) {
            System.err.println("Failed to decode MP3: " + relativePath);
            return -1;
        }

        int format = decoded.channels == 1 ? AL_FORMAT_MONO16 : AL_FORMAT_STEREO16;
        int buffer = alGenBuffers();
        alBufferData(buffer, format, decoded.pcmData, decoded.sampleRate);
        MemoryUtil.memFree(decoded.pcmData);

        int source = alGenSources();
        alSourcei(source, AL_BUFFER, buffer);
        alSourcei(source, AL_LOOPING, loop ? AL_TRUE : AL_FALSE);
        alSourcef(source, AL_GAIN, gain);
        alSourcef(source, AL_PITCH, pitch);
        alSourcef(source, AL_REFERENCE_DISTANCE, referenceDistance);
        alSourcef(source, AL_MAX_DISTANCE, maxDistance);
        alSourcef(source, AL_ROLLOFF_FACTOR, rolloff);
        alSource3f(source, AL_POSITION, 0.0f, 0.0f, 0.0f);

        sourceIds.add(source);
        bufferIds.add(buffer);
        return source;
    }

    public void setListener(Vector3 position, Vector3 forward, Vector3 up) {
        if (!initialized) {
            return;
        }

        alListener3f(AL_POSITION, position.x, position.y, position.z);
        float[] orientation = new float[] {
            forward.x, forward.y, forward.z,
            up.x, up.y, up.z
        };
        alListenerfv(AL_ORIENTATION, orientation);
    }

    public void setSourcePosition(int sourceId, Vector3 position) {
        if (!initialized || sourceId < 0) {
            return;
        }
        alSource3f(sourceId, AL_POSITION, position.x, position.y, position.z);
    }

    public void setSourceGain(int sourceId, float gain) {
        if (!initialized || sourceId < 0) {
            return;
        }
        alSourcef(sourceId, AL_GAIN, Math.max(0.0f, gain));
    }

    public void playOneShot(int sourceId) {
        if (!initialized || sourceId < 0) {
            return;
        }
        alSourceStop(sourceId);
        alSourceRewind(sourceId);
        alSourcePlay(sourceId);
    }

    public void setLoopPlaying(int sourceId, boolean shouldPlay) {
        if (!initialized || sourceId < 0) {
            return;
        }

        int state = alGetSourcei(sourceId, AL_SOURCE_STATE);
        if (shouldPlay) {
            if (state != AL_PLAYING) {
                alSourcePlay(sourceId);
            }
        } else {
            if (state == AL_PLAYING) {
                alSourcePause(sourceId);
            }
        }
    }

    public void cleanup() {
        for (int sourceId : sourceIds) {
            alDeleteSources(sourceId);
        }
        sourceIds.clear();

        for (int bufferId : bufferIds) {
            alDeleteBuffers(bufferId);
        }
        bufferIds.clear();

        if (context != MemoryUtil.NULL) {
            alcDestroyContext(context);
            context = MemoryUtil.NULL;
        }

        if (device != MemoryUtil.NULL) {
            alcCloseDevice(device);
            device = MemoryUtil.NULL;
        }

        initialized = false;
    }

    private Path resolveResourcePath(String relativePath) {
        Path[] possiblePaths = new Path[] {
            Paths.get("app/src/main/resources").resolve(relativePath),
            Paths.get("src/main/resources").resolve(relativePath),
            Paths.get("resources").resolve(relativePath),
            Paths.get(relativePath)
        };

        for (Path p : possiblePaths) {
            if (Files.exists(p)) {
                return p;
            }
        }
        return null;
    }

    private DecodedAudio decodeMp3(Path path) {
        try (BufferedInputStream in = new BufferedInputStream(new FileInputStream(path.toFile()))) {
            Bitstream bitstream = new Bitstream(in);
            Decoder decoder = new Decoder();
            ByteArrayOutputStream pcmOut = new ByteArrayOutputStream();

            int channels = 0;
            int sampleRate = 0;

            while (true) {
                Header header = bitstream.readFrame();
                if (header == null) {
                    break;
                }

                SampleBuffer output = (SampleBuffer) decoder.decodeFrame(header, bitstream);
                if (channels == 0) {
                    channels = output.getChannelCount();
                    sampleRate = output.getSampleFrequency();
                }

                short[] samples = output.getBuffer();
                int sampleCount = output.getBufferLength();
                for (int i = 0; i < sampleCount; i++) {
                    short sample = samples[i];
                    pcmOut.write(sample & 0xFF);
                    pcmOut.write((sample >>> 8) & 0xFF);
                }

                bitstream.closeFrame();
            }

            byte[] pcmBytes = pcmOut.toByteArray();
            if (channels < 1 || sampleRate <= 0 || pcmBytes.length == 0) {
                return null;
            }

            ByteBuffer pcmBuffer = MemoryUtil.memAlloc(pcmBytes.length);
            pcmBuffer.put(pcmBytes).flip();
            return new DecodedAudio(pcmBuffer, channels, sampleRate);
        } catch (Exception ex) {
            System.err.println("Failed decoding MP3 " + path + ": " + ex.getMessage());
            return null;
        }
    }

    private static final class DecodedAudio {
        final ByteBuffer pcmData;
        final int channels;
        final int sampleRate;

        DecodedAudio(ByteBuffer pcmData, int channels, int sampleRate) {
            this.pcmData = pcmData;
            this.channels = channels;
            this.sampleRate = sampleRate;
        }
    }
}
