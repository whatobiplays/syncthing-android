package com.nutomic.syncthingandroid.runtime;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Objects;

/** Persists a versioned execution identity in the app's no-backup private storage. */
final class FileExecutionRecordStore implements ExecutionRecordStore {
    private static final int MAGIC = 0x53544558;
    private static final int VERSION = 1;
    private static final long MAX_RECORD_BYTES = 16 * 1024;

    private final File recordFile;

    FileExecutionRecordStore(File recordFile) {
        this.recordFile = Objects.requireNonNull(recordFile);
    }

    @Override
    public synchronized ReadResult read() {
        byte[] bytes;
        try (FileInputStream fileInput = new FileInputStream(recordFile);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024];
            int count;
            while ((count = fileInput.read(buffer)) != -1) {
                if (output.size() + count > MAX_RECORD_BYTES) return ReadResult.corrupt();
                output.write(buffer, 0, count);
            }
            bytes = output.toByteArray();
        } catch (FileNotFoundException e) {
            return recordFile.exists() ? ReadResult.readFailed() : ReadResult.missing();
        } catch (IOException e) {
            return ReadResult.readFailed();
        }
        if (bytes.length == 0) return ReadResult.corrupt();

        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (input.readInt() != MAGIC) return ReadResult.corrupt();
            int version = input.readInt();
            if (version != VERSION) return ReadResult.unsupportedVersion();
            int pid = input.readInt();
            long startTicks = input.readLong();
            String bootId = input.readUTF();
            String executable = input.readUTF();
            String runToken = input.readUTF();
            if (input.read() != -1) return ReadResult.corrupt();
            ExecutionIdentity identity = new ExecutionIdentity(
                    pid, startTicks, bootId, executable, runToken
            );
            return runToken.isEmpty() ? ReadResult.corrupt() : ReadResult.valid(identity);
        } catch (IOException | IllegalArgumentException e) {
            return ReadResult.corrupt();
        }
    }

    @Override
    public synchronized void write(ExecutionIdentity identity) throws IOException {
        File parent = recordFile.getParentFile();
        if (parent == null) throw new IOException("Execution record path has no parent");
        if (!parent.exists() && !parent.mkdirs()) {
            throw new IOException("Could not create execution record directory");
        }

        File temporary = new File(recordFile.getPath() + ".tmp");
        try (FileOutputStream fileOutput = new FileOutputStream(temporary);
             DataOutputStream output = new DataOutputStream(fileOutput)) {
            output.writeInt(MAGIC);
            output.writeInt(VERSION);
            output.writeInt(identity.pid());
            output.writeLong(identity.processStartTimeTicks());
            output.writeUTF(identity.bootId());
            output.writeUTF(identity.executablePath());
            output.writeUTF(identity.runToken());
            output.flush();
            fileOutput.getFD().sync();
        }

        if (!temporary.renameTo(recordFile)) {
            throw new IOException("Could not atomically replace execution record");
        }
    }

    @Override
    public synchronized boolean deleteIfRunTokenMatches(String runToken) throws IOException {
        ReadResult current = read();
        if (current.status() != ReadResult.Status.VALID
                || !current.identity().runToken().equals(runToken)) {
            return false;
        }
        if (!recordFile.delete()) {
            throw new IOException("Could not remove execution record");
        }
        return true;
    }
}
